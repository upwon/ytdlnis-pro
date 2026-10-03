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

    private fun has(text: String, ms: Long = 6_000) = device.wait(Until.hasObject(By.textContains(text)), ms)

    @Test fun showsStatusAndFiltersByIt() {
        val ctx = Env.ctx
        DubbingStatusStore.clearFinished(ctx)
        DubbingStatusStore.add(ctx, DubbingTask(id = "t-done", title = "Task finished video", state = DubbingState.DONE, outputPath = "/sdcard/Movies/x.zh.mp4"))
        DubbingStatusStore.add(ctx, DubbingTask(id = "t-fail", title = "Task broken video", state = DubbingState.FAILED, message = "no subtitles found", historyId = 1))
        try {
            ActivityScenario.launch(DubbingTasksActivity::class.java).use {
                assertTrue("done task visible", has("Task finished video"))
                assertTrue("failed task visible", has("Task broken video"))
                assertTrue("error message visible", has("no subtitles found"))

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
