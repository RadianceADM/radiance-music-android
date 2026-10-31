package io.github.radianceadm.music.play

/**
 * Songs that wouldn't play, one after another: each is skipped to the next,
 * but the third in a row stops the music (no connection mustn't run through
 * the whole queue in a few seconds). A song that plays, or one you pick,
 * starts the count again; a song you retry that still won't play stops there.
 */
class Streak(private val most: Int = MOST) {
    /** What the player does about a song that won't play. */
    enum class Then {
        /** On to the next song. */
        NEXT,
        /** Stop here (no next song, or you retried this one). */
        STOP,
        /** Stop: too many in a row. */
        TOO_MANY,
    }

    /** Songs that wouldn't play, in a row. */
    var inARow = 0
        private set
    private var retrying = false

    /** A song wouldn't play ([hasNext]: there's a song after it): what to do. */
    fun failed(hasNext: Boolean): Then {
        inARow++
        val then = when {
            retrying -> Then.STOP
            inARow >= most -> Then.TOO_MANY
            !hasNext -> Then.STOP
            else -> Then.NEXT
        }
        retrying = false
        return then
    }

    /** A song plays: all's well again. */
    fun played() {
        inARow = 0
        retrying = false
    }

    /** You picked a song (or the next one): a fresh start. */
    fun picked() = played()

    /** You asked for this song again: if it still won't play, it stops there (no skipping on). */
    fun retry() {
        retrying = true
    }

    companion object {
        const val MOST = 3
    }
}
