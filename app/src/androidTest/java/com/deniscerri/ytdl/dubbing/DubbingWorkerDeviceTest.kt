package com.deniscerri.ytdl.dubbing

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import com.deniscerri.ytdl.work.DubbingWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/** Runs the real DubbingWorker inside the real app (bundled ffmpeg/python, real DB, real network). */
@RunWith(AndroidJUnit4::class)
class DubbingWorkerDeviceTest {
    @get:Rule val timeout: Timeout = Timeout(10, TimeUnit.MINUTES)

    private fun runWorker(historyId: Long): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DubbingWorker>(Env.ctx)
            .setInputData(workDataOf(DubbingWorker.KEY_HISTORY_ID to historyId))
            .build()
            .doWork()
    }

    @Test fun bundledFfmpegRunsAndProbesMedia() {
        RuntimeManager.init(Env.ctx)
        val video = Env.installDemo(Env.newWorkDir())
        val info = Env.probe(video)
        Log.i(TAG, "probe: $info")
        assertTrue(info.hasVideo)
        assertEquals(1, info.audioStreams.size)
        assertEquals("eng", info.audioStreams[0].language)
        assertTrue("duration ${info.durationMs}", info.durationMs in 23_000..25_000)
    }

    @Test fun dubsADownloadedVideoEndToEnd() {
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false)
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir)
        val item = Env.insertHistory(video.absolutePath, "Demo title")

        val result = runWorker(item.id)
        Log.i(TAG, "worker result: $result")
        assertTrue("worker result: $result", result is ListenableWorker.Result.Success)

        val out = File(dir, "Demo clip.zh.mp4")
        assertTrue("dubbed file missing; dir has ${dir.list()?.toList()}", out.exists() && out.length() > 10_000)
        assertTrue("original must stay untouched", video.exists())
        val info = Env.probe(out)
        Log.i(TAG, "dubbed probe: $info")
        assertTrue(info.hasVideo)
        assertEquals(2, info.audioStreams.size)
        assertEquals("chi", info.audioStreams[0].language)
        assertEquals("eng", info.audioStreams[1].language)
        assertTrue("duration ${info.durationMs}", info.durationMs in 23_000..25_500)

        // real Mandarin speech starts with each sentence and nothing plays before the first one
        val dub = Env.decode(out, 0)
        assertTrue("silent before first cue", Env.rms(dub, 0, 900) < 150)
        for (start in listOf(1000L, 5000L, 9000L, 13000L, 17000L, 21000L)) {
            val level = Env.rms(dub, start + 200, start + 1500)
            assertTrue("speech expected at ${start}ms but rms=$level", level > 300)
        }
        // the original English track is still the pristine tone
        assertTrue(Env.rms(Env.decode(out, 1), 6000, 8000) > 1000)

        // a second history entry for the dubbed file
        val entries = Env.historyByUrl(item.url)
        assertEquals(2, entries.size)
        val dubbed = entries.first { it.id != item.id }
        assertEquals(listOf(out.absolutePath), dubbed.downloadPath)
        assertTrue(dubbed.title.contains("Chinese dub"))
        // temp work dir cleaned up
        assertFalse(File(com.deniscerri.ytdl.util.FileUtil.getCachePath(Env.ctx), "dubbing/${item.id}").exists())
    }

    @Test fun replaceOriginalWithMixedBackgroundAndSoftSubtitle() {
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false) {
            putBoolean(DubbingPrefs.REPLACE_ORIGINAL, true)
            putInt(DubbingPrefs.ORIGINAL_VOLUME, 25)
            putBoolean(DubbingPrefs.EMBED_SUBTITLE, true)
            putString(DubbingPrefs.GLOSSARY, "artificial intelligence=人工智能")
        }
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "Replace me")
        val item = Env.insertHistory(video.absolutePath)

        val result = runWorker(item.id)
        assertTrue("worker result: $result", result is ListenableWorker.Result.Success)

        assertFalse("no extra file in replace mode", File(dir, "Replace me.zh.mp4").exists())
        val info = Env.probe(video)
        Log.i(TAG, "replaced probe: $info")
        assertEquals(2, info.audioStreams.size)
        assertEquals("chi", info.audioStreams[0].language)
        assertEquals(1, info.subtitleStreams)
        // mixed track: speech plus quiet background between the sentences
        val mixed = Env.decode(video, 0)
        assertTrue(Env.rms(mixed, 1200, 3500) > 300)
        assertTrue("background audible", Env.rms(mixed, 22_900, 23_900) > 100)
        assertEquals("history keeps a single entry in replace mode", 1, Env.historyByUrl(item.url).size)
    }

    @Test fun failsClearlyWhenThereAreNoSubtitlesAndNoSpeechRecognition() {
        Env.configure("any/model:free", engine = DubbingPrefs.ENGINE_EDGE)
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "No subs", subtitle = false)
        // unreachable url: yt-dlp (bundled python) is started for the subtitle lookup and fails
        val item = Env.insertHistory(video.absolutePath, url = "https://invalid.invalid/watch?v=none")

        val result = runWorker(item.id)
        Log.i(TAG, "worker result: $result")
        assertTrue("worker result: $result", result is ListenableWorker.Result.Failure)
        assertFalse(File(dir, "No subs.zh.mp4").exists())
        assertEquals(1, Env.historyByUrl(item.url).size)
    }

    /** Local page served by CI (see .github/scripts/prepare-e2e-site.py); the emulator reaches the host as 10.0.2.2. */
    private fun siteUrl() = "http://10.0.2.2:8000/index.html?run=${System.nanoTime()}"

    // plain socket: Android's cleartext-HTTP policy would block an HTTP request from the app process
    private fun siteReachable(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("10.0.2.2", 8000), 3_000); true }
    }.getOrDefault(false)

    @Test fun fetchesEnglishSubtitlesOnlineWhenNoneWereSavedWithTheVideo() {
        assumeTrue("local test site is not running", siteReachable())
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false)
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "Online subs", subtitle = false)
        assertFalse(File(dir, "Online subs.en.srt").exists())
        val item = Env.insertHistory(video.absolutePath, "Online subs", url = siteUrl())

        val result = runWorker(item.id)
        Log.i(TAG, "worker result: $result")
        assertTrue("worker result: $result", result is ListenableWorker.Result.Success)
        val out = File(dir, "Online subs.zh.mp4")
        assertTrue("dubbed file missing", out.exists())
        assertEquals(2, Env.probe(out).audioStreams.size)
        assertTrue("speech expected from the downloaded subtitles", Env.rms(Env.decode(out, 0), 1200, 3000) > 300)
    }

    @Test fun missingFileFailsWithoutCrashing() {
        Env.configure("any/model:free")
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "Gone")
        val item = Env.insertHistory(video.absolutePath)
        video.delete()
        val result = runWorker(item.id)
        assertTrue("worker result: $result", result is ListenableWorker.Result.Failure)
    }

    @Test fun systemVoiceEngineProducesADub() {
        val available = systemChineseTtsAvailable()
        Log.i(TAG, "system Chinese TTS available: $available")
        assumeTrue("no Chinese system TTS voice on this device", available)
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_SYSTEM)
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "System voice")
        val item = Env.insertHistory(video.absolutePath)
        val result = runWorker(item.id)
        assertTrue("worker result: $result", result is ListenableWorker.Result.Success)
        assertEquals(2, Env.probe(File(dir, "System voice.zh.mp4")).audioStreams.size)
    }

    private fun systemChineseTtsAvailable(): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(Env.ctx) { status ->
            ok = status == TextToSpeech.SUCCESS &&
                (tts?.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        tts.shutdown()
        return ok
    }
}
