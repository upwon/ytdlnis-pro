package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatorTest {
    private fun cues(n: Int) = (1..n).map { Cue(it, it * 3000L, it * 3000L + 2500, "Sentence number $it here.") }

    @Test
    fun translatesInBatchesWithContextAndLengthHints() = runBlocking {
        val chat = FakeChat { FakeChat.echoZh(it) }
        val t = LlmTranslator(chat, "m", TranslatorConfig(batchSize = 2, concurrency = 1, glossary = "OpenAI=开放人工智能"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val batches = AtomicInteger()
        val out = t.translate(cues(5), { d, total -> progress += d to total }, { batches.incrementAndGet() })

        assertEquals(5, out.count { it.zh.isNotBlank() })
        assertEquals("译文3:Senten", out[2].zh)
        assertEquals(3, chat.requests.size)
        assertEquals(3, batches.get())
        assertEquals(5 to 5, progress.last())

        // 2nd batch (cues 3,4) carries the 2 previous source lines as context, which must not be translated
        val second = Json.parseToJsonElement(chat.requests[1].messages.last().content).jsonObject
        assertEquals(2, second["context"]!!.jsonArray.size)
        assertEquals("Sentence number 2 here.", second["context"]!!.jsonArray.last().jsonPrimitive.content)
        // time budget: 2.5 s window (+ up to 0.5 s gap to next) -> >= 8 chars hint
        val maxChars = second["items"]!!.jsonArray[0].jsonObject["max_chars"]!!.jsonPrimitive.content.toInt()
        assertTrue(maxChars in 8..20, "max_chars=$maxChars")
        assertTrue(chat.requests[0].messages.first().content.contains("OpenAI=开放人工智能"))
        assertEquals("system", chat.requests[0].messages.first().role)
    }

    @Test
    fun parsesCommonModelOutputQuirks() {
        val p = LlmTranslator.Companion::parseTranslations
        assertEquals(mapOf(1 to "你好"), p("""{"translations":[{"id":1,"zh":"你好"}]}"""))
        assertEquals(mapOf(1 to "你好"), p("```json\n{\"translations\":[{\"id\":1,\"zh\":\"你好\"}]}\n```"))
        assertEquals(mapOf(2 to "再见"), p("<think>let me think { not json }</think>[{\"id\":\"2\",\"translation\":\"再见\"}]"))
        assertEquals(mapOf(1 to "甲", 2 to "乙"), p("""{"1":"甲","2":"乙"}"""))
        assertEquals(mapOf(3 to "丙"), p("Sure! Here you go:\n{\"translations\":[{\"id\":3,\"zh\":\"丙\"}]}\nHope it helps"))
        assertEquals(mapOf(), p("I cannot do that"))
        assertEquals(mapOf(), p(""))
    }

    @Test
    fun retriesMissingItemsThenFallsBackToSingleRequests() = runBlocking {
        var call = 0
        val chat = FakeChat { req ->
            call++
            val items = FakeChat.userItems(req)
            // 1st call: model "forgets" id 2; later calls behave
            val use = if (call == 1) items.filter { it.first != 2 } else items
            use.joinToString(",", """{"translations":[""", "]}") { (id, _) -> """{"id":$id,"zh":"z$id"}""" }
        }
        val out = LlmTranslator(chat, "m", TranslatorConfig(batchSize = 10)).translate(cues(3))
        assertEquals(listOf("z1", "z2", "z3"), out.map { it.zh })
        assertEquals(2, chat.requests.size)
        assertEquals(listOf(2), FakeChat.userItems(chat.requests[1]).map { it.first }) // only the missing one is re-asked
    }

    @Test
    fun givesUpWithClearErrorWhenModelNeverAnswers() {
        val chat = FakeChat { "I'm sorry, I can't help with that." }
        val e = assertFailsWith<TranslationException> {
            runBlocking { LlmTranslator(chat, "m", TranslatorConfig(batchSize = 2, maxRetries = 1)).translate(cues(2)) }
        }
        assertTrue(e.message!!.contains("cue(s)"))
    }

    @Test
    fun alreadyTranslatedCuesAreSkippedForResume() = runBlocking {
        val chat = FakeChat { FakeChat.echoZh(it) }
        val input = cues(4).mapIndexed { i, c -> if (i < 2) c.copy(zh = "已译${c.id}") else c }
        val out = LlmTranslator(chat, "m", TranslatorConfig(batchSize = 10)).translate(input)
        assertEquals(listOf("已译1", "已译2"), out.take(2).map { it.zh })
        assertEquals(listOf(3, 4), FakeChat.userItems(chat.requests.single()).map { it.first })
    }

    @Test
    fun concurrentBatchesKeepEverythingInOrder() = runBlocking {
        val chat = FakeChat { FakeChat.echoZh(it) }
        val out = LlmTranslator(chat, "m", TranslatorConfig(batchSize = 3, concurrency = 4)).translate(cues(25))
        assertEquals((1..25).map { "译文$it:Senten" }, out.map { it.zh })
    }

    @Test
    fun promptKeepsEnglishTermsAndAsksForNaturalChinese() {
        val p = LlmTranslator(FakeChat { "" }, "m", TranslatorConfig(glossary = "pstack=pstack", extraSystemPrompt = "视频讲软件工厂。")).systemPrompt()
        assertTrue(p.contains("信、达、雅"))
        assertTrue(p.contains("agent") && p.contains("保留英文原文"), "tech terms stay in English by default")
        assertTrue(p.contains("pstack=pstack"))
        assertTrue(p.contains("视频讲软件工厂。"))
        val off = LlmTranslator(FakeChat { "" }, "m", TranslatorConfig(keepEnglishTerms = false)).systemPrompt()
        assertTrue(!off.contains("保留英文原文"), "the option switches the rule off")
        assertTrue(off.contains("通用的中文译名"))
    }
}
