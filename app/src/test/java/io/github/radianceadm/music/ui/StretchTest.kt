package io.github.radianceadm.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Choose lyrics: a line held, then slid over (android#37; owner, 2026-11-08: "click and swipe to select"). */
class StretchTest {
    private val lengths = listOf(10, 20, 30, 40, 200, 60, 60, 60)

    @Test fun downFromTheLineHeld() = assertEquals(listOf(1, 2, 3) to false, stretch(1, 3, lengths))
    @Test fun upFromTheLineHeld() = assertEquals(listOf(0, 1, 2, 3) to false, stretch(3, 0, lengths))
    @Test fun justTheLineHeld() = assertEquals(listOf(2) to false, stretch(2, 2, lengths))
    // 150 characters at most, counting from the line held: the slide stops at the last line that fits
    @Test fun stopsAtTheLimit() = assertEquals(listOf(5, 6) to true, stretch(5, 7, lengths))
    @Test fun aLongLineStopsIt() = assertEquals(listOf(2, 3) to true, stretch(2, 6, lengths))
}
