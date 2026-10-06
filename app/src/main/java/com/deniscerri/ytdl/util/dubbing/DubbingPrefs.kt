package com.deniscerri.ytdl.util.dubbing

/** SharedPreferences keys of the AI dubbing feature. Everything ending in "_key" is a secret. */
object DubbingPrefs {
    const val LLM_PRESET = "dubbing_llm_preset"
    const val LLM_BASE_URL = "dubbing_llm_base_url"
    const val LLM_API_KEY = "dubbing_llm_api_key"
    const val LLM_MODEL = "dubbing_llm_model"
    const val LLM_EXTRA_BODY = "dubbing_llm_extra_body"
    const val LLM_CONCURRENCY = "dubbing_llm_concurrency"

    const val ASR_ENABLED = "dubbing_asr_enabled"
    const val ASR_BASE_URL = "dubbing_asr_base_url"
    const val ASR_API_KEY = "dubbing_asr_api_key"
    const val ASR_MODEL = "dubbing_asr_model"

    const val TTS_ENGINE = "dubbing_tts_engine"
    const val VOICE = "dubbing_voice"
    const val VOICE_CUSTOM = "dubbing_voice_custom"
    const val MULTI_VOICE = "dubbing_multi_voice"
    const val VOICE_MALE = "dubbing_voice_male"
    const val ROLES_LLM = "dubbing_roles_llm"
    const val ROLES_CONFIRM = "dubbing_roles_confirm"
    const val SPEAKER_COUNT = "dubbing_speaker_count"
    const val SPEECH_RATE = "dubbing_speech_rate"
    const val AZURE_REGION = "dubbing_azure_region"
    const val AZURE_KEY = "dubbing_azure_key"
    const val TTS_BASE_URL = "dubbing_tts_base_url"
    const val TTS_API_KEY = "dubbing_tts_api_key"
    const val TTS_MODEL = "dubbing_tts_model"
    const val FALLBACK_SYSTEM_TTS = "dubbing_fallback_system_tts"

    const val KEEP_ORIGINAL = "dubbing_keep_original"
    const val ORIGINAL_VOLUME = "dubbing_original_volume"
    const val EMBED_SUBTITLE = "dubbing_embed_subtitle"
    const val EXTERNAL_SUBTITLE = "dubbing_external_subtitle"
    const val SUBTITLE_BILINGUAL = "dubbing_subtitle_bilingual"
    const val REPLACE_ORIGINAL = "dubbing_replace_original"
    const val AUTO = "dubbing_auto"
    const val GLOSSARY = "dubbing_glossary"
    const val KEEP_TERMS = "dubbing_keep_terms"
    const val EXTRA_PROMPT = "dubbing_extra_prompt"

    const val ENGINE_EDGE = "edge"
    const val ENGINE_AZURE = "azure"
    const val ENGINE_OPENAI = "openai"
    const val ENGINE_SYSTEM = "system"
    const val ENGINE_MULTITTS = "multitts"
    const val MULTITTS_VOICES = "dubbing_multitts_voices"

    /** Secrets must never end up in settings backups. */
    fun isSecret(key: String) = key.startsWith("dubbing_") && key.endsWith("_key")
}
