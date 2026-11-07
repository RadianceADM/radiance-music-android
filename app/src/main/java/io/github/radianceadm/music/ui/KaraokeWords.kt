package io.github.radianceadm.music.ui

import io.github.radianceadm.music.split.Setup

/** What a tap on the Karaoke mic does (android#30). */
enum class KaraokeTap { TurnOn, WholeSong, StaysOn }

/**
 * A tap turns karaoke on, or back to the whole song once it's playing. While it's getting ready (the ring going
 * round the mic) a tap leaves it on: it switched it off without a word before, and a friend's report said the mic
 * "didn't work". The whole song is a tap away again once karaoke plays, or Whole song in the Mixer.
 */
fun karaokeTap(on: Boolean, ready: Boolean): KaraokeTap = when {
    !on -> KaraokeTap.TurnOn
    ready -> KaraokeTap.WholeSong
    else -> KaraokeTap.StaysOn
}

/**
 * The line under the mic while karaoke (or Vocals only) waits: [splitting] the split's step here, [fetchingFrom]
 * a server sending its parts, [first] next in the split queue, [queued] in it at all, [askServer] a server to ask
 * in the Mixer. The splitter stopped (offline, or its download failed) says why, with Try Again beside it.
 */
fun karaokeLine(mode: String, splitting: String?, pct: String, fetchingFrom: String?, first: Boolean, queued: Boolean, setup: Setup.State, problem: String?, askServer: String?): String = when {
    splitting != null -> "$mode in a moment: ${splitting.lowercase()} · $pct"
    fetchingFrom != null -> "$mode in a moment: getting its parts from $fetchingFrom · $pct"
    setup is Setup.State.Stopped && askServer == null -> "$mode needs the splitter first. ${setup.why}"
    first && setup == Setup.State.Ready -> "$mode in a moment: getting ready · $pct"
    queued && setup == Setup.State.Ready -> "$mode once the song before it is split"
    queued && setup is Setup.State.Getting -> "$mode once the splitter is downloaded · ${(setup.done * 100 / setup.total.coerceAtLeast(1)).toInt()} %"
    queued -> "$mode once the splitter is ready"
    problem != null -> problem
    askServer != null -> "$mode: ask $askServer to split it, in the Mixer"
    else -> "$mode: getting ready…"
}
