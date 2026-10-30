package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The screens drawn on a phone-sized screen, to look at on the build server (no phone needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w412dp-h892dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { RadianceTheme { Surface(color = Brand.Night, contentColor = Color.White) { content() } } }
        rule.waitForIdle()
        val dir = File("/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun songs() {
        val lib = App.instance.library
        listOf("Moolah" to "Young Thug", "Blinding Lights" to "The Weeknd", "Cruel Summer" to "Taylor Swift", "Dilaw" to "Maki", "Pasilyo" to "SunKissed Lola", "Golden Hour" to "JVKE")
            .forEachIndexed { i, (title, artist) -> lib.like(Track(Source.YOUTUBE, "test$i", title, artist, album = "Album $i", seconds = 200, format = if (i % 2 == 0) "FLAC" else "Opus")) }
    }

    @Test fun settings() = shot("settings") { SettingsScreen(onClose = {}) }
    @Test fun about() = shot("about") { AboutScreen(onClose = {}) }
    @Test fun library() { songs(); shot("library") { LibraryScreen(PaddingValues(), onSettings = {}) } }
    @Test fun home() { songs(); shot("home") { HomeScreen(PaddingValues(), onSearch = {}, onSettings = {}) } }
    @Test fun nowPlaying() = shot("now-playing") { NowPlayingScreen(onClose = {}) }
    @Test fun share() = shot("share") { ShareScreen(Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 209), emptyList(), onClose = {}) }
}
