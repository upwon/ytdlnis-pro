package com.deniscerri.ytdl.dubbing

import android.Manifest
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import com.deniscerri.ytdl.util.dubbing.DubbingScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/** The user paths that start a dubbing job: the history menu, and the "dub after download" scheduling path. */
@RunWith(AndroidJUnit4::class)
class DubbingHistoryUiTest {
    @get:Rule val timeout: Timeout = Timeout(12, TimeUnit.MINUTES)

    private fun grantNotifications() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Env.ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun waitForHistoryEntries(url: String, count: Int, timeoutMs: Long): List<HistoryItem> {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val items = Env.historyByUrl(url)
            if (items.size >= count) return items
            Thread.sleep(3_000)
        }
        val work = WorkManager.getInstance(Env.ctx).getWorkInfosByTag(DubbingScheduler.WORK_TAG).get().map { "${it.state}" }
        throw AssertionError("dubbed history entry never appeared (work states: $work)")
    }

    private fun assertDubbed(file: File) {
        assertTrue("dubbed file missing: $file", file.exists() && file.length() > 10_000)
        val info = Env.probe(file)
        Log.i(TAG, "dubbed probe: $info")
        assertEquals(2, info.audioStreams.size)
        assertEquals("chi", info.audioStreams[0].language)
        assertTrue(Env.rms(Env.decode(file, 0), 1200, 2800) > 300)
    }

    @Test fun historyMenuStartsDubbingAndTheResultAppearsInTheList() {
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false) {
            putBoolean("asked_auto_update_preferences", true)
        }
        grantNotifications()
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "History clip")
        val title = "History demo ${System.nanoTime() % 100000}"
        val item = Env.insertHistory(video.absolutePath, title)

        val ui = UiFlow(Env.ctx.packageName)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.relaunch = {
                Env.ctx.startActivity(
                    android.content.Intent(Env.ctx, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
            ui.ensureForeground { ui.relaunch!!.invoke() }
            Thread.sleep(2_000)
            ui.shot("main")
            ui.dismissIfPresent("Cancel", "Not now", "Later", "OK")
            ui.tap("Downloads")
            ui.check(ui.find(title, 30_000) != null, "history card '$title' should be listed")
            ui.shot("history-list")

            ui.find(title)!!.click(1_200) // long press selects the item and opens the contextual bar
            val more = ui.device.wait(Until.findObject(By.desc("More options")), 10_000)
            ui.check(more != null, "overflow menu of the selection bar should be available")
            more!!.click()
            ui.shot("overflow")
            ui.check(ui.find("Chinese dubbing (AI)") != null, "menu should offer the dubbing action")
            ui.tap("Chinese dubbing (AI)")
            ui.shot("after-tap")

            val entries = waitForHistoryEntries(item.url, 2, 6 * 60_000L)
            val dubbed = entries.first { it.id != item.id }
            assertTrue(dubbed.title.endsWith("[Chinese dub]"))
            assertDubbed(File(dubbed.downloadPath.single()))
            // and the new entry shows up in the list
            ui.check(ui.findContains("[Chinese dub]", 20_000) != null, "dubbed entry should appear in the history list")
            ui.shot("history-with-dub")
        } finally {
            scenario.close()
        }
    }

    @Test fun schedulingAfterADownloadFindsTheHistoryEntryAndDubs() {
        val model = Env.workingFreeModel()
        Env.configure(model, engine = DubbingPrefs.ENGINE_EDGE, fallback = false)
        grantNotifications()
        val dir = Env.newWorkDir()
        val video = Env.installDemo(dir, "Fresh download")
        val item = Env.insertHistory(video.absolutePath, "Fresh download title")
        // the download worker identifies the finished download by (download id, url, type)
        DubbingScheduler.enqueueForDownload(Env.ctx, item.downloadId, item.url, item.type.name)

        val entries = waitForHistoryEntries(item.url, 2, 6 * 60_000L)
        assertDubbed(File(entries.first { it.id != item.id }.downloadPath.single()))
    }
}
