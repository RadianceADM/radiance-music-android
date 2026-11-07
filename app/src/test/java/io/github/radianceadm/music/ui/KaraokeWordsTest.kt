package io.github.radianceadm.music.ui

import io.github.radianceadm.music.split.Setup
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Karaoke mic does and says while it gets ready (android#30: a friend's "the microphone it didn't work"). */
class KaraokeWordsTest {
    @Test fun aTapWhileKaraokeGetsReadyKeepsItOn() {
        assertEquals(KaraokeTap.TurnOn, karaokeTap(on = false, ready = false))
        assertEquals(KaraokeTap.TurnOn, karaokeTap(on = false, ready = true))
        // the ring going round: a second tap used to switch it off without a word
        assertEquals(KaraokeTap.StaysOn, karaokeTap(on = true, ready = false))
        assertEquals(KaraokeTap.WholeSong, karaokeTap(on = true, ready = true))
    }

    @Test fun theLineSaysWhyWhenTheSplitterStopped() {
        val stopped = Setup.State.Stopped("No internet: it downloads once you're online.", offline = true)
        assertEquals(
            "Karaoke needs the splitter first. No internet: it downloads once you're online.",
            karaokeLine("Karaoke", splitting = null, pct = "0 %", fetchingFrom = null, first = true, queued = true, setup = stopped, problem = null, askServer = null),
        )
    }

    @Test fun theLineSaysHowFarTheSplittersDownloadIs() {
        val getting = Setup.State.Getting(done = 8_000_000, total = 20_000_000)
        assertEquals(
            "Karaoke once the splitter is downloaded · 40 %",
            karaokeLine("Karaoke", splitting = null, pct = "0 %", fetchingFrom = null, first = true, queued = true, setup = getting, problem = null, askServer = null),
        )
    }

    @Test fun theOtherLinesStayAsTheyWere() {
        val ready = Setup.State.Ready
        assertEquals("Karaoke in a moment: splitting · 42 %", karaokeLine("Karaoke", "Splitting", "42 %", null, true, true, ready, null, null))
        assertEquals("Vocals only in a moment: getting ready · 3 %", karaokeLine("Vocals only", null, "3 %", null, true, true, ready, null, null))
        assertEquals("Karaoke once the song before it is split", karaokeLine("Karaoke", null, "0 %", null, false, true, ready, null, null))
        assertEquals("Karaoke: ask Bahay to split it, in the Mixer", karaokeLine("Karaoke", null, "0 %", null, false, false, ready, null, "Bahay"))
    }
}
