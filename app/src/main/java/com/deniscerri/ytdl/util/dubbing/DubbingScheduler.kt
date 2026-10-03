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

    private fun enqueue(context: Context, data: androidx.work.Data, historyId: Long = -1, title: String = "") {
        val taskId = DubbingStatusStore.newId()
        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putAll(data).putString(DubbingWorker.KEY_TASK_ID, taskId).build())
            .build()
        DubbingStatusStore.add(context, DubbingTask(id = taskId, historyId = historyId, title = title, workId = request.id.toString()))
        // APPEND_OR_REPLACE chains the jobs: videos are dubbed one after another, never in parallel.
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

    /** Used right after a download finished; the history row is looked up by its download id. */
    fun enqueueForDownload(context: Context, downloadId: Long, url: String, type: String) {
        enqueue(
            context,
            workDataOf(
                DubbingWorker.KEY_DOWNLOAD_ID to downloadId,
                DubbingWorker.KEY_URL to url,
                DubbingWorker.KEY_TYPE to type,
            )
        )
    }
}
