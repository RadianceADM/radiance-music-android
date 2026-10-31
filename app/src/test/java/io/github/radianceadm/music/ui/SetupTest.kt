package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.split.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The first open's setup on the start screen (owner, 2026-10-31: "download on
 * first open ng app" / "setup"): the splitter downloading, and offline with Try
 * again and Open Radiance Music now; drawn on a phone-sized screen to look at
 * on the build server, each checked to say what it should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w412dp-h892dp-xxhdpi")
class SetupTest {
    @get:Rule val rule = createComposeRule()

    private fun save(name: String) {
        val dir = File(System.getenv("SCREENS_DIR") ?: "/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun downloading() {
        var done = false
        rule.mainClock.autoAdvance = false
        rule.setContent { RadianceTheme { StartSplash(setup = Setup.State.Getting(72_400_000), onDone = { done = true }) } }
        rule.mainClock.advanceTimeBy(1_600) // the sun up, its bars playing
        rule.onNodeWithText("Getting Radiance Music ready: downloading the splitter (165 MB)…").assertExists()
        rule.onNodeWithText("72 MB of 165 MB").assertExists()
        rule.onNodeWithText("Try again").assertDoesNotExist() // nothing to press while it downloads
        save("setup-1-downloading")
        // it stays on the icon while the setup goes on (long past when the splash would have gone)
        rule.mainClock.advanceTimeBy(12_000)
        assertFalse(done)
    }

    @Test fun offline() {
        var retried = 0
        var openedNow = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RadianceTheme {
                StartSplash(setup = Setup.State.Stopped("You're offline.", offline = true), onRetry = { retried++ }, onOpenNow = { openedNow++ }, onDone = {})
            }
        }
        rule.mainClock.advanceTimeBy(1_600)
        rule.onNodeWithText("The splitter couldn't download").assertExists()
        rule.onNodeWithText("You're offline.").assertExists()
        rule.onNodeWithText("Your songs still play. Splitting gets ready by itself once you're back online.").assertExists()
        save("setup-2-offline")
        rule.onNodeWithText("Try again").assertExists().performClick()
        rule.onNodeWithText("Open Radiance Music now").assertExists().performClick()
        assertEquals(1, retried)
        assertEquals(1, openedNow)
    }

    @Test fun readyGoesOnIntoTheApp() {
        var setup by mutableStateOf<Setup.State?>(Setup.State.Checking)
        var done = false
        rule.mainClock.autoAdvance = false
        rule.setContent { RadianceTheme { StartSplash(setup = setup, onDone = { done = true }) } }
        rule.mainClock.advanceTimeBy(1_600)
        rule.onNodeWithText("Getting Radiance Music ready: checking the splitter…").assertExists()
        save("setup-3-checking")
        rule.mainClock.advanceTimeBy(5_000)
        assertFalse(done)
        // ready (or Open Radiance Music now): the bars settle, the logo writes itself, the dark opens
        rule.runOnIdle { setup = null }
        rule.mainClock.advanceTimeBy(6_000)
        assertTrue(done)
    }

    @Test fun mixerOffline() {
        rule.setContent {
            RadianceTheme {
                Surface(color = Brand.Night, contentColor = Color.White) {
                    Column(Modifier.padding(22.dp)) { SplitterNotReady(Setup.State.Stopped("You're offline.", offline = true)) }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Splitting gets ready when you're back online").assertExists()
        save("setup-4-mixer-offline")
    }
}
