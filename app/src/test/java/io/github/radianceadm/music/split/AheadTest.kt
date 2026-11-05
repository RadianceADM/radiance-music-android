package io.github.radianceadm.music.split

import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.play.Mixer.Voice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Karaoke stays on and splits ahead (android#20): the song playing first, then the next. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class AheadTest {
    private val ahead = Ahead(App.instance)
    private val a = Track(Source.YOUTUBE, "a", "Like You Do", "Joji", seconds = 240)
    private val b = Track(Source.YOUTUBE, "b", "Tadhana", "Up Dharma Down", seconds = 300)

    @Test fun theWholeSongSplitsNothing() =
        assertEquals(emptyList<Track>(), ahead.wanted(Voice.Song, a, b) { false })

    @Test fun karaokeSplitsThePlayingSongThenTheNext() =
        assertEquals(listOf(a, b), ahead.wanted(Voice.Karaoke, a, b) { false })

    @Test fun vocalsOnlyToo() =
        assertEquals(listOf(a, b), ahead.wanted(Voice.VocalsOnly, a, b) { false })

    @Test fun songsSplitOrOnTheirWayAreLeft() {
        assertEquals(listOf(b), ahead.wanted(Voice.Karaoke, a, b) { it == a })
        assertEquals(emptyList<Track>(), ahead.wanted(Voice.Karaoke, a, b) { true })
    }

    @Test fun oneSongQueuedOnce() {
        assertEquals(listOf(a), ahead.wanted(Voice.Karaoke, a, a) { false })
        assertEquals(listOf(b), ahead.wanted(Voice.Karaoke, null, b) { false })
    }
}
