package com.deniscerri.ytdl.dubbing

import kotlin.math.max
import kotlin.math.min

/** Parses SRT and WebVTT into [Cue]s. Multi-line cues keep their lines separated by '\n'. */
object SubtitleParser {
    private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
    private val TIME = Regex("""^(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$""")
    private val TAGS = Regex("""<[^>]*>""")
    private val ASS = Regex("""\{\\[^}]*\}""")

    fun parse(content: String): List<Cue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<Cue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val arrowIdx = lines.indexOfFirst { "-->" in it }
            if (arrowIdx < 0) continue
            val m = ARROW.find(lines[arrowIdx]) ?: continue
            val start = parseTime(m.groupValues[1]) ?: continue
            val end = parseTime(m.groupValues[2]) ?: continue
            val body = cleanText(lines.drop(arrowIdx + 1).joinToString("\n"))
            if (body.isBlank()) continue
            cues += Cue(cues.size + 1, start, end, body)
        }
        return cues
    }

    internal fun parseTime(s: String): Long? {
        val m = TIME.find(s.trim()) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val ms = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }

    internal fun cleanText(raw: String): String =
        raw.replace(TAGS, "")
            .replace(ASS, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .split('\n')
            .map { it.replace(Regex("""^\s*>>\s*"""), "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}

object SubtitleCleaner {
    private val SENTENCE_END = Regex("""[.!?…。！？]["'”’)\]]*$""")
    private val NON_SPEECH = Regex("""^\s*(?:[\[(（【].*[\])）】]|[♪♫\s]+)\s*$""")

    /**
     * YouTube auto captions "roll": every cue repeats the previous cue's last line and adds a new one.
     * Detects that pattern so [dedupeRolling] is only applied when needed.
     */
    fun looksRolling(cues: List<Cue>): Boolean {
        if (cues.size < 4) return false
        var repeats = 0
        for (i in 1 until cues.size) {
            val prev = cues[i - 1].src.split('\n')
            if (cues[i].src.split('\n').any { it in prev }) repeats++
        }
        return repeats * 100 / (cues.size - 1) >= 25
    }

    fun dedupeRolling(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var prevLines = emptyList<String>()
        for (c in cues) {
            val lines = c.src.split('\n')
            val fresh = lines.filter { it !in prevLines }
            prevLines = lines
            if (fresh.isEmpty()) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(endMs = max(out.last().endMs, c.endMs))
                continue
            }
            out += c.copy(src = fresh.joinToString(" "))
        }
        return out
    }

    fun dropNonSpeech(cues: List<Cue>): List<Cue> = cues.mapNotNull { c ->
        val kept = c.src.split('\n').filterNot { NON_SPEECH.matches(it) }
        if (kept.isEmpty()) null else c.copy(src = kept.joinToString(" "))
    }

    /** Cues must not overlap; each cue ends at most where the next one starts. */
    fun fixOverlaps(cues: List<Cue>, minDurationMs: Long = 300): List<Cue> {
        val sorted = cues.sortedBy { it.startMs }
        return sorted.mapIndexed { i, c ->
            val limit = sorted.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            var end = min(c.endMs, limit)
            if (end - c.startMs < minDurationMs) end = c.startMs + minDurationMs
            c.copy(endMs = end)
        }
    }

    /** Joins fragments into whole sentences so they are translated and spoken as a unit. */
    fun mergeSentences(
        cues: List<Cue>,
        maxGapMs: Long = 800,
        maxDurationMs: Long = 12_000,
        maxChars: Int = 220,
    ): List<Cue> {
        val out = ArrayList<Cue>()
        var cur: Cue? = null
        for (c in cues) {
            val p = cur
            if (p == null) { cur = c; continue }
            val gap = c.startMs - p.endMs
            val endsSentence = SENTENCE_END.containsMatchIn(p.src.trimEnd())
            val tooLong = c.endMs - p.startMs > maxDurationMs || p.src.length + c.src.length + 1 > maxChars
            if (endsSentence || gap > maxGapMs || tooLong) {
                out += p
                cur = c
            } else {
                cur = p.copy(endMs = c.endMs, src = p.src + " " + c.src)
            }
        }
        cur?.let { out += it }
        return out
    }

    fun normalize(cues: List<Cue>, merge: Boolean = true): List<Cue> {
        var r = cues
        if (looksRolling(r)) r = dedupeRolling(r)
        r = dropNonSpeech(r)
        r = fixOverlaps(r)
        if (merge) r = fixOverlaps(mergeSentences(r))
        return r.mapIndexed { i, c -> c.copy(id = i + 1) }
    }
}

object SrtWriter {
    fun format(cues: List<Cue>, useTranslation: Boolean = true): String = buildString {
        var n = 1
        for (c in cues) {
            val text = if (useTranslation) c.zh else c.src
            if (text.isBlank()) continue
            append(n++).append('\n')
            append(time(c.startMs)).append(" --> ").append(time(c.endMs)).append('\n')
            append(text.trim()).append("\n\n")
        }
    }

    /** Chinese line on top, original line below it. Falls back to the original when a cue was not translated. */
    fun formatBilingual(cues: List<Cue>): String = buildString {
        var n = 1
        for (c in cues) {
            if (c.zh.isBlank() && c.src.isBlank()) continue
            append(n++).append('\n')
            append(time(c.startMs)).append(" --> ").append(time(c.endMs)).append('\n')
            append(if (c.zh.isBlank()) c.src.trim() else c.zh.trim() + "\n" + c.src.trim()).append("\n\n")
        }
    }

    internal fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }
}
