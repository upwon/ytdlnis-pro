package com.deniscerri.ytdl.dubbing

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/**
 * The real thing, end to end: the app's own download queue downloads a video (and its English subtitles) from a
 * local test page, and "dub after download" turns the result into a file with a Chinese audio track.
 */
@RunWith(AndroidJUnit4::class)
class DubbingDownloadFlowTest {
    @get:Rule val timeout: Timeout = Timeout(12, TimeUnit.MINUTES)

    private val site = "http://10.0.2.2:8000/index.html?run=${System.nanoTime()}"

    // plain socket: Android's cleartext-HTTP policy would block an HTTP request from the app process
    private fun siteReachable(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("10.0.2.2", 8000), 3_000); true }
    }.getOrDefault(false)

    @Test fun downloadThenAutoDubProducesAFileWithAChineseTrack() {
        assumeTrue("local test site is not running", siteReachable())
        val model = Env.workingFreeModel()
        // keep the app's default settings, only add what the feature needs
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false, clear = false) {
            putBoolean(DubbingPrefs.AUTO, true)
        }
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Env.ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }

        val outDir = Env.newWorkDir()
        val vm = DownloadViewModel(Env.ctx.applicationContext as Application)
        val result = vm.createEmptyResultItem(site)
        val item = vm.createDownloadItemFromResult(result, site, DownloadType.video)
        item.downloadPath = outDir.absolutePath
        item.videoPreferences.writeSubs = true
        item.videoPreferences.writeAutoSubs = false
        item.videoPreferences.embedSubs = false
        item.videoPreferences.subsLanguages = "en.*,en"
        Log.i(TAG, "queueing download of $site into $outDir")
        val queued = runBlocking { vm.queueDownloads(listOf(item), ignoreDuplicates = true) }
        Log.i(TAG, "queue result: $queued")

        // downloaded video + dubbed copy
        val end = System.currentTimeMillis() + 10 * 60_000L
        var entries = Env.historyByUrl(site)
        while (entries.size < 2 && System.currentTimeMillis() < end) {
            Thread.sleep(4_000)
            entries = Env.historyByUrl(site)
        }
        Log.i(TAG, "history entries: ${entries.map { it.title to it.downloadPath }}; files: ${outDir.list()?.toList()}")
        assertEquals("expected the download and its dubbed copy, files: ${outDir.list()?.toList()}", 2, entries.size)

        val original = entries.first { !it.title.endsWith("[Chinese dub]") }
        val dubbed = entries.first { it.title.endsWith("[Chinese dub]") }
        val originalFile = File(original.downloadPath.first())
        val dubbedFile = File(dubbed.downloadPath.first())
        assertTrue("downloaded file missing: $originalFile", originalFile.exists())
        assertEquals(1, Env.probe(originalFile).audioStreams.size)

        val info = Env.probe(dubbedFile)
        Log.i(TAG, "dubbed probe: $info")
        assertEquals(2, info.audioStreams.size)
        assertEquals("chi", info.audioStreams[0].language)
        assertTrue("speech expected", Env.rms(Env.decode(dubbedFile, 0), 1200, 3000) > 300)
    }
}
