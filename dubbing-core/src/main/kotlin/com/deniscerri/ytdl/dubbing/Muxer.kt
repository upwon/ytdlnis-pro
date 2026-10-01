package com.deniscerri.ytdl.dubbing

import java.io.File
import java.util.Locale

data class MuxOptions(
    /** Keep the original audio tracks after the dub track so the player can switch back. */
    val keepOriginal: Boolean = true,
    /**
     * 0 = dub is a separate, clean track. > 0 = the dub track also contains the original audio at this volume
     * (0.05..0.5 typical), automatically ducked while the dub is speaking.
     */
    val originalVolume: Double = 0.0,
    val dubLanguage: String = "chi",
    val dubTitle: String = "中文配音 (AI)",
    val audioBitrateKbps: Int = 128,
    /** Optional Chinese subtitle file to add as a soft subtitle track (mp4/mov/mkv). */
    val extraSubtitle: File? = null,
)

object DubMuxer {
    private val SOFT_SUB_CONTAINERS = setOf("mp4", "m4v", "mov", "mkv")

    fun buildArgs(
        video: File,
        dubPcm: File,
        out: File,
        info: MediaInfo,
        opts: MuxOptions,
        sampleRate: Int = 24_000,
    ): List<String> {
        val ext = out.extension.lowercase()
        val args = mutableListOf(
            "-i", video.absolutePath,
            "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "1", "-i", dubPcm.absolutePath,
        )
        val subInput = opts.extraSubtitle != null && info.hasVideo && ext in SOFT_SUB_CONTAINERS
        if (subInput) args += listOf("-i", opts.extraSubtitle!!.absolutePath)

        val mix = opts.originalVolume > 0 && info.audioStreams.isNotEmpty()
        if (mix) {
            val v = String.format(Locale.US, "%.3f", opts.originalVolume)
            args += listOf(
                "-filter_complex",
                "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=$v[bg];" +
                    "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asplit=2[dub][sc];" +
                    "[bg][sc]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=300[duck];" +
                    "[duck][dub]amix=inputs=2:duration=longest:normalize=0[mix]"
            )
        }
        val dubMap = if (mix) "[mix]" else "1:a"
        val codec = when {
            ext == "webm" || ext == "opus" || ext == "ogg" -> "libopus"
            ext == "mp3" -> "libmp3lame"
            else -> "aac"
        }
        val bitrate = "${opts.audioBitrateKbps}k"

        if (!info.hasVideo) {
            // Audio-only source: the output is just the dub (optionally mixed with the original).
            args += listOf("-map", dubMap, "-c:a", codec, "-b:a", bitrate)
            args += metadata(opts, 0)
            args += listOf("-map_metadata", "0", out.absolutePath)
            return args
        }

        val keepOriginal = opts.keepOriginal && info.audioStreams.isNotEmpty()
        args += listOf("-map", "0:v?", "-map", dubMap)
        if (keepOriginal) args += listOf("-map", "0:a?")
        val keepSubs = ext != "webm" && info.subtitleStreams > 0
        if (keepSubs) args += listOf("-map", "0:s?")
        if (subInput) args += listOf("-map", "2:0")

        args += listOf("-c:v", "copy", "-c:a", "copy", "-c:a:0", codec, "-b:a:0", bitrate)
        if (keepSubs || subInput) args += listOf("-c:s", "copy")
        if (subInput) {
            val k = if (keepSubs) info.subtitleStreams else 0
            args += listOf(
                "-c:s:$k", if (ext == "mkv") "srt" else "mov_text",
                "-metadata:s:s:$k", "language=${opts.dubLanguage}",
                "-metadata:s:s:$k", "title=${opts.dubTitle}",
            )
        }
        args += metadata(opts, 0)
        args += listOf("-disposition:a:0", "default")
        if (keepOriginal) for (k in 1..info.audioStreams.size) args += listOf("-disposition:a:$k", "0")
        args += listOf("-map_metadata", "0")
        if (ext in setOf("mp4", "m4v", "mov")) args += listOf("-movflags", "+faststart")
        args += out.absolutePath
        return args
    }

    private fun metadata(opts: MuxOptions, audioIndex: Int) = listOf(
        "-metadata:s:a:$audioIndex", "language=${opts.dubLanguage}",
        "-metadata:s:a:$audioIndex", "title=${opts.dubTitle}",
        // MP4/MOV have no per-stream title; players show the handler name instead.
        "-metadata:s:a:$audioIndex", "handler_name=${opts.dubTitle}",
    )
}
