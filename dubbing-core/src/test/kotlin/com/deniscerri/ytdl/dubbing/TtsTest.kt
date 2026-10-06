package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsmlAndTextTest {
    @Test fun ssmlEscapesAndFormatsRate() {
        val s = Ssml.build("a<b & \"c\" 'd'", "zh-CN-XiaoxiaoNeural", -10)
        assertContains(s, "rate='-10%'")
        assertContains(s, "a&lt;b &amp; &quot;c&quot; &apos;d&apos;")
        assertContains(s, "<voice name='zh-CN-XiaoxiaoNeural'>")
        assertContains(s, "xml:lang='zh-CN'")
        assertContains(Ssml.build("x", "v", 25), "rate='+25%'")
    }

    @Test fun ttsTextCleaning() {
        assertEquals("你好 世界", TtsText.clean("你好 *世界*\u0007"))
        assertTrue(TtsText.speakable("好"))
        assertTrue(TtsText.speakable("42"))
        assertTrue(!TtsText.speakable("…… ！？"))
        assertEquals(5, TtsText.spokenLength("你好，世界！a"))
    }
}

class RestTtsTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.close() }

    private fun mp3() = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 1, 2, 3)

    @Test fun azureSendsKeyFormatAndSsml() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(mp3())).build())
        val out = File.createTempFile("azure", ".mp3").apply { deleteOnExit() }
        AzureTts("eastasia", "KEY", endpoint = server.url("/cognitiveservices/v1").toString(), retryBaseDelayMs = 1)
            .synthesize("你好 <世界>", "zh-CN-YunxiNeural", 15, out)
        assertTrue(mp3().contentEquals(out.readBytes()))
        assertEquals(2, server.requestCount) // 429 was retried
        server.takeRequest()
        val req = server.takeRequest()
        assertEquals("KEY", req.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("audio-24khz-48kbitrate-mono-mp3", req.headers["X-Microsoft-OutputFormat"])
        val body = req.body!!.utf8()
        assertContains(body, "zh-CN-YunxiNeural")
        assertContains(body, "rate='+15%'")
        assertContains(body, "你好 &lt;世界&gt;")
    }

    @Test fun openAiCompatTtsMapsRateToSpeed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(mp3())).build())
        val client = OpenAiCompatClient(server.url("/v1").toString(), "k", retryBaseDelayMs = 1)
        val out = File.createTempFile("openai", ".mp3").apply { deleteOnExit() }
        OpenAiCompatTts(client, "FunAudioLLM/CosyVoice2-0.5B").synthesize("你好", "alloy", 50, out)
        assertTrue(mp3().contentEquals(out.readBytes()))
        assertContains(server.takeRequest().body!!.utf8(), "\"speed\":1.5")
    }

    private class Counting(val fail: Boolean, val tag: String) : TtsProvider {
        var calls = 0
        override val fileExtension = "mp3"
        override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
            calls++
            if (fail) throw IOException("down")
            outFile.writeText("$tag:$voice")
        }
    }

    @Test fun fallbackTakesOverAndSticksAfterRepeatedFailures() = runBlocking {
        val primary = Counting(true, "p")
        val fallback = Counting(false, "f")
        val logs = mutableListOf<String>()
        val tts = FallbackTts(primary, fallback, "sys-voice", maxPrimaryFailures = 2, log = { logs += it }, primaryAttempts = 1, retryDelayMs = 0)
        val f = File.createTempFile("fallback", ".mp3").apply { deleteOnExit() }
        repeat(4) { tts.synthesize("x", "edge-voice", 0, f) }
        assertEquals("f:sys-voice", f.readText())
        assertEquals(2, primary.calls) // left alone for a while once it has failed twice in a row
        assertEquals(4, fallback.calls)
        assertEquals(3, logs.size) // two failures + the "paused" notice
    }

    @Test fun primaryIsTriedAgainAfterTheCoolDownInsteadOfBeingDroppedForTheWholeJob() = runBlocking {
        var now = 0L
        val primary = Counting(true, "p")
        val tts = FallbackTts(primary, Counting(false, "f"), "sys", maxPrimaryFailures = 2, primaryAttempts = 1, retryDelayMs = 0, coolDownMs = 10_000, clock = { now })
        val f = File.createTempFile("cool", ".mp3").apply { deleteOnExit() }
        repeat(3) { tts.synthesize("x", "v", 0, f) }
        assertEquals(2, primary.calls)
        now += 11_000
        tts.synthesize("x", "v", 0, f)
        assertEquals(3, primary.calls, "after the cool-down the primary voice gets another chance")
    }

    @Test fun aFlakyConnectionIsRetriedBeforeTheOtherVoiceIsUsed() = runBlocking {
        var failuresLeft = 2
        val flaky = object : TtsProvider {
            override val fileExtension = "mp3"
            var calls = 0
            override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
                calls++
                if (failuresLeft-- > 0) throw java.io.IOException()
                outFile.writeText("primary")
            }
        }
        val fallback = Counting(false, "f")
        val f = File.createTempFile("flaky", ".mp3").apply { deleteOnExit() }
        FallbackTts(flaky, fallback, "sys", retryDelayMs = 0).synthesize("x", "v", 0, f)
        assertEquals("primary", f.readText())
        assertEquals(3, flaky.calls)
        assertEquals(0, fallback.calls, "the sentence kept the same voice")
    }
}

class EdgeTtsTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.close() }

    private val fixedNow = 1_700_000_000_000L

    @Test fun secMsGecMatchesIndependentImplementation() {
        // Expected values computed with a separate Python implementation of the same published algorithm.
        assertEquals("42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF", EdgeTts.secMsGec(fixedNow))
        assertEquals("E85AECA4054665D350193FDC644D6716DF0F24350DAB0844476D80F50B0B57FD", EdgeTts.secMsGec(fixedNow, 3600.0))
        // stable inside a 5-minute bucket, changes in the next one
        assertEquals(EdgeTts.secMsGec(fixedNow + 50_000), EdgeTts.secMsGec(fixedNow + 51_000))
        assertTrue(EdgeTts.secMsGec(fixedNow) != EdgeTts.secMsGec(fixedNow + 300_000))
    }

    @Test fun voiceLanguageIsDerivedFromVoiceName() {
        assertEquals("zh-CN", EdgeTts.langOf("zh-CN-XiaoxiaoNeural"))
        assertEquals("en-US", EdgeTts.langOf("weird"))
    }

    internal fun binaryFrame(headerText: String, payload: ByteArray): ByteString {
        val headers = headerText.toByteArray()
        val buf = Buffer()
        buf.writeByte(headers.size shr 8).writeByte(headers.size and 0xFF).write(headers).write(payload)
        return buf.readByteString()
    }

    internal fun audioFrame(payload: ByteArray): ByteString =
        binaryFrame("X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nX-Stream-Id:1\r\nPath:audio\r\n", payload)

    private class FakeEdge(val frames: List<ByteArray>, val finish: Boolean = true) : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        lateinit var outer: EdgeTtsTest
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            if (text.contains("Path:ssml")) {
                webSocket.send("X-RequestId:abc\r\nContent-Type:application/json; charset=utf-8\r\nPath:turn.start\r\n\r\n{}")
                frames.forEach { webSocket.send(outer.audioFrame(it)) }
                // a trailing metadata frame without audio must be ignored
                webSocket.send(outer.binaryFrame("X-RequestId:abc\r\nPath:audio.metadata\r\n", ByteArray(0)))
                if (finish) webSocket.send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}") else webSocket.close(1000, "bye")
            }
        }
    }

    private fun wsUrl() = server.url("/edge/v1?TrustedClientToken=tok").toString().replaceFirst("http", "ws")

    private fun serve(l: FakeEdge): RecordedRequest? {
        l.outer = this
        server.enqueue(MockResponse.Builder().webSocketUpgrade(l).build())
        return null
    }

    @Test fun synthesizesAudioFollowingTheEdgeProtocol() = runBlocking {
        val l = FakeEdge(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        serve(l)
        val out = File.createTempFile("edge", ".mp3").apply { deleteOnExit() }
        EdgeTts(wssUrl = wsUrl(), clockMs = { fixedNow }).synthesize("你好 & 世界", "zh-CN-XiaoxiaoNeural", 25, out)

        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), out.readBytes().toList())

        val req = server.takeRequest()
        val q = req.url
        assertEquals("tok", q.queryParameter("TrustedClientToken"))
        assertEquals(EdgeTts.secMsGec(fixedNow), q.queryParameter("Sec-MS-GEC"))
        assertEquals(EdgeTts.GEC_VERSION, q.queryParameter("Sec-MS-GEC-Version"))
        assertEquals(32, q.queryParameter("ConnectionId")!!.length)
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", req.headers["Origin"])
        assertContains(req.headers["User-Agent"]!!, "Edg/")

        assertEquals(2, l.received.size)
        assertContains(l.received[0], "Path:speech.config")
        assertContains(l.received[0], "audio-24khz-48kbitrate-mono-mp3")
        assertContains(l.received[1], "Path:ssml")
        assertContains(l.received[1], "Content-Type:application/ssml+xml")
        assertContains(l.received[1], "<voice name='zh-CN-XiaoxiaoNeural'>")
        assertContains(l.received[1], "rate='+25%'")
        assertContains(l.received[1], "你好 &amp; 世界")
        Unit
    }

    @Test fun adoptsServerClockOnForbiddenAndRetriesOnce() = runBlocking {
        val serverTime = fixedNow + 3_600_000
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(serverTime).atZone(ZoneOffset.UTC))
        server.enqueue(MockResponse.Builder().code(403).addHeader("Date", date).build())
        serve(FakeEdge(listOf(byteArrayOf(9))))
        val out = File.createTempFile("edge", ".mp3").apply { deleteOnExit() }
        EdgeTts(wssUrl = wsUrl(), clockMs = { fixedNow }).synthesize("hi", "en-US-AriaNeural", 0, out)

        assertEquals(listOf<Byte>(9), out.readBytes().toList())
        val first = server.takeRequest().url.queryParameter("Sec-MS-GEC")
        val second = server.takeRequest().url.queryParameter("Sec-MS-GEC")
        assertEquals(EdgeTts.secMsGec(fixedNow), first)
        assertEquals(EdgeTts.secMsGec(fixedNow, 3600.0), second)
        Unit
    }

    @Test fun repeatedForbiddenIsReportedNotLooped() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(403).build()) }
        val out = File.createTempFile("edge", ".mp3").apply { deleteOnExit() }
        assertFailsWith<Exception> { runBlocking { EdgeTts(wssUrl = wsUrl(), clockMs = { fixedNow }).synthesize("hi", "v", 0, out) } }
        assertTrue(server.requestCount <= 2)
    }

    @Test fun failsClearlyWhenServerClosesWithoutAudio() {
        serve(FakeEdge(emptyList(), finish = false))
        val out = File.createTempFile("edge", ".mp3").apply { deleteOnExit() }
        val e = assertFailsWith<IOException> { runBlocking { EdgeTts(wssUrl = wsUrl()).synthesize("hi", "v", 0, out) } }
        assertContains(e.message!!, "turn.end")
    }

    @Test fun failsWhenTurnEndsButNoAudioArrived() {
        serve(FakeEdge(emptyList(), finish = true))
        val out = File.createTempFile("edge", ".mp3").apply { deleteOnExit() }
        val e = assertFailsWith<IOException> { runBlocking { EdgeTts(wssUrl = wsUrl()).synthesize("hi", "v", 0, out) } }
        assertContains(e.message!!, "no audio")
    }
}
