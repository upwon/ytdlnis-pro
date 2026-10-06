package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The "forward service" of the MultiTTS Android app: `GET {base}/forward?text&speed&volume&pitch&voice` returns audio,
 * `GET {base}/voices` lists the voices. Everything runs on the phone, so voice packs installed there work offline.
 */
class ForwardTts(
    baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
    private val volume: Int = 50,
    private val pitch: Int = 50,
) : TtsProvider {
    private val base = baseUrl.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/')

    // the clip's real format is sniffed by ffmpeg / MediaPlayer, the extension is only a name
    override val fileExtension: String get() = "mp3"

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        // MultiTTS speed is 0..100 with 50 = normal; map +-100 % onto it
        val speed = (50 + ratePercent / 2).coerceIn(0, 100)
        val url = "$base/forward".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .addQueryParameter("speed", speed.toString())
            .addQueryParameter("volume", volume.toString())
            .addQueryParameter("pitch", pitch.toString())
            .addQueryParameter("voice", voice)
            .build()
        withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) throw ApiException(resp.code, "MultiTTS: HTTP ${resp.code} ${bytes.decodeToString().take(200)}")
                    if (bytes.size < 200) throw IOException("MultiTTS returned no audio: ${bytes.decodeToString().take(200)}")
                    outFile.writeBytes(bytes)
                }
            } catch (e: java.net.ConnectException) {
                throw IOException("Cannot reach MultiTTS at $base - open MultiTTS and switch on its forward service (转发服务)", e)
            }
        }
    }

    /** (voice id, display name) pairs from `/voices`; the reply shape is not documented so several are accepted. */
    suspend fun listVoices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val body = try {
            http.newCall(Request.Builder().url("$base/voices").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "MultiTTS: HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        } catch (e: java.net.ConnectException) {
            throw IOException("Cannot reach MultiTTS at $base - open MultiTTS and switch on its forward service (转发服务)", e)
        }
        parseVoices(body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8774"
        private val ID_KEYS = listOf("id", "voice", "voiceCode", "code", "key", "value", "name")
        private val NAME_KEYS = listOf("name", "displayName", "label", "title", "voiceName", "nickname")

        fun parseVoices(body: String): List<Pair<String, String>> {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            val out = LinkedHashMap<String, String>()
            fun add(id: String?, name: String?) {
                if (!id.isNullOrBlank()) out.putIfAbsent(id, name?.takeIf { it.isNotBlank() } ?: id)
            }
            fun str(o: JsonObject, keys: List<String>): String? =
                keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            fun walk(e: JsonElement, parentKey: String? = null) {
                when (e) {
                    is JsonArray -> e.forEach { walk(it, parentKey) }
                    is JsonPrimitive -> add(e.contentOrNull, e.contentOrNull)
                    is JsonObject -> {
                        val id = str(e, ID_KEYS)
                        if (id != null) add(id, str(e, NAME_KEYS))
                        else e.forEach { (k, v) ->
                            if (v is JsonPrimitive) add(k, v.contentOrNull) else walk(v, k)
                        }
                    }
                }
            }
            walk(root)
            return out.map { it.key to it.value }
        }
    }
}
