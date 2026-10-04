package com.deniscerri.ytdl.util.dubbing

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class DubbingState { QUEUED, RUNNING, DONE, FAILED }

data class DubbingTask(
    val id: String,
    val historyId: Long = -1,
    val title: String = "",
    val state: DubbingState = DubbingState.QUEUED,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val outputPath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val workId: String = "",
    /** "dub" or "subtitle" (translate subtitles only). */
    val kind: String = KIND_DUB,
    /** For the detail view: file being processed, the sentence being handled right now, and the last log lines. */
    val sourcePath: String = "",
    val preview: String = "",
    val log: String = "",
)

const val KIND_DUB = "dub"
const val KIND_SUBTITLE = "subtitle"

/**
 * Small persistent list of dubbing jobs shown on the "dubbing tasks" screen. Kept in SharedPreferences (not Room)
 * so no database migration is needed. The worker is the only writer of progress; the screen reconciles with
 * WorkManager so a job that died with the process does not stay "running" forever.
 */
object DubbingStatusStore {
    private const val FILE = "dubbing_status"
    private const val KEY = "tasks"
    private const val MAX = 100

    @Synchronized
    fun all(context: Context): List<DubbingTask> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, task: DubbingTask) = save(context, listOf(task) + all(context))

    @Synchronized
    fun update(context: Context, id: String, change: (DubbingTask) -> DubbingTask) {
        val list = all(context)
        if (list.none { it.id == id }) return
        save(context, list.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    @Synchronized
    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    @Synchronized
    fun clearFinished(context: Context) =
        save(context, all(context).filter { it.state == DubbingState.QUEUED || it.state == DubbingState.RUNNING })

    fun newId() = UUID.randomUUID().toString()

    private fun save(context: Context, list: List<DubbingTask>) {
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, arr.toString()) }
    }

    private fun toJson(t: DubbingTask) = JSONObject()
        .put("id", t.id).put("historyId", t.historyId).put("title", t.title).put("state", t.state.name)
        .put("stage", t.stage).put("done", t.done).put("total", t.total).put("message", t.message)
        .put("outputPath", t.outputPath).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)
        .put("workId", t.workId).put("kind", t.kind).put("sourcePath", t.sourcePath).put("preview", t.preview).put("log", t.log)

    private fun fromJson(o: JSONObject) = DubbingTask(
        id = o.getString("id"),
        historyId = o.optLong("historyId", -1),
        title = o.optString("title"),
        state = runCatching { DubbingState.valueOf(o.optString("state")) }.getOrDefault(DubbingState.FAILED),
        stage = o.optString("stage"),
        done = o.optInt("done"),
        total = o.optInt("total"),
        message = o.optString("message"),
        outputPath = o.optString("outputPath"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        workId = o.optString("workId"),
        kind = o.optString("kind", KIND_DUB),
        sourcePath = o.optString("sourcePath"),
        preview = o.optString("preview"),
        log = o.optString("log"),
    )
}
