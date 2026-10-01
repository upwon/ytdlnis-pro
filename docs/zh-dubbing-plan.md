# 英文视频 → 中文配音（AI Dubbing）功能方案

> 状态：调研 / 方案稿（未实现）
> 日期：2026-10
> 目标：下载的视频只有英文音轨时，在 App 内自动生成中文配音音轨，并合并回视频（保留原音轨，可切换）。

---

## 1. 整体流程

```
 ┌──────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 │ 1.获取原文 │ → │ 2.翻译成中文   │ → │ 3.TTS 合成    │ → │ 4.时间轴对齐   │ → │ 5.FFmpeg 混流  │
 │ 字幕 / ASR │   │ LLM / 免费翻译 │   │ 逐句生成音频   │   │ 变速/补静音    │   │ 加中文音轨     │
 └──────────┘   └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
```

每一步都设计成**可替换的 Provider**，这样免费方案和付费/自带 API 方案可以自由组合。

### 关键思路：尽量跳过最贵的步骤

| 情况 | 走哪条路 | 成本 |
|---|---|---|
| YouTube 本身已有中文 AI 配音音轨（YouTube auto-dubbing） | 直接选中文音轨下载，**不需要本功能** | 0 |
| 视频有英文**人工字幕** | 字幕 → 翻译 → TTS | 低 |
| 只有英文**自动字幕** | 自动字幕（需要句子合并清洗）→ 翻译 → TTS | 低 |
| YouTube 可直接给出 `zh-Hans` **自动翻译字幕** | 跳过翻译，直接 TTS（质量一般） | 最低 |
| 完全没有字幕（非 YouTube 站点等） | ASR 语音识别 → 翻译 → TTS | 中 |

所以第一步优先级：**已有中文音轨 > 人工英文字幕 > 自动英文字幕 > ASR**。

---

## 2. 现有代码里可以直接复用的东西

调研了当前仓库，以下基础设施已经具备，大幅降低实现成本：

| 能力 | 位置 | 用途 |
|---|---|---|
| 内置 FFmpeg 7.0.1 | `core/packages/FFmpeg.kt`，`RuntimeManager.ffmpegLocation` | 抽音频、变速（`atempo`）、拼接、混音、mux 多音轨 |
| 内置 Python 3.14 + yt-dlp | `RuntimeManager.executePython()` / `execute()` | 可以跑 Python 脚本 |
| yt-dlp 后处理插件机制 | `assets/yt_dlp_plugins/burnsubs/...burnsubs.py`，`--use-postprocessor BurnSubs:when=after_move` | 参考它的写法，可做一个 `ZhDub` 插件 |
| 字幕下载参数 | `YTDLPUtil.kt:1328-1350`（`--write-subs` / `--write-auto-subs` / `--sub-langs`） | 获取英文字幕作为翻译源 |
| OkHttp 5 | `build.gradle` | 调翻译 API、TTS WebSocket、ASR API |
| WorkManager + 通知 | `work/DownloadWorker.kt`、`NotificationUtil` | 后台任务、进度通知 |
| Room 中 `VideoPreferences` 以 JSON 存储 | `database/Converters.kt:56` | 新增配音选项**不需要数据库迁移** |
| 命令执行 | `RuntimeManager.executeImpl(fullCommand, ...)` | 直接执行 ffmpeg 命令 |

---

## 3. 各环节方案调研

### 3.1 获取原文（字幕 / ASR）

#### A. 直接用字幕（推荐优先）
- yt-dlp：`--write-subs --write-auto-subs --sub-langs "en.*" --sub-format vtt/srt --convert-subtitles srt`
- 人工字幕质量好，可直接用。
- **自动字幕**（YouTube ASR）的问题：VTT 中是逐词滚动、有大量重复行，没有标点。需要做：
  1. 去重滚动行；
  2. 按停顿/时长把碎片合并成完整句子（否则翻译和 TTS 都会很碎）；
  3. 可选：交给 LLM 统一"断句 + 翻译"（见 3.2，一次请求完成）。

#### B. ASR 语音识别（无字幕时）

