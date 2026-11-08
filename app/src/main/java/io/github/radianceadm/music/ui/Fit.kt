package io.github.radianceadm.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window's shape, as Material's window sizes (android#34; owner, 2026-11-08: "di pa sya optimize for tablet, and sa car panel").
 * Under 600 dp across is a phone held upright: the phone's layout, as it always was. From 600 dp (a tablet, a
 * car's Android stereo, a phone on its side) the tabs go down the side and the pages keep to a width that reads
 * well; wider than tall, Now Playing sits side by side. Decided by the window, not the device, so a tablet in a
 * split screen gets what fits.
 */
object Fit {
    /** Material's medium window: from here, the tabs as a rail at the side. */
    val WIDE = 600.dp
    /** The widest a page of rows gets (Home, Search, Library, a listening party). */
    val PAGE = 840.dp
    /** The widest a page of settings or words gets (Settings, About, the guide). */
    val COLUMN = 640.dp

    fun wide(width: Dp) = width >= WIDE

    /** A tablet on its side, a car's stereo, a phone on its side: Now Playing's cover beside its controls. */
    fun sideBySide(width: Dp, height: Dp) = width >= WIDE && width > height
}

/**
 * A page no wider than [max], in the middle of a wider window. Narrower than [max] (every phone), it's the
 * page as it was: the same width and place. The same composition either way, so a page keeps what you typed
 * when the phone turns.
 */
@Composable
fun Readable(max: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxWidth().fillMaxHeight(), content = content)
    }
}

/** Clear of a notch at the side (a phone or tablet on its side); upright there's none at the sides, so no change. */
@Composable
fun Modifier.sideCutoutPadding(): Modifier = windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
