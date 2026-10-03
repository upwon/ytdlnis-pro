package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun client(key: String = "sk-test", headers: Map<String, String> = emptyMap()) =
        OpenAiCompatClient(server.url("/v1/").toString(), key, extraHeaders = headers, maxAttempts = 3, retryBaseDelayMs = 1)

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun dailyQuotaIsNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"Rate limit exceeded: free-models-per-day"}}""").build())
        val e = assertFailsWith<ApiException> { client().chat(ChatRequest("m", listOf(ChatMessage("user", "hi")))) }
        assertTrue(e.quotaExhausted)
        assertEquals(1, server.requestCount, "a used-up daily quota must not be hammered with retries")
    }

    @Test
    fun chatSendsAuthHeadersAndBodyAndParsesReply() = runBlocking {
        server.enqueue(ok("""{"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3}}"""))
        val r = client(headers = mapOf("X-Title" to "ytdlnis")).chat(
            ChatRequest("some/model:free", listOf(ChatMessage("system", "s"), ChatMessage("user", "u")), temperature = 0.2, jsonMode = true)
        )
        assertEquals("你好", r.content)
        assertEquals("stop", r.finishReason)
        assertEquals(10, r.promptTokens)
        assertEquals(3, r.completionTokens)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer sk-test", req.headers["Authorization"])
        assertEquals("ytdlnis", req.headers["X-Title"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("some/model:free", body["model"]!!.jsonPrimitive.content)
        assertEquals(2, body["messages"]!!.jsonArray.size)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun chatOmitsOptionalFieldsAndAuthWhenNoKey() = runBlocking {
        server.enqueue(ok("""{"choices":[{"message":{"content":"x"}}]}"""))
        client(key = "").chat(ChatRequest("m", listOf(ChatMessage("user", "u"))))
        val req = server.takeRequest()
        assertEquals(null, req.headers["Authorization"])
        val body = req.body!!.utf8()
        assertFalse(body.contains("response_format"))
        assertFalse(body.contains("temperature"))
    }

    @Test
    fun retriesRateLimitsAndServerErrorsThenSucceeds() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":"slow down"}""").build())
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        server.enqueue(ok("""{"choices":[{"message":{"content":"fine"}}]}"""))
        assertEquals("fine", client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))).content)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun doesNotRetryAuthErrors() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build())
        val e = assertFailsWith<ApiException> { runBlocking { client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))) } }
        assertEquals(401, e.code)
        assertFalse(e.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).body("boom").build()) }
        val e = assertFailsWith<ApiException> { runBlocking { client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))) } }
        assertEquals(500, e.code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun treatsGatewayErrorInsideA200AsAnError() {
        server.enqueue(ok("""{"error":{"code":401,"message":"No auth credentials found"}}"""))
        val e = assertFailsWith<ApiException> { runBlocking { client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))) } }
        assertEquals(401, e.code)
        assertTrue(e.message!!.contains("No auth credentials"))
    }

    @Test
    fun readsContentGivenAsPartsAndMissingContentAsEmpty() = runBlocking {
        server.enqueue(ok("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""))
        server.enqueue(ok("""{"choices":[{"message":{"content":null,"reasoning_content":"hmm"}}]}"""))
        assertEquals("ab", client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))).content)
        assertEquals("", client().chat(ChatRequest("m", listOf(ChatMessage("user", "u")))).content)
    }

    @Test
    fun listsModelsWithPricingAndFreeFlag() = runBlocking {
        server.enqueue(ok("""{"data":[
            {"id":"vendor/free-one:free","name":"Free One","pricing":{"prompt":"0","completion":"0"}},
            {"id":"vendor/paid","name":"Paid","pricing":{"prompt":"0.000001","completion":"0.000002"}},
            {"id":"vendor/free-priced","pricing":{"prompt":"0","completion":"0"}},
            {"id":"plain-no-pricing"}]}"""))
        val models = client().listModels()
        assertEquals(4, models.size)
        assertEquals(listOf(true, false, true, false), models.map { it.isFree })
        assertEquals(0.000002, models[1].completionPrice)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun transcribeSendsMultipartAndUsesSegmentTimestamps() = runBlocking {
        server.enqueue(ok("""{"text":"x","segments":[{"start":0.5,"end":2.25,"text":" Hello there."},{"start":2.5,"end":4,"text":""},{"start":4.0,"end":6.1,"text":"Second one"}]}"""))
        val f = File.createTempFile("seg", ".mp3").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val cues = client().transcribe(f, "whisper-large-v3-turbo", "en", 10_000)
        assertEquals(listOf("Hello there.", "Second one"), cues.map { it.src })
        assertEquals(500, cues[0].startMs)
        assertEquals(6100, cues[1].endMs)

        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = req.body!!.utf8()
        for (needle in listOf("whisper-large-v3-turbo", "verbose_json", "timestamp_granularities[]", "name=\"language\"", "filename=\"${f.name}\"")) {
            assertTrue(body.contains(needle), "multipart body should contain $needle")
        }
    }

    @Test
    fun transcribeFallsBackToWholeTextWhenNoSegments() = runBlocking {
        server.enqueue(ok("""{"text":"Only plain text here."}"""))
        val f = File.createTempFile("seg", ".mp3").apply { deleteOnExit() }
        val cues = client().transcribe(f, "m", null, 7_000)
        assertEquals(1, cues.size)
        assertEquals(7_000, cues[0].endMs)
        assertEquals("Only plain text here.", cues[0].src)
    }

    @Test
    fun speechReturnsAudioBytesAndRejectsJsonErrors() = runBlocking {
        val audio = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(audio)).build())
        assertTrue(audio.contentEquals(client().speech("tts-1", "alloy", "你好", 1.2)))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("你好", body["input"]!!.jsonPrimitive.content)
        assertEquals("1.2", body["speed"]!!.jsonPrimitive.content)

        server.enqueue(ok("""{"error":"quota"}"""))
        assertFailsWith<ApiException> { client().speech("tts-1", "alloy", "x", null) }
        Unit
    }
}
