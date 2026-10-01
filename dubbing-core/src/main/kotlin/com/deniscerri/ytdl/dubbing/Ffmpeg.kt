package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

data class FfmpegResult(val exitCode: Int, val log: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Runs ffmpeg. The Android app provides an implementation backed by the bundled binary. */
interface FfmpegRunner {
    suspend fun run(args: List<String>): FfmpegResult
}

class ProcessFfmpeg(
    private val executable: String = "ffmpeg",
    private val environment: Map<String, String> = emptyMap(),
    private val maxLogLines: Int = 400,
) : FfmpegRunner {
    override suspend fun run(args: List<String>): FfmpegResult = coroutineScope {
        val pb = ProcessBuilder(listOf(executable, "-hide_banner", "-nostdin", "-y") + args).redirectErrorStream(true)
        pb.environment().putAll(environment)
        val process = withContext(Dispatchers.IO) { pb.start() }
        val reader = async(Dispatchers.IO) {
            val lines = ArrayDeque<String>()
            process.inputStream.bufferedReader().forEachLine {
                lines.addLast(it)
                if (lines.size > maxLogLines) lines.removeFirst()
            }
            FfmpegResult(process.waitFor(), lines.joinToString("\n"))
        }
        try {
            reader.await()
        } catch (e: CancellationException) {
            process.destroyForcibly() // closes the stream, which lets the reader finish
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

data class AudioStreamInfo(val language: String?, val codec: String?)

data class MediaInfo(
    val durationMs: Long,
    val hasVideo: Boolean,
    val audioStreams: List<AudioStreamInfo>,
    val subtitleStreams: Int,
)

private val DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
private val STREAM = Regex("""^\s*Stream #\d+:\d+(?:\[[^\]]*\])?(?:\((\w+)\))?[^:]*:\s*(Video|Audio|Subtitle):\s*([^\s,]+)(.*)$""")

/** Parses the banner printed by `ffmpeg -i file` (no ffprobe needed on Android). */
fun parseMediaInfo(log: String): MediaInfo {
    var durationMs = 0L
    DURATION.find(log)?.let {
        val (h, m, s, frac) = it.destructured
        durationMs = ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 +
            frac.padEnd(3, '0').take(3).toLong()
    }
    var hasVideo = false
    var subs = 0
    val audio = ArrayList<AudioStreamInfo>()
    for (line in log.lines()) {
        val m = STREAM.find(line) ?: continue
        val (lang, kind, codec, rest) = m.destructured
        when (kind) {
            "Video" -> if (!rest.contains("attached pic")) hasVideo = true
            "Audio" -> audio += AudioStreamInfo(lang.ifEmpty { null }, codec)
            "Subtitle" -> subs++
        }
    }
    return MediaInfo(durationMs, hasVideo, audio, subs)
}

suspend fun FfmpegRunner.probe(file: File): MediaInfo {
    // `ffmpeg -i file` exits with 1 (no output given) but prints everything we need.
    val r = run(listOf("-i", file.absolutePath))
    if (!r.log.contains("Input #0")) throw DubbingException("Cannot read media file ${file.name}:\n${r.log.takeLast(500)}")
    return parseMediaInfo(r.log)
}
