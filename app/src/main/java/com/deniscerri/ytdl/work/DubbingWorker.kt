package com.deniscerri.ytdl.work

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.YTDLRequest
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.dao.HistoryDao
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.dubbing.Progress
import com.deniscerri.ytdl.dubbing.Stage
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.dubbing.DubbingFactory
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds a Chinese (AI) dub track to a downloaded video: subtitles / speech recognition -> LLM translation ->
 * TTS -> aligned audio track -> muxed with ffmpeg. The heavy lifting lives in the `dubbing-core` module.
 */
class DubbingWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private var lastNotificationAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(context.getString(R.string.dubbing_title), "", 0, 0)

    private fun progressNotification(title: String, text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, NotificationUtil.DOWNLOAD_MISC_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground_large)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, done, total == 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun foregroundInfo(title: String, text: String, done: Int, total: Int) = ForegroundInfo(
        FOREGROUND_ID,
        progressNotification(title, text, done, total),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    @SuppressLint("MissingPermission")
    private fun showProgress(title: String, p: Progress) {
        val now = System.currentTimeMillis()
        val finishedStage = p.total > 0 && p.done >= p.total
        if (!finishedStage && now - lastNotificationAt < 800) return
        lastNotificationAt = now
        val stage = context.getString(
            when (p.stage) {
                Stage.SOURCE -> R.string.dubbing_stage_source
                Stage.TRANSLATE -> R.string.dubbing_stage_translate
                Stage.SYNTHESIZE -> R.string.dubbing_stage_synthesize
                Stage.ALIGN -> R.string.dubbing_stage_align
                Stage.MUX -> R.string.dubbing_stage_mux
            }
        )
        val text = if (p.total > 1) "$stage ${p.done}/${p.total}" else stage
        runCatching { NotificationManagerCompat.from(context).notify(FOREGROUND_ID, progressNotification(title, text, p.done, p.total)) }
    }

    @SuppressLint("MissingPermission")
    private fun showResult(item: HistoryItem, success: Boolean, message: String) {
        val channel = if (success) NotificationUtil.DOWNLOAD_FINISHED_CHANNEL_ID else NotificationUtil.DOWNLOAD_ERRORED_CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(item.title.ifBlank { context.getString(R.string.dubbing_title) })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_launcher_foreground_large)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(RESULT_ID_BASE + (item.id % 10_000).toInt(), notification) }
    }

    override suspend fun doWork(): Result {
        val dao = DBManager.getInstance(context).historyDao
        val item = withContext(Dispatchers.IO) { resolveItem(dao) } ?: return Result.failure()
        val title = item.title.ifBlank { context.getString(R.string.dubbing_title) }

        try {
            withContext(Dispatchers.IO) { RuntimeManager.init(context) }
            runCatching { setForeground(foregroundInfo(title, "", 0, 0)) }

            val sourcePath = item.downloadPath.firstOrNull { FileUtil.exists(it) }
                ?: throw IllegalStateException(context.getString(R.string.dubbing_no_file))

            val workDir = File(FileUtil.getCachePath(context), "dubbing/${item.id}").apply { mkdirs() }
            val source = withContext(Dispatchers.IO) { localCopy(sourcePath, workDir) }

            val subtitle = withContext(Dispatchers.IO) {
                findSidecarSubtitle(source) ?: fetchSubtitle(item.url, workDir)
            }

            val outExt = if (source.extension.lowercase() in AUDIO_EXTENSIONS) "m4a" else source.extension.ifEmpty { "mp4" }
            val dubbed = File(workDir, "dubbed.$outExt")

            val setup = DubbingFactory(context).create(
                log = { android.util.Log.d(TAG, it) },
                onProgress = { showProgress(title, it) },
            )
            try {
                setup.pipeline.run(source, subtitle, workDir, dubbed)
            } finally {
                setup.close()
            }

            val newPath = withContext(Dispatchers.IO) { place(item, sourcePath, source, dubbed, outExt, dao) }
            runCatching { FileUtil.scanMedia(listOf(newPath), context) }
            workDir.deleteRecursively()
            showResult(item, true, context.getString(R.string.dubbing_done))
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "dubbing failed", e)
            showResult(item, false, "${context.getString(R.string.dubbing_failed)}: ${e.message}")
            return Result.failure()
        }
    }

    private fun resolveItem(dao: HistoryDao): HistoryItem? {
        val id = inputData.getLong(KEY_HISTORY_ID, -1L)
        if (id >= 0) return dao.getHistoryItem(id)
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        val url = inputData.getString(KEY_URL)
        val type = inputData.getString(KEY_TYPE)?.let { runCatching { DownloadType.valueOf(it) }.getOrNull() }
        if (downloadId < 0 || url == null || type == null) return null
        return dao.getAllHistoryByURLAndType(url, type).filter { it.downloadId == downloadId }.maxByOrNull { it.id }
    }

    /** Files picked through the storage access framework are copied into the cache so ffmpeg can read them. */
    private fun localCopy(path: String, workDir: File): File {
        if (!path.startsWith("content://")) return File(path)
        val uri = path.toUri()
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "source.mp4"
        val target = File(workDir, "source.${name.substringAfterLast('.', "mp4")}")
        if (!target.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Cannot open $path")
        }
        return target
    }

    /** English subtitles that yt-dlp already wrote next to the video ("name.en.srt", "name.en-orig.vtt" ...). */
    private fun findSidecarSubtitle(source: File): File? {
        val dir = source.parentFile ?: return null
        val base = source.nameWithoutExtension
        return dir.listFiles()
            ?.filter { f ->
                f.isFile && f.name.startsWith("$base.") && f.extension.lowercase() in SUBTITLE_EXTENSIONS &&
                    f.nameWithoutExtension.removePrefix("$base.").lowercase().startsWith("en")
            }
            ?.minWithOrNull(compareBy({ it.nameWithoutExtension.contains("orig") }, { it.name }))
    }

    private fun fetchSubtitle(url: String, workDir: File): File? {
        if (url.isBlank()) return null
        val out = File(workDir, "subs").apply { deleteRecursively(); mkdirs() }
        val request = YTDLRequest(url)
            .addOption("--skip-download")
            .addOption("--no-playlist")
            .addOption("--write-subs")
            .addOption("--write-auto-subs")
            .addOption("--sub-langs", "en.*,en")
            .addOption("--convert-subs", "srt")
            .addOption("-o", "${out.absolutePath}/sub.%(ext)s")
        runCatching { RuntimeManager.execute(request, processId = "dubbing-subs-${System.nanoTime()}") }
            .onFailure { android.util.Log.w(TAG, "subtitle download failed: ${it.message}") }
        return out.listFiles()
            ?.filter { it.extension.lowercase() == "srt" }
            ?.minWithOrNull(compareBy({ it.name.contains("orig") }, { it.name }))
    }

    /**
     * Either replaces the original file or saves "<name>.zh.<ext>" beside it (and adds a history entry).
     * Sources that live behind a content:// provider always produce a new file in the default folder.
     */
    private suspend fun place(
        item: HistoryItem,
        sourcePath: String,
        source: File,
        dubbed: File,
        ext: String,
        dao: HistoryDao,
    ): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val replace = prefs.getBoolean(DubbingPrefs.REPLACE_ORIGINAL, false)
        val isContent = sourcePath.startsWith("content://")

        if (replace && !isContent && source.extension.equals(ext, ignoreCase = true)) {
            dubbed.copyTo(source, overwrite = true)
            dubbed.delete()
            dao.update(item.copy(filesize = source.length()))
            return source.absolutePath
        }

        val newPath: String
        if (isContent) {
            val outDir = File(dubbed.parentFile, "out").apply { deleteRecursively(); mkdirs() }
            dubbed.copyTo(File(outDir, "${source.nameWithoutExtension}.zh.$ext"), overwrite = true)
            val destination = if (item.type == DownloadType.audio) {
                prefs.getString("music_path", FileUtil.getDefaultAudioPath())!!
            } else {
                prefs.getString("video_path", FileUtil.getDefaultVideoPath())!!
            }
            newPath = FileUtil.moveFile(outDir, context, destination, false) { }.firstOrNull() ?: throw IllegalStateException("Could not save the dubbed file")
        } else {
            val target = File(source.parentFile, "${source.nameWithoutExtension}.zh.$ext")
            dubbed.copyTo(target, overwrite = true)
            dubbed.delete()
            newPath = target.absolutePath
        }
        dao.insert(
            item.copy(
                id = 0,
                title = "${item.title} [${context.getString(R.string.dubbing_suffix)}]",
                downloadPath = listOf(newPath),
                time = System.currentTimeMillis() / 1000,
                filesize = runCatching { File(newPath).length() }.getOrDefault(0L),
            )
        )
        return newPath
    }

    companion object {
        const val KEY_HISTORY_ID = "history_id"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_URL = "url"
        const val KEY_TYPE = "type"
        private const val TAG = "DubbingWorker"
        private const val FOREGROUND_ID = 1_500_000_000
        private const val RESULT_ID_BASE = 1_500_100_000
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "wma")
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")
    }
}
