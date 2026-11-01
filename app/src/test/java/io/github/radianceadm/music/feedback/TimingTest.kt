package io.github.radianceadm.music.feedback

import io.github.radianceadm.music.feedback.Timing.DAY_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the feedback pop-up may ask by itself (owner, 2026-11-01): a week, 30 songs, then at random, at most once in 30 days. */
class TimingTest {
    private val start = 1_780_000_000_000L
    private val v = "0.5.16"
    /** Used for 8 days, 40 songs, never asked: due. */
    private val ready = Timing.Seen(firstUse = start, songs = 40, lastAsked = 0, never = false, sentVersion = null)
    private val now = start + 8 * DAY_MS

    @Test fun asksOnlyAfterAWeekSinceTheFirstUse() {
        assertFalse(Timing.due(ready, start, v)) // the first day (the first open, the setup)
        assertFalse(Timing.due(ready, start + 7 * DAY_MS - 1, v)) // not quite a week
        assertTrue(Timing.due(ready, start + 7 * DAY_MS, v))
    }

    @Test fun asksOnlyAfter30SongsPlayed() {
        assertFalse(Timing.due(ready.copy(songs = 0), now, v))
        assertFalse(Timing.due(ready.copy(songs = 29), now, v))
        assertTrue(Timing.due(ready.copy(songs = 30), now, v))
    }

    @Test fun thenAtRandom() {
        assertTrue(Timing.asks(ready, now, v, roll = 0.0))
        assertTrue(Timing.asks(ready, now, v, roll = Timing.CHANCE - 0.01))
        assertFalse(Timing.asks(ready, now, v, roll = Timing.CHANCE))
        assertFalse(Timing.asks(ready, now, v, roll = 0.99))
        // the dice never make it ask when it isn't due
        assertFalse(Timing.asks(ready.copy(songs = 3), now, v, roll = 0.0))
    }

    @Test fun atMostOnceIn30DaysAndNotNowWaits30Days() {
        val asked = ready.copy(lastAsked = now)
        assertFalse(Timing.due(asked, now, v))
        assertFalse(Timing.due(asked, now + 29 * DAY_MS, v))
        assertFalse(Timing.due(asked, now + 30 * DAY_MS - 1, v))
        assertTrue(Timing.due(asked, now + 30 * DAY_MS, v))
    }

    @Test fun dontAskAgainStopsItForGood() {
        val never = ready.copy(never = true)
        assertFalse(Timing.due(never, now, v))
        assertFalse(Timing.due(never, now + 3650 * DAY_MS, v))
        assertFalse(Timing.asks(never, now, v, roll = 0.0))
    }

    @Test fun afterSendingItWaitsForANewerVersion() {
        val sent = ready.copy(lastAsked = now, sentVersion = v)
        assertFalse(Timing.due(sent, now + 90 * DAY_MS, v)) // the same version: answered already
        assertTrue(Timing.due(sent, now + 30 * DAY_MS, "0.5.17")) // a newer one, 30 days on
        assertFalse(Timing.due(sent, now + 10 * DAY_MS, "0.5.17")) // a newer one, but not 30 days yet
    }

    @Test fun aClockPutBackDoesntHoldItUpForMonths() {
        // asked "a year from now" (the clock was wrong then, right now)
        assertTrue(Timing.due(ready.copy(lastAsked = now + 365 * DAY_MS), now, v))
        // a few hours' difference (time zones): still counts as just asked
        assertFalse(Timing.due(ready.copy(lastAsked = now + DAY_MS / 4), now, v))
    }
}
