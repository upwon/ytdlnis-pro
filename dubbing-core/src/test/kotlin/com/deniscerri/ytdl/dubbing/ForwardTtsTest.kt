package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardTtsTest {
    @Test fun parsesVoiceListShapes() {
        assertEquals(listOf("a" to "A 名"), ForwardTts.parseVoices("""[{"id":"a","name":"A 名"}]"""))
        assertEquals(listOf("x" to "x", "y" to "y"), ForwardTts.parseVoices("""["x","y"]"""))
        assertEquals(listOf("v1" to "小冰"), ForwardTts.parseVoices("""{"engines":[{"voices":[{"voiceCode":"v1","displayName":"小冰"}]}]}"""))
        assertTrue(ForwardTts.parseVoices("not json").isEmpty())
    }

    @Test fun sendsTextSpeedAndVoiceAndSavesAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(500) { 1 })))
            server.start()
            val out = File.createTempFile("fwd", ".mp3")
            ForwardTts(server.url("/").toString()).synthesize("你好", "v1", 20, out)
            val req = server.takeRequest()
            assertEquals("/forward", req.requestUrl!!.encodedPath)
            assertEquals("你好", req.requestUrl!!.queryParameter("text"))
            assertEquals("v1", req.requestUrl!!.queryParameter("voice"))
            assertEquals("60", req.requestUrl!!.queryParameter("speed"))
            assertEquals(500, out.length().toInt())
        }
    }
}
