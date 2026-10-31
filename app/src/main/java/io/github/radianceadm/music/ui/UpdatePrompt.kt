package io.github.radianceadm.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.radianceadm.music.App
import io.github.radianceadm.music.update.Updates

/**
 * The update pop-up (owner, 2026-10-31: "all of the app doesn't have a modal opening
 * when there's an update avail ... with a remind me later option"). It comes up by
 * itself when a newer version is out, or when one you chose to download is ready:
 * Update now (downloads while music plays) → Restart to update; Remind me later
 * (a day); Skip this version (until the next one). Not over the start's splash or
 * the first-time guide ([shown] false then).
 */
@Composable
fun UpdatePrompt(shown: Boolean) {
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    // "Update now" pressed here: the pop-up follows it through to "Restart to update", even if hidden meanwhile
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(state, shown) {
        if (!shown) return@LaunchedEffect
        when (val s = state) {
            is Updates.State.Available -> if (open || Updates.asks(context, s.release)) open = true
            is Updates.State.Ready -> if (open || following || Updates.asks(context, s.release)) open = true
            is Updates.State.Downloading, is Updates.State.Installing, is Updates.State.Allow, is Updates.State.Failed -> Unit
            else -> open = false // nothing to ask about (up to date, or looking)
        }
    }
    if (!shown || !open) return
    val later = { Updates.state.value.release()?.let { Updates.remindLater(context) }; open = false; following = false }
    val playing by App.instance.player.playing.collectAsState()
    Dialog(onDismissRequest = later, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        UpdateCard(
            state = state,
            playing = playing,
            metered = remember { Updates.metered(context) },
            onUpdate = { following = true; Updates.update(context, it) },
            onRestart = { Updates.restart(context, it) },
            onLater = later,
            onSkip = { Updates.skip(context, it); open = false; following = false },
            onHide = { open = false },
            onCancel = { Updates.cancel(); following = false },
            onAllow = { runCatching { context.startActivity(Updates.allowIntent(context)) } },
            onRetry = { r -> r?.let { Updates.update(context, it) } ?: Updates.check(context) },
        )
    }
}

private fun Updates.State.release() = when (this) {
    is Updates.State.Available -> release
    is Updates.State.Ready -> release
    is Updates.State.Downloading -> release
    is Updates.State.Installing -> release
    is Updates.State.Allow -> release
    is Updates.State.Failed -> release
    else -> null
}

/** The pop-up's card for each step (drawn alone in the screenshots). */
@Composable
fun UpdateCard(
    state: Updates.State,
    playing: Boolean,
    metered: Boolean,
    onUpdate: (Updates.Release) -> Unit,
    onRestart: (Updates.Release) -> Unit,
    onLater: () -> Unit,
    onSkip: (Updates.Release) -> Unit,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    onAllow: () -> Unit,
    onRetry: (Updates.Release?) -> Unit,
) {
    Surface(
        color = Brand.Sheet, contentColor = Color.White, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 440.dp).fillMaxWidth(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).animateContentSize()) {
            when (state) {
                is Updates.State.Available -> {
                    val r = state.release
                    Head(Icons.Rounded.SystemUpdate, "Radiance Music ${r.version} is out", state.note ?: "The newest open beta.")
                    Notes(r.notes)
                    Spacer(Modifier.height(20.dp))
                    Main(Icons.Rounded.SystemUpdate, "Update now (${mb(r.size)})") { onUpdate(r) }
                    Small(if (metered) "You're on mobile data: Wi-Fi may be cheaper for this. Music goes on playing while it downloads." else "Music goes on playing while it downloads.")
                    Spacer(Modifier.height(8.dp))
                    Second(Icons.Rounded.Snooze, "Remind me later", onLater)
                    Quiet(Icons.Rounded.NotificationsOff, "Skip this version") { onSkip(r) }
                }
                is Updates.State.Downloading -> {
                    val r = state.release
                    Head(Icons.Rounded.SystemUpdate, "Downloading ${r.version}…", "${mb((r.size * state.done).toLong())} of ${mb(r.size)}. Music goes on playing; you can hide this, it goes on downloading.")
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { state.done }, modifier = Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Line)
                    Spacer(Modifier.height(16.dp))
                    Second(Icons.Rounded.KeyboardArrowDown, "Hide", onHide)
                    Quiet(Icons.Rounded.Close, "Cancel the download", onCancel)
                }
                is Updates.State.Ready -> {
                    val r = state.release
                    Head(Icons.Rounded.RestartAlt, "Radiance Music ${r.version} is ready", state.note ?: "Downloaded and checked. Restarting closes the app for a moment${if (playing) " and stops the music" else ""}; a notification opens the new version.")
                    Spacer(Modifier.height(20.dp))
                    Main(Icons.Rounded.RestartAlt, "Restart to update") { onRestart(r) }
                    Spacer(Modifier.height(8.dp))
                    Second(Icons.Rounded.Snooze, "Remind me later", onLater)
                    Quiet(Icons.Rounded.NotificationsOff, "Skip this version") { onSkip(r) }
                }
                is Updates.State.Installing -> {
                    Head(Icons.Rounded.RestartAlt, "Updating to ${state.release.version}…", "If Android asks, tap Update. The app closes while it updates.")
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Line)
                    Spacer(Modifier.height(16.dp))
                    Second(Icons.Rounded.Refresh, "Ask again") { onRestart(state.release) }
                    Quiet(Icons.Rounded.KeyboardArrowDown, "Hide", onHide)
                }
                is Updates.State.Allow -> {
                    Head(Icons.Rounded.Settings, "Allow Radiance Music to update itself", "Android asks once: turn on \"Allow from this source\", then come back. The update goes on by itself.")
                    Spacer(Modifier.height(20.dp))
                    Main(Icons.Rounded.Settings, "Open the setting", onAllow)
                    Spacer(Modifier.height(8.dp))
                    Second(Icons.Rounded.Snooze, "Remind me later", onLater)
                }
                is Updates.State.Failed -> {
                    Head(Icons.Rounded.SystemUpdate, state.message, "Nothing changed: you're still on the version you had.")
                    Spacer(Modifier.height(20.dp))
                    Main(Icons.Rounded.Refresh, "Try again") { onRetry(state.release) }
                    Spacer(Modifier.height(8.dp))
                    Second(Icons.Rounded.Snooze, "Remind me later", onLater)
                }
                else -> Unit
            }
        }
    }
}

/** The step's icon in a soft orange circle, its title and what it means. */
@Composable
private fun Head(icon: ImageVector, title: String, text: String) {
    Box(Modifier.size(52.dp).background(Brand.Orange.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Brand.Orange, modifier = Modifier.size(28.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(text, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Main(icon: ImageVector, text: String, onClick: () -> Unit) =
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Labeled(icon, text)
    }

@Composable
private fun Second(icon: ImageVector, text: String, onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Card, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Labeled(icon, text)
    }

@Composable
private fun Quiet(icon: ImageVector, text: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onClick) { Labeled(icon, text, Brand.Dim) }
    }

@Composable
private fun Small(text: String) =
    Text(text, color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