| 方案 | 费用 | 优点 | 缺点 |
|---|---|---|---|
| **Groq Whisper API**（`whisper-large-v3-turbo`） | 免费额度：约 28,800 音频秒/天（≈8 小时），单文件 25MB | 极快、准确、OpenAI 兼容接口、带时间戳（`verbose_json`） | 需要 Key；需翻墙；超长音频要切片 |
| **硅基流动 SiliconFlow SenseVoiceSmall** | 免费模型 | 国内直连、OpenAI 兼容 `/audio/transcriptions` | 默认不一定返回逐句时间戳，需配合 VAD 切片 |
| OpenAI Whisper / `gpt-4o-transcribe` | 付费（便宜） | 质量好 | 收费、需翻墙 |
| 用户自带任意 OpenAI 兼容 ASR 接口 | 视提供商 | 灵活 | — |
| **本地离线 sherpa-onnx**（Whisper / SenseVoice / Paraformer + Silero VAD） | 免费、离线 | 无网络依赖、隐私好；官方提供 Android AAR | 模型 100MB+，需按需下载；低端机慢；APK/维护成本高 |

建议：**MVP 只做"OpenAI 兼容 ASR 接口"一种实现**（同时覆盖 Groq / 硅基流动 / OpenAI / 自建 whisper 服务），离线 sherpa-onnx 作为后续可选插件包（可仿照现有 `PackageBase` 的"可下载包"机制按需下载模型）。

切片策略：用 FFmpeg 抽成 16kHz 单声道 mp3/opus（体积小）→ 按 `silencedetect` 或固定 10 分钟分段，保证每段 < 25MB → 拼回时间戳。

### 3.2 翻译

| 方案 | 费用 | 说明 |
|---|---|---|
| **用户自配 LLM（OpenAI 兼容 Chat Completions）** | 视提供商，DeepSeek / 通义千问 / 智谱 GLM-4-Flash（免费）/ 硅基流动免费模型 / Gemini 免费额度 等 | **推荐主方案**。只需配置 `base_url` + `api_key` + `model`，一套代码兼容几乎所有厂商 |
| Microsoft Translator（Azure） | F0 免费 200 万字符/月 | 稳定，但没有上下文、口语化差 |
| Google 翻译网页接口（非官方） | 免费 | 非官方、随时可能失效，不推荐作为主方案 |
| 本地离线翻译模型 | 免费 | 移动端效果/体积不理想，暂不考虑 |

**LLM 翻译的关键设计（决定配音好不好听）：**

1. **分批 + 带上下文**：每批 30~60 条字幕，附带前一批的最后几句作为上下文，保持术语一致。
2. **结构化输出**：要求返回 JSON 数组 `[{ "id": 12, "zh": "..." }]`，按 id 回填，防止条数错位；解析失败则该批重试/拆小重试。
3. **控制长度（配音特有）**：中文朗读速度约 4~5 字/秒。在 prompt 中给出每句的时长，要求"译文字数 ≤ 时长(秒) × 4.5"，优先意译、口语化，避免后面 TTS 被迫大幅加速。
4. **可选：断句与翻译一起做**——对自动字幕，把碎片文本+时间戳交给 LLM，让它输出"合并后的句子区间 + 译文"。
5. 用户可自定义 System Prompt / 术语表。

示例 Prompt（精简版）：

```
你是专业的视频配音翻译。把下面的英文字幕翻译成自然口语化的简体中文，用于配音朗读。
要求：
- 每条译文的汉字数不要超过 max_chars（按时长估算），必要时意译、精简；
- 保持与上下文一致的术语与人名；
- 只输出 JSON 数组：[{"id":<id>,"zh":"<译文>"}]，不要输出其它内容。
上下文（仅供参考，不要翻译）：...
待翻译：[{"id":1,"en":"...","max_chars":14}, ...]
```

### 3.3 TTS 语音合成（重点：免费方案）

