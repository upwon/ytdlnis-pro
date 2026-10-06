package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Microsoft Edge "Read aloud" neural voices, free and keyless.
 *
 * NOTE: this is an unofficial endpoint (same one the `edge-tts` Python package uses). Microsoft may change
 * the handshake at any time, so always keep a fallback provider configured (see [FallbackTts]).
 */
class EdgeTts(
    private val http: OkHttpClient = defaultHttpClient(),
    private val wssUrl: String = DEFAULT_WSS_URL,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 30_000,
) : TtsProvider {
    override val fileExtension: String get() = "mp3"

    @Volatile private var clockSkewSeconds = 0.0

    class HandshakeRejected(val serverDateMs: Long?) : IOException("Edge TTS handshake rejected (HTTP 403)")

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        val audio = try {
            once(text, voice, ratePercent)
        } catch (e: HandshakeRejected) {
            // 403 usually means our clock differs from Microsoft's: adopt the server's time and retry once.
            val serverMs = e.serverDateMs ?: throw e
            clockSkewSeconds = (serverMs - clockMs()) / 1000.0
            once(text, voice, ratePercent)
        }
        outFile.writeBytes(audio)
    }

    private suspend fun once(text: String, voice: String, ratePercent: Int): ByteArray {
        val audio = ByteArrayOutputStream()
        val done = CompletableDeferred<Unit>()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url = "$wssUrl&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${secMsGec(clockMs(), clockSkewSeconds)}&Sec-MS-GEC-Version=$GEC_VERSION"
        val request = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "X-Timestamp:${jsDate()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
                        "\r\n"
                )
                webSocket.send(
                    "X-RequestId:$connectionId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${jsDate()}Z\r\nPath:ssml\r\n\r\n" +
                        Ssml.build(text, voice, ratePercent, langOf(voice))
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) done.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 2) return
                val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                if (bytes.size < 2 + headerLen) return
                val headers = bytes.substring(2, 2 + headerLen).utf8()
                if (headers.contains("Path:audio")) {
                    val body = bytes.substring(2 + headerLen)
                    if (body.size > 0) synchronized(audio) { audio.write(body.toByteArray()) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                done.completeExceptionally(IOException("Edge TTS closed before turn.end ($code $reason)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.completeExceptionally(IOException("Edge TTS closed before turn.end ($code $reason)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    val date = response.header("Date")?.let {
                        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    }
                    done.completeExceptionally(HandshakeRejected(date))
                } else {
                    done.completeExceptionally(
                        if (response != null) ApiException(response.code, t.message.orEmpty()) else IOException(t.message ?: t.javaClass.simpleName, t)
                    )
                }
            }
        })
        try {
            try {
                withTimeout(timeoutMs) { done.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // a timeout is a failure of this request, not a cancellation of the whole job
                throw IOException("Edge TTS did not answer within ${timeoutMs / 1000}s")
            }
        } finally {
            ws.cancel()
        }
        val bytes = synchronized(audio) { audio.toByteArray() }
        if (bytes.isEmpty()) throw IOException("Edge TTS returned no audio")
        return bytes
    }

    companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        const val GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        const val DEFAULT_WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        private const val WIN_EPOCH_SECONDS = 11_644_473_600L

        /** Token the Edge servers require: SHA-256 of the 5-minute-rounded Windows file-time + client token. */
        fun secMsGec(nowMs: Long, skewSeconds: Double = 0.0): String {
            var ticks = (nowMs / 1000.0 + skewSeconds).toLong() + WIN_EPOCH_SECONDS
            ticks -= ticks % 300
            val input = (ticks * 10_000_000L).toString() + TRUSTED_CLIENT_TOKEN
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02X".format(it) }
        }

        internal fun jsDate(): String {
            val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date())
        }

        internal fun langOf(voice: String): String =
            Regex("^([a-z]{2,3}-[A-Z]{2})-").find(voice)?.groupValues?.get(1) ?: "en-US"
    }
}
