package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Guesses "male" / "female" for every subtitle line from the pitch of the original voice, so that a conversation
 * between a man and a woman can be dubbed with two different voices. It is a heuristic (fundamental frequency of the
 * voiced frames), not speaker diarization: it separates low voices from high voices, nothing more.
 */
object PitchEstimator {
    const val SAMPLE_RATE = 8000
    private const val FRAME = 400          // 50 ms
    private const val HOP = 200
    private const val MIN_F0 = 70.0
    private const val MAX_F0 = 400.0
    private const val MAX_FRAMES = 16

    /** Median fundamental frequency (Hz) of the voiced frames in [pcm] (mono, 8 kHz), or null when there is no clear voice. */
    fun medianF0(pcm: ShortArray): Double? {
        if (pcm.size < FRAME * 2) return null
        val starts = (0..(pcm.size - FRAME) step HOP).toList()
        val energies = starts.map { rms(pcm, it) }
        val sorted = energies.sorted()
        val p90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        val gate = max(150.0, p90 * 0.3)
        val loud = starts.filterIndexed { i, _ -> energies[i] >= gate }
        if (loud.isEmpty()) return null
        val picked = if (loud.size <= MAX_FRAMES) loud else List(MAX_FRAMES) { loud[it * loud.size / MAX_FRAMES] }
        val f0s = picked.mapNotNull { frameF0(pcm, it) }.sorted()
        if (f0s.size < 3) return null
        return f0s[f0s.size / 2]
    }

    private fun rms(p: ShortArray, start: Int): Double {
        var s = 0.0
        for (i in start until start + FRAME) { val v = p[i].toDouble(); s += v * v }
        return sqrt(s / FRAME)
    }

    private fun frameF0(p: ShortArray, start: Int): Double? {
        val minLag = (SAMPLE_RATE / MAX_F0).toInt()
        val maxLag = (SAMPLE_RATE / MIN_F0).toInt()
        var mean = 0.0
        for (i in 0 until FRAME) mean += p[start + i]
        mean /= FRAME
        val x = DoubleArray(FRAME) { p[start + it] - mean }
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 < 1.0) return null
        val r = DoubleArray(maxLag + 1)
        var best = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until FRAME - lag) s += x[i] * x[i + lag]
            r[lag] = s / r0
            if (r[lag] > best) best = r[lag]
        }
        if (best < 0.5) return null
        // the first strong peak, not the strongest: avoids picking an octave below the real pitch
        var lag = minLag
        while (lag < maxLag) {
            if (r[lag] >= best * 0.9 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) break
            lag++
        }
        return SAMPLE_RATE.toDouble() / lag
    }
}

class SpeakerClassifier(private val ffmpeg: FfmpegRunner, private val log: (String) -> Unit = {}) {
    /** Returns [cues] with [Cue.speaker] filled in; on any problem the cues come back unchanged. */
    suspend fun classify(video: File, cues: List<Cue>, workDir: File): List<Cue> {
        if (cues.isEmpty()) return cues
        workDir.mkdirs()
        val pcm = File(workDir, "pitch.pcm")
        val r = ffmpeg.run(listOf("-i", video.absolutePath, "-vn", "-map", "0:a:0", "-ac", "1", "-ar", PitchEstimator.SAMPLE_RATE.toString(), "-f", "s16le", pcm.absolutePath))
        if (!r.ok || !pcm.exists()) {
            log("Pitch analysis skipped: ${r.log.takeLast(200)}")
            return cues
        }
        try {
            val f0s = withContext(Dispatchers.IO) { RandomAccessFile(pcm, "r").use { f -> cues.map { cueF0(f, it) } } }
            val threshold = genderThreshold(f0s.filterNotNull())
            log("Pitch analysis: ${f0s.count { it != null }}/${cues.size} lines voiced, threshold ${"%.0f".format(threshold)} Hz")
            var previous = "F"
            return cues.mapIndexed { i, cue ->
                val f0 = f0s[i]
                val gender = if (f0 == null) previous else if (f0 < threshold) "M" else "F"
                previous = gender
                cue.copy(speaker = gender)
            }
        } finally {
            pcm.delete()
        }
    }

    private fun cueF0(f: RandomAccessFile, cue: Cue): Double? {
        val rate = PitchEstimator.SAMPLE_RATE
        val from = (cue.startMs * rate / 1000).coerceAtLeast(0)
        val spanMs = min(cue.durationMs, 8_000L)
        val samples = (spanMs * rate / 1000).toInt()
        if (samples <= 0) return null
        val bytes = ByteArray(samples * 2)
        f.seek(from * 2)
        val n = f.read(bytes)
        if (n < 2) return null
        val count = n / 2
        val pcm = ShortArray(count) { ((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xFF)).toShort() }
        return PitchEstimator.medianF0(pcm)
    }

    companion object {
        const val DEFAULT_THRESHOLD_HZ = 165.0

        /**
         * Fixed boundary between typical male (85-155 Hz) and female (165-255 Hz) speech, refined with a 2-means split
         * of the video's own pitches when two groups are clearly separated (a man and a woman talking).
         */
        fun genderThreshold(f0s: List<Double>): Double {
            if (f0s.size < 6) return DEFAULT_THRESHOLD_HZ
            val logs = f0s.map { ln(it) }
            var lo = ln(120.0)
            var hi = ln(210.0)
            repeat(20) {
                val mid = (lo + hi) / 2
                val a = logs.filter { it < mid }
                val b = logs.filter { it >= mid }
                if (a.isNotEmpty()) lo = a.average()
                if (b.isNotEmpty()) hi = b.average()
            }
            val separated = exp(hi - lo) >= 1.3 && logs.count { it < (lo + hi) / 2 } >= 2 && logs.count { it >= (lo + hi) / 2 } >= 2
            val mid = exp((lo + hi) / 2)
            return if (separated && mid in 130.0..200.0) mid else DEFAULT_THRESHOLD_HZ
        }
    }
}
