package io.github.radianceadm.music.split

import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every liked song is split by itself once it's saved (owner, 2026-11-06). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class SplitLikedTest {
    private val liked = SplitLiked(App.instance)
    private fun song(id: String, file: String? = "content://media/external/audio/media/$id") = Track(Source.YOUTUBE, id, "Song $id", "Artist", seconds = 200, file = file)

    @Test fun savedSongsNotSplitAreSplit() {
        val a = song("a")
        val b = song("b")
        assertEquals(listOf(a, b), liked.wanted(listOf(a, b), ready = { false }, server = { false }, failed = { false }))
    }

    @Test fun aSongNotSavedYetWaitsForItsFile() {
        val notYet = song("n", file = null)
        assertEquals(emptyList<Track>(), liked.wanted(listOf(notYet), ready = { false }, server = { false }, failed = { false }))
    }

    @Test fun splitWaitingServersAndFailedAreLeft() {
        val split = song("s")
        val server = song("v")
        val failed = song("f")
        val todo = song("t")
        val got = liked.wanted(
            listOf(split, server, failed, todo),
            ready = { it.key == split.key },
            server = { it.key == server.key },
            failed = { it == failed.key },
        )
        assertEquals(listOf(todo), got)
    }
}
