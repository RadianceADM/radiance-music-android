package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.play.Streak
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app's states when something's wrong (offline, a song that won't play),
 * drawn on a phone-sized screen to look at on the build server; each checked
 * to say what it should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w412dp-h892dp-xxhdpi")
class NoticesTest {
    @get:Rule val rule = createComposeRule()

    @After fun online() = Connectivity.pretend(true) // the next test starts online

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { RadianceTheme { Surface(color = Brand.Night, contentColor = Color.White) { content() } } }
        rule.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val dir = File(System.getenv("SCREENS_DIR") ?: "/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun offlinePill() {
        Connectivity.pretend(false)
        shot("state-offline-pill") { Column { OfflinePill(); MiniPlayer(onOpen = {}) } }
        rule.onNodeWithText("Offline").assertExists()
    }

    @Test fun backOnline() {
        Connectivity.pretend(false)
        rule.setContent { RadianceTheme { Surface(color = Brand.Night, contentColor = Color.White) { OfflinePill() } } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        Connectivity.pretend(true)
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Back online").assertExists()
        save("state-back-online")
        // a moment later it's gone
        rule.mainClock.advanceTimeBy(4000)
        rule.onNodeWithText("Back online").assertDoesNotExist()
    }

    @Test fun searchOffline() {
        shot("state-search-offline") { Problem("You're offline. Connect to search.", offline = true, onRetry = {}) }
        rule.onNodeWithText("Connect").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun searchFailed() {
        shot("state-search-failed") { Problem("Couldn't search. The connection is too slow right now.", offline = false, onRetry = {}) }
        rule.onNodeWithText("Connect").assertDoesNotExist()
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun aNoticeWithRetry() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RadianceTheme {
                Surface(color = Brand.Night, contentColor = Color.White) {
                    val host = remember { SnackbarHostState() }
                    LaunchedEffect(Unit) { host.tell(Notice("3 songs in a row couldn't play, so the music stopped. You're offline.", Notice.retry {})) }
                    NoticeHost(host)
                }
            }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Retry").assertExists()
        save("state-notice")
    }

    @Test fun nowPlayingStoppedOffline() {
        Connectivity.pretend(false)
        // a song that wouldn't play, the music stopped: the reason stays under the song
        App.instance.player.couldnt(null, "You're offline.", Streak.Then.TOO_MANY)
        shot("state-now-playing-stopped") { NowPlayingScreen(onClose = {}) }
        rule.onNodeWithText("Couldn't play. You're offline.").assertExists()
        rule.onNodeWithText("Offline").assertExists()
    }
}
