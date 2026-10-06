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
                    val bytes = resp.body.bytes()
                    if (!resp.isSuccessful) throw ApiException(resp.code, "MultiTTS: HTTP ${resp.code} ${bytes.decodeToString().take(200)}")
                    if (bytes.size < 200) throw IOException("MultiTTS returned no audio: ${bytes.decodeToString().take(200)}")
                    outFile.writeBytes(bytes)
                }
            } catch (e: java.net.ConnectException) {
                throw IOException("Cannot reach MultiTTS at $base - open MultiTTS and switch on its forward service (转发服务)", e)
            }
        }
    }

    /** Every voice MultiTTS reports; see [parseVoices]. */
    suspend fun listVoices(): List<ForwardVoice> = withContext(Dispatchers.IO) {
        val body = try {
            http.newCall(Request.Builder().url("$base/voices").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "MultiTTS: HTTP ${resp.code}")
                resp.body.string()
            }
        } catch (e: java.net.ConnectException) {
            throw IOException("Cannot reach MultiTTS at $base - open MultiTTS and switch on its forward service (转发服务)", e)
        }
        parseVoices(body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8774"

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        /**
         * MultiTTS answers `{"success":true,"data":{"count":N,"catalog":{"<engine>":[{"id","name","gender","locale","desc","type"}]}}}`.
         * A plain array of such objects (or of id strings) is accepted too.
         */
        fun parseVoices(body: String): List<ForwardVoice> {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            val out = LinkedHashMap<String, ForwardVoice>()
            fun add(o: JsonObject, engine: String) {
                val id = o.text("id") ?: o.text("voice") ?: o.text("voiceCode") ?: return
                out.putIfAbsent(id, ForwardVoice(
                    id = id,
                    name = o.text("name") ?: o.text("displayName") ?: id,
                    gender = o.text("gender").orEmpty(),
                    locale = o.text("locale").orEmpty(),
                    desc = o.text("desc").orEmpty(),
                    online = o.text("type") == "online",
                    engine = engine,
                ))
            }
            val catalog = ((root as? JsonObject)?.get("data") as? JsonObject)?.get("catalog") as? JsonObject
            if (catalog != null) {
                catalog.forEach { (engine, list) -> (list as? JsonArray)?.forEach { (it as? JsonObject)?.let { o -> add(o, engine) } } }
            } else {
                val list = root as? JsonArray ?: ((root as? JsonObject)?.get("data") as? JsonArray) ?: return emptyList()
                list.forEach { e ->
                    when (e) {
                        is JsonObject -> add(e, "")
                        is JsonPrimitive -> e.contentOrNull?.let { id -> out.putIfAbsent(id, ForwardVoice(id, id, "", "", "", false, "")) }
                        else -> {}
                    }
                }
            }
            return out.values.toList()
        }
    }
}

data class ForwardVoice(
    val id: String,
    val name: String,
    val gender: String,
    val locale: String,
    val desc: String,
    val online: Boolean,
    val engine: String,
) {
    val isMale get() = gender == "male"
    val isFemale get() = gender == "female"
    val isMandarin get() = locale.isEmpty() || locale == "zh-CN"
}
