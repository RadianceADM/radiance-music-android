package io.github.radianceadm.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.share.Session
import io.github.radianceadm.music.share.ShareService

/**
 * Songs to another phone or the computer, by QR: Send shows a QR for the
 * songs (with their parts, lyrics, cover, and videos if you like); Scan reads
 * another's QR, to take its songs or to send yours. Nothing the other side
 * has already is sent again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSongsSheet(songs: List<Track>?, scan: Boolean = false, onDismiss: () -> Unit) {
    val share = App.instance.share
    val state by share.state.collectAsState()
    ModalBottomSheet(
        onDismissRequest = { if (state is Session.State.Idle || state is Session.State.Done || state is Session.State.Failed) { share.close(); onDismiss() } else onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Sheet,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            when (val s = state) {
                is Session.State.Idle -> Start(songs, scan)
                is Session.State.Opening -> Waiting("Getting the QR ready…") { share.close() }
                is Session.State.Showing -> Showing(s) { share.close() }
                is Session.State.Connecting -> Waiting("Connecting to ${s.to}…") { share.cancel() }
                is Session.State.Choose -> Choose(s)
                is Session.State.Moving -> Moving(s) { share.cancel() }
                is Session.State.Done -> Finished(s.message, false) { share.close(); onDismiss() }
                is Session.State.Failed -> Finished(s.message, true) { share.close() }
            }
        }
    }
}

@Composable
private fun Title(text: String, note: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Color.White)
    note?.let { Text(it, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium) }
    Gap(14.dp)
}

/** The permission the phone's own network needs (Android 13 and later: Nearby devices; before: location). */
private fun directPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

@Composable
private fun Start(songs: List<Track>?, scan: Boolean) {
    val app = App.instance
    val share = app.share
    val context = LocalContext.current
    val liked by app.library.liked.collectAsState()
    var scanning by remember { mutableStateOf(scan) }
    var videos by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf(true) }
    val all = (songs ?: liked).filter { it.file != null }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        share.send(all, videos, describe(songs, all.size), direct && ok)
    }
    if (scanning) {
        Title("Scan a QR", "From Radiance Music on another phone or the computer (Share songs there).")
        QrScanner(onFound = { scanning = false; share.scanned(it) }, modifier = Modifier.fillMaxWidth())
        Gap(12.dp)
        FilledTonalButton(onClick = { scanning = false }) { Text("Back") }
        return
    }
    Title("Share songs", "Straight to another phone or the computer, with their parts: nothing to download or split there. What it has already isn't sent again.")
    Choice(Icons.Rounded.QrCode2, "Send ${describe(songs, all.size)}", "Shows a QR. The other phone scans it and takes them.", null)
    Toggle("With music videos", "Bigger: each about 30 to 60 MB", videos) { videos = it }
    Toggle("Also without Wi-Fi", "The other phone can join this phone's own Wi-Fi network", direct) { direct = it }
    Gap(6.dp)
    Button(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, directPermission()) == PackageManager.PERMISSION_GRANTED
            if (direct && !granted) ask.launch(directPermission()) else share.send(all, videos, describe(songs, all.size), direct)
        },
        enabled = all.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange),
    ) { Text("Show the QR") }
    if (all.isEmpty()) Text("Only saved songs can be sent; like a song to save it.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
    Gap(22.dp)
    Choice(Icons.Rounded.QrCodeScanner, "Get songs", "Scan the QR on the other phone or the computer.") { scanning = true }
    Gap(6.dp)
    FilledTonalButton(onClick = { scanning = true }) { Text("Scan a QR") }
}

private fun describe(songs: List<Track>?, n: Int) = when {
    songs != null && songs.size == 1 -> "“${songs[0].title}”"
    songs != null -> "$n songs"
    else -> "Liked Songs ($n)"
}

@Composable
private fun Choice(icon: ImageVector, title: String, note: String, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Brand.Orange, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(note, color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { set(!on) }.padding(start = 44.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(note, color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
        }
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Brand.Orange))
    }
}

@Composable
private fun Waiting(what: String, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Brand.Orange, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(what, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
    Gap(16.dp)
    FilledTonalButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun Showing(s: Session.State.Showing, onStop: () -> Unit) {
    val t = s.ticket
    Title(if (t.mode == "get") "Scan to take ${s.what.removePrefix("Send ")}" else "Scan to send songs here", "With Radiance Music on the other phone: Settings → Get songs. It takes only what it hasn't got.")
    QrCode(t.toUri(), Modifier.fillMaxWidth().padding(horizontal = 18.dp))
    Gap(12.dp)
    val ways = listOfNotNull(t.host?.let { "this Wi-Fi" }, t.group?.let { "this phone's own Wi-Fi (${it.removePrefix("DIRECT-rm-")})" })
    Text("The other phone connects over ${ways.joinToString(" or ")}.", color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
    s.progress?.let { p ->
        Gap(10.dp)
        Text("Sending: ${ShareService.describe(p)}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    Gap(14.dp)
    FilledTonalButton(onClick = onStop) { Text("Stop sharing") }
}

@Composable
private fun Choose(s: Session.State.Choose) {
    val app = App.instance
    val liked by app.library.liked.collectAsState()
    var videos by remember { mutableStateOf(false) }
    val all = liked.filter { it.file != null }
    Title("${s.ticket.name} asks for songs", "Send it your Liked Songs (${all.size}). It takes only what it hasn't got.")
    Toggle("With music videos", "Bigger: each about 30 to 60 MB", videos) { videos = it }
    Gap(8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { app.share.sendTo(s.ticket, all, videos) }, enabled = all.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Text("Send") }
        FilledTonalButton(onClick = { app.share.close() }) { Text("Cancel") }
    }
}

@Composable
private fun Moving(s: Session.State.Moving, onCancel: () -> Unit) {
    val p = s.progress
    Title(if (s.receiving) "Getting songs from ${s.to}" else "Sending songs to ${s.to}", ShareService.describe(p).ifEmpty { "Comparing what each has…" })
    LinearProgressIndicator(
        progress = { if (p.totalBytes > 0) (p.bytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f },
        color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth(),
    )
    Gap(8.dp)
    Text("It goes on with the screen off. Keep the two close together.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
    Gap(14.dp)
    FilledTonalButton(onClick = onCancel) { Text("Stop") }
}

@Composable
private fun Finished(message: String, failed: Boolean, onOk: () -> Unit) {
    Title(if (failed) "Couldn't share" else "Done", message)
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Transparent)) {
        Button(onClick = onOk, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Text(if (failed) "Try again" else "OK") }
    }
}
