package com.deniscerri.ytdl.dubbing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.util.dubbing.AndroidFfmpegRunner
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.sqrt

const val TAG = "DubbingE2E"

/** Shared helpers for the on-device dubbing tests. */
object DeviceTestEnv {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = inst.targetContext
    val prefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(ctx)
    val openRouterKey: String get() = InstrumentationRegistry.getArguments().getString("OPENROUTER_API_KEY").orEmpty()
    const val OPENROUTER = "https://openrouter.ai/api/v1"
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    fun newWorkDir(): File = File(ctx.getExternalFilesDir(null), "dubtest-${System.nanoTime()}").apply { mkdirs() }

    private fun copyAsset(asset: String, to: File) {
        inst.context.assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    /** Copies the demo clip (24 s, one English audio track) and its yt-dlp style sidecar subtitle. */
    fun installDemo(dir: File, name: String = "Demo clip", subtitle: Boolean = true): File {
        val video = File(dir, "$name.mp4")
        copyAsset("dubbing/demo.mp4", video)
        if (subtitle) copyAsset("dubbing/demo.en.srt", File(dir, "$name.en.srt"))
        return video
    }

    fun client() = OpenAiCompatClient(
        OPENROUTER, openRouterKey,
        extraHeaders = mapOf("HTTP-Referer" to "https://github.com/deniscerri/ytdlnis", "X-Title" to "YTDLnis e2e"),
        maxAttempts = 3, retryBaseDelayMs = 4_000,
    )

    private var cachedModel: String? = null

    /** A free OpenRouter model that really translates right now (free models come and go, so probe a few). */
    @Synchronized
    fun workingFreeModel(): String {
        cachedModel?.let { return it }
        require(openRouterKey.isNotBlank()) { "OPENROUTER_API_KEY instrumentation argument is missing" }
        val sample = listOf(Cue(1, 0, 3000, "Welcome back to the channel, everyone."))
        val errors = mutableListOf<String>()
        runBlocking {
            val models = client().listModels()
            val free = models.filter { it.promptPrice == 0.0 && it.completionPrice == 0.0 && it.id.endsWith(":free") }
                .filterNot { Regex("vision|image|audio|embed|guard|moderation", RegexOption.IGNORE_CASE).containsMatchIn(it.id) }
            val preferred = listOf("gemma", "llama", "qwen", "mistral", "deepseek", "gpt-oss", "glm")
            val candidates = free.sortedBy { m -> preferred.indexOfFirst { m.id.contains(it, true) }.let { if (it < 0) 99 else it } }.take(8)
            Log.i(TAG, "free candidates: ${candidates.map { it.id }}")
            for (m in candidates) {
                check(m.isFree && m.promptPrice == 0.0 && m.completionPrice == 0.0) { "refusing to use non-free model ${m.id}" }
                try {
                    val out = LlmTranslator(client(), m.id).translate(sample)
                    if (cjk.containsMatchIn(out.first().zh)) {
                        Log.i(TAG, "using free model ${m.id}: ${out.first().zh}")
                        cachedModel = m.id
                        return@runBlocking
                    }
                    errors += "${m.id}: no Chinese in '${out.first().zh}'"
                } catch (e: Exception) {
                    errors += "${m.id}: ${e.message?.take(120)}"
                }
            }
        }
        return cachedModel ?: throw AssertionError("No free model produced a translation:\n" + errors.joinToString("\n"))
    }

    fun configure(
        model: String,
        engine: String = DubbingPrefs.ENGINE_EDGE,
        fallback: Boolean = false,
        clear: Boolean = true,
        block: SharedPreferences.Editor.() -> Unit = {},
    ) {
        prefs.edit(commit = true) {
            if (clear) clear()
            putString(DubbingPrefs.LLM_BASE_URL, OPENROUTER)
            putString(DubbingPrefs.LLM_API_KEY, openRouterKey)
            putString(DubbingPrefs.LLM_MODEL, model)
            putString(DubbingPrefs.TTS_ENGINE, engine)
            putBoolean(DubbingPrefs.FALLBACK_SYSTEM_TTS, fallback)
            block()
        }
    }

    fun insertHistory(path: String, title: String = "Demo title", type: DownloadType = DownloadType.video, url: String? = null): HistoryItem {
        val dao = DBManager.getInstance(ctx).historyDao
        val u = url ?: "https://example.test/video/${System.nanoTime()}"
        runBlocking {
            dao.insert(
                HistoryItem(
                    id = 0, url = u, title = title, author = "tester", duration = "0:24", thumb = "", type = type,
                    time = System.currentTimeMillis() / 1000, downloadPath = listOf(path), website = "example.test",
                    format = Format(), filesize = File(path).length(), downloadId = 0,
                )
            )
        }
        return dao.getAllHistoryByURLAndType(u, type).single()
    }

    fun historyByUrl(url: String, type: DownloadType = DownloadType.video): List<HistoryItem> =
        DBManager.getInstance(ctx).historyDao.getAllHistoryByURLAndType(url, type)

    fun probe(file: File): MediaInfo = runBlocking { AndroidFfmpegRunner().probe(file) }

    /** Mono 24 kHz samples of audio stream [index]. */
    fun decode(file: File, index: Int = 0): ShortArray {
        val out = File(ctx.cacheDir, "e2e-${System.nanoTime()}.pcm")
        try {
            val r = runBlocking {
                AndroidFfmpegRunner().run(listOf("-i", file.absolutePath, "-map", "0:a:$index", "-f", "s16le", "-ac", "1", "-ar", "24000", out.absolutePath))
            }
            check(r.ok) { "ffmpeg decode failed: ${r.log.takeLast(300)}" }
            val b = out.readBytes()
            return ShortArray(b.size / 2) { ((b[it * 2 + 1].toInt() shl 8) or (b[it * 2].toInt() and 0xFF)).toShort() }
        } finally {
            out.delete()
        }
    }

    fun rms(samples: ShortArray, fromMs: Long, toMs: Long): Double {
        val a = (fromMs * 24).toInt().coerceIn(0, samples.size)
        val b = (toMs * 24).toInt().coerceIn(a, samples.size)
        if (b == a) return 0.0
        var sum = 0.0
        for (i in a until b) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (b - a))
    }
}
