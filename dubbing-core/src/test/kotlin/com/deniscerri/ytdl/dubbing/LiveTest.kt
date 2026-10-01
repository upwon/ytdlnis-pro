package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests against the real internet. They are skipped unless explicitly enabled:
 *
 *   OPENROUTER_API_KEY=sk-or-...  gradle test --tests '*LiveTest*'      (free OpenRouter models only)
 *   EDGE_TTS_LIVE=1               gradle test --tests '*LiveTest*'      (Microsoft Edge voices)
 *
 * The OpenRouter test refuses to call any model that is not reported as free (zero prompt+completion price).
 */
class LiveTest {
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    private fun openRouterClient(key: String) = OpenAiCompatClient(
        "https://openrouter.ai/api/v1", key,
        extraHeaders = mapOf("HTTP-Referer" to "https://github.com/deniscerri/ytdlnis", "X-Title" to "YTDLnis dubbing test"),
        maxAttempts = 3, retryBaseDelayMs = 4_000,
    )

    private val sample = listOf(
        Cue(1, 1000, 4000, "Welcome back to the channel, everyone."),
        Cue(2, 4500, 8000, "Today we are going to look at how neural networks learn."),
        Cue(3, 8500, 12000, "It's simpler than you might think."),
    )

    @Test fun freeOpenRouterModelTranslatesAndDubsEndToEnd() {
        val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
        assumeTrue("OPENROUTER_API_KEY not set - live test skipped", key.isNotBlank())
        val client = openRouterClient(key)
        val models = runBlocking { client.listModels() }
        // Strictly free: provider reports zero price for both directions.
        val free = models.filter { it.promptPrice == 0.0 && it.completionPrice == 0.0 && it.id.endsWith(":free") }
            .filterNot { Regex("vision|image|audio|embed|guard|moderation", RegexOption.IGNORE_CASE).containsMatchIn(it.id) }
        assertTrue(free.isNotEmpty(), "OpenRouter lists no free chat models")
        val preferred = listOf("llama", "qwen", "gemma", "mistral", "deepseek", "gpt-oss", "glm")
        val candidates = free.sortedBy { m -> preferred.indexOfFirst { m.id.contains(it, true) }.let { if (it < 0) 99 else it } }.take(8)
        println("free candidates: ${candidates.map { it.id }}")

        val errors = mutableListOf<String>()
        for (m in candidates) {
            require(m.isFree && m.promptPrice == 0.0 && m.completionPrice == 0.0) { "refusing to call non-free model ${m.id}" }
            try {
                val out = runBlocking { LlmTranslator(client, m.id, TranslatorConfig(batchSize = 10)).translate(sample) }
                println("model ${m.id} -> ${out.map { it.zh }}")
                assertTrue(out.all { cjk.containsMatchIn(it.zh) }, "translations should contain Chinese: ${out.map { it.zh }}")
                endToEnd(client, m.id)
                return
            } catch (e: Exception) {
                errors += "${m.id}: ${e.message?.take(160)}"
            }
        }
        throw AssertionError("No free model produced a usable translation:\n" + errors.joinToString("\n"))
    }

    private fun endToEnd(client: OpenAiCompatClient, model: String) {
        val dir = TestMedia.tempDir("live")
        val video = File(dir, "v.mp4").also { TestMedia.makeVideo(it, 14) }
        val srt = File(dir, "en.srt").apply { writeText(SrtWriter.format(sample, useTranslation = false)) }
        val out = File(dir, "v.zh.mp4")
        runBlocking {
            DubbingPipeline(TestMedia.ffmpeg, LlmTranslator(client, model, TranslatorConfig(batchSize = 10)), ToneTts())
                .run(video, srt, File(dir, "work"), out)
        }
        assertTrue(TestMedia.streams(out, "audio").size == 2)
        println("end-to-end OK with $model")
    }

    @Test fun edgeTtsProducesRealMandarinSpeech() {
        assumeTrue("EDGE_TTS_LIVE not set - live test skipped", System.getenv("EDGE_TTS_LIVE") == "1")
        val out = File.createTempFile("edge-live", ".mp3").apply { deleteOnExit() }
        runBlocking { EdgeTts().synthesize("你好，欢迎收看今天的节目。", "zh-CN-XiaoxiaoNeural", 0, out) }
        assertTrue(out.length() > 5_000, "got ${out.length()} bytes")
        val info = runBlocking { TestMedia.ffmpeg.probe(out) }
        assertTrue(info.durationMs > 1500, "duration ${info.durationMs}")
    }
}
