package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whole pipeline on real media, with a local fake OpenAI-compatible server standing in for the LLM and ASR. */
class PipelineTest {
    private lateinit var server: MockWebServer
    private val chatCalls = AtomicInteger()
    private val asrCalls = AtomicInteger()
    private val dir = TestMedia.tempDir("pipe")

    /** zh text generator used by the fake LLM: (cue id) -> translation */
    @Volatile private var zhFor: (Int) -> String = { "这是第${it}句话" }
    @Volatile private var chatReply: ((RecordedRequest) -> String)? = null

    @Before fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/v1/chat/completions" -> {
                    chatCalls.incrementAndGet()
                    val content = chatReply?.invoke(request) ?: translationsFor(request)
                    val body = """{"choices":[{"message":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}}]}"""
                    MockResponse.Builder().code(200).body(body).build()
                }
                "/v1/audio/transcriptions" -> {
                    asrCalls.incrementAndGet()
                    MockResponse.Builder().code(200).body(
                        """{"text":"Hello there. How are you.","segments":[{"start":1.0,"end":3.0,"text":" Hello there."},{"start":4.0,"end":6.0,"text":" How are you."}]}"""
                    ).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After fun down() { server.close() }

    private fun translationsFor(request: RecordedRequest): String {
        val messages = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        val user = Json.parseToJsonElement(messages.last().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        return user["items"]!!.jsonArray.joinToString(",", """{"translations":[""", "]}") {
            val id = it.jsonObject["id"]!!.jsonPrimitive.content.toInt()
            """{"id":$id,"zh":"${zhFor(id)}"}"""
        }
    }

    private fun client() = OpenAiCompatClient(server.url("/v1").toString(), "test-key", retryBaseDelayMs = 1)

    private fun pipeline(
        tts: TtsProvider = ToneTts(),
        config: DubbingConfig = DubbingConfig(),
        withAsr: Boolean = false,
        events: MutableList<Progress>? = null,
        logs: MutableList<String>? = null,
    ): DubbingPipeline {
        val c = client()
        return DubbingPipeline(
            ffmpeg = TestMedia.ffmpeg,
            translator = LlmTranslator(c, "fake-model"),
            tts = tts,
            config = config,
            asr = if (withAsr) OpenAiCompatAsr(c, "whisper") else null,
            log = { logs?.add(it) },
            onProgress = { events?.add(it) },
        )
    }

    private fun video(seconds: Int = 30, name: String = "v.mp4") =
        File(dir, name).also { TestMedia.makeVideo(it, seconds) }

    private fun srt(vararg startsMs: Long, lengthMs: Long = 3500, name: String = "en.srt") =
        File(dir, name).apply {
            writeText(startsMs.mapIndexed { i, s ->
                "${i + 1}\n${SrtWriter.time(s)} --> ${SrtWriter.time(s + lengthMs)}\nThis is sentence number ${i + 1}."
            }.joinToString("\n\n"))
        }

    @Test fun subtitleOnlyWritesAnExternalChineseSrtWithoutSynthesizing() = runBlocking {
        val tts = ToneTts()
        val out = File(dir, "subs/v.zh.srt")
        val n = pipeline(tts).translateSubtitles(null, srt(1000, 6000, 11000, name = "only.en.srt"), File(dir, "work-sub"), out)

        assertEquals(3, n)
        assertEquals(0, tts.calls.size, "no speech is generated in subtitle-only mode")
        assertEquals(1, chatCalls.get())
        val parsed = SubtitleParser.parse(out.readText())
        assertEquals(listOf("这是第1句话", "这是第2句话", "这是第3句话"), parsed.map { it.src })
        assertEquals(listOf(1000L, 6000L, 11000L), parsed.map { it.startMs })
    }

    @Test fun bilingualSubtitleHasChineseAboveTheOriginal() = runBlocking {
        val out = File(dir, "subs/v.bi.srt")
        pipeline().translateSubtitles(null, srt(1000, name = "bi.en.srt"), File(dir, "work-bi"), out, bilingual = true)
        val lines = out.readText().lines()
        assertEquals("这是第1句话", lines[2])
        assertEquals("This is sentence number 1.", lines[3])
    }

    @Test fun dubbingCanAlsoWriteTheExternalSubtitle() = runBlocking {
        val ext = File(dir, "ext/v.zh.srt")
        pipeline(config = DubbingConfig(externalSubtitle = ext)).run(video(12, "ext.mp4"), srt(1000, 5000, name = "ext.en.srt"), File(dir, "work-ext"), File(dir, "ext/out.mp4"))
        assertTrue(ext.exists())
        assertTrue(ext.readText().contains("这是第2句话"))
    }

    /** 3 s of a low "voice" (130 Hz) followed by 3 s of a high one (230 Hz), both with harmonics like a real voice. */
    private fun twoVoiceVideo(name: String): File {
        val f = File(dir, name)
        TestMedia.ff(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=6",
            "-f", "lavfi", "-i", "aevalsrc='if(lt(t,3), sin(2*PI*130*t)+0.5*sin(2*PI*260*t)+0.3*sin(2*PI*390*t), sin(2*PI*230*t)+0.5*sin(2*PI*460*t)+0.3*sin(2*PI*690*t))*0.3':d=6:s=44100",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", f.absolutePath,
        )
        return f
    }

    @Test fun pitchSeparatesALowVoiceFromAHighOne() = runBlocking {
        val v = twoVoiceVideo("two.mp4")
        val cues = listOf(
            Cue(1, 200, 2800, "a", "甲"), Cue(2, 3200, 5800, "b", "乙"),
        )
        val tagged = SpeakerClassifier(TestMedia.ffmpeg).classify(v, cues, File(dir, "work-pitch"))
        assertEquals(listOf("M", "F"), tagged.map { it.speaker })
    }

    @Test fun twoVoicesAreUsedWhenMultiVoiceIsOn() = runBlocking {
        val v = twoVoiceVideo("two2.mp4")
        val tts = ToneTts()
        pipeline(tts, config = DubbingConfig(multiVoice = true, voice = "female-voice", maleVoice = "male-voice"))
            .run(v, srt(200, 3200, lengthMs = 2600, name = "two.en.srt"), File(dir, "work-two"), File(dir, "out/two.zh.mp4"))
        val byText = tts.calls.associate { it.text to it.voice }
        assertEquals("male-voice", byText["这是第1句话"])
        assertEquals("female-voice", byText["这是第2句话"])
    }

    @Test fun singleVoiceIsTheDefault() = runBlocking {
        val v = twoVoiceVideo("two3.mp4")
        val tts = ToneTts()
        pipeline(tts).run(v, srt(200, 3200, lengthMs = 2600, name = "two3.en.srt"), File(dir, "work-two3"), File(dir, "out/two3.zh.mp4"))
        assertEquals(setOf("zh-CN-XiaoxiaoNeural"), tts.calls.map { it.voice }.toSet())
    }

    @Test fun thresholdAdaptsToTwoClearGroups() {
        val t = SpeakerClassifier.genderThreshold(listOf(105.0, 110.0, 112.0, 108.0, 190.0, 195.0, 200.0, 188.0))
        assertTrue(t in 130.0..175.0, "threshold $t should sit between the two groups")
        assertEquals(SpeakerClassifier.DEFAULT_THRESHOLD_HZ, SpeakerClassifier.genderThreshold(listOf(100.0, 105.0)))
    }

    @Test fun subtitleToDubbedVideoEndToEnd() = runBlocking {
        val v = video(30)
        val starts = longArrayOf(1000, 6000, 11000, 16000, 21000, 26000)
        val events = mutableListOf<Progress>()
        val tts = ToneTts()
        val out = File(dir, "out/v.zh.mp4")
        val report = pipeline(tts, events = events).run(v, srt(*starts), File(dir, "work"), out)

        assertTrue(out.exists())
        assertFalse(File(dir, "out/v.zh.part.mp4").exists(), "temp file is renamed away")
        assertEquals(6, report.cues)
        assertEquals(6, report.spoken)
        assertFalse(report.usedAsr)
        assertEquals(1, chatCalls.get(), "all 6 cues fit in one translation batch")
        assertEquals(6, tts.calls.size)
        assertEquals("这是第1句话", tts.calls.minByOrNull { it.text }!!.text)
        assertEquals("zh-CN-XiaoxiaoNeural", tts.calls[0].voice)

        val audio = TestMedia.streams(out, "audio")
        assertEquals(2, audio.size)
        assertEquals("chi", TestMedia.tag(audio[0], "language"))
        assertEquals(30.0, TestMedia.durationSec(out), 0.4)

        val dub = TestMedia.decode(out, 0)
        for (s in starts) {
            assertTrue(TestMedia.loud(dub, s + 100, s + 1300), "speech expected at ${s}ms")
            assertTrue(TestMedia.quiet(dub, s + 2000, s + 4800.coerceAtMost(30_000 - s.toInt()).toLong()), "silence after clip at ${s}ms")
        }
        assertTrue(TestMedia.quiet(dub, 0, 900))
        // every stage reported, ending complete, in order
        assertEquals(Stage.entries, events.map { it.stage }.distinct())
        assertEquals(Progress(Stage.SYNTHESIZE, 6, 6), events.last { it.stage == Stage.SYNTHESIZE })
        assertEquals(Progress(Stage.MUX, 1, 1), events.last())
        assertTrue(File(dir, "work/cues.json").exists())
    }

    @Test fun rerunAfterCompletionReusesCheckpointWithoutAnyApiCalls() = runBlocking {
        val v = video(20)
        val s = srt(1000, 6000, 11000)
        val work = File(dir, "work")
        pipeline().run(v, s, work, File(dir, "a.mp4"))
        val chatBefore = chatCalls.get()

        val tts2 = ToneTts()
        pipeline(tts2).run(v, s, work, File(dir, "b.mp4"))
        assertEquals(chatBefore, chatCalls.get(), "no new LLM requests")
        assertEquals(0, tts2.calls.size, "no new TTS requests")
        assertEquals(2, TestMedia.streams(File(dir, "b.mp4"), "audio").size)
    }

    @Test fun changedSubtitleInvalidatesTheCheckpoint() = runBlocking {
        val v = video(20)
        val work = File(dir, "work")
        pipeline().run(v, srt(1000, 6000), work, File(dir, "a.mp4"))
        val before = chatCalls.get()
        pipeline().run(v, srt(1000, 6000, 11000), work, File(dir, "b.mp4"))
        assertEquals(before + 1, chatCalls.get())
    }

    @Test fun ttsFailureKeepsTranslationsSoRetryOnlyRedoesTheMissingClips() = runBlocking {
        val v = video(20)
        val s = srt(1000, 6000, 11000)
        val work = File(dir, "work")
        val out = File(dir, "o.mp4")
        val failing = ToneTts(failOn = { it.contains("3") })
        val e = assertFailsWith<DubbingException> { pipeline(failing).run(v, s, work, out) }
        assertTrue(e.message!!.contains("cue 3"), e.message)
        assertFalse(out.exists())
        assertFalse(File(dir, "o.part.mp4").exists())
        val translatedCalls = chatCalls.get()

        val good = ToneTts()
        pipeline(good).run(v, s, work, out)
        assertEquals(translatedCalls, chatCalls.get(), "translations were checkpointed")
        assertTrue(good.calls.size in 1..3)
        assertTrue(out.exists())
    }

    @Test fun lowSpeechRateLimitsAndLongTranslationsRequestFasterSpeechThenTempo() = runBlocking {
        zhFor = { "这是一段非常非常长的中文翻译文本用来测试语速自动调整功能是否正常工作哈" } // 33 chars, cue is only 2 s
        val v = video(20)
        val s = srt(1000, 15000, lengthMs = 2000)
        val tts = ToneTts(secondsPerChar = 0.25)
        val report = pipeline(tts).run(v, s, File(dir, "work"), File(dir, "o.mp4"))
        // natural ~7.3 s vs 4 s window (2 s + 2 s borrowed) -> engine asked for the cap (+40%) ...
        assertTrue(tts.calls.all { it.rate == 40 }, tts.calls.toString())
        // ... which gives ~5.9 s, so ffmpeg tempo (~1.5x) fits the rest
        assertEquals(2, report.align.spedUp)
        val dub = TestMedia.decode(File(dir, "o.mp4"), 0)
        assertTrue(TestMedia.loud(dub, 1100, 4800))
        assertTrue(TestMedia.quiet(dub, 5600, 14_500))
    }

    @Test fun noSubtitlesUsesSpeechRecognitionPerSegmentWithOffsets() = runBlocking {
        val v = video(25)
        val tts = ToneTts()
        val report = pipeline(tts, DubbingConfig(asrSegmentSeconds = 10), withAsr = true)
            .run(v, null, File(dir, "work"), File(dir, "o.mp4"))
        assertTrue(report.usedAsr)
        assertEquals(3, asrCalls.get(), "25 s in 10 s chunks")
        assertEquals(6, report.cues, "two sentences per chunk")
        val dub = TestMedia.decode(File(dir, "o.mp4"), 0)
        for (offset in listOf(0L, 10_000L, 20_000L)) {
            assertTrue(TestMedia.loud(dub, offset + 1100, offset + 2300), "sentence 1 at ${offset + 1000}")
            assertTrue(TestMedia.loud(dub, offset + 4100, offset + 5300), "sentence 2 at ${offset + 4000}")
        }
    }

    @Test fun noSubtitlesAndNoAsrGivesAClearError() {
        val e = assertFailsWith<DubbingException> {
            runBlocking { pipeline().run(video(10), null, File(dir, "w"), File(dir, "o.mp4")) }
        }
        assertTrue(e.message!!.contains("no subtitles"))
    }

    @Test fun emptySubtitleFileGivesAClearError() {
        val empty = File(dir, "empty.srt").apply { writeText("WEBVTT\n\n") }
        val e = assertFailsWith<DubbingException> {
            runBlocking { pipeline().run(video(10), empty, File(dir, "w"), File(dir, "o.mp4")) }
        }
        assertTrue(e.message!!.contains("No spoken text"))
    }

    @Test fun modelThatNeverReturnsJsonFailsWithoutLeavingOutput() {
        chatReply = { "Sorry, I can only chat." }
        val out = File(dir, "o.mp4")
        assertFailsWith<TranslationException> {
            runBlocking { pipeline().run(video(10), srt(1000), File(dir, "w"), out) }
        }
        assertFalse(out.exists())
    }

    @Test fun cancellingMidSynthesisStopsPromptlyAndLeavesNoOutput() = runBlocking {
        val v = video(20)
        val s = srt(1000, 6000, 11000)
        val out = File(dir, "o.mp4")
        val job = async(Dispatchers.Default) { pipeline(ToneTts(delayMs = 20_000)).run(v, s, File(dir, "w"), out) }
        delay(1500)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(out.exists())
    }

    @Test fun optionsSoftSubtitleAndMixedBackgroundWork() = runBlocking {
        val v = video(20)
        val cfg = DubbingConfig(
            embedChineseSubtitle = true,
            mux = MuxOptions(originalVolume = 0.25),
            voice = "zh-CN-YunxiNeural",
        )
        val tts = ToneTts()
        val out = File(dir, "o.mp4")
        pipeline(tts, cfg).run(v, srt(1000, 6000), File(dir, "work"), out)
        assertEquals("zh-CN-YunxiNeural", tts.calls[0].voice)
        assertEquals(1, TestMedia.streams(out, "subtitle").size)
        assertEquals(2, TestMedia.streams(out, "audio").size)
        val mixed = TestMedia.decode(out, 0)
        assertTrue(TestMedia.rms(mixed, 10_000, 14_000) > 150, "background kept between lines")
        val zh = File(dir, "work/zh.srt").readText()
        assertTrue(zh.contains("这是第1句话") && zh.contains("00:00:01,000"))
    }

    @Test fun estimateRateOnlyBoostsWhenTranslationIsLongForItsSlot() {
        val p = pipeline(config = DubbingConfig(baseRatePercent = 5))
        val cues = listOf(Cue(1, 0, 4000, "a", "短句"), Cue(2, 4000, 6000, "b", "字".repeat(20)))
        assertEquals(5, p.estimateRatePercent(cues, 0))
        // 20 chars / 4.5 = 4.4 s vs 2 s window -> +120% capped at 40
        assertEquals(40, p.estimateRatePercent(cues, 1))
    }
}
