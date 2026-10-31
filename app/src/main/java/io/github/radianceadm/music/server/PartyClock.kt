package io.github.radianceadm.music.server

import kotlin.math.abs

/**
 * The listening party's clock, apart from any player (so it's tested on a
 * computer): this phone's offset to the server's clock from a few pings
 * (NTP-style: the quickest one wins), where the party's song is now (as
 * party.rs keeps it), and what to do about the player's own place: stay,
 * nudge its speed a little, or jump.
 *
 *   position = (this phone's now + offset) - started_at_server_ms
 *
 * A change starts lead_ms after it's made, so a fresh song's position is
 * below 0 for a moment: it starts at 0 when that moment comes.
 */
object PartyClock {
    /** Further off than this (ms): the player jumps to where the party is. */
    const val JUMP_MS = 80L
    /** Within this (ms) it's in step: normal speed. In between: a nudge. */
    const val STEADY_MS = 20L
    /** A nudge: about 2 % faster or slower (nobody hears it; 20 ms a second). */
    const val NUDGE = 0.02f
    /** A jump lands late by however long the player takes to go on; it learns that, up to this. */
    const val MOST_LEAD_MS = 3000L

    /** One ping: [sent] and [received] by this phone's clock (ms), [server] the server's clock in between. */
    data class Ping(val sent: Long, val server: Long, val received: Long) {
        val rtt get() = received - sent
        /** The server's clock minus this phone's, taking the answer as made halfway. */
        val offset get() = server - (sent + received) / 2
    }

    /** The quickest ping (its offset is off by at most half its round trip); null: none came back. */
    fun best(pings: List<Ping>): Ping? = pings.filter { it.rtt >= 0 }.minByOrNull { it.rtt }

    /**
     * Where the party's song should be at [serverNow] (ms into it): playing,
     * from when it started; paused, where it stopped. Negative: it starts in
     * that long. null: nothing's playing.
     */
    fun target(p: PartyState, serverNow: Long): Long? = when {
        !p.on || p.song == null -> null
        p.playing -> p.startedAtServerMs?.let { serverNow - it }
        else -> p.pausedAtMs ?: 0
    }

    /** What the player should do. */
    sealed class Move {
        /** In step: play at normal speed. */
        object Steady : Move()
        /** A little off: this speed for now (1 ± [NUDGE]). */
        data class Nudge(val speed: Float) : Move()
        /** Too far off: go to [to] (ms). */
        data class Jump(val to: Long) : Move()
    }

    /**
     * The player is at [playerMs], the party at [targetMs]: jump past
     * [JUMP_MS] (landing [lead] ms further on, for the time a jump takes),
     * nudge the speed past [STEADY_MS] (slower when ahead, faster when
     * behind), else play at normal speed.
     */
    fun decide(playerMs: Long, targetMs: Long, lead: Long = 0): Move {
        val drift = playerMs - targetMs
        return when {
            abs(drift) > JUMP_MS -> Move.Jump((targetMs + lead).coerceAtLeast(0))
            abs(drift) > STEADY_MS -> Move.Nudge(if (drift > 0) 1f - NUDGE else 1f + NUDGE)
            else -> Move.Steady
        }
    }

    /**
     * The jump's lead, learned: [drift] is how far off the player was at the
     * first check after a jump (positive: ahead). Behind: lead more next time;
     * ahead: less.
     */
    fun learn(lead: Long, drift: Long): Long = if (abs(drift) <= STEADY_MS) lead else (lead - drift).coerceIn(0, MOST_LEAD_MS)
}
