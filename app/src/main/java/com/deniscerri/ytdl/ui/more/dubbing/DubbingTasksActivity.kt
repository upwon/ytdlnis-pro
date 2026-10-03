package com.deniscerri.ytdl.ui.more.dubbing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.dubbing.DubbingScheduler
import com.deniscerri.ytdl.util.dubbing.DubbingState
import com.deniscerri.ytdl.util.dubbing.DubbingStatusStore
import com.deniscerri.ytdl.util.dubbing.DubbingTask
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Lists every dubbing job with its status, progress, error and result, with a status filter. */
class DubbingTasksActivity : BaseActivity() {
    private lateinit var adapter: TaskAdapter
    private lateinit var empty: View
    private var filter = R.id.filter_all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dubbing_tasks)

        findViewById<MaterialToolbar>(R.id.dubbing_toolbar).apply {
            setNavigationOnClickListener { finish() }
            setOnMenuItemClickListener {
                if (it.itemId == R.id.clear_finished) {
                    DubbingStatusStore.clearFinished(this@DubbingTasksActivity)
                    refresh()
                }
                true
            }
        }

        empty = findViewById(R.id.dubbing_empty)
        adapter = TaskAdapter(
            onOpen = { task -> runCatching { FileUtil.openFileIntent(this, task.outputPath) } },
            onRetry = { task ->
                DubbingStatusStore.remove(this, task.id)
                if (task.kind == com.deniscerri.ytdl.util.dubbing.KIND_SUBTITLE) DubbingScheduler.enqueueSubtitlesForHistory(this, listOf(task.historyId))
                else DubbingScheduler.enqueueForHistory(this, listOf(task.historyId))
                lifecycleScope.launch { delay(400); refresh() }
            },
            onRemove = { task ->
                DubbingStatusStore.remove(this, task.id)
                refresh()
            },
        )
        findViewById<RecyclerView>(R.id.dubbing_list).apply {
            layoutManager = LinearLayoutManager(this@DubbingTasksActivity)
            adapter = this@DubbingTasksActivity.adapter
            itemAnimator = null
        }
        findViewById<ChipGroup>(R.id.dubbing_filter_group).setOnCheckedStateChangeListener { _, ids ->
            filter = ids.firstOrNull() ?: R.id.filter_all
            refresh()
        }
        // progress is written by the worker into the store; polling once a second keeps the screen live
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) { reconcile() }
            val shown = tasks.filter {
                when (filter) {
                    R.id.filter_running -> it.state == DubbingState.QUEUED || it.state == DubbingState.RUNNING
                    R.id.filter_done -> it.state == DubbingState.DONE
                    R.id.filter_failed -> it.state == DubbingState.FAILED
                    else -> true
                }
            }
            adapter.submitList(shown)
            empty.isVisible = shown.isEmpty()
        }
    }

    /** A job the worker never finished (app killed, work cancelled) must not look "in progress" forever. */
    private fun reconcile(): List<DubbingTask> {
        val tasks = DubbingStatusStore.all(this)
        if (tasks.none { it.state == DubbingState.QUEUED || it.state == DubbingState.RUNNING }) return tasks
        val infos = runCatching { WorkManager.getInstance(this).getWorkInfosByTag(DubbingScheduler.WORK_TAG).get() }
            .getOrNull()?.associateBy { it.id.toString() } ?: return tasks
        var changed = false
        tasks.forEach { t ->
            if (t.state != DubbingState.QUEUED && t.state != DubbingState.RUNNING) return@forEach
            val info = infos[t.workId]
            val dead = info == null || info.state == WorkInfo.State.CANCELLED || info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.SUCCEEDED
            if (dead) {
                changed = true
                DubbingStatusStore.update(this, t.id) {
                    it.copy(state = DubbingState.FAILED, message = it.message.ifBlank { getString(R.string.dubbing_interrupted) })
                }
            } else if (info!!.state == WorkInfo.State.RUNNING && t.state == DubbingState.QUEUED) {
                changed = true
                DubbingStatusStore.update(this, t.id) { it.copy(state = DubbingState.RUNNING) }
            }
        }
        return if (changed) DubbingStatusStore.all(this) else tasks
    }

    private class TaskAdapter(
        val onOpen: (DubbingTask) -> Unit,
        val onRetry: (DubbingTask) -> Unit,
        val onRemove: (DubbingTask) -> Unit,
    ) : ListAdapter<DubbingTask, TaskAdapter.Holder>(object : DiffUtil.ItemCallback<DubbingTask>() {
        override fun areItemsTheSame(a: DubbingTask, b: DubbingTask) = a.id == b.id
        override fun areContentsTheSame(a: DubbingTask, b: DubbingTask) = a == b
    }) {
        class Holder(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.dubbing_task_card, parent, false) as MaterialCardView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val t = getItem(position)
            val c = holder.card
            val ctx = c.context
            c.findViewById<TextView>(R.id.task_title).text = t.title.ifBlank { ctx.getString(R.string.dubbing_title) }

            val statusRes = when (t.state) {
                DubbingState.QUEUED -> R.string.dubbing_state_queued
                DubbingState.RUNNING -> R.string.dubbing_state_running
                DubbingState.DONE -> R.string.dubbing_state_done
                DubbingState.FAILED -> R.string.dubbing_state_failed
            }
            val detail = when {
                t.state == DubbingState.RUNNING && t.stage.isNotBlank() ->
                    if (t.total > 1) "${t.stage} ${t.done}/${t.total}" else t.stage
                else -> ""
            }
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.updatedAt))
            c.findViewById<TextView>(R.id.task_status).apply {
                val kind = if (t.kind == com.deniscerri.ytdl.util.dubbing.KIND_SUBTITLE) ctx.getString(R.string.dubbing_kind_subtitle) else ""
                text = listOf(kind, ctx.getString(statusRes), detail, time).filter { it.isNotBlank() }.joinToString(" · ")
                setTextColor(
                    when (t.state) {
                        DubbingState.FAILED -> com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)
                        DubbingState.DONE -> com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                        else -> com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
                    }
                )
            }

            c.findViewById<LinearProgressIndicator>(R.id.task_progress).apply {
                isVisible = t.state == DubbingState.RUNNING || t.state == DubbingState.QUEUED
                if (t.total > 0 && t.state == DubbingState.RUNNING) {
                    isIndeterminate = false
                    max = t.total
                    progress = t.done
                } else {
                    isIndeterminate = true
                }
            }

            c.findViewById<TextView>(R.id.task_message).apply {
                val msg = when (t.state) {
                    DubbingState.DONE -> if (t.outputPath.isNotBlank()) ctx.getString(R.string.dubbing_saved_to, t.outputPath) else ""
                    DubbingState.FAILED -> t.message
                    else -> ""
                }
                text = msg
                isVisible = msg.isNotBlank()
            }

            c.findViewById<MaterialButton>(R.id.task_open).apply {
                isVisible = t.state == DubbingState.DONE && t.outputPath.isNotBlank() && t.kind == com.deniscerri.ytdl.util.dubbing.KIND_DUB
                setOnClickListener { onOpen(t) }
            }
            c.findViewById<MaterialButton>(R.id.task_retry).apply {
                isVisible = t.state == DubbingState.FAILED && t.historyId >= 0
                setOnClickListener { onRetry(t) }
            }
            c.findViewById<MaterialButton>(R.id.task_remove).apply {
                isVisible = t.state != DubbingState.RUNNING
                setOnClickListener { onRemove(t) }
            }
        }
    }
}
