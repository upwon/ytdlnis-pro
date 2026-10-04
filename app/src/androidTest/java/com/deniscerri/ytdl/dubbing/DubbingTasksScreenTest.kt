package com.deniscerri.ytdl.dubbing

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.deniscerri.ytdl.ui.more.dubbing.DubbingTasksActivity
import com.deniscerri.ytdl.util.dubbing.DubbingState
import com.deniscerri.ytdl.util.dubbing.DubbingStatusStore
import com.deniscerri.ytdl.util.dubbing.DubbingTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/** The "dubbing tasks" screen shows status per job and the filter chips narrow the list. */
@RunWith(AndroidJUnit4::class)
class DubbingTasksScreenTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // UiFlow wakes the screen; a cold-started app on a busy emulator can take a while to show its first frame
    private val ui by lazy { UiFlow(Env.ctx.packageName) }

    private fun has(text: String, ms: Long = 25_000) = ui.findContains(text, ms) != null

    @Test fun waitingTaskOffersVoiceAssignmentPerPerson() {
        val ctx = Env.ctx
        val roles = """[{"id":"S1","gender":"M","lines":12,"samples":["你好，欢迎收听","今天聊聊配音"],"note":"host, male","voice":"zh-CN-YunxiNeural"},
            {"id":"S2","gender":"F","lines":9,"samples":["谢谢邀请"],"note":"guest, female","voice":"zh-CN-XiaoxiaoNeural"}]"""
        DubbingStatusStore.add(ctx, DubbingTask(id = "t-wait", title = "Two people talking", state = DubbingState.WAITING, historyId = 1, roles = roles))
        try {
            ActivityScenario.launch(DubbingTasksActivity::class.java).use { scenario ->
                ui.relaunch = { scenario.recreate() }
                ui.ensureForeground { scenario.recreate() }
                assertTrue("waiting task visible", has("Two people talking"))
                assertTrue("choose-voices state shown", has("Choose voices"))
                device.findObject(By.res(Env.ctx.packageName, "task_assign"))?.click()
                assertTrue("first person listed", has("Person 1"))
                assertTrue("second person listed", has("Person 2"))
                assertTrue("example line shown", has("你好，欢迎收听"))
                assertTrue("start button", has("Start dubbing"))
                device.pressBack()
            }
        } finally {
            DubbingStatusStore.remove(ctx, "t-wait")
        }
    }

    @Test fun showsStatusAndFiltersByIt() {
        val ctx = Env.ctx
        DubbingStatusStore.clearFinished(ctx)
        DubbingStatusStore.add(ctx, DubbingTask(id = "t-done", title = "Task finished video", state = DubbingState.DONE, outputPath = "/sdcard/Movies/x.zh.mp4"))
        DubbingStatusStore.add(ctx, DubbingTask(id = "t-fail", title = "Task broken video", state = DubbingState.FAILED, message = "no subtitles found", historyId = 1, sourcePath = "/sdcard/Movies/broken.mp4", log = "12:00:01  detail-log-line-xyz"))
        try {
            ActivityScenario.launch(DubbingTasksActivity::class.java).use { scenario ->
                ui.relaunch = { scenario.recreate() }
                ui.ensureForeground { scenario.recreate() }
                assertTrue("done task visible", has("Task finished video"))
                assertTrue("failed task visible", has("Task broken video"))
                assertTrue("error message visible", has("no subtitles found"))

                // the details dialog shows the file and the log of the job
                device.findObject(By.res(Env.ctx.packageName, "task_detail"))?.click()
                assertTrue("details dialog shows the log", has("detail-log-line-xyz"))
                assertTrue("details dialog shows the file", has("/sdcard/Movies/broken.mp4"))
                device.pressBack()
                Thread.sleep(500)

                device.findObject(By.text("Failed")).click()
                assertTrue(has("Task broken video"))
                assertFalse("done task hidden by the Failed filter", device.wait(Until.hasObject(By.textContains("Task finished video")), 1_500))

                device.findObject(By.text("Done")).click()
                assertTrue(has("Task finished video"))
                assertFalse("failed task hidden by the Done filter", device.wait(Until.hasObject(By.textContains("Task broken video")), 1_500))
            }
        } finally {
            DubbingStatusStore.remove(ctx, "t-done")
            DubbingStatusStore.remove(ctx, "t-fail")
        }
    }
}
