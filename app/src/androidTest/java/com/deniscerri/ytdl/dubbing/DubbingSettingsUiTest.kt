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
        assertTrue("dubbing screen should show its first section", ui.find("Translation service (LLM)") != null)
        ui.shot("dubbing-screen")
    }

    @Test fun entryIsReachableAndShowsAllSections() {
        openDubbingScreen()
        for (title in listOf("Provider", "API base URL", "Model", "Fetch model list", "Test translation")) {
            assertTrue("missing '$title'", ui.scrollTo(title))
        }
        for (section in listOf("Voice (text to speech)", "Speech recognition (videos without subtitles)", "Output")) {
            assertTrue("missing section '$section'", ui.scrollTo(section))
        }
        assertTrue(ui.scrollTo("Dub after download"))
        assertTrue(ui.scrollTo("Keep the original audio track"))
        ui.shot("sections")
    }

    @Test fun engineChoiceShowsOnlyRelevantFields() {
        openDubbingScreen()
        ui.scrollAndTap("Engine")
        ui.tapDialogItem("Azure Speech (official, needs a key)")
        assertTrue("Azure fields should appear", ui.scrollTo("Azure region"))
        assertEquals("azure", Env.prefs.getString(DubbingPrefs.TTS_ENGINE, null))
        ui.shot("engine-azure")

        ui.scrollAndTap("Engine")
        ui.tapDialogItem("Microsoft Edge voices (free, no key)")
        Thread.sleep(500)
        assertFalse("Azure fields should hide again", ui.has("Azure region"))
        assertEquals("edge", Env.prefs.getString(DubbingPrefs.TTS_ENGINE, null))

        ui.scrollAndTap("Engine")
        ui.tapDialogItem("OpenAI-compatible service")
        assertTrue(ui.scrollTo("TTS service URL"))
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
        assertTrue("key must be masked, only the tail is shown", ui.hasContains("••••••7890"))
        assertFalse("full key must not be shown", ui.hasContains("sk-test-1234567890"))
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
        assertTrue(ui.hasContains("••••••${key.takeLast(4)}"))

        // fetch the model list from the real service and pick a free one
        ui.scrollAndTap("Fetch model list")
        assertTrue("model picker should open", ui.find("Choose a model", 60_000) != null)
        ui.shot("model-picker")
        ui.tapDialogItem("🆓 $model")
        Thread.sleep(500)
        assertEquals(model, Env.prefs.getString(DubbingPrefs.LLM_MODEL, null))
        assertTrue("chosen model shown as summary", ui.scrollTo(model))

        // translation test with the real model
        ui.scrollAndTap("Test translation")
        assertTrue("translation test should succeed", ui.find("It works", 120_000) != null)
        val message = ui.device.findObject(androidx.test.uiautomator.By.res("android:id/message"))?.text.orEmpty()
        Log.i(TAG, "test translation shows: $message")
        ui.shot("test-translation")
        assertTrue("expected Chinese in '$message'", cjk.containsMatchIn(message))
        ui.tap("OK")

        // real Edge voice, played on the device
        ui.scrollAndTap("Test voice")
        Thread.sleep(12_000)
        ui.shot("test-voice")
        assertFalse("voice test must not show an error", ui.has("Error"))
    }
}
