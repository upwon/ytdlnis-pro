package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class AlignConfig(
    val sampleRate: Int = 24_000,
    /** Fastest we are willing to speed a clip up so it fits (1.5x is still clearly intelligible). */
    val maxTempo: Double = 1.5,
    /** How far past the cue end a clip may run into the silence before the next cue. */
    val maxBorrowMs: Long = 2_000,
    /** Max amount the following speech may be pushed later because a clip overran. */
    val maxDriftMs: Long = 1_500,
    val minWindowMs: Long = 500,
    val concurrency: Int = 4,
    val fadeMs: Int = 30,
)

data class AlignReport(
    val clips: Int,
    val spedUp: Int,
    val shifted: Int,
    val truncated: Int,
    val skipped: Int,
    val maxTempoUsed: Double,
)

/**
 * Lays the synthesized clips on a single mono 16-bit PCM timeline (raw s16le, [AlignConfig.sampleRate] Hz).
 * The track is written as a stream, so memory stays flat even for multi-hour videos.
 */
class TimelineAligner(
    private val ffmpeg: FfmpegRunner,
    private val config: AlignConfig = AlignConfig(),
    private val log: (String) -> Unit = {},
) {
    private class Clip(val cue: Cue, val file: File, var pcm: File, var samples: Long)
    private class Plan(val clip: Clip, val startMs: Long, val tempo: Double, val truncateMs: Long?)

    private fun msToSamples(ms: Long) = ms * config.sampleRate / 1000
    private fun samplesToMs(s: Long) = s * 1000 / config.sampleRate

    suspend fun align(cues: List<Cue>, workDir: File, totalMs: Long, outPcm: File): AlignReport {
        val pcmDir = File(workDir, "pcm").apply { mkdirs() }
        val gate = Semaphore(max(1, config.concurrency))
        var skipped = 0

        // Pass 1: decode every clip (and trim its leading/trailing silence).
        val candidates = cues.sortedBy { it.startMs }.filter { it.ttsFile != null }
        val decoded = coroutineScope {
            candidates.map { cue ->
                async {
                    gate.withPermit {
                        val src = File(workDir, cue.ttsFile!!)
                        val pcm = File(pcmDir, "%05d.pcm".format(cue.id))
                        if (!src.exists() || !decode(src, pcm, 1.0)) null
                        else Clip(cue, src, pcm, pcm.length() / 2)
                    }
                }
            }.awaitAll()
        }
        val clips = decoded.filterNotNull().filter { it.samples > 0 }
        skipped += candidates.size - clips.size

        // Plan placement + tempo.
        val plans = ArrayList<Plan>()
        var cursor = 0L
        var shifted = 0
        var truncated = 0
        clips.forEachIndexed { i, clip ->
            val cue = clip.cue
            val d = samplesToMs(clip.samples)
            val start = max(cue.startMs, cursor)
            if (start > cue.startMs) shifted++
            val nextStart = clips.getOrNull(i + 1)?.cue?.startMs
            val windowEnd = max(cue.endMs, min(nextStart ?: Long.MAX_VALUE, cue.endMs + config.maxBorrowMs))
            val window = max(config.minWindowMs, windowEnd - start)
            var tempo = 1.0
            var dur = d
            if (d > window) {
                tempo = min(config.maxTempo, d.toDouble() / window)
                dur = (d / tempo).toLong()
            }
            var trunc: Long? = null
            if (nextStart != null && start + dur - nextStart > config.maxDriftMs) {
                trunc = max(config.minWindowMs, nextStart + config.maxDriftMs - start)
                dur = trunc
                truncated++
                log("Cue ${cue.id}: clip too long even at ${"%.2f".format(Locale.US, tempo)}x, truncating")
            }
            plans += Plan(clip, start, tempo, trunc)
            cursor = start + dur
        }

        // Pass 2: re-decode the clips that need speeding up.
        val tempoPlans = plans.filter { it.tempo > 1.001 }
        coroutineScope {
            tempoPlans.map { p ->
                async {
                    gate.withPermit {
                        val out = File(pcmDir, "%05d.t.pcm".format(p.clip.cue.id))
                        if (decode(p.clip.file, out, p.tempo)) {
                            p.clip.pcm = out
                            p.clip.samples = out.length() / 2
                        } else {
                            log("Cue ${p.clip.cue.id}: tempo re-decode failed, using original speed")
                        }
                    }
                }
            }.awaitAll()
        }

        // Assemble the single track as a stream.
        val totalSamples = if (totalMs > 0) msToSamples(totalMs) else Long.MAX_VALUE
        var written = 0L
        BufferedOutputStream(outPcm.outputStream(), 1 shl 16).use { out ->
            val zeros = ByteArray(1 shl 16)
            fun silence(samples: Long) {
                var left = samples * 2
                while (left > 0) {
                    val n = min(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n)
                    left -= n
                }
            }
            for (p in plans) {
                val startSample = max(msToSamples(p.startMs), written)
                if (startSample >= totalSamples) break
                silence(startSample - written)
                written = startSample
                var bytes = p.clip.pcm.readBytes()
                var samples = (bytes.size / 2).toLong()
                val cap = min(
                    p.truncateMs?.let { msToSamples(it) } ?: Long.MAX_VALUE,
                    totalSamples - written,
                )
                if (samples > cap) {
                    samples = cap
                    bytes = fadeOut(bytes.copyOf((samples * 2).toInt()), msToSamples(config.fadeMs.toLong()).toInt())
                }
                out.write(bytes, 0, (samples * 2).toInt())
                written += samples
            }
            if (totalMs > 0 && written < totalSamples) silence(totalSamples - written)
        }
        return AlignReport(
            clips = plans.size,
            spedUp = plans.count { it.tempo > 1.001 },
            shifted = shifted,
            truncated = truncated,
            skipped = skipped,
            maxTempoUsed = plans.maxOfOrNull { it.tempo } ?: 1.0,
        )
    }

    private suspend fun decode(input: File, out: File, tempo: Double): Boolean {
        val trim = "silenceremove=start_periods=1:start_silence=0.04:start_threshold=-45dB," +
            "areverse," +
            "silenceremove=start_periods=1:start_silence=0.04:start_threshold=-45dB," +
            "areverse"
        val filter = if (tempo > 1.001) "$trim,atempo=${"%.4f".format(Locale.US, tempo)}" else trim
        val r = ffmpeg.run(
            listOf("-i", input.absolutePath, "-af", filter, "-f", "s16le", "-ac", "1", "-ar", config.sampleRate.toString(), out.absolutePath)
        )
        if (!r.ok) log("ffmpeg failed to decode ${input.name}: ${r.log.takeLast(200)}")
        return r.ok
    }

    private fun fadeOut(pcm: ByteArray, fadeSamples: Int): ByteArray {
        val total = pcm.size / 2
        val n = min(fadeSamples, total)
        for (k in 0 until n) {
            val idx = (total - n + k) * 2
            val v = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort().toInt()
            val scaled = (v * (1.0 - (k + 1).toDouble() / n)).toInt()
            pcm[idx] = (scaled and 0xFF).toByte()
            pcm[idx + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
        return pcm
    }
}
