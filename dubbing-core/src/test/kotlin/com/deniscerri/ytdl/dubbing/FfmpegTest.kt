package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegTest {
    private val dir = TestMedia.tempDir("ffmpeg")

    @Test fun parsesDurationAndStreamsFromRealMedia() = runBlocking {
        val f = File(dir, "a.mkv")
        TestMedia.makeVideo(f, 4, extraArgs = listOf("-metadata:s:a:0", "language=eng"))
        val info = TestMedia.ffmpeg.probe(f)
        assertTrue(info.hasVideo)
        assertEquals(1, info.audioStreams.size)
        assertEquals("eng", info.audioStreams[0].language)
        assertEquals("aac", info.audioStreams[0].codec)
        assertTrue(info.durationMs in 3900..4300, "duration=${info.durationMs}")
        assertEquals(0, info.subtitleStreams)
    }

    @Test fun reportsVideoWithoutAudioAndAudioOnly() = runBlocking {
        val v = File(dir, "silent.mp4").also { TestMedia.makeVideo(it, 2, audio = false) }
        assertEquals(emptyList(), TestMedia.ffmpeg.probe(v).audioStreams)
        val a = File(dir, "only.m4a").also { TestMedia.makeAudioOnly(it, 2) }
        val ai = TestMedia.ffmpeg.probe(a)
        assertFalse(ai.hasVideo)
        assertEquals(1, ai.audioStreams.size)
    }

    @Test fun parseMediaInfoHandlesCoverArtAndSubtitleStreams() {
        val log = """
            Input #0, matroska,webm, from 'x.mkv':
              Duration: 01:02:03.50, start: 0.000000, bitrate: 1000 kb/s
              Stream #0:0: Video: mjpeg (Baseline), yuvj420p, 600x600 (attached pic)
              Stream #0:1(eng): Audio: opus, 48000 Hz, stereo, fltp (default)
              Stream #0:2(jpn): Audio: aac (LC), 44100 Hz, stereo, fltp
              Stream #0:3(eng): Subtitle: subrip
        """.trimIndent()
        val i = parseMediaInfo(log)
        assertFalse(i.hasVideo, "attached picture is not video")
        assertEquals(listOf("eng", "jpn"), i.audioStreams.map { it.language })
        assertEquals(1, i.subtitleStreams)
        assertEquals(3_723_500, i.durationMs)
    }

    @Test fun probeFailsClearlyOnGarbage() {
        val f = File(dir, "garbage.mp4").apply { writeText("not media") }
        val e = assertFailsWith<DubbingException> { runBlocking { TestMedia.ffmpeg.probe(f) } }
        assertTrue(e.message!!.contains("garbage.mp4"))
    }

    @Test fun cancellingTheCoroutineKillsFfmpegQuickly() = runBlocking {
        val started = System.currentTimeMillis()
        val job = async {
            // -re = real time, so this would run for 60 s if not killed
            TestMedia.ffmpeg.run(listOf("-re", "-f", "lavfi", "-i", "sine=d=60", "-f", "null", "-"))
        }
        delay(700)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        withTimeout(5_000) { job.join() }
        assertTrue(System.currentTimeMillis() - started < 8_000)
    }
}

class AlignerTest {
    private val dir = TestMedia.tempDir("align")
    private val noLog: (String) -> Unit = {}

    /** A cue with a real mp3 clip of [clipSec] seconds of tone (plus padding that the aligner must trim). */
    private fun cue(id: Int, startMs: Long, endMs: Long, clipSec: Double): Cue {
        val tts = File(dir, "tts").apply { mkdirs() }
        val f = File(tts, "%05d.mp3".format(id))
        TestMedia.ff(
            "-f", "lavfi", "-i", "sine=frequency=440:duration=$clipSec:sample_rate=24000",
            "-af", "adelay=150:all=1,apad=pad_dur=0.2", "-c:a", "libmp3lame", f.absolutePath
        )
        return Cue(id, startMs, endMs, "src", "zh", "tts/${f.name}")
    }

    private fun run(cues: List<Cue>, totalMs: Long, cfg: AlignConfig = AlignConfig()): Pair<AlignReport, ShortArray> {
        val out = File(dir, "dub-${System.nanoTime()}.pcm")
        val report = runBlocking { TimelineAligner(TestMedia.ffmpeg, cfg, noLog).align(cues, dir, totalMs, out) }
        assertEquals(totalMs * 24 * 2, out.length(), "track must be exactly the video length")
        return report to TestMedia.pcm(out)
    }

