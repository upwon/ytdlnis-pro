package com.deniscerri.ytdl.util.dubbing

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.deniscerri.ytdl.database.DBManager
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.deniscerri.ytdl.work.DubbingWorker

object DubbingScheduler {
    const val WORK_TAG = "dubbing"

    private fun enqueue(context: Context, data: androidx.work.Data, historyId: Long = -1, title: String = "", kind: String = KIND_DUB) {
        val taskId = DubbingStatusStore.newId()
        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // a timeout or a 5xx is retried by the worker itself; wait a little between attempts
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 2, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(Data.Builder().putAll(data).putString(DubbingWorker.KEY_TASK_ID, taskId).build())
            .build()
        DubbingStatusStore.add(context, DubbingTask(id = taskId, historyId = historyId, title = title, workId = request.id.toString(), kind = kind))
        // APPEND_OR_REPLACE chains the jobs: videos are dubbed one after another, never in parallel.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The user picked a voice for every person: continue the waiting task (same entry, analysis is reused). */
    fun continueWithRoles(context: Context, task: DubbingTask, voices: Map<String, String>) {
        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 2, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    DubbingWorker.KEY_HISTORY_ID to task.historyId,
                    DubbingWorker.KEY_TASK_ID to task.id,
                    DubbingWorker.KEY_ROLE_VOICES to org.json.JSONObject(voices).toString(),
                )
            )
            .build()
        DubbingStatusStore.update(context, task.id) {
            it.copy(state = DubbingState.QUEUED, workId = request.id.toString(), message = "", stage = "", roles = "")
        }
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueForHistory(context: Context, historyIds: List<Long>) {
        val appContext = context.applicationContext
        // the DAO is blocking, and callers are usually on the main thread
        CoroutineScope(Dispatchers.IO).launch {
            val dao = DBManager.getInstance(appContext).historyDao
            historyIds.forEach {
                val title = runCatching { dao.getHistoryItem(it)?.title }.getOrNull().orEmpty()
                enqueue(appContext, workDataOf(DubbingWorker.KEY_HISTORY_ID to it), it, title)
            }
        }
    }

    /** Translates the English subtitles into an external "<name>.zh.srt" without generating any speech. */
    fun enqueueSubtitlesForHistory(context: Context, historyIds: List<Long>) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = DBManager.getInstance(appContext).historyDao
            historyIds.forEach {
                val title = runCatching { dao.getHistoryItem(it)?.title }.getOrNull().orEmpty()
                enqueue(
                    appContext,
                    workDataOf(DubbingWorker.KEY_HISTORY_ID to it, DubbingWorker.KEY_MODE to DubbingWorker.MODE_SUBTITLE),
                    it, title, KIND_SUBTITLE,
                )
            }
        }
    }

    /** Used right after a download finished; the history row is looked up by its download id. */
    fun enqueueForDownload(context: Context, downloadId: Long, url: String, type: String) {
        enqueue(
            context,
            workDataOf(
                DubbingWorker.KEY_DOWNLOAD_ID to downloadId,
                DubbingWorker.KEY_URL to url,
                DubbingWorker.KEY_TYPE to type,
                // started automatically after a download: nobody is waiting to pick voices, use the suggested ones
                DubbingWorker.KEY_SKIP_CONFIRM to true,
            )
        )
    }
}
