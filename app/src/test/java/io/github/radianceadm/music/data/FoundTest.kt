package io.github.radianceadm.music.data

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoundTest {
    private fun yt(id: String, title: String, artist: String, seconds: Int) = Track(Source.YOUTUBE, id, title, artist, seconds = seconds)

    @Test fun aSavedNameIsItsArtistAndTitle() {
        assertEquals("Al James" to "Mood (feat. Muric)", Found.split("Al James - Mood (feat. Muric)"))
        // a title with " - " in it: the artist is before the first one
        assertEquals("Baby Blood" to "3 AM - Baby Blood (Official Lyric Video)", Found.split("Baby Blood - 3 AM - Baby Blood (Official Lyric Video)"))
        assertEquals("" to "Untitled", Found.split("Untitled"))
    }

    @Test fun theSongWithTheSameNameAndLengthIsPicked() {
        val f = Found.File("content://media/1", "Al James - PSG", "opus", 201)
        val results = listOf(
            yt("a", "PSG (Remix)", "Someone Else", 250),
            yt("b", "PSG", "Al James", 202),
            yt("c", "PSG", "Al James", 320), // a live one: too long
        )
        assertEquals("b", Found.pick(f, results)?.id)
    }

    @Test fun aSavedNameWithReplacedCharactersStillMatches() {
        // Saver puts _ for characters a file name can't have
        val f = Found.File("content://media/2", "AC_DC - Back In Black", "flac", 255)
        assertEquals("x", Found.pick(f, listOf(yt("x", "Back In Black", "AC/DC", 255)))?.id)
    }

    @Test fun nothingLikeItIsNoMatch() {
        val f = Found.File("content://media/3", "HELLMERRY - Summer Crush", "m4a", 180)
        assertNull(Found.pick(f, listOf(yt("z", "Other Song", "Other Artist", 120))))
        assertNull(Found.pick(f, emptyList()))
    }

    @Test fun anUnknownLengthGoesByTheName() {
        val f = Found.File("content://media/4", "IV OF SPADES - Come Inside Of My Heart", "opus", 0)
        assertEquals("k", Found.pick(f, listOf(yt("k", "Come Inside Of My Heart", "IV OF SPADES", 260)))?.id)
    }
}
