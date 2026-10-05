package com.deniscerri.ytdl.dubbing

import kotlinx.serialization.Serializable

/** One subtitle line / spoken sentence. Times are in milliseconds from the start of the video. */
@Serializable
data class Cue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val src: String,
    val zh: String = "",
    /** Synthesized clip, relative to the work dir. */
    val ttsFile: String? = null,
    /** "M" or "F" when voices are assigned per speaker (guessed from the pitch of the original audio); blank = unknown. */
    val speaker: String = "",
    /** The subtitle marked a change of speaker here (">>" or a leading dash) - the best clue for "who speaks". */
    val turn: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

enum class Stage { SOURCE, TRANSLATE, SYNTHESIZE, ALIGN, MUX }

data class Progress(val stage: Stage, val done: Int, val total: Int)

class DubbingException(message: String, cause: Throwable? = null) : Exception(message, cause)
