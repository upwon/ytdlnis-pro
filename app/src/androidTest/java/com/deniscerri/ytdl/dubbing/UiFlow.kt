package com.deniscerri.ytdl.dubbing

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File

/** Small UiAutomator toolbox; failures include the texts currently on screen so CI logs are self-explanatory. */
class UiFlow(val pkg: String) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    init {
        // a screen that went to sleep shows nothing to UiAutomator
        runCatching { device.wakeUp() }
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
    }

    private val shots = File(DeviceTestEnv.ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
    private var counter = 0

    fun shot(name: String) {
        runCatching { device.takeScreenshot(File(shots, "%02d-%s.png".format(counter++, name))) }
    }

    fun visibleTexts(): List<String> =
        device.findObjects(By.pkg(pkg)).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.distinct()

    private fun shell(cmd: String): String = runCatching { device.executeShellCommand(cmd).trim() }.getOrDefault("?")

    private fun fail(msg: String): Nothing {
        shot("failure")
        val top = shell("dumpsys activity activities").lines().filter { "ResumedActivity" in it }.take(2)
        val focus = shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "isKeyguardShowing" in it || "mScreenOn" in it }.take(3)
        throw AssertionError("$msg\nVisible texts: ${visibleTexts()}\nResumed: $top\nWindow: $focus")
    }

    /** Assertion that prints the current screen contents (and a screenshot) when it fails. */
    fun check(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    fun find(text: String, timeoutMs: Long = 10_000): UiObject2? = device.wait(Until.findObject(By.text(text)), timeoutMs)
    fun findContains(text: String, timeoutMs: Long = 10_000): UiObject2? = device.wait(Until.findObject(By.textContains(text)), timeoutMs)
    fun has(text: String): Boolean = device.hasObject(By.text(text))
    fun hasContains(text: String): Boolean = device.hasObject(By.textContains(text))

    fun tap(text: String, timeoutMs: Long = 15_000) {
        val o = find(text, timeoutMs) ?: fail("Cannot find '$text'")
        o.click()
        Log.i(TAG, "tapped '$text'")
    }

    /** First-run dialogs and the like: tap the first of [texts] that is on screen, if any. */
    fun dismissIfPresent(vararg texts: String) {
        for (t in texts) {
            val o = device.findObject(By.text(t)) ?: continue
            o.click()
            Log.i(TAG, "dismissed '$t'")
            Thread.sleep(500)
            return
        }
    }

    /** Scrolls a settings list until [text] is visible. */
    fun scrollTo(text: String, listClass: String = "androidx.recyclerview.widget.RecyclerView"): Boolean {
        if (has(text)) return true
        return runCatching {
            UiScrollable(UiSelector().className(listClass).scrollable(true)).apply {
                setAsVerticalList()
                maxSearchSwipes = 25
            }.scrollIntoView(UiSelector().text(text))
        }.getOrDefault(false)
    }

    fun scrollAndTap(text: String) {
        if (!scrollTo(text)) fail("Cannot scroll to '$text'")
        tap(text)
    }

    /** Dialog item lists (single choice / items). */
    fun tapDialogItem(text: String) {
        if (!has(text)) {
            runCatching {
                UiScrollable(UiSelector().resourceId("$pkg:id/select_dialog_listview")).apply {
                    setAsVerticalList()
                    maxSearchSwipes = 40
                }.scrollIntoView(UiSelector().text(text))
            }
        }
        tap(text)
    }

    fun typeInTextDialog(value: String) {
        val edit = device.wait(Until.findObject(By.res(pkg, "url_edittext")), 10_000) ?: fail("Text dialog did not open")
        edit.text = value
        shot("dialog-filled")
        tap("OK")
    }
}
