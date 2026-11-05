package io.github.radianceadm.music.play

import io.github.radianceadm.music.App
import io.github.radianceadm.music.play.Mixer.Voice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Karaoke as Apple Music Sing (android#20): one tap, for the four parts and the two halves alike. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class MixerTest {
    private val mixer get() = App.instance.mixer
    private fun g(part: String) = mixer.gains.value.getValue(part)

    @Test fun theWholeSongAtFirst() {
        assertEquals(Voice.Song, mixer.voice.value)
        for (p in Mixer.PARTS + Mixer.HALVES) assertEquals(p, 1f, g(p))
    }

    @Test fun karaokeTakesTheSingingOut() {
        mixer.voice(Voice.Karaoke)
        assertEquals(0f, g("vocals"))
        for (p in listOf("drums", "bass", "other", "music")) assertEquals(p, 1f, g(p))
        mixer.voice(Voice.Song) // tap again: the whole song
        for (p in Mixer.PARTS + Mixer.HALVES) assertEquals(p, 1f, g(p))
    }

    @Test fun vocalsOnlyKeepsJustTheSinging() {
        mixer.voice(Voice.VocalsOnly)
        assertEquals(1f, g("vocals"))
        for (p in listOf("drums", "bass", "other", "music")) assertEquals(p, 0f, g(p))
    }

    @Test fun pressAndSlideKeepsSomeSingingAndTurnsKaraokeOn() {
        mixer.singing(0.3f)
        assertEquals(Voice.Karaoke, mixer.voice.value)
        assertEquals(0.3f, g("vocals"))
        assertEquals(1f, g("music"))
        mixer.singingSet()
        mixer.voice(Voice.Song)
        mixer.voice(Voice.Karaoke) // the level stays for next time
        assertEquals(0.3f, g("vocals"))
        mixer.singing(5f)
        assertEquals(1f, mixer.singing.value)
        mixer.singing(-1f)
        assertEquals(0f, mixer.singing.value)
    }
}
