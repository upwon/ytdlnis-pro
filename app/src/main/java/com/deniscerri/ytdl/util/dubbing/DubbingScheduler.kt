package com.deniscerri.ytdl.util.dubbing

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.deniscerri.ytdl.work.DubbingWorker

object DubbingScheduler {
    const val WORK_TAG = "dubbing"

    private fun enqueue(context: Context, data: androidx.work.Data) {
        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data)
            .build()
        // APPEND_OR_REPLACE chains the jobs: videos are dubbed one after another, never in parallel.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueForHistory(context: Context, historyIds: List<Long>) {
        historyIds.forEach { enqueue(context, workDataOf(DubbingWorker.KEY_HISTORY_ID to it)) }
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
