package io.github.radianceadm.music.play

import io.github.radianceadm.music.play.Streak.Then
import org.junit.Assert.assertEquals
import org.junit.Test

/** Songs that won't play: skipped, but never more than three in a row (a dead connection mustn't run through the queue). */
class StreakTest {
    @Test fun skipsTwiceThenStopsAtTheThird() {
        val s = Streak()
        assertEquals(Then.NEXT, s.failed(hasNext = true))
        assertEquals(Then.NEXT, s.failed(hasNext = true))
        assertEquals(Then.TOO_MANY, s.failed(hasNext = true))
        assertEquals(3, s.inARow)
    }

    @Test fun aSongThatPlaysStartsTheCountAgain() {
        val s = Streak()
        s.failed(true)
        s.failed(true)
        s.played()
        assertEquals(0, s.inARow)
        assertEquals(Then.NEXT, s.failed(true))
        assertEquals(Then.NEXT, s.failed(true))
        assertEquals(Then.TOO_MANY, s.failed(true))
    }

    @Test fun theLastSongStopsThere() {
        val s = Streak()
        assertEquals(Then.STOP, s.failed(hasNext = false))
        assertEquals(1, s.inARow)
    }

    @Test fun aSongRetriedThatStillWontPlayStopsThere() {
        val s = Streak()
        repeat(3) { s.failed(true) } // stopped: three in a row
        s.retry()
        assertEquals(Then.STOP, s.failed(hasNext = true)) // no skipping on after Retry
        s.retry()
        assertEquals(Then.STOP, s.failed(hasNext = true))
    }

    @Test fun aRetryThatPlaysIsForgotten() {
        val s = Streak()
        s.failed(true)
        s.retry()
        s.played()
        assertEquals(Then.NEXT, s.failed(true))
    }

    @Test fun aSongYouPickStartsAfresh() {
        val s = Streak()
        repeat(3) { s.failed(true) }
        s.picked()
        assertEquals(Then.NEXT, s.failed(true))
        assertEquals(1, s.inARow)
    }

    @Test fun aWholeQueueThatWontPlayStopsAfterThree() {
        // ten songs, none plays (offline): the player goes on twice and stops at the third
        val s = Streak()
        var at = 0
        val songs = 10
        while (true) {
            val then = s.failed(hasNext = at < songs - 1)
            if (then != Then.NEXT) break
            at++
        }
        assertEquals(2, at)
        assertEquals(Streak.MOST, s.inARow)
    }
}
