package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface Translator {
    /**
     * Fills [Cue.zh]. Cues that already have a translation are left untouched (resume support).
     * [onBatch] receives the full, current cue list after every finished batch so callers can checkpoint.
     */
    suspend fun translate(
        cues: List<Cue>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBatch: suspend (List<Cue>) -> Unit = {},
    ): List<Cue>
}

data class TranslatorConfig(
    val batchSize: Int = 30,
    val concurrency: Int = 2,
    val contextLines: Int = 3,
    val charsPerSecond: Double = 4.5,
    val temperature: Double = 0.3,
    val jsonMode: Boolean = false,
    val maxRetries: Int = 2,
    val glossary: String = "",
    val sourceLanguage: String = "English",
    val extraSystemPrompt: String = "",
    /** Keep common tech terms (agent, prompt, API ...) in English instead of translating them. */
    val keepEnglishTerms: Boolean = true,
    /**
     * Subtitle-only runs: the text is read, not spoken, so there is no time-based length cap and long lines may be
     * broken in two with a newline.
     */
    val subtitleMode: Boolean = false,
)

class TranslationException(message: String) : Exception(message)

class LlmTranslator(
    private val client: ChatClient,
    private val model: String,
    private val config: TranslatorConfig = TranslatorConfig(),
    private val log: (String) -> Unit = {},
) : Translator {

    override suspend fun translate(
        cues: List<Cue>,
        onProgress: (Int, Int) -> Unit,
        onBatch: suspend (List<Cue>) -> Unit,
    ): List<Cue> = coroutineScope {
        val result = cues.toMutableList()
        val total = cues.size
        var done = cues.count { it.zh.isNotBlank() }
        onProgress(done, total)

        val pending = cues.indices.filter { cues[it].zh.isBlank() }
        val lock = Mutex()
        val gate = Semaphore(max(1, config.concurrency))

        pending.chunked(max(1, config.batchSize)).map { batch ->
            async {
                gate.withPermit {
                    val t0 = System.currentTimeMillis()
                    log("Translating lines ${cues[batch.first()].id}-${cues[batch.last()].id} (${batch.size}), waiting for the model…")
                    val translated = translateBatchResilient(cues, batch)
                    log("Translated ${translated.size} lines in ${(System.currentTimeMillis() - t0) / 1000}s")
                    lock.withLock {
                        translated.forEach { (idx, zh) -> result[idx] = result[idx].copy(zh = zh) }
                        done += translated.size
                        onProgress(done, total)
                        onBatch(result.toList())
                    }
                }
            }
        }.awaitAll()
        result
    }

    /**
     * A slow or flaky model often times out on a big batch (reasoning models especially). Halve the batch and try again
     * instead of failing the whole video; permanent errors (bad key, used-up quota) are rethrown at once.
     */
    private suspend fun translateBatchResilient(all: List<Cue>, batch: List<Int>): Map<Int, String> = try {
        translateBatch(all, batch)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        val transient = e is java.io.IOException || (e is ApiException && e.retryable) || e is TranslationException
        if (batch.size > 1 && transient) {
            val mid = batch.size / 2
            translateBatchResilient(all, batch.subList(0, mid)) + translateBatchResilient(all, batch.subList(mid, batch.size))
        } else throw e
    }

    /** Returns cue-index -> translation for every index in [batch]; throws if a cue can't be translated. */
    private suspend fun translateBatch(all: List<Cue>, batch: List<Int>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        var missing = batch
        for (attempt in 0..config.maxRetries) {
            if (missing.isEmpty()) break
            out += requestOnce(all, missing)
            missing = batch.filter { it !in out }
        }
        // Last resort: one request per stubborn cue.
        if (missing.size > 1 || (missing.size == 1 && batch.size > 1)) {
            for (idx in missing.toList()) {
                repeat(config.maxRetries + 1) {
                    if (idx !in out) out += requestOnce(all, listOf(idx))
                }
            }
            missing = batch.filter { it !in out }
        }
        if (missing.isNotEmpty()) {
            throw TranslationException(
                "Model did not return a usable translation for cue(s) ${missing.map { all[it].id }}"
            )
        }
        return out
    }

    private suspend fun requestOnce(all: List<Cue>, indices: List<Int>): Map<Int, String> {
        val first = indices.first()
        val context = ((first - config.contextLines) until first).filter { it >= 0 }.map { all[it].src }
        val items = buildJsonArray {
            indices.forEach { i ->
                val c = all[i]
                add(buildJsonObject {
                    put("id", c.id)
                    put("en", c.src)
                    if (!config.subtitleMode) put("max_chars", maxChars(all, i))
                })
            }
        }
        val last = indices.last()
        val following = ((last + 1)..(last + 2)).filter { it < all.size }.map { all[it].src }
        val user = buildJsonObject {
            put("context", buildJsonArray { context.forEach { add(JsonPrimitive(it)) } })
            put("items", items)
            put("following", buildJsonArray { following.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        val reply = client.chat(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage("system", systemPrompt()), ChatMessage("user", user)),
                temperature = config.temperature,
                jsonMode = config.jsonMode,
            )
        )
        val byId = parseTranslations(reply.content)
        val out = LinkedHashMap<Int, String>()
        for (i in indices) {
            val raw = byId[all[i].id]?.trim().orEmpty()
            val zh = tidyEllipsis(all[i].src, if (config.subtitleMode) tidyLines(raw) else raw)
            if (zh.isNotEmpty()) out[i] = zh
        }
        if (out.size < indices.size) {
            log("The reply covered ${out.size} of ${indices.size} lines" + if (byId.isEmpty()) " (could not read it as JSON)" else "")
        }
        return out
    }

    /** Characters that comfortably fit in the time this cue may occupy. */
    internal fun maxChars(all: List<Cue>, i: Int): Int {
        val c = all[i]
        val next = all.getOrNull(i + 1)?.startMs
        val window = if (next == null) c.durationMs else max(c.durationMs, min(next - c.startMs, c.durationMs + 1500))
        return max(8, (window / 1000.0 * config.charsPerSecond).roundToInt())
    }

    internal fun systemPrompt(): String = if (config.subtitleMode) subtitlePrompt() else dubbingPrompt()

    /** For text people read on screen (no speaking-time limit); tuned for Chinese viewers of English videos. */
    internal fun subtitlePrompt(): String = buildString {
        append("你是顶级的中文字幕本地化专家，专门把${config.sourceLanguage}视频字幕翻译成自然、流畅、符合中国观众观看习惯的中文。\n")
        append("翻译原则（必须严格遵守）：\n")
        append("· 意译优先，直译为辅：意思对了就行，表达必须像中国人说的话。\n")
        append("· 口语化：字幕是给人看着听的，多用短句、语气词、日常表达；数字、人名、事实不能错。\n")
        append("· 拒绝翻译腔：不要“正在进行中”“这是一个……的事情”“我认为它是……”“被”字句堆叠、一长串“的”这类生硬表达。\n")
        append("· 情绪和语气还原：原文轻松就轻松，吐槽就吐槽，激动就激动，反问、调侃、强调都要带出来。\n")
        append("· 文化本地化：遇到英文俚语、梗、双关，优先找中文里对应的说法或意译，让中国观众也能秒懂。\n")
        append("· 字幕可读性：每行尽量不超过 18 个汉字；译文较长时，在合适的语义停顿处用一个换行符 \\n 分成两行，最多两行。\n")
        append("规则：\n")
        append("1. 只翻译 items 里每条的 \"en\" 字段；context（前文）和 following（后文）仅供理解上下文，不要翻译，也不要输出。\n")
        if (config.keepEnglishTerms) {
            append("2. 专有名词和术语：使用最常见的中文译名；没有固定译名的保留英文。科技、编程、AI 领域里中文圈日常直接说英文的词（agent、prompt、token、API、SDK、LLM、MCP、PR、repo、commit、benchmark、framework、pipeline 等）保留英文，不加括号解释；已有通用中文说法的（数据库、服务器、神经网络、算法）仍用中文。产品名、公司名、人名、代码、命令、缩写保持原样，同一术语全文写法一致。\n")
        } else {
            append("2. 专有名词：使用最常见的中文译名；没有固定译名的保留英文。产品名、人名、代码保持原样，同一术语全文写法一致。\n")
        }
        append("3. 口语里的填充词和重复（you know、like、um、I mean、sort of）按需省略或化成语气，不要逐个翻译。\n")
        append("4. 字幕常在句子中间被切开：某条没有说完时，照原意顺畅地翻出这半句，结尾不要加省略号或破折号，更不要自己补全后文。\n")
        append("5. 人称代词按上下文确定，同一个人前后称呼一致。不要括号注释、表情符号、Markdown。\n")
        append("6. 不要合并或拆分条目，每个 id 必须恰好有一条译文，且不能为空。\n")
        append("7. 只输出 JSON，格式严格为 {\"translations\":[{\"id\":1,\"zh\":\"译文\"}]}，不要输出任何其他文字。\n")
        if (config.glossary.isNotBlank()) append("术语表（必须遵守，格式“原文=译法”；译法写成原文即表示保留原文）：\n${config.glossary.trim()}\n")
        if (config.extraSystemPrompt.isNotBlank()) append("用户补充要求：\n${config.extraSystemPrompt.trim()}\n")
    }

    internal fun dubbingPrompt(): String = buildString {
        append("你是资深的视频字幕译者兼配音文案编辑。把用户给出的${config.sourceLanguage}对话 / 演讲字幕翻译成地道、流畅的简体中文，译文会被直接朗读配音。\n")
        append("翻译要做到“信、达、雅”：\n")
        append("· 信：忠实原意，不增不漏，不曲解；数字、人名、事实、语气（反问、调侃、强调、委婉）都要保留。\n")
        append("· 达：用母语者日常会说的中文表达，不要逐字硬译；必要时调整语序、拆分或合并短语，避免翻译腔（滥用“被”字句、一长串“的”、“我认为……的话”、“一个……的事情”等）。\n")
        append("· 雅：用词准确、简练、有节奏，读出来顺口，像真人在说话，不啰嗦、不书面腔、不口号化。\n")
        append("规则：\n")
        append("1. 只翻译 items 里每条的 \"en\" 字段；context（前文）和 following（后文）仅供理解上下文，不要翻译，也不要输出。\n")
        if (config.keepEnglishTerms) {
            append("2. 专业术语：科技、编程、AI 领域里中文圈日常直接说英文的词，一律保留英文原文，不要硬译，也不要加括号解释。")
            append("例如 agent、prompt、token、API、SDK、LLM、MCP、PR、repo、commit、branch、CI/CD、benchmark、framework、runtime、deploy、fine-tuning、open source、context window、pipeline 等。")
            append("已有广泛通用中文说法的词（如“数据库”“服务器”“神经网络”“算法”）仍用中文。产品名、公司名、人名、代码、命令、文件名、缩写保持原样。同一术语在全文中写法保持一致。\n")
        } else {
            append("2. 术语尽量使用通用的中文译名；没有通用译名的专有名词、产品名、人名、代码保持原样。同一术语在全文中写法保持一致。\n")
        }
        append("3. 口语里的填充词和重复（you know、like、um、I mean、sort of、kind of、基本上说、其实就是）按需省略，或化成自然的语气，不要逐个翻译；保留说话人的态度和幽默。\n")
        append("4. 习语、比喻、玩笑按意思换成自然的中文说法，不要直译；有文化背景的梗，用一句话的分量带过即可。\n")
        append("5. 译文要适合朗读：不要括号注释、表情符号、Markdown；数字、单位、时间按中文口语习惯书写（如“一千多个”“三成”“每个月”）；断句用逗号、句号，不要出现生硬的长句。\n")
        append("6. 译文汉字数尽量不超过该条的 \"max_chars\"（对应时间有限），必要时精简、意译，但不能丢失关键信息。\n")
        append("7. 人称代词按上下文确定（he / she / they、you 指谁），同一个人前后称呼一致。\n")
        append("8. 字幕常在句子中间被切开：某条没有说完时，照原意顺畅地翻出这半句，结尾不要加省略号（……、...）或破折号来表示未完，更不要自己补全后文。\n")
        append("9. 不要合并或拆分条目，每个 id 必须恰好有一条译文，且不能为空。\n")
        append("10. 只输出 JSON，格式严格为 {\"translations\":[{\"id\":1,\"zh\":\"译文\"}]}，不要输出任何其他文字。\n")
        if (config.glossary.isNotBlank()) append("术语表（必须遵守，格式“原文=译法”；译法写成原文即表示保留原文）：\n${config.glossary.trim()}\n")
        if (config.extraSystemPrompt.isNotBlank()) append("用户补充要求：\n${config.extraSystemPrompt.trim()}\n")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val FENCE = Regex("```[a-zA-Z]*")

        private val TRAILING_ELLIPSIS = Regex("""(?:…+|\.{2,}|。{2,})[\s"'”’)）]*$""")

        /** Keeps at most two non-empty lines, so a line break in a subtitle never turns into a blank line. */
        internal fun tidyLines(zh: String): String {
            val lines = zh.replace("\\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                lines.size <= 2 -> lines.joinToString("\n")
                else -> lines.take(1).joinToString("") + "\n" + lines.drop(1).joinToString("")
            }
        }

        /** Models like to trail off with "……" on a cut-off line. Keep it only when the English itself trails off. */
        internal fun tidyEllipsis(src: String, zh: String): String {
            if (zh.isEmpty() || TRAILING_ELLIPSIS.containsMatchIn(src.trimEnd())) return zh
            return zh.replace(TRAILING_ELLIPSIS, "").trimEnd('，', ',', '、', ' ').ifEmpty { zh }
        }

        /** Accepts `{"translations":[...]}`, a bare array, or an `{"1":"..."}` map; tolerates fences and <think> blocks. */
        fun parseTranslations(content: String): Map<Int, String> {
            val cleaned = content.replace(THINK, "").replace(FENCE, "").trim()
            val element = parseLenient(cleaned) ?: return emptyMap()
            val out = LinkedHashMap<Int, String>()
            fun readItem(o: JsonObject) {
                val id = (o["id"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
                val zh = listOf("zh", "translation", "text", "cn").firstNotNullOfOrNull {
                    (o[it] as? JsonPrimitive)?.contentOrNull
                }
                if (id != null && zh != null) out[id] = zh
            }
            fun readArray(a: JsonArray) = a.forEach { (it as? JsonObject)?.let(::readItem) }
            when (element) {
                is JsonArray -> readArray(element)
                is JsonObject -> {
                    val arr = element["translations"] as? JsonArray
                        ?: element.values.firstOrNull { it is JsonArray } as? JsonArray
                    if (arr != null) readArray(arr)
                    else if (element["id"] != null) readItem(element)
                    else element.forEach { (k, v) ->
                        val id = k.toIntOrNull()
                        val text = (v as? JsonPrimitive)?.contentOrNull
                        if (id != null && text != null) out[id] = text
                    }
                }
                else -> {}
            }
            return out
        }

        private fun parseLenient(s: String): JsonElement? {
            runCatching { return json.parseToJsonElement(s) }
            for ((open, close) in listOf('{' to '}', '[' to ']')) {
                val a = s.indexOf(open)
                val b = s.lastIndexOf(close)
                if (a in 0 until b) runCatching { return json.parseToJsonElement(s.substring(a, b + 1)) }
            }
            return null
        }
    }
}
