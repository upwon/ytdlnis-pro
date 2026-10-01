package com.deniscerri.ytdl.dubbing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleTest {
    @Test
    fun parsesSrtWithBomCrlfTagsAndMultipleLines() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i> &amp; welcome\r\nto the show\r\n\r\n" +
            "2\r\n00:01:02,5 --> 00:01:04,000\r\n{\\an8}>> Second line\r\n\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1000, cues[0].startMs)
        assertEquals(3500, cues[0].endMs)
        assertEquals("Hello & welcome\nto the show", cues[0].src)
        assertEquals(62_500, cues[1].startMs)
        assertEquals("Second line", cues[1].src)
    }

    @Test
    fun parsesVttWithSettingsHeaderAndShortTimestamps() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            NOTE this is a comment

            00:01.000 --> 00:03.000 align:start position:0%
            First

            01:00:00.000 --> 01:00:02.000
            Hour mark
        """.trimIndent()
        val cues = SubtitleParser.parse(vtt)
        assertEquals(listOf("First", "Hour mark"), cues.map { it.src })
        assertEquals(1000, cues[0].startMs)
        assertEquals(3_600_000, cues[1].startMs)
    }

    @Test
    fun youtubeRollingAutoCaptionsAreDedupedAndMergedIntoSentences() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:00.160 --> 00:00:02.350 align:start position:0%
            hello<00:00:00.640><c> everyone</c><00:00:01.040><c> welcome</c>

            00:00:02.350 --> 00:00:02.360 align:start position:0%
            hello everyone welcome

            00:00:02.360 --> 00:00:04.789 align:start position:0%
            hello everyone welcome
            to<00:00:02.800><c> the</c><00:00:02.960><c> show.</c>

            00:00:04.789 --> 00:00:04.799 align:start position:0%
            to the show.

            00:00:04.799 --> 00:00:07.000 align:start position:0%
            to the show.
            today<00:00:05.0><c> we</c><c> learn</c>
        """.trimIndent()
        val raw = SubtitleParser.parse(vtt)
        assertTrue(SubtitleCleaner.looksRolling(raw))
        val cues = SubtitleCleaner.normalize(raw)
        assertEquals(listOf("hello everyone welcome to the show.", "today we learn"), cues.map { it.src })
        assertEquals(listOf(1, 2), cues.map { it.id })
        // no overlaps, ascending
        assertTrue(cues[0].endMs <= cues[1].startMs)
    }

    @Test
    fun cleanManualSubtitlesAreNotTreatedAsRolling() {
        val srt = (1..6).joinToString("\n\n") { "$it\n00:00:0$it,000 --> 00:00:0$it,900\nLine number $it." }
        assertFalse(SubtitleCleaner.looksRolling(SubtitleParser.parse(srt)))
    }

    @Test
    fun dropsMusicAndSoundTagsButKeepsSpeech() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n[Music]\n\n2\n00:00:02,000 --> 00:00:03,000\n♪♪\n\n" +
            "3\n00:00:03,000 --> 00:00:04,000\n(applause)\nThank you!\n\n4\n00:00:04,500 --> 00:00:05,000\nBye."
        val cues = SubtitleCleaner.normalize(SubtitleParser.parse(srt), merge = false)
        assertEquals(listOf("Thank you!", "Bye."), cues.map { it.src })
    }

    @Test
    fun mergeRespectsGapAndLimits() {
        val c = listOf(
            Cue(1, 0, 1000, "and then"), Cue(2, 1100, 2000, "we went home."),
            Cue(3, 2200, 3000, "Next"), Cue(4, 9000, 10_000, "much later"),
        )
        val merged = SubtitleCleaner.mergeSentences(c)
        assertEquals(listOf("and then we went home.", "Next", "much later"), merged.map { it.src })
        assertEquals(2000, merged[0].endMs)
    }

    @Test
    fun fixOverlapsClipsEndsToNextStart() {
        val fixed = SubtitleCleaner.fixOverlaps(listOf(Cue(1, 0, 5000, "a"), Cue(2, 3000, 4000, "b")))
        assertEquals(3000, fixed[0].endMs)
        assertEquals(4000, fixed[1].endMs)
    }

    @Test
    fun srtWriterRoundTrips() {
        val cues = listOf(Cue(1, 1500, 3_661_250, "x", zh = "你好"), Cue(2, 4_000_000, 4_001_000, "y", zh = ""))
        val text = SrtWriter.format(cues)
        assertTrue(text.contains("00:00:01,500 --> 01:01:01,250"))
        val parsed = SubtitleParser.parse(text)
        assertEquals(1, parsed.size) // blank translation skipped
        assertEquals("你好", parsed[0].src)
        assertEquals(3_661_250, parsed[0].endMs)
    }
}
