package io.github.radianceadm.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.clock

/** A song's cover (or a note while there's none), with soft corners. */
@Composable
fun Artwork(url: String?, size: Dp, corner: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(Brand.Card), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Brand.Dim.copy(alpha = 0.5f), modifier = Modifier.size(size / 2.6f))
        if (!url.isNullOrEmpty()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    }
}

/** "Lossless · FLAC" in green, or "Opus" quietly. */
@Composable
fun Quality(t: Track) {
    val lossless = t.lossless || t.format.contains("FLAC")
    Text(
        if (lossless) "Lossless · ${t.format.ifEmpty { "FLAC" }}" else t.format.ifEmpty { "Opus" },
        style = MaterialTheme.typography.labelSmall,
        color = if (lossless) Brand.Lossless else Brand.Dim.copy(alpha = 0.8f),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (lossless) Brand.Lossless.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Heart(t: Track) {
    val liked by App.instance.library.liked.collectAsState()
    val on = liked.any { it.key == t.key }
    IconButton(onClick = { if (on) App.instance.unlike(t) else App.instance.like(t) }) {
        Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (on) "Remove from Liked" else "Like (and save)", tint = if (on) Brand.Heart else Brand.Dim)
    }
}

/** A song in a list: tap plays from it; hold for Play Next / Add to Queue. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: Track, onPlay: () -> Unit, highlight: Boolean = false, trailing: @Composable () -> Unit = { Heart(t) }) {
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val hub = App.instance.player
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (highlight) Brand.Orange.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.art, 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.byline(), style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.seconds > 0) Text(clock(t.seconds.toLong()), style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, modifier = Modifier.padding(start = 8.dp))
        trailing()
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Play Next") }, onClick = { hub.playNext(t); menu = false })
            DropdownMenuItem(text = { Text("Add to Queue") }, onClick = { hub.enqueue(t); menu = false })
            DropdownMenuItem(text = { Text("Share…") }, onClick = { sharing = true; menu = false })
            if (t.file != null || App.instance.library.best(t).file != null) DropdownMenuItem(text = { Text("Send to a phone…") }, onClick = { App.instance.shareOpen.value = App.ShareAsk(listOf(App.instance.library.best(t))); menu = false })
        }
        if (sharing) ShareSheet(t, onDismiss = { sharing = false })
    }
}

/** A song as a big cover with its title under it (Home's rows). */
@Composable
fun Card(t: Track, onPlay: () -> Unit, size: Dp = 150.dp) {
    // (the cover rounds itself: a clip on the whole card cut the corner off the artist's line)
    Column(Modifier.width(size).combinedClickableSimple(onPlay).padding(bottom = 2.dp)) {
        Artwork(t.art, size, corner = 16.dp)
        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimple(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
fun Note(text: String) {
    Text(text, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.size(h))

/** A button's inside: its icon, then its words (owner, 2026-10-31: every button carries an icon). */
@Composable
fun Labeled(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = Color.Unspecified) {
    androidx.compose.material3.Icon(icon, null, tint = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color, modifier = Modifier.size(18.dp))
    Spacer(Modifier.size(8.dp))
    Text(text, color = color)
}
