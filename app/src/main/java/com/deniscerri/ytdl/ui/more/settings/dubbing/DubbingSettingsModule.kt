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
    private val VALUE_SHOWN_KEYS = setOf(
        DubbingPrefs.ASR_BASE_URL, DubbingPrefs.TTS_BASE_URL, DubbingPrefs.VOICE_CUSTOM,
        DubbingPrefs.LLM_EXTRA_BODY, DubbingPrefs.EXTRA_PROMPT, DubbingPrefs.GLOSSARY,
    )
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

        // Fields that carry an explanatory summary must still show what was entered, or they look empty
        if (pref is EditTextPreference && pref.key in VALUE_SHOWN_KEYS && !DubbingPrefs.isSecret(pref.key)) {
            val hint = pref.summary
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val v = it.text.orEmpty().trim().replace(Regex("\\s+"), " ")
                if (v.isEmpty()) hint else if (v.length > 80) v.take(80) + "…" else v
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
                        (host.findPref(DubbingPrefs.LLM_CONCURRENCY) as? ListPreference)?.value = preset.concurrency.toString()
                        (host.findPref(DubbingPrefs.LLM_EXTRA_BODY) as? EditTextPreference)?.text = preset.extraBody
                    }
                    true
                }
            }

            DubbingPrefs.VOICE_MALE, DubbingPrefs.VOICE -> applyVoices(pref as ListPreference, prefs)

            DubbingPrefs.LLM_PROFILES -> pref.setOnPreferenceClickListener {
                showProfiles(host)
                true
            }

            "dubbing_multitts_load" -> pref.setOnPreferenceClickListener {
                loadMultiTtsVoices(context, host)
                true
            }

            "dubbing_multitts_probe" -> pref.setOnPreferenceClickListener {
                probeMultiTtsVoices(context, host, pref)
                true
            }

            DubbingPrefs.TTS_ENGINE -> {
                applyEngineVisibility(host, prefs.getString(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE)!!)
                pref.setOnPreferenceChangeListener { _, newValue ->
                    applyEngineVisibility(host, newValue as String)
                    prefs.edit().putString(DubbingPrefs.TTS_ENGINE, newValue as String).apply()
                    refreshVoices(host, prefs)
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

            "dubbing_test_asr" -> pref.setOnPreferenceClickListener {
                testRecognition(context, host)
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
        host.findPref(DubbingPrefs.TTS_BASE_URL)?.isVisible = engine == DubbingPrefs.ENGINE_OPENAI || engine == DubbingPrefs.ENGINE_MULTITTS
        host.findPref("dubbing_multitts_load")?.isVisible = engine == DubbingPrefs.ENGINE_MULTITTS
        host.findPref("dubbing_multitts_probe")?.isVisible = engine == DubbingPrefs.ENGINE_MULTITTS
        VOICE_KEYS.forEach { host.findPref(it)?.isVisible = engine != DubbingPrefs.ENGINE_SYSTEM }
        host.findPref(DubbingPrefs.FALLBACK_SYSTEM_TTS)?.isVisible = engine != DubbingPrefs.ENGINE_SYSTEM
    }

    /** Voice lists: the built-in Mandarin voices, or what MultiTTS reported on this phone. */
    /** [sex]: "male" / "female" to keep one sex, null for both (the main voice may be either). */
    private fun currentVoices(prefs: android.content.SharedPreferences, sex: String?): List<Pair<String, String>> {
        if (prefs.getString(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE) != DubbingPrefs.ENGINE_MULTITTS) return Presets.chineseVoices
        // lines of "id<TAB>label<TAB>gender"
        val working = prefs.getString(DubbingPrefs.MULTITTS_WORKING, "").orEmpty()
        return working.ifBlank { prefs.getString(DubbingPrefs.MULTITTS_VOICES, "").orEmpty() }.lines().filter { it.isNotBlank() }
            .map { it.split('\t') }.filter { it.size >= 3 && (sex == null || it[2] == sex) }
            .map { it[0] to if (sex == null && !it[1].contains("男") && !it[1].contains("女")) it[1] + if (it[2] == "male") "（男）" else "（女）" else it[1] }
    }

    private fun applyVoices(pref: ListPreference, prefs: android.content.SharedPreferences) {
        val voices = currentVoices(prefs, if (pref.key == DubbingPrefs.VOICE_MALE) "male" else null)
        pref.entries = voices.map { it.second }.toTypedArray()
        pref.entryValues = voices.map { it.first }.toTypedArray()
        if (voices.isNotEmpty() && voices.none { it.first == pref.value }) pref.value = voices.first().first
    }

    private fun refreshVoices(host: SettingHost, prefs: android.content.SharedPreferences) {
        listOf(DubbingPrefs.VOICE, DubbingPrefs.VOICE_MALE).forEach { key ->
            (host.findPref(key) as? ListPreference)?.let { applyVoices(it, prefs) }
        }
    }

    private fun loadMultiTtsVoices(context: Context, host: SettingHost) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        Toast.makeText(context, R.string.dubbing_test_running, Toast.LENGTH_SHORT).show()
        host.hostLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val base = prefs.getString(DubbingPrefs.TTS_BASE_URL, "").orEmpty().trim()
                withContext(Dispatchers.IO) { com.deniscerri.ytdl.dubbing.ForwardTts(base).listVoices() }
            }.onSuccess { voices ->
                if (voices.isEmpty()) {
                    showDialog(host, context.getString(R.string.dubbing_error), context.getString(R.string.no_results))
                    return@onSuccess
                }
                // Mandarin only, voices that work without internet first
                val usable = voices.filter { it.isMandarin && (it.isMale || it.isFemale) }.sortedBy { it.online }
                if (usable.isEmpty()) {
                    showDialog(host, context.getString(R.string.dubbing_error), context.getString(R.string.no_results))
                    return@onSuccess
                }
                val lines = usable.joinToString("\n") {
                    val label = listOf(it.name, it.desc).filter { s -> s.isNotBlank() }.joinToString(" · ") + if (it.online) " (在线)" else ""
                    "${it.id}\t${label.replace('\t', ' ').replace('\n', ' ')}\t${it.gender}"
                }
                // a new catalogue invalidates the earlier probe
                prefs.edit().putString(DubbingPrefs.MULTITTS_VOICES, lines).remove(DubbingPrefs.MULTITTS_WORKING).apply()
                refreshVoices(host, prefs)
                Toast.makeText(context, context.getString(R.string.dubbing_multitts_loaded, usable.size), Toast.LENGTH_LONG).show()
                // many listed voices have no data on this phone: find the ones that really speak, right away
                host.findPref("dubbing_multitts_probe")?.let { probeMultiTtsVoices(context, host, it) }
            }.onFailure { showDialog(host, context.getString(R.string.dubbing_error), it.message ?: it.javaClass.simpleName) }
        }
    }

    private class Profile(val name: String, val baseUrl: String, val apiKey: String, val model: String, val concurrency: String, val extraBody: String)

    private fun loadProfiles(prefs: android.content.SharedPreferences): MutableList<Profile> {
        val arr = runCatching { org.json.JSONArray(prefs.getString(DubbingPrefs.LLM_PROFILES, "[]")) }.getOrDefault(org.json.JSONArray())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Profile(it.optString("name"), it.optString("baseUrl"), it.optString("apiKey"), it.optString("model"), it.optString("concurrency", "1"), it.optString("extraBody"))
        }.toMutableList()
    }

    private fun storeProfiles(prefs: android.content.SharedPreferences, list: List<Profile>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().put("name", it.name).put("baseUrl", it.baseUrl).put("apiKey", it.apiKey)
                .put("model", it.model).put("concurrency", it.concurrency).put("extraBody", it.extraBody))
        }
        prefs.edit().putString(DubbingPrefs.LLM_PROFILES, arr.toString()).apply()
    }

    private fun applyProfile(host: SettingHost, p: Profile) {
        (host.findPref(DubbingPrefs.LLM_BASE_URL) as? EditTextPreference)?.text = p.baseUrl
        (host.findPref(DubbingPrefs.LLM_API_KEY) as? EditTextPreference)?.text = p.apiKey
        (host.findPref(DubbingPrefs.LLM_MODEL) as? EditTextPreference)?.text = p.model
        (host.findPref(DubbingPrefs.LLM_CONCURRENCY) as? ListPreference)?.value = p.concurrency
        (host.findPref(DubbingPrefs.LLM_EXTRA_BODY) as? EditTextPreference)?.text = p.extraBody
    }

    /** Named bundles of provider / key / model so switching services does not mean retyping everything. */
    private fun showProfiles(host: SettingHost) {
        val ctx = host.getHostContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val profiles = loadProfiles(prefs)
        fun cur(key: String) = prefs.getString(key, "").orEmpty()
        val labels = profiles.map {
            val active = it.baseUrl == cur(DubbingPrefs.LLM_BASE_URL) && it.model == cur(DubbingPrefs.LLM_MODEL) && it.apiKey == cur(DubbingPrefs.LLM_API_KEY)
            (if (active) "✓ " else "") + "${it.name}\n${it.model} · ${it.baseUrl.removePrefix("https://")}"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dubbing_profiles)
            .apply {
                if (profiles.isEmpty()) setMessage(R.string.dubbing_profiles_empty)
                else setItems(labels.toTypedArray()) { _, which -> chooseProfile(host, profiles[which]) }
            }
            .setPositiveButton(R.string.dubbing_profile_save_short) { _, _ -> saveCurrentAsProfile(host) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseProfile(host: SettingHost, p: Profile) {
        val ctx = host.getHostContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(p.name)
            .setItems(arrayOf(ctx.getString(R.string.dubbing_profile_switch), ctx.getString(R.string.dubbing_profile_delete))) { _, which ->
                if (which == 0) {
                    applyProfile(host, p)
                    Toast.makeText(ctx, ctx.getString(R.string.dubbing_profile_switched, p.name), Toast.LENGTH_SHORT).show()
                } else {
                    storeProfiles(prefs, loadProfiles(prefs).filter { it.name != p.name })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveCurrentAsProfile(host: SettingHost) {
        val ctx = host.getHostContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        fun s(key: String) = prefs.getString(key, "").orEmpty()
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.dubbing_profile_name)
            setSingleLine()
            setText(Presets.llm.firstOrNull { it.baseUrl.isNotEmpty() && it.baseUrl == s(DubbingPrefs.LLM_BASE_URL) }?.name?.substringBefore("（")?.substringBefore(" (").orEmpty())
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dubbing_profile_name)
            .setView(android.widget.FrameLayout(ctx).apply {
                val pad = (20 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { s(DubbingPrefs.LLM_MODEL).ifEmpty { "方案" } }
                val list = loadProfiles(prefs).filter { it.name != name }.toMutableList()
                list += Profile(name, s(DubbingPrefs.LLM_BASE_URL), s(DubbingPrefs.LLM_API_KEY), s(DubbingPrefs.LLM_MODEL),
                    s(DubbingPrefs.LLM_CONCURRENCY).ifEmpty { "1" }, s(DubbingPrefs.LLM_EXTRA_BODY))
                storeProfiles(prefs, list)
                Toast.makeText(ctx, ctx.getString(R.string.dubbing_profile_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var probeJob: kotlinx.coroutines.Job? = null

    /** Tries every offline voice once and keeps those that really make sound (many are listed but have no data installed). */
    private fun probeMultiTtsVoices(context: Context, host: SettingHost, pref: Preference) {
        if (probeJob?.isActive == true) {
            probeJob?.cancel()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val all = prefs.getString(DubbingPrefs.MULTITTS_VOICES, "").orEmpty().lines().filter { it.isNotBlank() }
            .map { it.split('\t') }.filter { it.size >= 3 && !it[1].endsWith("(在线)") }
        if (all.isEmpty()) {
            Toast.makeText(context, R.string.dubbing_multitts_load_first, Toast.LENGTH_LONG).show()
            return
        }
        val idleSummary = pref.summary
        val tts = com.deniscerri.ytdl.dubbing.ForwardTts(prefs.getString(DubbingPrefs.TTS_BASE_URL, "").orEmpty().trim(), probeClient)
        probeJob = host.hostLifecycleOwner.lifecycleScope.launch {
            val ok = mutableListOf<List<String>>()
            val file = File(context.cacheDir, "multitts_probe.mp3")
            try {
                all.forEachIndexed { i, v ->
                    pref.summary = context.getString(R.string.dubbing_multitts_probing, i + 1, all.size, ok.size)
                    val works = runCatching {
                        withContext(Dispatchers.IO) { tts.synthesize("你好", v[0], 0, file) }
                    }.isSuccess
                    if (works) ok += v
                    if (i % 10 == 9) saveWorking(prefs, ok)
                }
            } finally {
                saveWorking(prefs, ok)
                refreshVoices(host, prefs)
                pref.summary = idleSummary
                Toast.makeText(context, context.getString(R.string.dubbing_multitts_probe_done, ok.size, all.size), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val probeClient = com.deniscerri.ytdl.dubbing.defaultHttpClient().newBuilder()
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun saveWorking(prefs: android.content.SharedPreferences, ok: List<List<String>>) {
        prefs.edit().putString(DubbingPrefs.MULTITTS_WORKING, ok.joinToString("\n") { it.joinToString("\t") }).apply()
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
                    LlmTranslator(
                        factory.llmClient(), model,
                        com.deniscerri.ytdl.dubbing.TranslatorConfig(
                            keepEnglishTerms = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(DubbingPrefs.KEEP_TERMS, true),
                            extraSystemPrompt = PreferenceManager.getDefaultSharedPreferences(context).getString(DubbingPrefs.EXTRA_PROMPT, "").orEmpty(),
                            glossary = PreferenceManager.getDefaultSharedPreferences(context).getString(DubbingPrefs.GLOSSARY, "").orEmpty(),
                        ),
                    )
                        .translate(listOf(Cue(1, 0, 4500, "So, you know, the agent calls the API, and then we just merge the PR.")))
                        .first().zh
                }
            }
                .onSuccess { showDialog(host, context.getString(R.string.dubbing_test_ok), it) }
                .onFailure { showDialog(host, context.getString(R.string.dubbing_error), it.message.orEmpty()) }
        }
    }

    /** Speaks an English sentence with the free Edge voice and sends it to the configured recognition service. */
    private fun testRecognition(context: Context, host: SettingHost) {
        val asr = DubbingFactory(context).asrProvider()
        if (asr == null) {
            Toast.makeText(context, R.string.dubbing_asr_off, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, R.string.dubbing_test_running, Toast.LENGTH_SHORT).show()
        host.hostLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "dubbing_asr_test.mp3")
                    com.deniscerri.ytdl.dubbing.EdgeTts().synthesize("Hello, this is a test of speech recognition.", "en-US-AriaNeural", 0, file)
                    asr.transcribe(file, "en", 4_000).joinToString(" ") { it.src }
                }
            }
                .onSuccess { showDialog(host, context.getString(R.string.dubbing_test_ok), it.ifBlank { context.getString(R.string.dubbing_asr_empty) }) }
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