| 方案 | 费用 | 中文音质 | 接入方式 | 风险/备注 |
|---|---|---|---|---|
| **Edge 在线朗读（edge-tts 协议）** | 免费、无需 Key | ★★★★★ 与 Azure 神经语音同源（晓晓、云希、云扬、晓伊…） | WebSocket：`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`，SSML 支持 `rate`/`pitch`/`volume` | **非官方接口**（反向工程 Edge 浏览器"大声朗读"），微软可能随时改动鉴权（已经加过 `Sec-MS-GEC` 签名），需要能跟进更新；有频率限制；商用授权不明确 |
| **Azure Speech 官方（F0 免费层）** | 每月 50 万字符免费神经 TTS，之后约 $16/百万字符 | ★★★★★ 同上，且有更多风格（`mstts:express-as`） | REST：`https://{region}.tts.speech.microsoft.com/cognitiveservices/v1` + `Ocp-Apim-Subscription-Key` | 需要用户自己注册 Azure 拿 Key；F0 仅限评估用途；稳定 |
| **Android 系统 TTS**（`TextToSpeech.synthesizeToFile`） | 免费、离线 | ★★~★★★★ 取决于引擎（Google 语音服务 / 厂商引擎 / 用户装的 MultiTTS 等） | 系统 API，零依赖 | 声音机械一些；不同手机差异大；但**作为兜底方案非常可靠** |
| **OpenAI 兼容 TTS 接口**（`/v1/audio/speech`） | 视提供商：硅基流动 CosyVoice2 / Fish Speech、OpenAI `gpt-4o-mini-tts`、自建 | ★★★★~★★★★★ | 与 ASR/翻译同一套配置 | 多数收费但便宜；CosyVoice 支持**用原视频说话人声音克隆**（进阶） |
| 火山引擎（豆包）/ 阿里云 / 腾讯云 TTS | 有免费试用额度 | ★★★★★ | 各家私有 API | 接口不统一，后续按需加 |
| 本地 sherpa-onnx（VITS / Matcha / Kokoro 中文模型） | 免费、离线 | ★★★ | Android AAR | 模型体积大、需按需下载 |

**推荐：**
- **默认：Edge TTS（免费、音质最好）**，在 Kotlin 中用 OkHttp WebSocket 自己实现协议（约 200 行，参考开源项目 `rany2/edge-tts` 的 Python 实现以及 Android 上的 `jing332/tts-server-android`），不依赖 Python 包。
  - 之所以不直接在内置 Python 里 `pip install edge-tts`：它依赖 `aiohttp`（及 `multidict`/`yarl`/`frozenlist` 等 C 扩展），在 App 内置的 Android Python 上编译安装不可靠。
- **备选 1：Azure 官方 Key**（同样的声音，稳定合规，用户自填 Key 和区域）。
- **备选 2：系统 TTS**（离线兜底，Edge 接口失效时自动降级）。
- **备选 3：OpenAI 兼容 TTS**（用户自己的大模型平台，比如硅基流动 CosyVoice）。

常用中文音色（Edge/Azure 共用）：`zh-CN-XiaoxiaoNeural`（女，通用）、`zh-CN-YunxiNeural`（男，叙述）、`zh-CN-YunyangNeural`（男，新闻）、`zh-CN-XiaoyiNeural`（女，活泼）、`zh-CN-YunjianNeural`（男，解说/体育）。

### 3.4 时间轴对齐（配音质量的核心难点）

每条字幕有 `[start, end]`，TTS 生成的音频时长 `d` 不一定等于 `end - start`。处理策略：

1. **先在 TTS 端调速**：根据"译文字数 / 时长"预估，给 SSML `<prosody rate="+15%">`，一次合成大概率就能放下。
2. **合成后仍超长**：
   - 允许"借用"到下一句开始前的空隙（`next.start - cur.end`）；
   - 还放不下就用 FFmpeg `atempo` 加速（上限建议 1.5x，超过会难听）；
   - 仍放不下：记录日志，允许轻微重叠或截断（可配置）。
3. **太短**：保持原速，后面补静音，不要拉慢。
4. **拼接**：不要用大量 `adelay` 滤镜（几百句时 filter graph 很慢）。推荐在 Kotlin 里直接拼 PCM：
   - 所有 TTS 片段统一解码为 `24kHz/16bit/mono` 的 PCM（FFmpeg 一次一条或批量）；
   - 在内存/临时文件中按 `start * sampleRate` 偏移写入一条完整长度的 PCM 轨道（空白处为 0，即静音）；
   - 最后一次性 FFmpeg 编码成 AAC/Opus。
   - 这样复杂度 O(总时长)，几百句也很快，而且时间轴完全精确。

### 3.5 背景音保留（可选进阶）

