package com.deniscerri.ytdl.util.dubbing

import android.content.Context
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.dubbing.AzureTts
import com.deniscerri.ytdl.dubbing.DubbingConfig
import com.deniscerri.ytdl.dubbing.DubbingException
import com.deniscerri.ytdl.dubbing.DubbingPipeline
import com.deniscerri.ytdl.dubbing.EdgeTts
import com.deniscerri.ytdl.dubbing.FallbackTts
import com.deniscerri.ytdl.dubbing.LlmTranslator
import com.deniscerri.ytdl.dubbing.MuxOptions
import com.deniscerri.ytdl.dubbing.OpenAiCompatAsr
import com.deniscerri.ytdl.dubbing.OpenAiCompatClient
import com.deniscerri.ytdl.dubbing.OpenAiCompatTts
import com.deniscerri.ytdl.dubbing.Presets
import com.deniscerri.ytdl.dubbing.Progress
import com.deniscerri.ytdl.dubbing.TranslatorConfig
import com.deniscerri.ytdl.dubbing.TtsProvider

/** Builds the dubbing pipeline from the user's settings. */
class DubbingFactory(private val context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private fun str(key: String, default: String = ""): String = prefs.getString(key, default)?.trim().orEmpty()

    fun llmConfigured(): Boolean = str(DubbingPrefs.LLM_BASE_URL).isNotEmpty() && str(DubbingPrefs.LLM_MODEL).isNotEmpty()

    /** OpenRouter asks API clients to identify themselves with these headers. */
    private fun headersFor(baseUrl: String): Map<String, String> =
        if (baseUrl.contains("openrouter.ai")) mapOf("HTTP-Referer" to "https://github.com/deniscerri/ytdlnis", "X-Title" to "YTDLnis")
        else emptyMap()

    fun llmClient(log: (String) -> Unit = {}): OpenAiCompatClient {
        val base = str(DubbingPrefs.LLM_BASE_URL)
        return OpenAiCompatClient(
            base, str(DubbingPrefs.LLM_API_KEY), extraHeaders = headersFor(base), log = log,
            extraBodyJson = str(DubbingPrefs.LLM_EXTRA_BODY),
        )
    }

    /** The configured speech-recognition service, or null when it is switched off. */
    fun asrProvider(): OpenAiCompatAsr? {
        if (!prefs.getBoolean(DubbingPrefs.ASR_ENABLED, false)) return null
        // an own key without an address almost always means Groq (the free Whisper); never send it to the translation service
        val ownKey = str(DubbingPrefs.ASR_API_KEY)
        val base = str(DubbingPrefs.ASR_BASE_URL).ifEmpty {
            if (ownKey.isNotEmpty()) "https://api.groq.com/openai/v1" else str(DubbingPrefs.LLM_BASE_URL)
        }
        val key = ownKey.ifEmpty { str(DubbingPrefs.LLM_API_KEY) }
        val model = str(DubbingPrefs.ASR_MODEL, "whisper-large-v3-turbo").ifEmpty { "whisper-large-v3-turbo" }
        if (base.isEmpty()) return null
        return OpenAiCompatAsr(OpenAiCompatClient(base, key, extraHeaders = headersFor(base)), model)
    }

    fun voice(): String =
        str(DubbingPrefs.VOICE_CUSTOM).ifEmpty { str(DubbingPrefs.VOICE, "zh-CN-XiaoxiaoNeural") }.ifEmpty { "zh-CN-XiaoxiaoNeural" }

    /** The TTS engine chosen in settings, wrapped with the system voice as a safety net when enabled. */
    fun createTts(log: (String) -> Unit = {}): Pair<TtsProvider, SystemTtsProvider?> {
        val system = SystemTtsProvider(context)
        val primary: TtsProvider = when (str(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE)) {
            DubbingPrefs.ENGINE_SYSTEM -> return system to system
            DubbingPrefs.ENGINE_AZURE -> {
                val region = str(DubbingPrefs.AZURE_REGION)
                val key = str(DubbingPrefs.AZURE_KEY)
                if (region.isEmpty() || key.isEmpty()) throw DubbingException("Azure region / key are not configured")
                AzureTts(region, key)
            }
            DubbingPrefs.ENGINE_OPENAI -> {
                val base = str(DubbingPrefs.TTS_BASE_URL).ifEmpty { str(DubbingPrefs.LLM_BASE_URL) }
                val key = str(DubbingPrefs.TTS_API_KEY).ifEmpty { str(DubbingPrefs.LLM_API_KEY) }
                val model = str(DubbingPrefs.TTS_MODEL)
                if (base.isEmpty() || model.isEmpty()) throw DubbingException("TTS service URL / model are not configured")
                OpenAiCompatTts(OpenAiCompatClient(base, key, extraHeaders = headersFor(base)), model)
            }
            else -> EdgeTts()
        }
        return if (prefs.getBoolean(DubbingPrefs.FALLBACK_SYSTEM_TTS, true)) {
            FallbackTts(primary, system, "", log = log) to system
        } else {
            primary to system
        }
    }

    /**
     * Voices for up to four speakers: the chosen female / male voice first, then further Mandarin voices of the same
     * sex. Extra voices only exist for Edge / Azure (they share the voice names); other engines reuse the two chosen.
     */
    private fun extraVoicesAvailable(): Boolean =
        str(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE).let { it == DubbingPrefs.ENGINE_EDGE || it == DubbingPrefs.ENGINE_AZURE }

    private fun speakerVoices(female: String, male: String): Map<String, String> {
        val map = linkedMapOf("F1" to female, "M1" to male)
        val engine = str(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE)
        if (engine == DubbingPrefs.ENGINE_EDGE || engine == DubbingPrefs.ENGINE_AZURE) {
            val used = mutableSetOf(female, male)
            Presets.maleVoiceIds.filter { it !in used }.take(3).forEachIndexed { i, v -> map["M${i + 2}"] = v; used += v }
            Presets.femaleVoiceIds.filter { it !in used }.take(3).forEachIndexed { i, v -> map["F${i + 2}"] = v; used += v }
        }
        return map
    }

    class Setup(val pipeline: DubbingPipeline, private val system: SystemTtsProvider?) {
        fun close() { system?.shutdown() }
    }

    /** Stands in for the TTS engine in subtitle-only runs, which never synthesize anything. */
    private object NoTts : TtsProvider {
        override val fileExtension = "mp3"
        override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: java.io.File) =
            throw DubbingException("Speech is not used in subtitle-only mode")
    }

    /**
     * @param externalSubtitle where to also write the Chinese .srt while dubbing (null = do not)
     * @param needTts false for subtitle-only runs, so a missing TTS setup does not block translating subtitles
     */
    fun create(
        log: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        externalSubtitle: java.io.File? = null,
        needTts: Boolean = true,
        onPreview: (String) -> Unit = {},
        roleVoices: Map<String, String> = emptyMap(),
    ): Setup {
        if (!llmConfigured()) throw DubbingException("Translation service is not configured")
        val (tts, system) = if (needTts) createTts(log) else (NoTts as TtsProvider) to null
        val config = DubbingConfig(
            voice = voice(),
            baseRatePercent = prefs.getInt(DubbingPrefs.SPEECH_RATE, 0),
            // the free Edge endpoint drops connections when too many are open at once
            ttsConcurrency = if (str(DubbingPrefs.TTS_ENGINE, DubbingPrefs.ENGINE_EDGE) == DubbingPrefs.ENGINE_EDGE) 2 else 3,
            embedChineseSubtitle = prefs.getBoolean(DubbingPrefs.EMBED_SUBTITLE, false),
            multiVoice = prefs.getBoolean(DubbingPrefs.MULTI_VOICE, false),
            maleVoice = str(DubbingPrefs.VOICE_MALE, "zh-CN-YunxiNeural").ifEmpty { "zh-CN-YunxiNeural" },
            maxSpeakers = (str(DubbingPrefs.SPEAKER_COUNT, "2").toIntOrNull() ?: 2).coerceIn(2, 4),
            llmRoles = prefs.getBoolean(DubbingPrefs.ROLES_LLM, false),
            roleVoices = roleVoices,
            extraFemaleVoices = if (extraVoicesAvailable()) Presets.femaleVoiceIds else emptyList(),
            extraMaleVoices = if (extraVoicesAvailable()) Presets.maleVoiceIds else emptyList(),
            speakerVoices = speakerVoices(voice(), str(DubbingPrefs.VOICE_MALE, "zh-CN-YunxiNeural").ifEmpty { "zh-CN-YunxiNeural" }),
            externalSubtitle = externalSubtitle,
            externalSubtitleBilingual = prefs.getBoolean(DubbingPrefs.SUBTITLE_BILINGUAL, false),
            mux = MuxOptions(
                keepOriginal = prefs.getBoolean(DubbingPrefs.KEEP_ORIGINAL, true),
                originalVolume = prefs.getInt(DubbingPrefs.ORIGINAL_VOLUME, 0) / 100.0,
            ),
        )
        val translator = LlmTranslator(
            client = llmClient(log),
            model = str(DubbingPrefs.LLM_MODEL),
            config = TranslatorConfig(
                glossary = str(DubbingPrefs.GLOSSARY),
                keepEnglishTerms = prefs.getBoolean(DubbingPrefs.KEEP_TERMS, true),
                extraSystemPrompt = str(DubbingPrefs.EXTRA_PROMPT),
                concurrency = (str(DubbingPrefs.LLM_CONCURRENCY, "2").toIntOrNull() ?: 2).coerceIn(1, 3),
            ),
            log = log,
        )
        val pipeline = DubbingPipeline(
            ffmpeg = AndroidFfmpegRunner(),
            translator = translator,
            tts = tts,
            config = config,
            asr = asrProvider(),
            log = log,
            onProgress = onProgress,
            onPreview = onPreview,
            labeler = if (prefs.getBoolean(DubbingPrefs.ROLES_LLM, false)) {
                com.deniscerri.ytdl.dubbing.SpeakerLabeler(llmClient(log), str(DubbingPrefs.LLM_MODEL), log = log)
            } else null,
        )
        return Setup(pipeline, system)
    }
}
