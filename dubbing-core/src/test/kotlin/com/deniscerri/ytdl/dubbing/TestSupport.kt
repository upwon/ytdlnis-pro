package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

object TestMedia {
    val ffmpeg = ProcessFfmpeg()

    fun tempDir(name: String): File = Files.createTempDirectory("dub-$name").toFile().apply { deleteOnExit() }

    fun ff(vararg args: String) {
        val r = runBlocking { ffmpeg.run(args.toList()) }
        check(r.ok) { "ffmpeg ${args.joinToString(" ")} failed:\n${r.log}" }
    }

    /** Test video: colour bars + (optionally) a constant sine tone, with the container chosen by extension. */
    fun makeVideo(file: File, seconds: Int, audio: Boolean = true, freq: Int = 880, extraArgs: List<String> = emptyList()) {
        val args = mutableListOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=$seconds")
        if (audio) args += listOf("-f", "lavfi", "-i", "sine=frequency=$freq:duration=$seconds:sample_rate=44100")
        val vcodec = if (file.extension == "webm") listOf("-c:v", "libvpx", "-b:v", "200k") else listOf("-c:v", "libx264", "-pix_fmt", "yuv420p")
        args += vcodec
        if (audio) args += if (file.extension == "webm") listOf("-c:a", "libopus") else listOf("-c:a", "aac")
        args += extraArgs
        args += file.absolutePath
        ff(*args.toTypedArray())
    }

    fun makeAudioOnly(file: File, seconds: Int) =
        ff("-f", "lavfi", "-i", "sine=frequency=500:duration=$seconds", "-c:a", "aac", file.absolutePath)

    /** Mono 24 kHz s16le samples of audio stream [index] of [file]. */
    fun decode(file: File, index: Int = 0): ShortArray {
        val out = File.createTempFile("dec", ".pcm")
        try {
            ff("-i", file.absolutePath, "-map", "0:a:$index", "-f", "s16le", "-ac", "1", "-ar", "24000", out.absolutePath)
            return pcm(out)
        } finally { out.delete() }
    }

    fun pcm(file: File): ShortArray {
        val b = file.readBytes()
        return ShortArray(b.size / 2) { ((b[it * 2 + 1].toInt() shl 8) or (b[it * 2].toInt() and 0xFF)).toShort() }
    }

    /** RMS (0..32767) of the 24 kHz mono [samples] between [fromMs, toMs). */
    fun rms(samples: ShortArray, fromMs: Long, toMs: Long): Double {
        val a = (fromMs * 24).toInt().coerceIn(0, samples.size)
        val b = (toMs * 24).toInt().coerceIn(a, samples.size)
        if (b == a) return 0.0
        var sum = 0.0
        for (i in a until b) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (b - a))
    }

    fun loud(samples: ShortArray, fromMs: Long, toMs: Long) = rms(samples, fromMs, toMs) > 800
    fun quiet(samples: ShortArray, fromMs: Long, toMs: Long) = rms(samples, fromMs, toMs) < 150

    fun probe(file: File): JsonObject {
        val p = ProcessBuilder(
            "ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", file.absolutePath
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Json.parseToJsonElement(text).jsonObject
    }

    fun streams(file: File, type: String): List<JsonObject> =
        probe(file)["streams"]!!.jsonArray.map { it.jsonObject }.filter { it["codec_type"]!!.jsonPrimitive.content == type }

    fun durationSec(file: File): Double = probe(file)["format"]!!.jsonObject["duration"]!!.jsonPrimitive.content.toDouble()

    fun tag(stream: JsonObject, key: String): String? =
        (stream["tags"] as? JsonObject)?.get(key)?.jsonPrimitive?.content

    fun disposition(stream: JsonObject, key: String): Int =
        stream["disposition"]!!.jsonObject[key]!!.jsonPrimitive.content.toInt()
}

/**
 * Fake TTS: a sine tone whose length is proportional to the text length, with some silence around it
 * (like real engines) so trimming and tempo handling are exercised.
 */
class ToneTts(
    private val secondsPerChar: Double = 0.25,
    private val padSeconds: Double = 0.15,
    private val delayMs: Long = 0,
    private val failOn: (String) -> Boolean = { false },
) : TtsProvider {
    data class Call(val text: String, val voice: String, val rate: Int)

    val calls = CopyOnWriteArrayList<Call>()
    override val fileExtension = "mp3"

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        calls += Call(text, voice, ratePercent)
        if (delayMs > 0) delay(delayMs)
        if (failOn(text)) throw java.io.IOException("synthetic TTS failure")
        val dur = maxOf(0.3, TtsText.spokenLength(text) * secondsPerChar / (1.0 + ratePercent / 100.0))
        TestMedia.ff(
            "-f", "lavfi", "-i", "sine=frequency=440:duration=$dur:sample_rate=24000",
            "-af", "adelay=${(padSeconds * 1000).toInt()}:all=1,apad=pad_dur=$padSeconds",
            "-c:a", "libmp3lame", outFile.absolutePath,
        )
    }
}

class FakeChat(private val handler: (ChatRequest) -> String) : ChatClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()
    override suspend fun chat(request: ChatRequest): ChatResult {
        requests += request
        return ChatResult(handler(request))
    }

    companion object {
        /** Parses the translator's user message and answers like a well-behaved model. */
        fun userItems(r: ChatRequest): List<Pair<Int, String>> {
            val o = Json.parseToJsonElement(r.messages.last().content).jsonObject
            return (o["items"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content.toInt() to it.jsonObject["en"]!!.jsonPrimitive.content }
        }

        fun echoZh(r: ChatRequest, prefix: String = "译文"): String =
            userItems(r).joinToString(",", """{"translations":[""", "]}") { (id, en) ->
                """{"id":$id,"zh":"$prefix$id:${en.take(6)}"}"""
            }
    }
}