纯配音会丢掉原视频的背景音乐/音效。几种做法：

| 做法 | 效果 | 成本 |
|---|---|---|
| A. 只加一条纯中文配音轨（原英文轨保留可切换） | 无背景音 | 最低，**MVP** |
| B. 中文配音 + 原音轨压低音量（如 15%）混合；配音说话时用 `sidechaincompress` 自动"闪避"原声 | 有背景音，但能隐约听到英文 | 低，FFmpeg 一条命令 |
| C. 人声分离（Demucs / UVR / sherpa-onnx 的 source separation）后：伴奏 + 中文配音 | 最佳 | 高：模型大、手机上很慢，可放到远端/自建服务 |

建议 MVP 做 A + B（用户可选"原声保留音量 0~50%"），C 后续再考虑。

B 的 FFmpeg 示例：

```bash
ffmpeg -i video.mp4 -i zh_dub.m4a -filter_complex \
 "[0:a]volume=0.25[bg];[bg][1:a]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=300[duck];[duck][1:a]amix=inputs=2:duration=first:normalize=0[mix]" \
 -map 0:v -map "[mix]" -map 0:a \
 -c:v copy -c:a:0 aac -b:a:0 160k -c:a:1 copy \
 -metadata:s:a:0 language=chi -metadata:s:a:0 title="中文配音" \
 -metadata:s:a:1 language=eng -metadata:s:a:1 title="Original" \
 -disposition:a:0 default -disposition:a:1 0 \
 out.mp4
```

### 3.6 合并到视频（FFmpeg mux）

- **不重新编码视频**（`-c:v copy`），只编码新音轨，速度很快。
- 默认：中文配音设为第 1 条且 `default`，英文原声保留为第 2 条，播放器里可以切换。
- 容器兼容：
  - MP4：音频用 AAC；
  - MKV：AAC/Opus 都行；
  - WebM：只能 Opus/Vorbis → 用 Opus，或改输出为 MKV。
- 可选：同时把中文字幕作为软字幕轨封装进去（`-c:s mov_text` / `srt`），或复用现有的 BurnSubs 插件烧录。
- 可选：只导出 `xxx.zh.m4a` 单独文件 + `xxx.zh.srt`，不改原视频。

---

## 4. 在 App 中的落地方案

### 4.1 架构选择

| 方案 | 说明 | 结论 |
|---|---|---|
| ① yt-dlp Python 后处理插件（仿 `burnsubs.py`） | 下载完成时在 yt-dlp 进程里跑 | 好处是与下载天然串起来；但 Edge TTS/HTTP 依赖在内置 Python 上难装，API Key 传递、进度回显、失败重试都不方便 |
| **② Kotlin 独立 `DubbingWorker`（WorkManager）** | 下载成功后入队，或在"历史记录"里对已下载视频手动发起 | **推荐**。HTTP 用 OkHttp，进度走通知，失败可单步重试，和下载解耦 |

### 4.2 模块划分（建议新建包 `com.deniscerri.ytdl.dubbing`）

```
dubbing/
├── DubbingWorker.kt              // WorkManager 入口，串起整个流水线，更新通知进度
├── DubbingConfig.kt              // 从 SharedPreferences 读取配置
├── model/
│   ├── Cue.kt                    // data class Cue(id, startMs, endMs, src, zh, ttsFile, ttsDurMs)
│   └── DubbingJob.kt             // 任务状态（可选：Room 表，支持断点续跑）
├── source/
│   ├── SubtitleParser.kt         // SRT/VTT 解析 + 自动字幕去重/合句
│   └── AsrProvider.kt            // interface；OpenAiCompatibleAsr（Groq/硅基流动/OpenAI）
├── translate/
│   ├── Translator.kt             // interface
│   ├── LlmTranslator.kt          // OpenAI 兼容 Chat Completions，分批+JSON 输出+重试
│   └── AzureTranslator.kt        // （可选）
├── tts/
│   ├── TtsProvider.kt            // interface: suspend fun synth(text, voice, rate): File
│   ├── EdgeTtsProvider.kt        // OkHttp WebSocket，实现 Sec-MS-GEC 签名
│   ├── AzureTtsProvider.kt       // 官方 REST
│   ├── SystemTtsProvider.kt      // android.speech.tts.TextToSpeech.synthesizeToFile
│   └── OpenAiTtsProvider.kt      // /v1/audio/speech
├── align/
│   └── TimelineAligner.kt        // 调速/补静音/PCM 拼接
└── mux/
    └── DubMuxer.kt               // 调 RuntimeManager.executeImpl 执行 ffmpeg
```

