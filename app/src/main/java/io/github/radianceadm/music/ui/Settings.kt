package io.github.radianceadm.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.split.Splits
import io.github.radianceadm.music.update.Updates
import kotlin.math.roundToInt

/**
 * Settings (owner, 2026-10-31: "need paren naten ng settings sa app, dun mo ilagay
 * yung get or received songs"): sharing songs by QR (send, get, receive here), how
 * many cores splitting may use, the look (True Black or Stone, as the computer app),
 * updates, and About.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, onGuide: () -> Unit = {}) {
    var about by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !about, onBack = onClose)
    if (about) {
        AboutScreen(onClose = { about = false })
        return
    }
    val context = LocalContext.current
    val app = App.instance
    val update by Updates.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Brand.Night).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        Text("Settings", style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        (update as? Updates.State.Available)?.let { s ->
            SettingsGroup(null) {
                SettingsItem(Icons.Rounded.SystemUpdate, "Radiance Music ${s.release.version} is out", "Update it here, over the air") { about = true }
            }
        }
        SettingsGroup("Share songs") {
            SettingsItem(Icons.Rounded.QrCode2, "Send songs", "Shows a QR: another phone scans it and takes your songs, with their parts, lyrics and covers") {
                app.shareOpen.value = App.ShareAsk(null)
            }
            SettingsItem(Icons.Rounded.QrCodeScanner, "Get songs", "Scan the QR on another phone, or on your computer (Share Songs with a Phone there)") {
                app.shareOpen.value = App.ShareAsk(null, scan = true)
            }
            SettingsItem(Icons.Rounded.Download, "Receive songs here", "Shows a QR for another phone or your computer to send songs to this one, on the same Wi-Fi") {
                app.share.receive(direct = false)
                app.shareOpen.value = App.ShareAsk(null)
            }
        }
        SettingsGroup("Splitting") { Cores() }
        SettingsGroup("Look") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LookPill("True Black", Color(0xFF000000), !Looks.stone, Modifier.weight(1f)) { Looks.set(context, false) }
                LookPill("Stone", Color(0xFF1C1917), Looks.stone, Modifier.weight(1f)) { Looks.set(context, true) }
            }
        }
        SettingsGroup(null) {
            SettingsItem(Icons.Rounded.School, "How to use it", "The five things to know, as on the first day", arrow = true) { onGuide() }
            SettingsItem(Icons.Rounded.Info, "About Radiance Music", "${Updates.current(context)}, open beta. Updates, what's new, and where to tell us what breaks", arrow = true) { about = true }
        }
    }
}

/** How many of the phone's cores splitting may use (as in Now Playing's split panel). */
@Composable
private fun Cores() {
    val splits = App.instance.splits
    var cores by remember { mutableIntStateOf(splits.cores) }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cores", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp))
            Slider(
                value = cores.toFloat(),
                onValueChange = { cores = it.roundToInt().coerceIn(1, Splits.MAX_CORES) },
                onValueChangeFinished = { splits.cores = cores },
                valueRange = 1f..Splits.MAX_CORES.toFloat(),
                steps = (Splits.MAX_CORES - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                modifier = Modifier.weight(1f),
            )
            Text("$cores of ${Splits.MAX_CORES}", color = Brand.Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp))
        }
        Text("Songs split here, on this phone. More cores split sooner; fewer keep the phone cooler.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A titled group of rows on a rounded card (Settings and About). */
@Composable
fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) Text(title, color = Brand.Dim, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 22.dp, top = 26.dp, bottom = 8.dp))
    else Spacer(Modifier.size(22.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brand.Card), content = content)
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, note: String, arrow: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Brand.Orange.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.Orange, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(note, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
        }
        if (arrow) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Brand.Dim)
    }
}

@Composable
private fun LookPill(name: String, swatch: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)).padding(1.5.dp).clip(CircleShape).background(swatch))
        Spacer(Modifier.width(8.dp))
        Text(name, color = if (on) Color.White else Brand.Dim, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}
