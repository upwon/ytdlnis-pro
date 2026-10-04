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
            onDetail = { task -> showDetail(task.id) },
            onAssign = { task -> showRoleDialog(task) },
            onCancel = { task ->
                // stops the worker (it aborts the running request / ffmpeg); finished work stays saved for a retry
                runCatching { WorkManager.getInstance(this).cancelWorkById(java.util.UUID.fromString(task.workId)) }
                DubbingStatusStore.update(this, task.id) { it.copy(state = DubbingState.FAILED, message = getString(R.string.dubbing_cancelled)) }
                refresh()
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
                    R.id.filter_running -> it.state == DubbingState.QUEUED || it.state == DubbingState.RUNNING || it.state == DubbingState.WAITING
                    R.id.filter_done -> it.state == DubbingState.DONE
                    R.id.filter_failed -> it.state == DubbingState.FAILED
                    else -> true
                }
            }
            adapter.submitList(shown)
            empty.isVisible = shown.isEmpty()
        }
    }

    /** One card per person: who they are, example lines, a voice picker and a "listen" button. Start continues the task. */
    private fun showRoleDialog(task: DubbingTask) {
        val roles = runCatching {
            val arr = org.json.JSONArray(task.roles)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
        if (roles.isEmpty()) return
        val voices = com.deniscerri.ytdl.dubbing.Presets.chineseVoices
        val dp = resources.displayMetrics.density
        val chosen = HashMap<String, String>()
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        var player: android.media.MediaPlayer? = null

        roles.forEachIndexed { n, r ->
            val id = r.getString("id")
            val male = r.getString("gender") == "M"
            chosen[id] = r.optString("voice")
            column.addView(TextView(this).apply {
                text = getString(R.string.dubbing_roles_person, n + 1, getString(if (male) R.string.dubbing_male else R.string.dubbing_female), r.optInt("lines"))
                textSize = 16f
                setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
            })
            val note = r.optString("note")
            val samples = r.optJSONArray("samples")
            val sampleText = (0 until (samples?.length() ?: 0)).joinToString("\n") { "· " + samples!!.getString(it) }
            column.addView(TextView(this).apply {
                text = (if (note.isNotBlank()) note + "\n" else "") + sampleText
                textSize = 13f
                alpha = 0.75f
            })
            val ids = voices.map { it.first }.toMutableList()
            val labels = voices.map { it.second }.toMutableList()
            if (chosen[id] !in ids) { ids += chosen[id]!!; labels += chosen[id]!! }
            val spinner = android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(this@DubbingTasksActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(ids.indexOf(chosen[id]).coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, i: Long) { chosen[id] = ids[pos] }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            }
            val listen = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.dubbing_roles_listen)
                setOnClickListener {
                    val line = samples?.takeIf { it.length() > 0 }?.getString(0) ?: return@setOnClickListener
                    lifecycleScope.launch {
                        runCatching {
                            val factory = com.deniscerri.ytdl.util.dubbing.DubbingFactory(this@DubbingTasksActivity)
                            val (tts, system) = factory.createTts()
                            try {
                                val file = java.io.File(cacheDir, "dubbing_role_${n}.${tts.fileExtension}")
                                withContext(Dispatchers.IO) { tts.synthesize(line, chosen[id].orEmpty(), 0, file) }
                                player?.release()
                                player = android.media.MediaPlayer().apply {
                                    setDataSource(file.absolutePath); prepare(); start()
                                    setOnCompletionListener { release() }
                                }
                            } finally { system?.shutdown() }
                        }.onFailure { android.widget.Toast.makeText(this@DubbingTasksActivity, it.message.orEmpty(), android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(spinner, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(listen)
            }
            column.addView(row)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dubbing_roles_title)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dubbing_roles_start) { _, _ ->
                DubbingScheduler.continueWithRoles(this, task, chosen)
                refresh()
            }
            .create()
        dialog.setOnDismissListener { player?.release() }
        dialog.show()
    }

    /** Live details of one job: status, time, the file, the sentence being handled and the recent log. */
    private fun showDetail(taskId: String) {
        val text = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13f
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dubbing_detail)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .create()

        fun render() {
            val t = DubbingStatusStore.all(this).firstOrNull { it.id == taskId } ?: return
            val state = getString(
                when (t.state) {
                    DubbingState.QUEUED -> R.string.dubbing_state_queued
                    DubbingState.RUNNING -> R.string.dubbing_state_running
                    DubbingState.WAITING -> R.string.dubbing_state_waiting
                    DubbingState.DONE -> R.string.dubbing_state_done
                    DubbingState.FAILED -> R.string.dubbing_state_failed
                }
            )
            val running = t.state == DubbingState.RUNNING || t.state == DubbingState.QUEUED
            val end = if (running) System.currentTimeMillis() else t.updatedAt
            val secs = ((end - t.createdAt) / 1000).coerceAtLeast(0)
            val stage = listOf(t.stage, if (t.total > 1) "${t.done}/${t.total}" else "").filter { it.isNotBlank() }.joinToString(" ")
            val none = getString(R.string.dubbing_detail_nothing)
            fun section(label: Int, value: String) = "${getString(label)}\n${value.ifBlank { none }}\n\n"
            text.text = buildString {
                append(t.title.ifBlank { getString(R.string.dubbing_title) }).append("\n\n")
                append(section(R.string.dubbing_detail_status, listOf(state, stage).filter { it.isNotBlank() }.joinToString(" · ")))
                append(section(R.string.dubbing_detail_kind, if (t.kind == com.deniscerri.ytdl.util.dubbing.KIND_SUBTITLE) getString(R.string.dubbing_kind_subtitle) else getString(R.string.dubbing_detail_kind_dub)))
                append(section(R.string.dubbing_detail_elapsed, "%d:%02d".format(secs / 60, secs % 60)))
                if (running) {
                    val idle = ((System.currentTimeMillis() - t.updatedAt) / 1000).coerceAtLeast(0)
                    append(section(R.string.dubbing_detail_idle, "%d:%02d".format(idle / 60, idle % 60)))
                }
                append(section(R.string.dubbing_detail_file, t.sourcePath))
                if (running) append(section(R.string.dubbing_detail_now, t.preview))
                if (t.state == DubbingState.FAILED) append(section(R.string.dubbing_detail_error, t.message))
                if (t.state == DubbingState.DONE && t.outputPath.isNotBlank()) append(getString(R.string.dubbing_saved_to, t.outputPath)).append("\n\n")
                append(section(R.string.dubbing_detail_log, t.log))
            }
        }
        render()
        dialog.show()
        // the worker writes progress into the store about once a second; keep the dialog in step while it is open
        val job = lifecycleScope.launch { while (true) { delay(1000); render() } }
        dialog.setOnDismissListener { job.cancel() }
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
        val onDetail: (DubbingTask) -> Unit,
        val onAssign: (DubbingTask) -> Unit,
        val onCancel: (DubbingTask) -> Unit,
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
                DubbingState.WAITING -> R.string.dubbing_state_waiting
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
                    DubbingState.FAILED, DubbingState.WAITING -> t.message
                    DubbingState.QUEUED -> t.message
                    else -> ""
                }
                text = msg
                isVisible = msg.isNotBlank()
            }

            c.findViewById<MaterialButton>(R.id.task_cancel).apply {
                isVisible = t.state == DubbingState.RUNNING || t.state == DubbingState.QUEUED
                setOnClickListener { onCancel(t) }
            }
            c.findViewById<MaterialButton>(R.id.task_assign).apply {
                isVisible = t.state == DubbingState.WAITING && t.roles.isNotBlank()
                setOnClickListener { onAssign(t) }
            }
            c.findViewById<MaterialButton>(R.id.task_open).apply {
                isVisible = t.state == DubbingState.DONE && t.outputPath.isNotBlank() && t.kind == com.deniscerri.ytdl.util.dubbing.KIND_DUB
                setOnClickListener { onOpen(t) }
            }
            c.findViewById<MaterialButton>(R.id.task_retry).apply {
                isVisible = t.state == DubbingState.FAILED && t.historyId >= 0
                setOnClickListener { onRetry(t) }
            }
            c.setOnClickListener { onDetail(t) }
            c.findViewById<MaterialButton>(R.id.task_detail).setOnClickListener { onDetail(t) }
            c.findViewById<MaterialButton>(R.id.task_remove).apply {
                isVisible = t.state != DubbingState.RUNNING
                setOnClickListener { onRemove(t) }
            }
        }
    }
}
