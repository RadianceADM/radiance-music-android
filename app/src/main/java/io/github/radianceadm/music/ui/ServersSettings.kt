package io.github.radianceadm.music.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.server.Can
import io.github.radianceadm.music.server.Health
import io.github.radianceadm.music.server.Server
import io.github.radianceadm.music.server.Servers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What you can do on a server, in plain words: "Play, add songs, split songs and start parties". */
fun canSay(can: Can): String {
    val more = listOfNotNull("add songs".takeIf { can.add }, "split songs".takeIf { can.split }, "start parties".takeIf { can.host })
    return if (more.isEmpty()) "Play its songs and join its parties" else "Play, " + (if (more.size == 1) more[0] else more.dropLast(1).joinToString(", ") + " and " + more.last())
}

/**
 * Settings → Servers: the Radiance Music Servers this phone joined (each one
 * asked how it is when Settings opens: answering, not answering and why, or
 * this phone removed), and Join a server (an invite's QR or its link).
 */
@Composable
fun ServersGroup(onOpen: (Server) -> Unit, check: Boolean = true) {
    val list by Servers.list.collectAsState()
    val health by Servers.health.collectAsState()
    var joining by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<String?>(null) }
    // each one asked who this phone is now (its name, what you can do), and whether it answers
    LaunchedEffect(list.map { it.base }) {
        if (check) for (s in list) launch(Dispatchers.IO) { runCatching { Servers.refresh(s) } }
    }
    SettingsGroup("Servers") {
        for (s in list) {
            val h = health[s.base]
            SettingsItem(Icons.Rounded.Dns, s.name, serverNote(s, h), arrow = true) { showing = s.base }
        }
        SettingsItem(Icons.Rounded.AddLink, "Join a server", "Scan its invite's QR, or paste the link its owner sent you. Play its songs, split on it, join its parties") { joining = true }
    }
    if (joining) JoinSheet(onOpen = { joining = false; onOpen(it) }, onDone = { joining = false })
    // (left meanwhile: nothing to show)
    val shown = showing?.let { b -> list.firstOrNull { it.base == b } }
    if (shown != null) ServerSheet(shown, health[shown.base], onOpen = { showing = null; onOpen(shown) }, onDone = { showing = null })
}

/** A server's line in Settings: where it is and what you can do, or why it isn't answering. */
fun serverNote(s: Server, h: Health?): String = when (h) {
    is Health.Down -> "Not answering. ${h.why}"
    is Health.Removed -> h.why
    else -> "${s.host} · ${canSay(s.can)}"
}

