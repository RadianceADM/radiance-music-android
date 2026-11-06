package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.feedback.Account
import io.github.radianceadm.music.feedback.Answers
import io.github.radianceadm.music.feedback.Area
import io.github.radianceadm.music.feedback.Feedback
import io.github.radianceadm.music.feedback.Kind
import io.github.radianceadm.music.feedback.Step
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
        val dir = File(System.getenv("SCREENS_DIR") ?: "/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun songs() {
        val lib = App.instance.library
        listOf("Moolah" to "Young Thug", "Blinding Lights" to "The Weeknd", "Cruel Summer" to "Taylor Swift", "Dilaw" to "Maki", "Pasilyo" to "SunKissed Lola", "Golden Hour" to "JVKE",
            "Araw-Araw" to "Ben&Ben", "Like You Do" to "Joji", "Kathang Isip" to "Ben&Ben", "Tadhana" to "Up Dharma Down", "Espresso" to "Sabrina Carpenter", "Wildflower" to "Billie Eilish", "Sining" to "Dionela", "7 Rings" to "Ariana Grande")
            .forEachIndexed { i, (title, artist) -> lib.like(Track(Source.YOUTUBE, "test$i", title, artist, album = "Album $i", seconds = 200, format = if (i % 2 == 0) "FLAC" else "Opus")) }
    }

    @Test fun settings() = shot("settings") { SettingsScreen(onClose = {}) }
    @Test fun about() = shot("about") { AboutScreen(onClose = {}) }
    @Test fun library() { songs(); shot("library") { LibraryScreen(PaddingValues(), onSettings = {}) } }
    @Test fun home() { songs(); shot("home") { HomeScreen(PaddingValues(), onSearch = {}, onSettings = {}) } }
    /** The largest system font on a narrow phone: the hero's buttons stay whole (android#15). */
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    @Test fun homeLargestFont() { songs(); shot("home-largest-font") { HomeScreen(PaddingValues(), onSearch = {}, onSettings = {}) } }
    @Test fun nowPlaying() = shot("now-playing") { NowPlayingScreen(onClose = {}) }
    // the music video's button at the top right of the cover (android#26)
    @Test fun coverWithVideo() = shot("cover-video") { Box(Modifier.padding(26.dp).size(360.dp)) { Cover(null, androidx.compose.foundation.shape.RoundedCornerShape(14.dp), Modifier, watch = {}) } }
    @Test fun lyricPicker() = shot("lyric-picker") {
        val lines = listOf("I'm in the mood for dancing", "Romancing, ooh", "Give it up, it's a beautiful night", "So I'm dancing with my baby", "And it feels so right", "Under the stars, under the lights", "We're never going home tonight")
            .map { Pick(it, null) }
        val picked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf(2, 3) }
        LyricPicker(lines, picked, Color(0xFF5C2A12), withSound = true, onDone = {})
    }
    @Test fun guide() = shot("guide") { GuideScreen(onDone = {}) }
    // one tap each (android#20): Karaoke lit, no slider per part, for four parts and two halves alike
    @Test fun mixer() {
        App.instance.mixer.voice(io.github.radianceadm.music.play.Mixer.Voice.Karaoke)
        shot("mixer") { MixerSheet(Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 209), mixable = true, onDone = {}) }
    }
    @Test fun mixerSplit() = shot("mixer-split") { MixerSheet(Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 209), mixable = false, onDone = {}) }
    // the mic held (as Apple Music Sing's): how much of the singing stays
    @Test fun singSlider() = shot("sing-slider") { Box(Modifier.padding(40.dp)) { SingSlider(0.3f) } }
    @Test fun singSliderNone() = shot("sing-slider-none") { Box(Modifier.padding(40.dp)) { SingSlider(0f) } }

    // the update pop-up, each step (owner, 2026-10-31: a modal when an update is out, with Remind me later)
    private val release = io.github.radianceadm.music.update.Updates.Release(
        "0.5.13", "## What's new\n- The update pop-up: Update now, Remind me later, Skip this version\n- Restart to update once it's downloaded\n- Faster search", "", 218_000_000, "",
    )
    private fun card(name: String, state: io.github.radianceadm.music.update.Updates.State, playing: Boolean = false, metered: Boolean = false) = shot(name) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            UpdateCard(state, playing, metered, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
    @Test fun updateOut() = card("update-1-out", io.github.radianceadm.music.update.Updates.State.Available(release), metered = true)
    @Test fun updateDownloading() = card("update-2-downloading", io.github.radianceadm.music.update.Updates.State.Downloading(release, 0.42f))
    @Test fun updateReady() = card("update-3-ready", io.github.radianceadm.music.update.Updates.State.Ready(release), playing = true)
    @Test fun updateInstalling() = card("update-4-installing", io.github.radianceadm.music.update.Updates.State.Installing(release))
    @Test fun updateAllow() = card("update-5-allow", io.github.radianceadm.music.update.Updates.State.Allow(release))
    @Test fun updateFailed() = card("update-6-failed", io.github.radianceadm.music.update.Updates.State.Failed("Couldn't update: the download stopped", release))
    // the feedback pop-up (#14), as its bottom sheet shows it over the app
    private val filled = Area.entries.zip(listOf(5, 4, 5, 3, 4))
        .fold(Answers(enjoying = true, comment = "Love splitting songs on the phone. More lyrics for OPM songs, please!")) { a, (area, n) -> a.rate(area, n) }
    private val ana = Account.Me("Ana Reyes", "ana@example.com")
    private fun feedback(name: String, answers: Answers, step: Step, me: Account.Me? = null, online: Boolean = true) = shot(name) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.BottomCenter) {
            Surface(color = Brand.Sheet, contentColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    // the sheet's handle
                    Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp, 4.dp).background(Brand.Dim.copy(alpha = 0.5f), RoundedCornerShape(50)))
                    }
                    FeedbackForm(answers, step, me, online, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }
    @Test fun feedbackEmpty() = feedback("feedback-1-empty", Answers(), Step.Form)
    @Test fun feedbackFilled() = feedback("feedback-2-filled", filled, Step.Form, me = ana)
    @Test fun feedbackSending() = feedback("feedback-3-sending", filled, Step.Sending, me = ana)
    @Test fun feedbackMissing() = feedback("feedback-4-missing", Answers().rate(Area.OVERALL, 4).rate(Area.SOUND, 5),
        Step.Problem(Kind.MISSING, Feedback.MISSING))
    @Test fun feedbackOffline() = feedback("feedback-5-offline", filled, Step.Problem(Kind.OFFLINE, Feedback.OFFLINE), me = ana, online = false)
    @Test fun feedbackLater() = feedback("feedback-6-later", filled, Step.Problem(Kind.LATER, "Too many tries just now. Try again in 2 minutes."), me = ana)
    @Test fun feedbackThanks() = feedback("feedback-7-thanks", Answers(), Step.Thanks(share = true))
    @Test fun feedbackThanksNoShare() = feedback("feedback-8-thanks-plain", Answers(), Step.Thanks(share = false))
    // the strip under the story (#19): the part picked, the lines' part (not movable), still reading the song
    private val loud = FloatArray(64) { i -> io.github.radianceadm.music.share.Loudness.bars(FloatArray(64) { j -> (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(j / 5f))) * (if (j in 24..40) 1f else 0.6f) })[i] }
    @Test fun clipStrip() = shot("clip-strip") { ClipStrip(loud, io.github.radianceadm.music.share.StoryVideo.Window(70_000, 22_000), 209_000, enabled = true, playing = true, onChange = {}, onTouch = { _, _ -> }, onHear = {}) }
    @Test fun clipStripLines() = shot("clip-strip-lines") { ClipStrip(loud, io.github.radianceadm.music.share.StoryVideo.Window(70_000, 9_000), 209_000, enabled = false, playing = false, onChange = {}, onTouch = { _, _ -> }, onHear = {}) }
    @Test fun clipStripReading() = shot("clip-strip-reading") { ClipStrip(null, io.github.radianceadm.music.share.StoryVideo.Window(1_000, 15_000), 209_000, enabled = true, playing = false, onChange = {}, onTouch = { _, _ -> }, onHear = {}) }
    @Test fun shareWithSound() = shot("share-sound") { ShareScreen(Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 209, file = "file:///nonexistent/golden-hour.m4a"), emptyList(), onClose = {}) }
    @Test fun share() = shot("share") { ShareScreen(Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 209), emptyList(), onClose = {}) }
}
