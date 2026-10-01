package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuxerTest {
    private val dir = TestMedia.tempDir("mux")

    /** 24 kHz mono track of [seconds]: a 300 Hz tone for the first half, silence after (so ducking is observable). */
    private fun dubPcm(seconds: Int): File {
        val f = File(dir, "dub-$seconds.pcm")
        if (!f.exists()) {
            val tmp = File(dir, "dub-$seconds.wav")
            TestMedia.ff(
                "-f", "lavfi", "-i", "sine=frequency=300:duration=${seconds / 2.0}:sample_rate=24000",
                "-af", "apad=whole_dur=$seconds", "-f", "s16le", "-ac", "1", "-ar", "24000", f.absolutePath
            )
            tmp.delete()
        }
        return f
    }

    private fun mux(video: File, out: File, opts: MuxOptions = MuxOptions(), seconds: Int = 6): MediaInfo = runBlocking {
        val info = TestMedia.ffmpeg.probe(video)
        val r = TestMedia.ffmpeg.run(DubMuxer.buildArgs(video, dubPcm(seconds), out, info, opts))
        assertTrue(r.ok && out.exists(), "ffmpeg failed:\n${r.log}")
        info
    }

    @Test fun mp4KeepsOriginalAsSecondTrackAndMarksDubAsDefault() {
        val v = File(dir, "a.mp4").also { TestMedia.makeVideo(it, 6) }
        val out = File(dir, "a.zh.mp4")
        mux(v, out)
        val audio = TestMedia.streams(out, "audio")
        assertEquals(2, audio.size)
        assertEquals("chi", TestMedia.tag(audio[0], "language"))
        assertEquals("中文配音 (AI)", TestMedia.tag(audio[0], "title") ?: TestMedia.tag(audio[0], "handler_name"))
        assertEquals(1, TestMedia.disposition(audio[0], "default"))
        assertEquals(0, TestMedia.disposition(audio[1], "default"))
        assertEquals("h264", TestMedia.streams(out, "video").single()["codec_name"].toString().trim('"'))
        assertEquals(6.0, TestMedia.durationSec(out), 0.3)

        // first track really is the dub (300 Hz tone for 3 s then silence), second the untouched original
        val dub = TestMedia.decode(out, 0)
        assertTrue(TestMedia.loud(dub, 500, 2500))
        assertTrue(TestMedia.quiet(dub, 3500, 5900))
        val orig = TestMedia.decode(out, 1)
        assertTrue(TestMedia.loud(orig, 3500, 5900))
    }

    @Test fun videoStreamIsCopiedNotReencoded() {
        val v = File(dir, "copy.mp4").also { TestMedia.makeVideo(it, 6) }
        val out = File(dir, "copy.zh.mp4")
        mux(v, out)
        val a = TestMedia.streams(v, "video").single()
        val b = TestMedia.streams(out, "video").single()
        assertEquals(a["profile"], b["profile"])
        assertEquals(a["nb_frames"], b["nb_frames"])
    }

    @Test fun dropOriginalLeavesOnlyTheDub() {
        val v = File(dir, "drop.mp4").also { TestMedia.makeVideo(it, 6) }
        val out = File(dir, "drop.zh.mp4")
        mux(v, out, MuxOptions(keepOriginal = false))
        assertEquals(1, TestMedia.streams(out, "audio").size)
    }

    @Test fun mixModeDucksOriginalUnderTheDubAndKeepsPristineOriginalTrack() {
        val v = File(dir, "mix.mp4").also { TestMedia.makeVideo(it, 6) }
        val out = File(dir, "mix.zh.mp4")
        mux(v, out, MuxOptions(originalVolume = 0.3))
        assertEquals(2, TestMedia.streams(out, "audio").size)
        val mixed = TestMedia.decode(out, 0)
        val duringDub = TestMedia.rms(mixed, 1000, 2800)
        val afterDub = TestMedia.rms(mixed, 3600, 5800)
        assertTrue(duringDub > 800, "dub audible: $duringDub")
        assertTrue(afterDub > 150, "original background still audible when nobody speaks: $afterDub")
        assertTrue(TestMedia.loud(TestMedia.decode(out, 1), 3600, 5800), "second track is the pristine original")
    }

    @Test fun sourceWithoutAudioGetsTheDubAsOnlyTrack() {
        val v = File(dir, "silent.mp4").also { TestMedia.makeVideo(it, 6, audio = false) }
        val out = File(dir, "silent.zh.mp4")
        mux(v, out)
        val audio = TestMedia.streams(out, "audio")
        assertEquals(1, audio.size)
        assertTrue(TestMedia.loud(TestMedia.decode(out, 0), 500, 2500))
    }

    @Test fun audioOnlySourceBecomesM4aWithTheDub() {
        val a = File(dir, "pod.m4a").also { TestMedia.makeAudioOnly(it, 6) }
        val out = File(dir, "pod.zh.m4a")
        mux(a, out)
        assertEquals(1, TestMedia.streams(out, "audio").size)
        assertEquals(0, TestMedia.streams(out, "video").size)
        assertTrue(TestMedia.loud(TestMedia.decode(out, 0), 500, 2500))
    }

    @Test fun mkvKeepsExistingSubtitlesAndAddsChineseOne() {
        val srt = File(dir, "en.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n") }
        val v = File(dir, "subs.mkv")
        TestMedia.ff(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=6", "-f", "lavfi", "-i", "sine=duration=6",
            "-i", srt.absolutePath, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "srt",
            "-metadata:s:s:0", "language=eng", v.absolutePath
        )
        val zh = File(dir, "zh.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\n你好\n") }
        val out = File(dir, "subs.zh.mkv")
        mux(v, out, MuxOptions(extraSubtitle = zh))
        val subs = TestMedia.streams(out, "subtitle")
        assertEquals(2, subs.size)
        assertEquals("eng", TestMedia.tag(subs[0], "language"))
        assertEquals("chi", TestMedia.tag(subs[1], "language"))
        assertEquals(2, TestMedia.streams(out, "audio").size)
    }

    @Test fun mp4GetsSoftChineseSubtitleAsMovText() {
        val v = File(dir, "soft.mp4").also { TestMedia.makeVideo(it, 6) }
        val zh = File(dir, "zh2.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\n你好\n") }
        val out = File(dir, "soft.zh.mp4")
        mux(v, out, MuxOptions(extraSubtitle = zh))
        val subs = TestMedia.streams(out, "subtitle")
        assertEquals(1, subs.size)
        assertEquals("mov_text", subs[0]["codec_name"].toString().trim('"'))
    }

    @Test fun webmUsesOpusAndDoesNotTryToCopySubtitles() {
        val v = File(dir, "w.webm")
        runCatching { TestMedia.makeVideo(v, 6) }
        assumeTrue("libvpx/libopus not available", v.exists() && v.length() > 0)
        val out = File(dir, "w.zh.webm")
        mux(v, out)
        val audio = TestMedia.streams(out, "audio")
        assertEquals(2, audio.size)
        assertEquals("opus", audio[0]["codec_name"].toString().trim('"'))
        assertEquals("chi", TestMedia.tag(audio[0], "language"))
    }

    @Test fun muxArgsForMultipleOriginalTracksClearTheirDefaultFlags() {
        val info = MediaInfo(6000, true, listOf(AudioStreamInfo("eng", "aac"), AudioStreamInfo("jpn", "aac")), 0)
        val args = DubMuxer.buildArgs(File("v.mp4"), File("d.pcm"), File("o.mp4"), info, MuxOptions())
        assertTrue(args.windowed(2).any { it == listOf("-disposition:a:0", "default") })
        assertTrue(args.windowed(2).any { it == listOf("-disposition:a:1", "0") })
        assertTrue(args.windowed(2).any { it == listOf("-disposition:a:2", "0") })
        assertTrue(args.windowed(2).any { it == listOf("-movflags", "+faststart") })
    }
}