/**
 * Joining a server: paste the invite's link (or scan its QR), and the server
 * gives this phone its key. Each invite works once, on one phone, for 7 days;
 * what went wrong is said in the server's own words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinSheet(onOpen: (Server) -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Brand.Sheet) {
        JoinPanel(onOpen, onDone)
    }
}

/** The join sheet's inside ([start]: where it begins; the tests' pictures start it anywhere). */
@Composable
fun JoinPanel(onOpen: (Server) -> Unit, onDone: () -> Unit, start: JoinState = JoinState.Typing) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(start) }
    val online by Connectivity.online.collectAsState()
    fun join(text: String) {
        if (Servers.invite(text) == null) {
            state = JoinState.Failed("That isn't an invite. It looks like https://music.example.com/join/ABCD-EFGH-JKLM: paste the whole link, or scan its QR.", offline = false)
            return
        }
        state = JoinState.Joining
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Servers.join(text) } }
            // not joined yet, so it's called by its address ("music.example.com isn't answering…")
            val where = Servers.invite(text)?.base?.substringAfter("://") ?: "The server"
            state = r.fold({ JoinState.Joined(it) }, { JoinState.Failed(Errors.server(it, where), Errors.offline(it)) })
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
        when (val s = state) {
            JoinState.Scanning -> {
                Text("Scan the invite's QR", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("On the server's page, its owner made an invite for you: a link and its QR.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(14.dp)
                QrScanner(onFound = { join(it) }, modifier = Modifier.fillMaxWidth())
                Gap(12.dp)
                FilledTonalButton(onClick = { state = JoinState.Typing }, colors = tonal()) { Labeled(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
            JoinState.Joining -> {
                Text("Join a server", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Gap(16.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Brand.Orange, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Joining…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            is JoinState.Joined -> {
                Text("You joined ${s.server.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    listOfNotNull(s.server.me?.name?.let { "You're $it there." }, canSay(s.server.can) + ". Its songs are in Library, under its name.").joinToString(" "),
                    color = Brand.Dim, style = MaterialTheme.typography.bodyMedium,
                )
                Gap(16.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onOpen(s.server) }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.Rounded.LibraryMusic, "Open its library") }
                    FilledTonalButton(onClick = onDone, colors = tonal()) { Labeled(Icons.Rounded.Check, "Done") }
                }
            }
            else -> {
                Text("Join a server", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("A Radiance Music Server's owner invites you: a link, or a QR on their screen. Each invite works once, on one phone, for 7 days.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(14.dp)
                TextField(
                    value = link, onValueChange = { link = it; if (state is JoinState.Failed) state = JoinState.Typing },
                    placeholder = { Text("https://…/join/ABCD-EFGH-JKLM") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clip.isNullOrBlank()) link = clip.trim()
                        }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { join(link) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (s is JoinState.Failed) {
                    if (s.offline || !online) Problem(s.why, offline = true, onRetry = { join(link) }, modifier = Modifier.padding(top = 4.dp))
                    else Text(s.why, color = Brand.Glow, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                }
                Gap(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { join(link) }, enabled = link.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.AutoMirrored.Rounded.Login, "Join") }
                    FilledTonalButton(onClick = { state = JoinState.Scanning }, colors = tonal()) { Labeled(Icons.Rounded.QrCodeScanner, "Scan its QR") }
                }
            }
        }
    }
}

/** Where joining is. */
sealed class JoinState {
    object Typing : JoinState()
    object Scanning : JoinState()
    object Joining : JoinState()
    data class Joined(val server: Server) : JoinState()
    data class Failed(val why: String, val offline: Boolean) : JoinState()
}

/**
 * A joined server: how it is, who you are there and what its owner lets you
 * do, its library, and Leave (this phone's key stops working there; songs
 * you saved stay).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(s: Server, h: Health?, onOpen: () -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) { ServerPanel(s, h, onOpen, onDone) }
}

/** A joined server's sheet, inside. */
@Composable
fun ServerPanel(s: Server, h: Health?, onOpen: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var couldnt by remember { mutableStateOf<String?>(null) }
    val owner = Servers.ownerOf(s)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(s.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(s.host, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            Gap(10.dp)
            when (h) {
                is Health.Down -> Problem(h.why, h.offline, onRetry = { scope.launch(Dispatchers.IO) { runCatching { Servers.refresh(s) } } })
                is Health.Removed -> Text(h.why, color = Brand.Glow, style = MaterialTheme.typography.bodyMedium)
                else -> s.me?.let { Text(if (it.owner) "It's your server: you can do everything on it." else "You're ${it.name} there.", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Gap(8.dp)
        Can(Icons.Rounded.PlayArrow, "Play its songs and join its parties", true, owner)
        Can(Icons.Rounded.LibraryAdd, "Add songs", s.can.add, owner)
        Can(Icons.AutoMirrored.Rounded.CallSplit, "Split songs", s.can.split, owner)
        Can(Icons.Rounded.Celebration, "Start a listening party", s.can.host, owner)
        Gap(10.dp)
        if (h !is Health.Removed) SheetAction(Icons.Rounded.LibraryMusic, "Open its library") { onOpen() }
        SheetAction(Icons.Rounded.Refresh, "Check again", "Ask it how it is now") { scope.launch(Dispatchers.IO) { runCatching { Servers.refresh(s) } } }
        when {
            busy -> Row(Modifier.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Brand.Orange, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text("Leaving…", color = Color.White)
            }
            couldnt != null -> Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("Couldn't leave it on ${s.name}. ${couldnt.orEmpty()}", color = Brand.Glow, style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { Servers.leave(s, onServer = false); onDone() }, colors = tonal()) { Labeled(Icons.Rounded.DeleteOutline, "Forget it on this phone") }
                    TextButton(onClick = { couldnt = null }) { Labeled(Icons.Rounded.Close, "Cancel", Brand.Dim) }
                }
            }
            leaving -> Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("Leave ${s.name}?", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("This phone's key stops working there; to come back you'd need a new invite. Songs you saved stay on this phone.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            val e = withContext(Dispatchers.IO) { runCatching { Servers.leave(s) }.exceptionOrNull() }
                            busy = false
                            if (e == null) onDone() else couldnt = Errors.server(e, s.name)
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.AutoMirrored.Rounded.Logout, "Leave") }
                    FilledTonalButton(onClick = { leaving = false }, colors = tonal()) { Labeled(Icons.Rounded.Close, "Stay") }
                }
            }
            h is Health.Removed -> SheetAction(Icons.Rounded.DeleteOutline, "Forget this server") { Servers.leave(s, onServer = false); onDone() }
            else -> SheetAction(Icons.AutoMirrored.Rounded.Logout, "Leave this server") { leaving = true }
        }
    }
}

/** One thing you can do there: on, or turned off by its owner. */
@Composable
private fun Can(icon: ImageVector, what: String, on: Boolean, owner: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) icon else Icons.Rounded.Block, null, tint = if (on) Brand.Lossless else Brand.Dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(what, color = if (on) Color.White else Brand.Dim, style = MaterialTheme.typography.bodyLarge)
            if (!on) Text("Turned off for you by $owner", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
        }
        if (on) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.TaskAlt, null, tint = Brand.Lossless.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}
