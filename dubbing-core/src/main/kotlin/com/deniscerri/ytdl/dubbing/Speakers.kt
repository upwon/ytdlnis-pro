package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
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

/** What was measured of one line of speech: pitch and the average spectrum shape ("timbre" of the voice and the mic). */
class VoiceSample(val f0: Double?, val spectrum: DoubleArray?)

/**
 * Long-term average spectrum of a stretch of speech in 16 log-spaced bands, loudness removed. Two people on different
 * microphones / rooms - or just with different voices - leave clearly different fingerprints, even at the same pitch.
 */
object VoiceFingerprint {
    const val BANDS = 16
    private const val N = 512
    private const val HOP = 256
    private const val MAX_FRAMES = 24
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val edges: IntArray = run {
        val binHz = PitchEstimator.SAMPLE_RATE.toDouble() / N
        val e = IntArray(BANDS + 1)
        for (i in 0..BANDS) e[i] = (100.0 * (3800.0 / 100.0).pow(i.toDouble() / BANDS) / binHz).toInt()
        for (i in 1..BANDS) if (e[i] <= e[i - 1]) e[i] = e[i - 1] + 1
        e
    }

    fun of(pcm: ShortArray): DoubleArray? {
        if (pcm.size < N * 2) return null
        val starts = (0..(pcm.size - N) step HOP).toList()
        val energy = starts.map { s -> sqrt((s until s + N).sumOf { pcm[it].toDouble() * pcm[it] } / N) }
        val sorted = energy.sorted()
        val gate = max(150.0, sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] * 0.3)
        val loud = starts.filterIndexed { i, _ -> energy[i] >= gate }
        if (loud.size < 3) return null
        val picked = if (loud.size <= MAX_FRAMES) loud else List(MAX_FRAMES) { loud[it * loud.size / MAX_FRAMES] }
        val acc = DoubleArray(BANDS)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        for (start in picked) {
            for (i in 0 until N) { re[i] = pcm[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (b in 0 until BANDS) {
                var p = 0.0
                for (k in edges[b] until minOf(edges[b + 1], N / 2)) p += re[k] * re[k] + im[k] * im[k]
                acc[b] += kotlin.math.log10(1e-6 + p / (edges[b + 1] - edges[b]))
            }
        }
        val mean = acc.map { it / picked.size }
        val avg = mean.average()
        return DoubleArray(BANDS) { mean[it] - avg }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Groups lines by voice (k-means on the fingerprint + pitch) and says whether the groups are real or just noise. */
object VoiceClusterer {
    private const val MIN_SILHOUETTE = 0.25
    private const val MIN_SHARE = 0.12

    /** Cluster index per line (0 = the voice heard first), null for lines without a clear voice; null overall = one voice. */
    fun cluster(samples: List<VoiceSample?>, maxK: Int): List<Int?>? {
        val idx = samples.indices.filter { samples[it]?.spectrum != null }
        if (idx.size < 8 || maxK < 2) return null
        val dims = VoiceFingerprint.BANDS + 1
        val raw = idx.map { i ->
            val s = samples[i]!!
            DoubleArray(dims) { d -> if (d < VoiceFingerprint.BANDS) s.spectrum!![d] else s.f0?.let { ln(it) } ?: Double.NaN }
        }
        // fill missing pitch with the mean, then z-score every dimension
        val x = Array(raw.size) { DoubleArray(dims) }
        for (d in 0 until dims) {
            val vals = raw.map { it[d] }.filter { !it.isNaN() }
            val mean = if (vals.isEmpty()) 0.0 else vals.average()
            val sd = sqrt(vals.sumOf { (it - mean) * (it - mean) } / max(1, vals.size)).coerceAtLeast(1e-6)
            val weight = if (d == dims - 1) 1.5 else 1.0
            for (r in raw.indices) x[r][d] = ((if (raw[r][d].isNaN()) mean else raw[r][d]) - mean) / sd * weight
        }
        var best: IntArray? = null
        var bestScore = -1.0
        for (k in 2..min(maxK, 4)) {
            val assign = kmeans(x, k)
            val sizes = IntArray(k).also { a -> assign.forEach { a[it]++ } }
            if (sizes.any { it < MIN_SHARE * x.size }) continue
            val score = silhouette(x, assign, k)
            if (score > bestScore) { bestScore = score; best = assign }
        }
        val chosen = best ?: return null
        if (bestScore < MIN_SILHOUETTE) return null
        // number the voices in the order they are first heard
        val order = LinkedHashMap<Int, Int>()
        chosen.forEach { if (it !in order) order[it] = order.size }
        val out = MutableList<Int?>(samples.size) { null }
        idx.forEachIndexed { n, i -> out[i] = order.getValue(chosen[n]) }
        return out
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s }

    private fun kmeans(x: Array<DoubleArray>, k: Int): IntArray {
        val dims = x[0].size
        val mean = DoubleArray(dims) { d -> x.sumOf { it[d] } / x.size }
        // farthest-first initialisation: deterministic, no unlucky seeds
        val centers = ArrayList<DoubleArray>()
        centers += x.maxByOrNull { dist2(it, mean) }!!.copyOf()
        while (centers.size < k) centers += x.maxByOrNull { p -> centers.minOf { dist2(p, it) } }!!.copyOf()
        val assign = IntArray(x.size)
        repeat(40) {
            var changed = false
            for (i in x.indices) {
                val c = centers.indices.minByOrNull { dist2(x[i], centers[it]) }!!
                if (c != assign[i]) { assign[i] = c; changed = true }
            }
            for (c in centers.indices) {
                val members = x.indices.filter { assign[it] == c }
                if (members.isNotEmpty()) for (d in 0 until dims) centers[c][d] = members.sumOf { x[it][d] } / members.size
            }
            if (!changed && it > 0) return assign
        }
        return assign
    }

    private fun silhouette(x: Array<DoubleArray>, assign: IntArray, k: Int): Double {
        val step = max(1, x.size / 300)
        var total = 0.0
        var n = 0
        for (i in x.indices step step) {
            val sums = DoubleArray(k)
            val counts = IntArray(k)
            for (j in x.indices) if (j != i) { sums[assign[j]] += sqrt(dist2(x[i], x[j])); counts[assign[j]]++ }
            val own = assign[i]
            if (counts[own] == 0) continue
            val a = sums[own] / counts[own]
            val b = (0 until k).filter { it != own && counts[it] > 0 }.minOfOrNull { sums[it] / counts[it] } ?: continue
            total += (b - a) / max(a, b)
            n++
        }
        return if (n == 0) -1.0 else total / n
    }
}

class SpeakerClassifier(
    private val ffmpeg: FfmpegRunner,
    private val log: (String) -> Unit = {},
    /** 2 = man / woman; 3-4 = also split several voices of the same sex by pitch. */
    private val maxSpeakers: Int = 2,
) {
    /** Returns [cues] with [Cue.speaker] filled in; on any problem the cues come back unchanged. */
    suspend fun classify(video: File, cues: List<Cue>, workDir: File): List<Cue> {
        val f0s = measure(video, cues, workDir) ?: return cues
        val labels = assign(f0s, maxSpeakers)
        log("Pitch analysis: ${f0s.count { it != null }}/${cues.size} lines voiced -> ${labels.groupingBy { it }.eachCount()}")
        return cues.mapIndexed { i, cue -> cue.copy(speaker = labels[i]) }
    }

    /** Median pitch (Hz) of every line, null where the voice is unclear; null overall when the audio cannot be read. */
    suspend fun measure(video: File, cues: List<Cue>, workDir: File): List<Double?>? =
        analyze(video, cues, workDir)?.map { it?.f0 }

    /** Pitch and spectrum fingerprint of every line (one pass over the audio). */
    suspend fun analyze(video: File, cues: List<Cue>, workDir: File): List<VoiceSample?>? {
        if (cues.isEmpty()) return null
        workDir.mkdirs()
        val pcm = File(workDir, "pitch.pcm")
        val r = ffmpeg.run(listOf("-i", video.absolutePath, "-vn", "-map", "0:a:0", "-ac", "1", "-ar", PitchEstimator.SAMPLE_RATE.toString(), "-f", "s16le", pcm.absolutePath))
        if (!r.ok || !pcm.exists()) {
            log("Pitch analysis skipped: ${r.log.takeLast(200)}")
            return null
        }
        try {
            return withContext(Dispatchers.IO) { RandomAccessFile(pcm, "r").use { f -> cues.map { cueSample(f, it) } } }
        } finally {
            pcm.delete()
        }
    }

    private fun cueSample(f: RandomAccessFile, cue: Cue): VoiceSample? {
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
        val f0 = PitchEstimator.medianF0(pcm)
        val spectrum = VoiceFingerprint.of(pcm)
        return if (f0 == null && spectrum == null) null else VoiceSample(f0, spectrum)
    }

    companion object {
        const val DEFAULT_THRESHOLD_HZ = 165.0

        /**
         * Speaker label per line: "M1", "M2"... (men, lowest voice first) and "F1", "F2"... (women). Lines without a
         * clear voice take the label of the previous line.
         */
        fun assign(f0s: List<Double?>, maxSpeakers: Int = 2): List<String> {
            val voiced = f0s.filterNotNull()
            val threshold = genderThreshold(voiced)
            val k = maxSpeakers.coerceIn(2, 4)
            var previous = "F1"
            if (k == 2 || voiced.size < k * 3) {
                return f0s.map { f0 -> (if (f0 == null) previous else if (f0 < threshold) "M1" else "F1").also { previous = it } }
            }
            val logs = voiced.map { ln(it) }.sorted()
            var centers = List(k) { logs[((it + 0.5) / k * logs.size).toInt().coerceAtMost(logs.size - 1)] }
            repeat(30) {
                val groups = logs.groupBy { v -> centers.indices.minBy { abs(centers[it] - v) } }
                centers = centers.mapIndexed { i, c -> groups[i]?.average() ?: c }
            }
            // voices closer than ~12% are one speaker
            val merged = ArrayList<Double>()
            for (c in centers.sorted()) {
                if (merged.isNotEmpty() && exp(c - merged.last()) < 1.12) merged[merged.size - 1] = (merged.last() + c) / 2
                else merged += c
            }
            val labelOfCenter = HashMap<Int, String>()
            var men = 0
            var women = 0
            merged.forEachIndexed { i, c ->
                labelOfCenter[i] = if (exp(c) < threshold) "M${++men}" else "F${++women}"
            }
            return f0s.map { f0 ->
                (if (f0 == null) previous else labelOfCenter.getValue(merged.indices.minBy { abs(merged[it] - ln(f0)) }))
                    .also { previous = it }
            }
        }

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