### 4.3 与下载流程的衔接

1. `VideoPreferences` 新增字段（JSON 存储，无需迁移）：
   ```kotlin
   var aiDubbing: Boolean = false,
   var aiDubbingVoice: String = "",       // 空 = 用全局默认
   var aiDubbingKeepOriginal: Boolean = true,
   ```
2. `YTDLPUtil` 中当 `aiDubbing == true` 时，自动追加 `--write-subs --write-auto-subs --sub-langs "en.*,en"`（若用户本身没开字幕，则下载完把字幕文件留在缓存目录，不移动到用户目录）。
3. `DownloadWorker` 在写入 `HistoryItem` 之后（`DownloadWorker.kt` 约 450 行处），如果 `aiDubbing` 开启，`enqueue` 一个 `DubbingWorker`（输入：historyId / 文件路径 / 字幕路径）。
   - 注意：`finalPaths` 可能是 SAF 的 `content://` 路径（用户选择了外部文件夹），处理时需要先复制到 `cacheDir` 再处理、完成后写回（参考 `MoveCacheFilesWorker`）。
4. 历史记录长按菜单 / 详情页增加 **「生成中文配音」** 操作，对已下载的视频补做。
5. 判断"是否需要配音"：下载前看格式列表里是否已有 `language` 为 `zh*` 的音轨；有的话提示用户"该视频已有中文音轨"，直接选它即可。

### 4.4 设置页（新建 `dubbing_preferences.xml`，挂在「处理」设置下）

```
AI 中文配音
├── 默认开启（开关）
├── 目标语言：简体中文（预留多语言）
├── 原文来源：优先字幕 → ASR（自动） / 仅字幕 / 仅 ASR
├── ASR 服务：Base URL / API Key / 模型（预置模板：Groq、硅基流动、OpenAI）
├── 翻译服务：
│   ├── 类型：LLM（OpenAI 兼容） / Azure Translator / 不翻译（直接用 zh 自动翻译字幕）
│   ├── Base URL / API Key / 模型（预置模板：DeepSeek、通义千问、智谱、硅基流动、OpenAI、Gemini）
│   ├── 每批条数、并发数
│   └── 自定义提示词 / 术语表
├── 语音合成：
│   ├── 引擎：Edge（免费）/ Azure（Key）/ 系统 TTS / OpenAI 兼容
│   ├── 音色（列表 + 试听按钮）、基础语速、音量
│   └── 失败时自动降级到系统 TTS（开关）
├── 混音：保留原声音量 0~50%、自动闪避（开关）
├── 输出：替换原文件（中文为默认轨 + 保留英文轨）/ 另存新文件 / 仅导出音轨+字幕
└── 同时内嵌中文字幕（开关）
```

API Key 使用 `EncryptedSharedPreferences`（`androidx.security:security-crypto`）或 Android Keystore 加密保存；导出/备份设置时默认**不导出** Key。

### 4.5 执行细节与健壮性

- **缓存与断点续跑**：工作目录 `cacheDir/dubbing/<historyId>/`，保存 `cues.json`（含每句翻译结果和 TTS 文件路径）。失败重试时跳过已完成的句子，避免重复消耗 API 额度。
- **并发**：翻译按批并发 2~3；Edge TTS 并发 3~4（过高会被限流），带指数退避重试。
- **进度通知**：分阶段显示「识别中 / 翻译 12/40 批 / 合成 230/512 句 / 合并中」。
- **取消**：Worker 取消时终止 ffmpeg 进程（复用 `RuntimeManager.idProcessMap`）。
- **日志**：写入现有 `LogRepository`，便于用户排查 API 错误。
- **费用预估**：开始前显示"约 N 句 / M 字符 / 预计消耗"，避免误用付费 API。
- **长视频**：1 小时视频约 600~900 句、1.5~2 万汉字；Edge TTS 约需数分钟；需保证 Worker 为前台服务（`setForeground`）。

