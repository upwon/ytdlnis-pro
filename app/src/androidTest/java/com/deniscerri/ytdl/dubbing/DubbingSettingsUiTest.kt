package com.deniscerri.ytdl.dubbing

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deniscerri.ytdl.ui.more.settings.SettingsActivity
import com.deniscerri.ytdl.util.dubbing.DubbingPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/** Drives the real settings screen like a user would. */
@RunWith(AndroidJUnit4::class)
class DubbingSettingsUiTest {
    @get:Rule val timeout: Timeout = Timeout(10, TimeUnit.MINUTES)

    private lateinit var scenario: ActivityScenario<SettingsActivity>
    private lateinit var ui: UiFlow
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Before fun launch() {
        Env.prefs.edit().clear().commit()
        ui = UiFlow(Env.ctx.packageName)
        scenario = ActivityScenario.launch(Intent(Env.ctx, SettingsActivity::class.java))
        ui.ensureForeground {
            Env.ctx.startActivity(
                Intent(Env.ctx, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        ui.shot("settings-main")
    }

    @After fun close() {
        ui.shot("end")
        scenario.close()
    }

    private fun openDubbingScreen() {
        ui.tap("Processing")
        ui.shot("processing")
        ui.tap("AI Chinese dubbing")
        ui.check(ui.find("Translation service (LLM)") != null, "dubbing screen should show its first section")
        ui.shot("dubbing-screen")
    }

    @Test fun entryIsReachableAndShowsAllSections() {
        openDubbingScreen()
        for (title in listOf("Provider", "API base URL", "Model", "Fetch model list", "Test translation")) {
            ui.check(ui.scrollTo(title), "missing '$title'")
        }
        for (section in listOf("Voice (text to speech)", "Speech recognition (videos without subtitles)", "Output")) {
            ui.check(ui.scrollTo(section), "missing section '$section'")
        }
        ui.check(ui.scrollTo("Dub after download"), "missing Dub after download")
        ui.check(ui.scrollTo("Keep the original audio track"), "missing Keep the original audio track")
        ui.shot("sections")
    }

    @Test fun engineChoiceShowsOnlyRelevantFields() {
        openDubbingScreen()
        ui.scrollAndTap("Engine")
        ui.tapDialogItem("Azure Speech (official, needs a key)")
        ui.check(ui.scrollTo("Azure region"), "Azure fields should appear")
        assertEquals("azure", Env.prefs.getString(DubbingPrefs.TTS_ENGINE, null))
        ui.shot("engine-azure")

        ui.scrollAndTap("Engine")
        ui.tapDialogItem("Microsoft Edge voices (free, no key)")
        Thread.sleep(500)
        ui.check(!ui.has("Azure region"), "Azure fields should hide again")
        assertEquals("edge", Env.prefs.getString(DubbingPrefs.TTS_ENGINE, null))

        ui.scrollAndTap("Engine")
        ui.tapDialogItem("OpenAI-compatible service")
        ui.check(ui.scrollTo("TTS service URL"), "TTS service URL should appear")
        ui.shot("engine-openai")
    }

    @Test fun switchesAreSavedAndSecretsAreMasked() {
        openDubbingScreen()
        ui.scrollAndTap("Dub after download")
        Thread.sleep(300)
        assertTrue(Env.prefs.getBoolean(DubbingPrefs.AUTO, false))

        ui.scrollAndTap("Provider")
        ui.tapDialogItem("DeepSeek")
        Thread.sleep(500)
        assertEquals("https://api.deepseek.com/v1", Env.prefs.getString(DubbingPrefs.LLM_BASE_URL, null))
        assertEquals("deepseek-chat", Env.prefs.getString(DubbingPrefs.LLM_MODEL, null))

        ui.scrollAndTap("API key")
        ui.typeInTextDialog("sk-test-1234567890")
        Thread.sleep(300)
        assertEquals("sk-test-1234567890", Env.prefs.getString(DubbingPrefs.LLM_API_KEY, null))
        ui.check(ui.scrollTo("API key") && ui.hasContains("7890"), "key tail should be shown in the summary")
        Log.i(TAG, "screen after key entry: ${ui.visibleTexts()}")
        ui.check(ui.hasContains("••••••7890"), "key must be masked, only the tail is shown")
        ui.check(!ui.hasContains("sk-test-1234567890"), "full key must not be shown")
        ui.shot("masked-key")
    }

    @Test fun fullUserJourneyWithRealOpenRouter() {
        val model = Env.workingFreeModel()
        val key = Env.openRouterKey
        openDubbingScreen()

        ui.scrollAndTap("Provider")
        ui.tapDialogItem("OpenRouter")
        Thread.sleep(500)
        assertEquals("https://openrouter.ai/api/v1", Env.prefs.getString(DubbingPrefs.LLM_BASE_URL, null))
        ui.shot("provider-openrouter")

        ui.scrollAndTap("API key")
        ui.typeInTextDialog(key)
        // the summary is refreshed asynchronously after the dialog closes
        ui.check(ui.findContains("••••••${key.takeLast(4)}", 8_000) != null, "masked key expected in summary")
        assertEquals(key, Env.prefs.getString(DubbingPrefs.LLM_API_KEY, null))

        // fetch the model list from the real service and pick a free one
        ui.scrollAndTap("Fetch model list")
        ui.check(ui.find("Choose a model", 60_000) != null, "model picker should open")
        ui.shot("model-picker")
        ui.tapDialogItem("🆓 $model")
        Thread.sleep(500)
        assertEquals(model, Env.prefs.getString(DubbingPrefs.LLM_MODEL, null))
        ui.check(ui.scrollTo(model), "chosen model shown as summary")

        // translation test with the real model
        ui.scrollAndTap("Test translation")
        ui.check(ui.find("It works", 120_000) != null, "translation test should succeed")
        val message = ui.device.findObject(androidx.test.uiautomator.By.res("android:id/message"))?.text.orEmpty()
        Log.i(TAG, "test translation shows: $message")
        ui.shot("test-translation")
        ui.check(cjk.containsMatchIn(message), "expected Chinese in '$message'")
        ui.tap("OK")

        // real Edge voice, played on the device
        ui.scrollAndTap("Test voice")
        Thread.sleep(12_000)
        ui.shot("test-voice")
        ui.check(!ui.has("Error"), "voice test must not show an error")
    }
}
