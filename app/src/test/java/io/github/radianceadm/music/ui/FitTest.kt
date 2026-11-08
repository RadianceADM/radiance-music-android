package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.play.PlayerHub
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app on every kind of screen it meets (android#34): a phone upright and on its side, a tablet
 * both ways, and a car's Android stereo (1024×600, and 1280×720 at a higher density). Drawn on the
 * build server to look at; nothing to compare against, so each shot only has to draw.
 */
abstract class FitShots(private val size: String) {
    @get:Rule val rule = createComposeRule()

    private val cover = File("/srv/build/android/splittest/cover.jpg")
    private val song = Track(Source.YOUTUBE, "fit", "Golden Hour", "JVKE", album = "this is what ____ feels like", seconds = 209, format = "Opus",
        art = if (cover.exists()) "file://${cover.path}" else "")

    private fun shot(name: String, before: () -> Unit = {}, content: @Composable () -> Unit) {
        Guide.seen(RuntimeEnvironment.getApplication())
        App.instance.splashed = true
        rule.setContent { RadianceTheme { Surface(color = Brand.Night, contentColor = Color.White) { content() } } }
        rule.waitForIdle()
        before()
        // the covers come from a file, off the main thread: a moment for them to land
        Thread.sleep(700)
        rule.waitForIdle()
        val dir = File(System.getenv("SCREENS_DIR") ?: "/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$size-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A song playing (and a few liked), as the screens see the player. */
    @Suppress("UNCHECKED_CAST")
    private fun playing() {
        val lib = App.instance.library
        listOf("Moolah" to "Young Thug", "Blinding Lights" to "The Weeknd", "Cruel Summer" to "Taylor Swift", "Dilaw" to "Maki", "Pasilyo" to "SunKissed Lola",
            "Araw-Araw" to "Ben&Ben", "Like You Do" to "Joji", "Kathang Isip" to "Ben&Ben", "Tadhana" to "Up Dharma Down", "Espresso" to "Sabrina Carpenter",
            "Wildflower" to "Billie Eilish", "Sining" to "Dionela", "7 Rings" to "Ariana Grande")
            .forEachIndexed { i, (title, artist) -> lib.like(Track(Source.YOUTUBE, "fit$i", title, artist, album = "Album $i", seconds = 200, format = if (i % 2 == 0) "FLAC" else "Opus")) }
        lib.like(song)
        fun <T> flow(name: String) = PlayerHub::class.java.getDeclaredField(name).apply { isAccessible = true }.get(App.instance.player) as MutableStateFlow<T>
        flow<Track?>("_current").value = song
        flow<Boolean>("_playing").value = true
    }

    @Test fun home() { playing(); shot("1-home") { Radiance() } }
    @Test fun library() { playing(); shot("2-library", before = { rule.onAllNodesWithText("Library").onFirst().performClick() }) { Radiance() } }
    @Test fun search() { playing(); shot("3-search", before = { rule.onAllNodesWithText("Search").onFirst().performClick() }) { Radiance() } }
    @Test fun nowPlaying() { playing(); shot("4-now-playing") { NowPlayingScreen(onClose = {}) } }
    @Test fun lyrics() { playing(); shot("5-lyrics", before = { rule.onNodeWithContentDescription("Lyrics").performClick() }) { NowPlayingScreen(onClose = {}) } }
    @Test fun settings() = shot("6-settings") { SettingsScreen(onClose = {}, checkServers = false) }
    @Test fun guide() = shot("7-guide") { GuideScreen(onDone = {}) }
    @Test fun share() { playing(); shot("9-share") { ShareScreen(song, emptyList(), onClose = {}) } }
    @Test fun lyricPicker() = shot("9-lyric-picker") {
        val lines = listOf("I'm in the mood for dancing", "Romancing, ooh", "Give it up, it's a beautiful night", "So I'm dancing with my baby", "And it feels so right", "Under the stars, under the lights")
            .map { Pick(it, null) }
        val picked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf(2, 3) }
        LyricPicker(lines, picked, Color(0xFF5C2A12), withSound = true, onDone = {})
    }
    @Test fun update() = shot("8-update") {
        val release = io.github.radianceadm.music.update.Updates.Release("1.1.0", "## What's new\n- Tablets and car stereos: the tabs down the side, Now Playing side by side", "", 44_000_000, "")
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            UpdateCard(io.github.radianceadm.music.update.Updates.State.Available(release), false, false, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w412dp-h892dp-port-xxhdpi")
class PhoneFitTest : FitShots("a-phone")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w892dp-h412dp-land-xxhdpi")
class PhoneSidewaysFitTest : FitShots("b-phone-sideways")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w800dp-h1280dp-port-xhdpi")
class TabletFitTest : FitShots("c-tablet")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletSidewaysFitTest : FitShots("d-tablet-sideways")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w1024dp-h600dp-land-mdpi")
class CarFitTest : FitShots("e-car-1024x600")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w853dp-h480dp-land-hdpi")
class Car720FitTest : FitShots("f-car-1280x720")
