package io.github.radianceadm.music.ui

import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.radianceadm.music.share.Logo
import io.github.radianceadm.music.update.Updates

/**
 * About Radiance Music (owner, 2026-10-31: "about page / sa android wala???"), as
 * the computer app's About: what it is, its version (an open beta), updates over
 * the air, what's new, where to tell us what breaks, and who made what it uses.
 */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val version = remember { Updates.current(context) }
    val icon = remember { runCatching { context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap(288, 288).asImageBitmap() }.getOrNull() }
    val open = { url: String -> openLink(context, url) }
    LaunchedEffect(Unit) { if (Updates.state.value is Updates.State.Unknown) Updates.check(context) }
    Column(
        Modifier.fillMaxSize().background(Brand.Night).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        }
        icon?.let { Image(it, null, Modifier.size(104.dp).clip(RoundedCornerShape(26.dp))) }
        Spacer(Modifier.height(16.dp))
        Text("Radiance Music", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Text("Radiance Innovation Centers", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(version, Color.White.copy(alpha = 0.1f), Color.White)
            Pill("Open beta", Brand.Orange.copy(alpha = 0.18f), Brand.Glow)
        }
        Text(
            "Search any song and play it, lossless first. Like it to keep it on your phone with its cover, lyrics and music video, " +
                "and split it into drums, bass, other and vocals right here.\n\nIt's an open beta: some things may not work yet, and it changes often.",
            color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
        )
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup("Updates") { UpdatePanel() }
            WhatsNew(context)
            SettingsGroup("Tell us what breaks") {
                SettingsItem(Icons.Rounded.BugReport, "Report a problem", "On GitHub: what you did, and what happened") { open("https://github.com/RadianceADM/radiance-music-android/issues") }
                SettingsItem(Icons.Rounded.MailOutline, "Email us", "hello@radianceph.com") { open("mailto:hello@radianceph.com?subject=Radiance%20Music%20$version%20(Android)") }
            }
            SettingsGroup("More") {
                SettingsItem(Icons.Rounded.Public, "music.radianceph.com", "The app for your computer, and what's coming") { open("https://music.radianceph.com") }
                SettingsItem(Icons.Rounded.Code, "Free and open source", "GNU GPL, version 3 or later: the code is on GitHub") { open("https://github.com/RadianceADM/radiance-music-android") }
            }
            SettingsGroup("Music and lyrics from") {
                Credits(listOf("Internet Archive" to "https://archive.org", "YouTube Music" to "https://music.youtube.com", "LRCLIB" to "https://lrclib.net"))
            }
            SettingsGroup("Built with") {
                Credits(listOf(
                    "NewPipe Extractor" to "https://github.com/TeamNewPipe/NewPipeExtractor",
                    "Media3" to "https://developer.android.com/media/media3",
                    "Demucs" to "https://github.com/facebookresearch/demucs",
                    "ONNX Runtime" to "https://onnxruntime.ai",
                    "OkHttp" to "https://square.github.io/okhttp",
                    "Coil" to "https://coil-kt.github.io/coil",
                    "ZXing" to "https://github.com/zxing/zxing",
                ))
            }
        }
        Spacer(Modifier.height(28.dp))
        Canvas(Modifier.width((Logo.widthFor(44f)).dp).height(44.dp)) {
            drawIntoCanvas { Logo.draw(it.nativeCanvas, 0f, 0f, size.height) }
        }
        Spacer(Modifier.height(12.dp))
        Text("© 2026 Radiance Innovation Centers", color = Brand.Dim, style = MaterialTheme.typography.labelMedium)
        Text(
            "Saving songs and videos from YouTube goes against YouTube's terms of service: your choice to make, for your own use.",
            color = Brand.Dim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
        )
    }
}

