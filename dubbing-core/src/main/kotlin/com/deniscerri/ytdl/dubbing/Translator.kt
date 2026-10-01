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
)

class TranslationException(message: String) : Exception(message)

class LlmTranslator(
    private val client: ChatClient,
    private val model: String,
    private val config: TranslatorConfig = TranslatorConfig(),
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
                    val translated = translateBatch(cues, batch)
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
                    put("max_chars", maxChars(all, i))
                })
            }
        }
        val user = buildJsonObject {
            put("context", buildJsonArray { context.forEach { add(JsonPrimitive(it)) } })
            put("items", items)
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
            val zh = byId[all[i].id]?.trim().orEmpty()
            if (zh.isNotEmpty()) out[i] = zh
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

    private fun systemPrompt(): String = buildString {
        append("你是专业的视频配音翻译。把用户给出的${config.sourceLanguage}字幕逐条翻译成自然、口语化的简体中文，译文将被直接朗读配音。\n")
        append("规则：\n")
        append("1. 只翻译 items 里每条的 \"en\" 字段；context 仅供理解上下文，不要翻译，也不要输出。\n")
        append("2. 译文要适合朗读：不要括号注释、表情符号或 Markdown；数字、单位按中文口语习惯书写。\n")
        append("3. 译文汉字数尽量不超过该条的 \"max_chars\"，必要时意译、精简，但不能丢失关键信息。\n")
        append("4. 人名、术语前后保持一致；没有通用译名的专有名词可保留原文。\n")
        append("5. 不要合并或拆分条目，每个 id 必须恰好有一条译文。\n")
        append("6. 只输出 JSON，格式严格为 {\"translations\":[{\"id\":1,\"zh\":\"译文\"}]}，不要输出任何其他文字。\n")
        if (config.glossary.isNotBlank()) append("术语表（必须遵守）：\n${config.glossary.trim()}\n")
        if (config.extraSystemPrompt.isNotBlank()) append(config.extraSystemPrompt.trim()).append('\n')
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val FENCE = Regex("```[a-zA-Z]*")

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