---

## 5. 推荐组合

| 组合 | 原文 | 翻译 | TTS | 成本 | 适用 |
|---|---|---|---|---|---|
| **零成本（推荐默认）** | YouTube 字幕 | 智谱 GLM-4-Flash / 硅基流动免费模型等免费 LLM | Edge TTS | 0 | 大多数 YouTube 视频 |
| 极简零配置 | YouTube `zh-Hans` 自动翻译字幕 | 不需要 | Edge TTS | 0，无需任何 Key | 想要开箱即用，接受机翻质量 |
| 稳定合规 | 字幕 / Groq Whisper | 用户自己的 LLM（DeepSeek 等，费用极低） | Azure 官方 F0（50 万字符/月） | 基本免费 | 不想依赖非官方接口 |
| 离线 | sherpa-onnx | 用户 LLM（需联网）或不翻译 | 系统 TTS | 0 | 隐私优先 / 无法访问外网 |
| 高质量 | 字幕 / Whisper | 强 LLM | CosyVoice（克隆原说话人音色）+ 人声分离 | 付费 | 追求效果 |

---

## 6. 分阶段实施计划

| 阶段 | 内容 | 预估工作量 |
|---|---|---|
| **P0 MVP** | 字幕解析（SRT/VTT，含自动字幕清洗）；LLM 翻译（OpenAI 兼容）；Edge TTS；时间轴对齐 + PCM 拼接；FFmpeg 加中文音轨（保留原轨）；历史记录手动触发；基础设置页 | 1.5~2 周 |
| P1 | 下载时勾选自动配音；系统 TTS / Azure TTS 备选与自动降级；原声闪避混音；内嵌中文字幕；断点续跑；音色试听 | 1 周 |
| P2 | ASR（OpenAI 兼容：Groq / 硅基流动）+ 切片；费用预估；术语表；OpenAI 兼容 TTS | 1 周 |
| P3（可选） | sherpa-onnx 离线 ASR/TTS 可下载包；人声分离；声音克隆；多说话人识别分配不同音色 | 视需求 |

---

## 7. 风险与注意事项

1. **Edge TTS 是非官方接口**：微软曾多次调整鉴权（如 2024 年加入 `Sec-MS-GEC` 签名），需要持续跟进上游 `rany2/edge-tts` 的变更；务必实现自动降级到 Azure/系统 TTS。
2. **授权/版权**：生成的配音仅供个人观看；Azure F0 免费层官方说明仅用于评估，不含商用授权。界面上应给出提示。
3. **F-Droid 版本**：F-Droid 对"依赖非自由网络服务"会打 `NonFreeNet` 反特性标签；可将该功能默认关闭并在描述中注明，或在 `foss` flavor 中仅保留系统 TTS + 用户自配接口。
4. **隐私**：字幕/音频会发送给第三方 API，首次启用时提示用户。
5. **YouTube 自动字幕/自动翻译字幕限流**：近期 YouTube 对字幕请求存在 429 限流，需要重试和友好报错。
6. **SAF 存储路径**：下载到用户选择的外部目录时，需要先复制到缓存处理，再写回。
7. **性能/存储**：处理期间需要约"原视频大小 + 数十 MB"临时空间，完成后清理缓存。

---

## 8. 参考资料

- edge-tts（Python，协议参考）：https://github.com/rany2/edge-tts ，PyPI 最新 7.2.8（2026-03）
- tts-server-android（Android 上的 Edge/系统 TTS 实现参考）：https://github.com/jing332/tts-server-android
- Azure Speech 定价（F0 免费层 50 万字符/月神经 TTS）：https://azure.microsoft.com/en-us/pricing/details/speech/
- Azure 免费层商用说明（Q&A）：https://learn.microsoft.com/en-us/answers/questions/5805156/
- Groq Whisper 免费额度：https://www.free-model.com/models/groq/whisper-large-v3-turbo/
- 硅基流动 语音转文本 API：https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions
- sherpa-onnx（离线 ASR/TTS/VAD/人声分离，支持 Android）：https://github.com/k2-fsa/sherpa-onnx
- YouTube 自动配音说明：https://support.google.com/youtube/answer/15569972
- yt-dlp：https://github.com/yt-dlp/yt-dlp
