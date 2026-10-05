package com.deniscerri.ytdl.dubbing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val jsonMode: Boolean = false,
)

data class ChatResult(
    val content: String,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

interface ChatClient {
    suspend fun chat(request: ChatRequest): ChatResult
}

data class ModelInfo(
    val id: String,
    val name: String? = null,
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
) {
    /** True only when the provider reports zero pricing (or the OpenRouter ":free" suffix). */
    val isFree: Boolean
        get() = (promptPrice == 0.0 && completionPrice == 0.0) || id.endsWith(":free")
}

interface AsrProvider {
    /** Returns cues relative to the start of [audio]. */
    suspend fun transcribe(audio: File, language: String?, fallbackDurationMs: Long): List<Cue>
}

/**
 * Minimal client for any "OpenAI compatible" endpoint: DeepSeek, Qwen/DashScope, GLM, Kimi, SiliconFlow,
 * OpenRouter, Groq, Ollama, OpenAI itself ...
 */
class OpenAiCompatClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val maxAttempts: Int = 4,
    private val retryBaseDelayMs: Long = 1500,
    /** Hard limit for one chat request. The read timeout alone never fires when a gateway keeps the line alive while queueing. */
    private val chatTimeoutSeconds: Long = 150,
    /** Receives one line per request outcome (reply head, HTTP error, timeout, retry) for the app's details view. */
    private val log: (String) -> Unit = {},
    /**
     * Extra JSON fields merged into every chat request, e.g. {"thinking":{"type":"disabled"}} (Zhipu) or
     * {"reasoning_effort":"low"} (gpt-oss): reasoning models otherwise "think" for minutes before answering.
     */
    extraBodyJson: String = "",
) : ChatClient {
    private val extraBody: JsonObject? = extraBodyJson.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull().also { parsed ->
            if (parsed == null) log("The extra request parameters are not a valid JSON object and are ignored")
        }
    }

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url("$base$path")
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> "HTTP ${e.code}: ${e.body.replace(Regex("\\s+"), " ").take(240)}"
        is java.io.InterruptedIOException -> "no answer in time (timeout)"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    override suspend fun chat(request: ChatRequest): ChatResult = retrying(
        maxAttempts, retryBaseDelayMs,
        onError = { attempt, e, willRetry ->
            log("Model request failed (attempt $attempt/$maxAttempts): ${describe(e)}" + if (willRetry) " — trying again" else "")
        },
    ) {
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            })
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            if (request.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            extraBody?.forEach { (k, v) -> put(k, v) }
        }
        val call = http.newCall(request("/chat/completions").post(body.toString().toRequestBody(jsonType)).build())
        call.timeout().timeout(chatTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        parseChat(call.awaitBytes().toString(Charsets.UTF_8)).also {
            log("Model replied in ${(System.currentTimeMillis() - t0) / 1000.0}s: " + it.content.replace(Regex("\\s+"), " ").take(200))
        }
    }

    internal fun parseChat(raw: String): ChatResult {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw ApiException(502, "Not a JSON response: ${raw.take(200)}")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice == null) {
            // Some gateways (OpenRouter) answer 200 with {"error": {...}}
            val err = root["error"] as? JsonObject
            val code = err?.get("code")?.jsonPrimitive?.intOrNull ?: 502
            val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.take(300)
            throw ApiException(code, msg)
        }
        val message = choice["message"] as? JsonObject
        val content = when (val c = message?.get("content")) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> ""
        }
        val usage = root["usage"] as? JsonObject
        return ChatResult(
            content = content,
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    suspend fun listModels(): List<ModelInfo> = retrying(maxAttempts, retryBaseDelayMs) {
        val raw = http.newCall(request("/models").get().build()).awaitBytes().toString(Charsets.UTF_8)
        val data = (json.parseToJsonElement(raw).jsonObject["data"] as? JsonArray).orEmpty()
        data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val pricing = o["pricing"] as? JsonObject
            ModelInfo(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull,
                promptPrice = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                completionPrice = pricing?.get("completion")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            )
        }
    }

    /** `POST /audio/transcriptions` (Whisper style). Segment timestamps are used when the provider returns them. */
    suspend fun transcribe(file: File, model: String, language: String?, fallbackDurationMs: Long): List<Cue> =
        retrying(maxAttempts, retryBaseDelayMs) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
            if (!language.isNullOrBlank()) form.addFormDataPart("language", language)
            form.addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            val call = http.newCall(request("/audio/transcriptions").post(form.build()).build())
            call.timeout().timeout(480, java.util.concurrent.TimeUnit.SECONDS)
            val raw = call.awaitBytes().toString(Charsets.UTF_8)
            parseTranscription(raw, fallbackDurationMs)
        }

    internal fun parseTranscription(raw: String, fallbackDurationMs: Long): List<Cue> {
        val root = try { json.parseToJsonElement(raw).jsonObject } catch (e: Exception) {
            // response_format fallback: some servers return plain text
            return if (raw.isBlank()) emptyList() else listOf(Cue(1, 0, fallbackDurationMs, raw.trim()))
        }
        val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val s = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val e = o["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (text.isEmpty()) null else Triple((s * 1000).toLong(), (e * 1000).toLong(), text)
        }
        if (segments.isNotEmpty()) return segments.mapIndexed { i, (s, e, t) -> Cue(i + 1, s, e, t) }
        val text = root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return if (text.isEmpty()) emptyList() else listOf(Cue(1, 0, fallbackDurationMs, text))
    }

    /** `POST /audio/speech`; returns the encoded audio (mp3 by default). */
    suspend fun speech(model: String, voice: String, text: String, speed: Double?, format: String = "mp3"): ByteArray =
        retrying(maxAttempts, retryBaseDelayMs) {
            val body = buildJsonObject {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("response_format", format)
                speed?.let { put("speed", it) }
            }
            val bytes = http.newCall(request("/audio/speech").post(body.toString().toRequestBody(jsonType)).build())
                .awaitBytes()
            if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) {
                throw ApiException(422, "TTS returned no audio: ${bytes.toString(Charsets.UTF_8).take(200)}")
            }
            bytes
        }
}

class OpenAiCompatAsr(private val client: OpenAiCompatClient, private val model: String) : AsrProvider {
    override suspend fun transcribe(audio: File, language: String?, fallbackDurationMs: Long): List<Cue> =
        client.transcribe(audio, model, language, fallbackDurationMs)
}
