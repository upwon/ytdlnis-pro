package com.deniscerri.ytdl.util.dubbing

import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.dubbing.FfmpegResult
import com.deniscerri.ytdl.dubbing.FfmpegRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Runs the ffmpeg binary that ships with the app (same environment/LD_LIBRARY_PATH as yt-dlp's post-processing). */
class AndroidFfmpegRunner : FfmpegRunner {
    override suspend fun run(args: List<String>): FfmpegResult = coroutineScope {
        val processId = "dubbing-${System.nanoTime()}"
        val command = listOf(RuntimeManager.ffmpegLocation.executable.absolutePath, "-hide_banner", "-nostdin", "-y") + args
        val work = async(Dispatchers.IO) {
            try {
                val r = RuntimeManager.executeImpl(command, processId)
                // ffmpeg writes its log to stderr
                FfmpegResult(r.exitCode, r.err.ifBlank { r.out })
            } catch (e: ExecuteException) {
                // Non-zero exit: the exception message is ffmpeg's stderr, which `probe` relies on
                FfmpegResult(1, e.message.orEmpty())
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            RuntimeManager.idProcessMap[processId]?.destroyForcibly()
            throw e
        }
    }
}
