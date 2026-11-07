package io.github.radianceadm.music.split

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner, 2026-11-07: a song split ahead (the next one, a liked one) on the lightest setting, so the music
 * doesn't skip while the phone is busy; once it's the song playing, the cores set in Settings.
 */
class SplitCoresTest {
    @Test fun aSongNotPlayingIsSplitOnOneCore() {
        assertEquals(1, Splitter.cores(playing = false, warm = false, threads = 4))
        assertEquals(1, Splitter.cores(playing = false, warm = true, threads = 4))
    }

    @Test fun theSongPlayingGetsTheSetCoresFewerWhileWarm() {
        assertEquals(4, Splitter.cores(playing = true, warm = false, threads = 4))
        assertEquals(6, Splitter.cores(playing = true, warm = false, threads = 6))
        assertEquals(Guard.WARM_CORES, Splitter.cores(playing = true, warm = true, threads = 4))
        assertEquals(1, Splitter.cores(playing = true, warm = true, threads = 1))
    }
}