    @Test fun clipsThatFitStartExactlyAtTheirCue() {
        val (r, pcm) = run(listOf(cue(1, 1000, 3000, 1.0), cue(2, 5000, 7000, 1.5)), 10_000)
        assertEquals(2, r.clips)
        assertEquals(0, r.spedUp)
        assertEquals(0, r.shifted)
        // speech 1.0-2.0 s and 5.0-6.5 s; leading padding trimmed so it starts on time
        assertTrue(TestMedia.quiet(pcm, 0, 950))
        assertTrue(TestMedia.loud(pcm, 1050, 1900))
        assertTrue(TestMedia.quiet(pcm, 2150, 4900))
        assertTrue(TestMedia.loud(pcm, 5050, 6400))
        assertTrue(TestMedia.quiet(pcm, 6700, 10_000))
    }

    @Test fun longClipIsSpedUpToFitWindowIncludingBorrowedGap() {
        // cue 0-2 s, nothing for a long time: window = 2 s + 2 s borrow = 4 s; a 6 s clip -> 1.5x -> 4 s
        val (r, pcm) = run(listOf(cue(1, 0, 2000, 6.0), cue(2, 20_000, 21_000, 0.5)), 25_000)
        assertEquals(1, r.spedUp)
        assertEquals(1.5, r.maxTempoUsed, 0.001)
        assertTrue(TestMedia.loud(pcm, 100, 3800), "dub plays through the borrowed gap")
        assertTrue(TestMedia.quiet(pcm, 4400, 19_900), "and stops after ~4 s instead of 6 s")
    }

    @Test fun moderateOverrunOnlyUsesTheSpeedItNeeds() {
        // 3 s clip in a 2 s cue followed by silence: window 4 s -> fits without tempo
        val (r, pcm) = run(listOf(cue(1, 0, 2000, 3.0)), 8_000)
        assertEquals(0, r.spedUp)
        assertTrue(TestMedia.loud(pcm, 100, 2900))
        assertTrue(TestMedia.quiet(pcm, 3300, 8000))
    }

    @Test fun overrunningClipPushesNextCueLaterInsteadOfOverlapping() {
        // clip 1 (3 s) can only get ~1.2 s of room -> 1.5x -> 2 s; cue 2 wants to start at 1.2 s -> shifted to ~2 s
        val (r, pcm) = run(listOf(cue(1, 0, 1000, 3.0), cue(2, 1200, 2200, 1.0)), 6_000)
        assertEquals(1, r.shifted)
        assertEquals(0, r.truncated)
        assertTrue(TestMedia.loud(pcm, 100, 1900), "first clip plays to ~2 s")
        assertTrue(TestMedia.loud(pcm, 2100, 2900), "second clip starts after the first, not on top of it")
        assertTrue(TestMedia.quiet(pcm, 3300, 6000))
    }

    @Test fun hopelessOverrunIsTruncatedWithinDriftLimit() {
        val cfg = AlignConfig(maxDriftMs = 500, maxBorrowMs = 0)
        // 10 s clip, 1 s window, next cue at 1.5 s: even at 1.5x it is 6.7 s -> truncate to next start + drift
        val (r, pcm) = run(listOf(cue(1, 0, 1000, 10.0), cue(2, 1500, 2500, 1.0)), 6_000, cfg)
        assertEquals(1, r.truncated)
        assertTrue(TestMedia.loud(pcm, 100, 1900))
        assertTrue(TestMedia.loud(pcm, 2100, 2900), "next cue is at most drift-limit late")
    }

    @Test fun missingOrSilentClipsAreSkippedAndTrackIsStillFullLength() {
        val good = cue(1, 1000, 2000, 0.5)
        val missing = Cue(2, 3000, 4000, "s", "z", "tts/nope.mp3")
        val silentFile = File(dir, "tts/silent.mp3")
        TestMedia.ff("-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono", "-t", "1", "-c:a", "libmp3lame", silentFile.absolutePath)
        val silent = Cue(3, 5000, 6000, "s", "z", "tts/silent.mp3")
        val noClip = Cue(4, 7000, 8000, "s", "z", null)
        val (r, _) = run(listOf(good, missing, silent, noClip), 9_000)
        assertEquals(1, r.clips)
        assertEquals(2, r.skipped)
    }

    @Test fun noClipsAtAllStillProducesSilentTrack() {
        val (r, pcm) = run(listOf(Cue(1, 0, 1000, "a", "b", null)), 3_000)
        assertEquals(0, r.clips)
        assertTrue(TestMedia.quiet(pcm, 0, 3000))
    }

    @Test fun audioPastTheEndOfTheVideoIsCutOff() {
        val (_, pcm) = run(listOf(cue(1, 3000, 4000, 4.0)), 4_000)
        assertEquals(4_000 * 24, pcm.size)
        assertTrue(TestMedia.loud(pcm, 3100, 3900))
    }

    @Test fun sortsUnorderedCues() {
        val (_, pcm) = run(listOf(cue(2, 5000, 6000, 0.5), cue(1, 1000, 2000, 0.5)), 8_000)
        assertTrue(TestMedia.loud(pcm, 1100, 1500))
        assertTrue(TestMedia.loud(pcm, 5100, 5500))
    }
}