/** The updates card: up to date, a newer version to install, or how far its download is. */
@Composable
fun UpdatePanel() {
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    // back from Android's "Install unknown apps" page
    LifecycleResumeEffect(Unit) { Updates.resumed(context); onPauseOrDispose { } }
    Column(Modifier.fillMaxWidth().padding(16.dp).animateContentSize()) {
        when (val s = state) {
            Updates.State.Unknown, Updates.State.Checking -> {
                Text("Looking for updates…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Line)
            }
            is Updates.State.UpToDate -> {
                Text("You have the newest version", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Radiance Music ${s.version}. It looks for updates by itself, too. When it updates, it adds one to our count of updates (only the version, nothing about you).", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Updates.check(context) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Labeled(Icons.Rounded.Refresh, "Look again", Brand.Orange) }
            }
            is Updates.State.Available -> {
                Text("Radiance Music ${s.release.version} is out", color = Color.White, style = MaterialTheme.typography.titleMedium)
                s.note?.let { Text(it, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium) }
                Notes(s.release.notes)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { Updates.update(context, s.release) }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) {
                    Labeled(Icons.Rounded.SystemUpdate, "Update now (${mb(s.release.size)})")
                }
                if (Updates.metered(context)) Text("You're on mobile data: Wi-Fi may be cheaper for this.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
            is Updates.State.Downloading -> {
                Text("Downloading ${s.release.version}…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("${mb((s.release.size * s.done).toLong())} of ${mb(s.release.size)}. Keep the app open; music goes on playing.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { s.done }, modifier = Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Line)
                TextButton(onClick = { Updates.cancel() }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Labeled(Icons.Rounded.Close, "Cancel", Brand.Orange) }
            }
            is Updates.State.Ready -> {
                Text("Radiance Music ${s.release.version} is ready", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(s.note ?: "Downloaded and checked. Restarting closes the app for a moment; a notification opens the new version.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { Updates.restart(context, s.release) }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) {
                    Labeled(Icons.Rounded.RestartAlt, "Restart to update")
                }
            }
            is Updates.State.Installing -> {
                Text("Updating to ${s.release.version}…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("If Android asks, tap Update. The app closes while it updates; a notification opens the new version.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Updates.restart(context, s.release) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Labeled(Icons.Rounded.Refresh, "Ask again", Brand.Orange) }
            }
            is Updates.State.Allow -> {
                Text("Allow Radiance Music to install its updates", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Android asks once: turn on \"Allow from this source\", then come back here. The update goes on by itself.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { runCatching { context.startActivity(Updates.allowIntent(context)) } }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.Rounded.Settings, "Open the setting") }
            }
            is Updates.State.Failed -> {
                Text(s.message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { s.release?.let { Updates.update(context, it) } ?: Updates.check(context) }) { Labeled(Icons.Rounded.Refresh, "Try again") }
                s.release?.page?.takeIf { it.isNotEmpty() }?.let { page ->
                    TextButton(onClick = { openLink(context, page) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Labeled(Icons.AutoMirrored.Rounded.OpenInNew, "Get it from GitHub instead", Brand.Orange) }
                }
            }
        }
    }
}

/** A release's notes, the first few lines (GitHub's markdown, plainly). */
@Composable
internal fun Notes(notes: String) {
    val lines = notes.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.take(4)
    if (lines.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    lines.forEach { Bullet(it) }
}

/** What's new in this version, from the changelog that comes with the app; earlier ones on a tap. */
@Composable
private fun WhatsNew(context: Context) {
    val versions = remember { runCatching { context.assets.open("CHANGELOG.md").bufferedReader().readText() }.getOrNull()?.let(::changelog).orEmpty() }
    if (versions.isEmpty()) return
    var more by remember { mutableStateOf(false) }
    SettingsGroup("What's new in ${versions[0].first}") {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            versions[0].second.forEach { Bullet(it) }
            if (more) versions.drop(1).take(4).forEach { (v, items) ->
                Text(v, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                items.forEach { Bullet(it) }
            }
            if (versions.size > 1) TextButton(onClick = { more = !more }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Labeled(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (more) "Fewer" else "Earlier versions", Brand.Orange)
            }
        }
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•", color = Brand.Orange, modifier = Modifier.width(16.dp))
        Text(bold(text), color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Credits(names: List<Pair<String, String>>) {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 6.dp)) {
        names.forEach { (name, url) ->
            Text(name, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { openLink(context, url) }.padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color) {
    Text(text, color = ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 12.dp, vertical = 5.dp))
}

/** The changelog's versions, newest first: each with its items (a wrapped line joins its item). */
fun changelog(text: String): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, List<String>>>()
    var version: String? = null
    val items = mutableListOf<String>()
    fun flush() {
        version?.let { out += it to items.toList() }
        items.clear()
    }
    for (line in text.lines()) {
        val t = line.trim()
        when {
            line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
            t.startsWith("- ") -> items += t.removePrefix("- ")
            t.isNotEmpty() && line.startsWith(" ") && items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + t
        }
    }
    flush()
    return out
}

/** **bold** as bold, the rest as it is. */
private fun bold(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) } else append(part)
    }
}

internal fun mb(bytes: Long) = if (bytes <= 0) "size unknown" else "${(bytes + 500_000) / 1_000_000} MB"

fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
