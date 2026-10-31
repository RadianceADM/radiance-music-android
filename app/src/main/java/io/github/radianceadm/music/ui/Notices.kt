package io.github.radianceadm.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Connectivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Something to tell you (in place of Android's toasts), with what you can do
 * about it: Retry, Connect… or nothing to do. Shown at the top of the screen,
 * one at a time.
 */
data class Notice(val text: String, val action: Action? = null) {
    /** A notice's button: its words, its icon (every button carries one), what it does. */
    class Action(val label: String, val icon: ImageVector, val run: () -> Unit)

    companion object {
        fun retry(run: () -> Unit) = Action("Retry", Icons.Rounded.Refresh, run)

        /** The phone's Internet panel, to connect. */
        fun connect() = Action("Connect", Icons.Rounded.Wifi) { Connectivity.settings(App.instance) }
    }
}

private class Shown(val notice: Notice) : SnackbarVisuals {
    override val message get() = notice.text
    override val actionLabel get() = notice.action?.label
    override val withDismissAction get() = true
    // a notice with something to do stays longer
    override val duration get() = if (notice.action != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Shows [n] here (waits for the ones before it); its button does its thing. */
suspend fun SnackbarHostState.tell(n: Notice) {
    if (showSnackbar(Shown(n)) == SnackbarResult.ActionPerformed) n.action?.run?.invoke()
}

/**
 * The app's notices (App.tell), at the top of the screen, once the start's
 * sunrise (or the splitter's setup) is over and while the app is on screen.
 * (No splash this time, animations off: MainActivity says splashed at once.)
 */
@Composable
fun Notices() {
    val host = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        snapshotFlow { App.instance.splashed }.first { it }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            App.instance.notices.collect { host.tell(it) }
        }
    }
    NoticeHost(host)
}

/** Where [host]'s notices show: at the top, under the status bar. */
@Composable
fun NoticeHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        SnackbarHost(host) { NoticeCard(it) }
    }
}

/** A notice: soft corners, its words, its button (an icon and words), and a ✕ to close it. */
@Composable
private fun NoticeCard(data: SnackbarData) {
    val action = (data.visuals as? Shown)?.notice?.action
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().shadow(14.dp, shape).clip(shape).background(Brand.Card)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(data.visuals.message, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (action != null) TextButton(onClick = { data.performAction() }) { Labeled(action.icon, action.label, Brand.Orange) }
        IconButton(onClick = { data.dismiss() }) { Icon(Icons.Rounded.Close, "Close", tint = Brand.Dim, modifier = Modifier.size(20.dp)) }
    }
}

/**
 * "Offline" while the phone has no internet, a small pill (saved songs still
 * play); "Back online" for a moment when it returns.
 */
@Composable
fun OfflinePill(modifier: Modifier = Modifier) {
    val online by Connectivity.online.collectAsState()
    var back by remember { mutableStateOf(false) }
    var was by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (online && !was) {
            was = true
            back = true
            delay(3000)
            back = false
        } else {
            was = online
            back = false
        }
    }
    AnimatedVisibility(!online || back, modifier.fillMaxWidth(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (online) Brand.Lossless.copy(alpha = 0.16f) else Brand.Card).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (online) Icons.Rounded.Wifi else Icons.Rounded.CloudOff, null, tint = if (online) Brand.Lossless else Brand.Dim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                if (online) {
                    Text("Back online", color = Brand.Lossless, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                } else {
                    Text("Offline", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(" · saved songs still play", color = Brand.Dim, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Why there's nothing here (offline, or what went wrong), and what to do about
 * it: Connect (offline: the phone's Internet panel) and Try again.
 */
@Composable
fun Problem(text: String, offline: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, color: Color = Brand.Dim) {
    val context = LocalContext.current
    val buttons = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White)
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (offline) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        }
        Gap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (offline) FilledTonalButton(onClick = { Connectivity.settings(context) }, colors = buttons) { Labeled(Icons.Rounded.Wifi, "Connect") }
            FilledTonalButton(onClick = onRetry, colors = buttons) { Labeled(Icons.Rounded.Refresh, "Try again") }
        }
    }
}
