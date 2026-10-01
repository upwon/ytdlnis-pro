package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

interface TtsProvider {
    /** Extension hint for the clip files this provider writes ("mp3", "wav", ...). */
    val fileExtension: String

    /**
     * Synthesizes [text] into [outFile].
     * @param ratePercent speaking-rate change relative to normal, e.g. +20 = 20% faster, -10 = slower.
     */
    suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File)
}

object Ssml {
    fun escape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    fun signed(v: Int): String = if (v >= 0) "+$v" else "$v"

    fun build(text: String, voice: String, ratePercent: Int, lang: String = "zh-CN"): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='${escape(voice)}'><prosody pitch='+0Hz' rate='${signed(ratePercent)}%' volume='+0%'>" +
            escape(text) + "</prosody></voice></speak>"
}

object TtsText {
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n]]")
    private val MARKUP = Regex("[*_#`~^|\\\\]")

    fun clean(text: String): String = text
        .replace(CONTROL, " ").replace(MARKUP, " ")
        .replace(Regex("\\s+"), " ").trim()

    /** True if there is something a voice could actually pronounce (a letter or digit, any script). */
    fun speakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /** Characters that count towards spoken length (Chinese chars, letters, digits). */
    fun spokenLength(text: String): Int = text.count { it.isLetterOrDigit() }
}

/** `POST {base}/audio/speech` (OpenAI, SiliconFlow CosyVoice, Groq, self-hosted ...). */
class OpenAiCompatTts(
    private val client: OpenAiCompatClient,
    private val model: String,
    private val format: String = "mp3",
) : TtsProvider {
    override val fileExtension: String get() = format

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        val speed = (1.0 + ratePercent / 100.0).coerceIn(0.25, 4.0)
        outFile.writeBytes(client.speech(model, voice, text, speed, format))
    }
}

/** Official Azure Speech REST API (free F0 tier: 500k neural characters / month). */
class AzureTts(
    private val region: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val endpoint: String = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
    private val maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 1000,
) : TtsProvider {
    override val fileExtension: String get() = "mp3"

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        val ssml = Ssml.build(text, voice, ratePercent)
        val bytes = retrying(maxAttempts, retryBaseDelayMs) {
            val req = Request.Builder().url(endpoint)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                .header("User-Agent", "ytdlnis-dubbing")
                .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                .build()
            http.newCall(req).awaitBytes()
        }
        if (bytes.isEmpty()) throw ApiException(502, "Azure TTS returned no audio")
        outFile.writeBytes(bytes)
    }
}

/**
 * Tries [primary] first; when it fails (blocked endpoint, quota ...) the clip is produced by [fallback].
 * After [maxPrimaryFailures] failures in a row the primary is skipped for the rest of the job.
 */
class FallbackTts(
    private val primary: TtsProvider,
    private val fallback: TtsProvider,
    private val fallbackVoice: String,
    private val maxPrimaryFailures: Int = 3,
    private val log: (String) -> Unit = {},
) : TtsProvider {
    @Volatile private var consecutiveFailures = 0
    override val fileExtension: String get() = primary.fileExtension

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        if (consecutiveFailures < maxPrimaryFailures) {
            try {
                primary.synthesize(text, voice, ratePercent, outFile)
                consecutiveFailures = 0
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                log("Primary TTS failed (${e.message}); using fallback voice")
            }
        }
        fallback.synthesize(text, fallbackVoice, ratePercent, outFile)
    }
}
