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
    @Test fun parsesMultiTtsCatalog() {
        val body = """{"success":true,"data":{"count":3,"catalog":{
            "sougou":[{"id":"sougou_xiyue","name":"夕月","gender":"female","locale":"zh-CN","desc":"清冷御姐","type":"offline"}],
            "microsoft":[{"id":"microsoft_en-US-GuyNeural","name":"Guy","gender":"male","locale":"en-US","type":"offline"}],
            "namiai":[{"id":"namiai_doubao_1","name":"豆包","gender":"female","locale":"zh-CN","type":"online"}]}}}"""
        val v = ForwardTts.parseVoices(body)
        assertEquals(listOf("sougou_xiyue", "microsoft_en-US-GuyNeural", "namiai_doubao_1"), v.map { it.id })
        assertTrue(v[0].isFemale && v[0].isMandarin && !v[0].online && v[0].engine == "sougou")
        assertTrue(!v[1].isMandarin && v[1].isMale)
        assertTrue(v[2].online)
    }

    @Test fun parsesPlainListsAndRejectsGarbage() {
        assertEquals(listOf("a"), ForwardTts.parseVoices("""[{"id":"a","name":"A"}]""").map { it.id })
        assertEquals(listOf("x", "y"), ForwardTts.parseVoices("""["x","y"]""").map { it.id })
        assertTrue(ForwardTts.parseVoices("""{"success":true,"data":{"count":2}}""").isEmpty())
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
