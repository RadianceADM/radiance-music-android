package io.github.radianceadm.music.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The party's clock: the offset from pings, where the song is, and staying in step (no player, no server). */
class PartyClockTest {
    private fun song(entry: Long = 1, ms: Long? = 200_000) = PartySong(entry, 7, "Maki", "Dilaw", "Maki - Dilaw.opus", "https://s/files/Maki%20-%20Dilaw.opus", null, ms, null)

    @Test fun theQuickestPingWins() {
        // this phone's clock runs 5 000 ms behind the server's; the slow pings' answers came late on the way back
        val pings = listOf(
            PartyClock.Ping(sent = 1_000, server = 6_150, received = 1_400), // 400 ms round trip (connecting)
            PartyClock.Ping(sent = 2_000, server = 7_010, received = 2_020), // 20 ms: the best
            PartyClock.Ping(sent = 3_000, server = 8_090, received = 3_100),
        )
        val best = PartyClock.best(pings)!!
        assertEquals(20, best.rtt)
        assertEquals(5_000, best.offset)
        assertNull(PartyClock.best(emptyList()))
    }

    @Test fun whereTheSongIs() {
        val playing = PartyState(on = true, id = "p1", song = song(), playing = true, startedAtServerMs = 10_000)
        assertEquals(84_000L, PartyClock.target(playing, 94_000))
        // a fresh song starts lead_ms after the change: before then, it's below 0
        assertEquals(-800L, PartyClock.target(playing.copy(startedAtServerMs = 10_800), 10_000))
        // paused: where it stopped, whatever the time
        val paused = playing.copy(playing = false, startedAtServerMs = null, pausedAtMs = 61_500)
        assertEquals(61_500L, PartyClock.target(paused, 999_999))
        // nothing playing, or no party
        assertNull(PartyClock.target(playing.copy(song = null), 94_000))
        assertNull(PartyClock.target(PartyState.OFF, 94_000))
    }

    @Test fun inStepNudgedOrJumped() {
        // within 20 ms: normal speed
        assertEquals(PartyClock.Move.Steady, PartyClock.decide(50_010, 50_000))
        assertEquals(PartyClock.Move.Steady, PartyClock.decide(49_985, 50_000))
        // a little ahead: 2 % slower; a little behind: 2 % faster
        assertEquals(PartyClock.Move.Nudge(0.98f), PartyClock.decide(50_050, 50_000))
        assertEquals(PartyClock.Move.Nudge(1.02f), PartyClock.decide(49_930, 50_000))
        assertEquals(PartyClock.Move.Nudge(1.02f), PartyClock.decide(49_920, 50_000)) // 80 ms: still a nudge
        // past 80 ms either way: a jump, landing the learned lead further on
        assertEquals(PartyClock.Move.Jump(50_000), PartyClock.decide(50_081, 50_000))
        assertEquals(PartyClock.Move.Jump(50_150), PartyClock.decide(48_000, 50_000, lead = 150))
        // never before the song's start
        assertEquals(PartyClock.Move.Jump(0), PartyClock.decide(900, 0, lead = 0))
    }

    @Test fun aJumpLearnsHowLateItLands() {
        // landed 180 ms behind: lead 180 more next time; 40 ms ahead: 40 less
        assertEquals(180L, PartyClock.learn(0, -180))
        assertEquals(140L, PartyClock.learn(180, 40))
        // in step: as it was
        assertEquals(180L, PartyClock.learn(180, 10))
        // never below 0, never more than 3 s
        assertEquals(0L, PartyClock.learn(30, 200))
        assertEquals(PartyClock.MOST_LEAD_MS, PartyClock.learn(2_900, -900))
    }

    /**
     * A player whose clock runs 0.4 % fast (as some phones' sound does), checked
     * once a second the way Party does it: once it's in step, it stays within
     * the nudge's reach (never a jump), and mostly within 20 ms.
     */
    @Test fun aDriftingPlayerStaysInStep() {
        var player = 0.0
        var speed = 1f
        var jumps = 0
        val worst = mutableListOf<Long>()
        for (second in 1..300) {
            player += 1000.0 * 1.004 * speed
            val target = second * 1000L
            when (val m = PartyClock.decide(player.toLong(), target)) {
                is PartyClock.Move.Jump -> { player = m.to.toDouble(); speed = 1f; jumps++ }
                is PartyClock.Move.Nudge -> speed = m.speed
                PartyClock.Move.Steady -> speed = 1f
            }
            if (second > 10) worst += abs(player.toLong() - target)
        }
        assertEquals(0, jumps)
        assertTrue("worst drift ${worst.max()} ms", worst.max() <= PartyClock.JUMP_MS)
        assertTrue("mostly in step", worst.count { it <= PartyClock.STEADY_MS + 4 } > worst.size / 2)
    }

    /** A late joiner, its jumps landing 250 ms late each time: after one jump it's learned, and the second lands in step. */
    @Test fun aLateJoinerCatchesUp() {
        var lead = 0L
        var player = 0L
        val target = 95_000L
        val first = PartyClock.decide(player, target, lead) as PartyClock.Move.Jump
        player = first.to - 250 // it took the player 250 ms to go on
        lead = PartyClock.learn(lead, player - target)
        assertEquals(250L, lead)
        val second = PartyClock.decide(0, target, lead) as PartyClock.Move.Jump
        player = second.to - 250
        assertEquals(PartyClock.Move.Steady, PartyClock.decide(player, target, lead))
    }
}
