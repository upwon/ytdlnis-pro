package com.deniscerri.ytdl.ui.more.settings.dubbing

import android.content.Context
import android.media.MediaPlayer
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.dubbing.Cue
import com.deniscerri.ytdl.dubbing.LlmTranslator
import com.deniscerri.ytdl.dubbing.ModelInfo
import com.deniscerri.ytdl.dubbing.Presets
import com.deniscerri.ytdl.ui.more.settings.SettingHost
import com.deniscerri.ytdl.ui.more.settings.SettingModule
import com.deniscerri.ytdl.util.dubbing.DubbingFactory
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DubbingSettingsModule : SettingModule {
    private val AZURE_KEYS = listOf(DubbingPrefs.AZURE_REGION, DubbingPrefs.AZURE_KEY)
    private val OPENAI_TTS_KEYS = listOf(DubbingPrefs.TTS_BASE_URL, DubbingPrefs.TTS_API_KEY, DubbingPrefs.TTS_MODEL)
    private val VOICE_KEYS = listOf(DubbingPrefs.VOICE, DubbingPrefs.VOICE_CUSTOM)

    override fun bindLogic(pref: Preference, host: SettingHost) {
        val context = pref.context
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // API keys are shown masked
        if (DubbingPrefs.isSecret(pref.key) && pref is EditTextPreference) {
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val v = it.text.orEmpty()
                if (v.isEmpty()) context.getString(R.string.dubbing_not_set) else "••••••" + v.takeLast(4)
            }
        }

        when (pref.key) {
            DubbingPrefs.LLM_PRESET -> (pref as ListPreference).apply {
                entries = Presets.llm.map { it.name }.toTypedArray()
                entryValues = Presets.llm.indices.map { it.toString() }.toTypedArray()
                setOnPreferenceChangeListener { _, newValue ->
                    val preset = Presets.llm.getOrNull((newValue as String).toInt())
                    if (preset != null && preset.baseUrl.isNotEmpty()) {
                        (host.findPref(DubbingPrefs.LLM_BASE_URL) as? EditTextPreference)?.text = preset.baseUrl
                        if (preset.defaultModel.isNotEmpty()) {
                            (host.findPref(DubbingPrefs.LLM_MODEL) as? EditTextPreference)?.text = preset.defaultModel
                        }
                    }
                    true
                }
            }

            DubbingPrefs.VOICE -> (pref as ListPreference).apply {
                entries = Presets.chineseVoices.map { it.second }.toTypedArray()
                entryValues = Presets.chineseVoices.map { it.first }.toTypedArray()
            }

            DubbingPrefs.TTS_ENGINE -> {
                applyEngineVisibility(host, prefs.getString(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE)!!)
                pref.setOnPreferenceChangeListener { _, newValue ->
                    applyEngineVisibility(host, newValue as String)
                    true
                }
            }

            "dubbing_tasks" -> pref.setOnPreferenceClickListener {
                context.startActivity(android.content.Intent(context, com.deniscerri.ytdl.ui.more.dubbing.DubbingTasksActivity::class.java))
                true
            }

            "dubbing_fetch_models" -> pref.setOnPreferenceClickListener {
                fetchModels(context, host)
                true
            }

            "dubbing_test_llm" -> pref.setOnPreferenceClickListener {
                testTranslation(context, host)
                true
            }

            "dubbing_test_tts" -> pref.setOnPreferenceClickListener {
                testVoice(context, host)
                true
            }
        }
    }

    private fun applyEngineVisibility(host: SettingHost, engine: String) {
        AZURE_KEYS.forEach { host.findPref(it)?.isVisible = engine == DubbingPrefs.ENGINE_AZURE }
        OPENAI_TTS_KEYS.forEach { host.findPref(it)?.isVisible = engine == DubbingPrefs.ENGINE_OPENAI }
        VOICE_KEYS.forEach { host.findPref(it)?.isVisible = engine != DubbingPrefs.ENGINE_SYSTEM }
        host.findPref(DubbingPrefs.FALLBACK_SYSTEM_TTS)?.isVisible = engine != DubbingPrefs.ENGINE_SYSTEM
    }

    private fun showDialog(host: SettingHost, title: String, message: String) {
        MaterialAlertDialogBuilder(host.getHostContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun fetchModels(context: Context, host: SettingHost) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getString(DubbingPrefs.LLM_BASE_URL, "").isNullOrBlank()) {
            Toast.makeText(context, R.string.dubbing_base_url_missing, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.dubbing_test_running, Toast.LENGTH_SHORT).show()
        host.hostLifecycleOwner.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { DubbingFactory(context).llmClient().listModels() } }
                .onSuccess { models ->
                    val sorted: List<ModelInfo> = models.sortedWith(compareBy({ !it.isFree }, { it.id }))
                    if (sorted.isEmpty()) {
                        showDialog(host, context.getString(R.string.dubbing_error), context.getString(R.string.no_results))
                        return@onSuccess
                    }
                    showModelPicker(host, sorted) { id ->
                        (host.findPref(DubbingPrefs.LLM_MODEL) as? EditTextPreference)?.text = id
                    }
                }
                .onFailure { showDialog(host, context.getString(R.string.dubbing_error), it.message.orEmpty()) }
        }
    }

    /** Model list with a keyword filter (several words = all must match) and a "free only" switch. */
    private fun showModelPicker(host: SettingHost, models: List<ModelInfo>, onPick: (String) -> Unit) {
        val ctx = host.getHostContext()
        val dp = ctx.resources.displayMetrics.density
        val search = EditText(ctx).apply {
            id = R.id.dubbing_model_search
            hint = ctx.getString(R.string.dubbing_search_model)
            setSingleLine()
        }
        val freeOnly = CheckBox(ctx).apply {
            text = ctx.getString(R.string.dubbing_free_only)
            isVisible = models.any { it.isFree }
        }
        val adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(ctx).apply { this.adapter = adapter }
        var shown: List<ModelInfo> = models

        fun label(m: ModelInfo) = if (m.isFree) "🆓 ${m.id}" else m.id
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.dubbing_choose_model))
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * dp).toInt()
                setPadding(pad, (8 * dp).toInt(), pad, 0)
                addView(search)
                addView(freeOnly)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt()))
            })
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun refresh() {
            val words = search.text.toString().lowercase().split(' ').filter { it.isNotEmpty() }
            shown = models.filter { m ->
                (!freeOnly.isChecked || m.isFree) &&
                    words.all { w -> m.id.lowercase().contains(w) || m.name?.lowercase()?.contains(w) == true }
            }
            adapter.clear()
            adapter.addAll(shown.map(::label))
            dialog.setTitle("${ctx.getString(R.string.dubbing_choose_model)} (${shown.size}/${models.size})")
        }
        search.doAfterTextChanged { refresh() }
        freeOnly.setOnCheckedChangeListener { _, _ -> refresh() }
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { onPick(it.id) }
            dialog.dismiss()
        }
        dialog.show()
        refresh()
    }

    private fun testTranslation(context: Context, host: SettingHost) {
        val factory = DubbingFactory(context)
        if (!factory.llmConfigured()) {
            Toast.makeText(context, R.string.dubbing_not_configured_desc, Toast.LENGTH_LONG).show()
            return
        }
        val model = PreferenceManager.getDefaultSharedPreferences(context).getString(DubbingPrefs.LLM_MODEL, "")!!.trim()
        Toast.makeText(context, R.string.dubbing_test_running, Toast.LENGTH_SHORT).show()
        host.hostLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    LlmTranslator(factory.llmClient(), model)
                        .translate(listOf(Cue(1, 0, 3000, "Hello, and welcome back to the channel!")))
                        .first().zh
                }
            }
                .onSuccess { showDialog(host, context.getString(R.string.dubbing_test_ok), it) }
                .onFailure { showDialog(host, context.getString(R.string.dubbing_error), it.message.orEmpty()) }
        }
    }

    private fun testVoice(context: Context, host: SettingHost) {
        val factory = DubbingFactory(context)
        Toast.makeText(context, R.string.dubbing_test_running, Toast.LENGTH_SHORT).show()
        host.hostLifecycleOwner.lifecycleScope.launch {
            val (tts, system) = runCatching { factory.createTts() }.getOrElse {
                showDialog(host, context.getString(R.string.dubbing_error), it.message.orEmpty())
                return@launch
            }
            runCatching {
                val file = File(context.cacheDir, "dubbing_test.${tts.fileExtension}")
                withContext(Dispatchers.IO) { tts.synthesize("你好，这是中文配音测试。", factory.voice(), 0, file) }
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release() }
                    prepare()
                    start()
                }
            }.onFailure { showDialog(host, context.getString(R.string.dubbing_error), it.message.orEmpty()) }
            system?.shutdown()
        }
    }
}
