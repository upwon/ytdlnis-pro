package com.deniscerri.ytdl.dubbing

/** Starting points for the settings screen; every field stays editable by the user. */
data class LlmPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String = "",
    /** Requests sent at the same time; free tiers often allow only one. */
    val concurrency: Int = 2,
)

object Presets {
    val llm = listOf(
        LlmPreset("OpenRouter", "https://openrouter.ai/api/v1"),
        LlmPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        LlmPreset("阿里云百炼 (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        LlmPreset("智谱 GLM（Flash 系列免费，1 并发）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", concurrency = 1),
        LlmPreset("Kimi (Moonshot)", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        LlmPreset("硅基流动 SiliconFlow（小模型免费）", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        LlmPreset("火山方舟 (豆包)", "https://ark.cn-beijing.volces.com/api/v3"),
        LlmPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        LlmPreset("Groq（免费，也提供 Whisper 语音识别）", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
        LlmPreset("OpenCode Zen", "https://opencode.ai/zen/v1"),
        LlmPreset("Gemini (OpenAI 兼容)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        LlmPreset("Ollama (本机/局域网)", "http://127.0.0.1:11434/v1"),
        LlmPreset("自定义", ""),
    )

    /** Extra voices handed out (in this order) to further speakers of the same sex. */
    val maleVoiceIds = listOf("zh-CN-YunxiNeural", "zh-CN-YunyangNeural", "zh-CN-YunjianNeural")
    val femaleVoiceIds = listOf("zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural", "zh-CN-XiaochenNeural", "zh-CN-XiaohanNeural")

    /** Common Mandarin neural voices (identical names for Edge and Azure). */
    val chineseVoices = listOf(
        "zh-CN-XiaoxiaoNeural" to "晓晓 (女，通用)",
        "zh-CN-YunxiNeural" to "云希 (男，叙述)",
        "zh-CN-YunyangNeural" to "云扬 (男，新闻)",
        "zh-CN-XiaoyiNeural" to "晓伊 (女，活泼)",
        "zh-CN-YunjianNeural" to "云健 (男，解说)",
        "zh-CN-XiaochenNeural" to "晓辰 (女)",
        "zh-CN-XiaohanNeural" to "晓涵 (女)",
    )
}
