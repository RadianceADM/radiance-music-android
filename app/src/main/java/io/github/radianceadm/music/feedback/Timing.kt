package io.github.radianceadm.music.feedback

/**
 * When the feedback pop-up may ask by itself (owner, 2026-11-01): only after 7
 * days since the first use AND 30 songs played; then at random, at most once
 * every 30 days. "Not now" waits 30 days; "Don't ask again" stops it for good.
 * After you've sent your answers it waits for a newer version too (one answer
 * per version: the admin keeps the newest). Pure rules, so they're tested alone;
 * Feedback keeps what they read.
 */
object Timing {
    const val DAY_MS = 24 * 60 * 60 * 1000L
    /** A week of using it first. */
    const val FIRST_WAIT_MS = 7 * DAY_MS
    /** And this many songs played. */
    const val SONGS = 30
    /** At most once in this long ("Not now" waits it too). */
    const val EVERY_MS = 30 * DAY_MS
    /** Once it may ask, the chance at each free moment (a pause, a song's end): so it comes at random, not on the dot. */
    const val CHANCE = 0.3

    /**
     * What's known about you and the pop-up: [firstUse] the first day the app was
     * used (ms), [songs] songs played, [lastAsked] the last time it asked or you
     * said "Not now" (0: never), [never] "Don't ask again", [sentVersion] the
     * version you last sent answers from (null: none yet).
     */
    data class Seen(val firstUse: Long, val songs: Int, val lastAsked: Long, val never: Boolean, val sentVersion: String?)

    /** Whether it may ask now, at [now], in app [version] (before the dice). */
    fun due(s: Seen, now: Long, version: String): Boolean {
        if (s.never) return false
        if (now - s.firstUse < FIRST_WAIT_MS) return false
        if (s.songs < SONGS) return false
        if (s.sentVersion != null && s.sentVersion == version) return false
        if (s.lastAsked > 0) {
            val since = now - s.lastAsked
            // the phone's clock put back by more than a day (it asked "in the future"): that mustn't hold it up for months
            val clockBack = since < -DAY_MS
            if (!clockBack && since < EVERY_MS) return false
        }
        return true
    }

    /** Whether it asks at this free moment: due, and the dice ([roll], 0 to 1) say so. */
    fun asks(s: Seen, now: Long, version: String, roll: Double): Boolean = due(s, now, version) && roll < CHANCE
}
