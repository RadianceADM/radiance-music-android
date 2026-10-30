package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.clock
import io.github.radianceadm.music.share.Share
import io.github.radianceadm.music.share.StoryCard
import io.github.radianceadm.music.share.StoryVideo
import io.github.radianceadm.music.share.Targets
import io.github.radianceadm.music.source.Lyrics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A lyric line to pick: its words, and when it's sung (seconds; null for lyrics without timing). */
internal data class Pick(val text: String, val start: Double?)

/**
 * Share a song to a story, as Spotify's share screen (owner, 2026-10-31: "gandahan
 * pa naten to pls parang spoti", "pede kaya to kasama yung clip nung sounds"):
 * the story big, Cover or Lyrics (the lines you pick, five at most), its colour
 * (the cover's own, or black, stone, sunset), with the song's sound or as a still
 * picture, and the apps on this phone to send it to, straight to their stories.
 */
@Composable
fun ShareSheet(t: Track, startLines: List<String> = emptyList(), onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ShareScreen(t, startLines, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareScreen(t: Track, startLines: List<String>, onClose: () -> Unit) {
    val context = App.instance
    val scope = rememberCoroutineScope()
    val hub = App.instance.player
    val song = remember(t.key) { (t.file ?: App.instance.library.best(t).file)?.let(Uri::parse) }
    var art by remember(t.key) { mutableStateOf<Bitmap?>(null) }
    var artLoaded by remember(t.key) { mutableStateOf(false) }
    var lines by remember(t.key) { mutableStateOf<List<Pick>?>(null) }
    val picked = remember(t.key) { mutableStateListOf<Int>() }
    var lyricsStyle by remember(t.key) { mutableStateOf(startLines.isNotEmpty()) }
    var tone by remember(t.key) { mutableStateOf<Int?>(null) } // null: the cover's own
    var sound by remember(t.key) { mutableStateOf(song != null) }
    var card by remember(t.key) { mutableStateOf<Bitmap?>(null) }
    var making by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var choosing by remember { mutableStateOf(false) }
    // the video made last, and what it was made of (sent again to another app as it is)
    var made by remember { mutableStateOf<Pair<String, File>?>(null) }

    LaunchedEffect(t.key) {
        art = withContext(Dispatchers.IO) { StoryCard.cover(context, t) }
        artLoaded = true
        val all = withContext(Dispatchers.IO) {
            when (val l = runCatching { Saver.lyrics(context, t) }.getOrDefault(Lyrics.None)) {
                is Lyrics.Timed -> l.lines.map { Pick(it.text.trim(), it.start) }
                is Lyrics.Plain -> l.text.lines().map { Pick(it.trim(), null) }
                else -> emptyList()
            }.filter { it.text.isNotEmpty() && it.text.any(Char::isLetterOrDigit) }
        }
        lines = all
        // a line long-pressed in the lyrics comes picked
        for (s in startLines) all.indexOfFirst { it.text == s.trim() }.takeIf { it >= 0 && it !in picked }?.let { picked.add(it) }
    }
    val swatches = remember(art) { StoryCard.swatches(art) }
    val order = picked.sorted()
    val chosen = if (lyricsStyle) order.mapNotNull { lines?.getOrNull(it)?.text } else emptyList()
    LaunchedEffect(t.key, artLoaded, chosen, tone) {
        if (!artLoaded) return@LaunchedEffect
        card = withContext(Dispatchers.Default) { StoryCard.draw(context, t, art, chosen, tone) }
    }
    // the clip of the song the video takes: the picked lines as they're sung, else 15 s from where you are
    val clip = run {
        val all = lines.orEmpty()
        val timed = chosen.isNotEmpty() && order.all { all.getOrNull(it)?.start != null }
        val here = if (hub.current.value?.key == t.key) hub.position else (t.seconds * 1000L * 0.3).toLong()
        StoryVideo.clip(
            starts = if (timed) order.map { all[it].start!! } else null,
            nextStart = if (timed) all.getOrNull(order.last() + 1)?.start else null,
            fromMs = here,
            songMs = t.seconds * 1000L,
        )
    }
    val mime = if (sound && song != null) "video/mp4" else "image/jpeg"
    val targets = remember(mime) { Targets.available(context, mime) }

    fun go(target: Targets.Target) {
        val c = card ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            val ok = runCatching {
                if (mime == "video/mp4") {
                    val what = listOf(t.key, chosen, tone, clip).toString()
                    val file = made?.takeIf { it.first == what && it.second.exists() }?.second ?: run {
                        making = 0f
                        val frames = withContext(Dispatchers.Default) { StoryVideo.frames(clip) { lit -> StoryCard.draw(context, t, art, chosen, tone, lit) } }
                        StoryVideo.make(context, Share.name(t), song!!, clip, frames) { making = it }.also { made = what to it }
                    }
                    making = null
                    if (target.kind == Targets.Kind.SAVE) withContext(Dispatchers.IO) { Share.saveVideo(context, t, file) }
                    else Targets.send(context, target, Share.uriOf(context, file), mime, Share.text(t), t.title)
                } else {
                    if (target.kind == Targets.Kind.SAVE) withContext(Dispatchers.IO) { Share.save(context, t, c) }
                    else Targets.send(context, target, withContext(Dispatchers.IO) { Share.image(context, t, c) }, mime, Share.text(t), t.title)
                }
            }
            making = null
            ok.onSuccess { if (target.kind == Targets.Kind.SAVE) Toast.makeText(context, if (mime == "video/mp4") "Saved in your gallery (Pictures/Radiance Music), with its sound" else "Saved in your gallery (Pictures/Radiance Music)", Toast.LENGTH_SHORT).show() }
            ok.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) Toast.makeText(context, "Couldn't share it: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val base = tone?.let { Color(it) } ?: art?.let { Color(StoryCard.swatches(it).first()) } ?: Brand.Card
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, Color.Black)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { job?.cancel(); onClose() }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Text("Share", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }
            // the story, as big as fits
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(9f / 16f).shadow(24.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    val c = card
                    if (c != null) Image(c.asImageBitmap(), "The story", Modifier.fillMaxSize()) else CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    if (sound && song != null) {
                        Row(
                            Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("${clock(clip.startMs / 1000)} · ${clip.lengthMs / 1000} s", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (making != null) {
                        Column(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(progress = { making ?: 0f }, color = Color.White, trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Making the video… ${((making ?: 0f) * 100).toInt()} %", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { job?.cancel(); making = null }) { Labeled(Icons.Rounded.Close, "Cancel", Color.White) }
                        }
                    }
                }
            }
            // Cover or Lyrics, and the sound
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Chip("Cover", !lyricsStyle, icon = { on -> Icon(Icons.Rounded.Image, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(16.dp)) }) { lyricsStyle = false }
                Spacer(Modifier.width(8.dp))
                Chip("Lyrics", lyricsStyle, icon = { on -> Icon(if (on && picked.isNotEmpty()) Icons.Rounded.Edit else Icons.Rounded.FormatQuote, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(16.dp)) }) {
                    if (lyricsStyle || picked.isEmpty()) choosing = true
                    lyricsStyle = true
                }
                Spacer(Modifier.width(8.dp))
                Chip(if (sound && song != null) "With sound" else "No sound", sound && song != null, icon = { on -> Icon(if (on) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(16.dp)) }) {
                    if (song == null) Toast.makeText(context, "Like the song first: the sound comes from its saved copy", Toast.LENGTH_LONG).show() else sound = !sound
                }
            }
            // its colour
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(swatches) { i, c ->
                    val on = if (tone == null) i == 0 else tone == c
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                            .padding(if (on) 5.dp else 2.dp).clip(CircleShape).background(Color(c)).clickable { tone = if (i == 0) null else c },
                    )
                }
            }
            // where it goes
            LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(targets, key = { it.id }) { target -> TargetButton(target, enabled = card != null && making == null) { go(target) } }
            }
        }
    }

    if (choosing) {
        BackHandler { choosing = false; if (picked.isEmpty()) lyricsStyle = false }
        LyricPicker(
            lines = lines,
            picked = picked,
            tone = base,
            withSound = sound && song != null,
            onDone = { choosing = false; if (picked.isEmpty()) lyricsStyle = false },
        )
    }
}

/**
 * Picking the lines, as Spotify's (owner, 2026-10-31: "Spotify has 149 chars, and
 * lyrics shouldn't have the circle select box"): the lyrics big on the story's
 * colour; tap a line and it lights up. Lines next to each other, up to
 * [StoryCard.MOST_CHARS] characters; a line away from them starts again from it.
 */
@Composable
internal fun LyricPicker(lines: List<Pick>?, picked: MutableList<Int>, tone: Color, withSound: Boolean, onDone: () -> Unit) {
    val context = App.instance
    val used = picked.sumOf { lines?.getOrNull(it)?.text?.length ?: 0 }
    Box(Modifier.fillMaxSize().background(tone).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.55f))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picked.clear(); onDone() }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Choose lyrics", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "$used/${StoryCard.MOST_CHARS}",
                        color = if (used >= StoryCard.MOST_CHARS - 10) Brand.Glow else Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(
                    Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (picked.isEmpty()) Color.White.copy(alpha = 0.25f) else Color.White)
                        .clickable(enabled = picked.isNotEmpty(), onClick = onDone).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                if (withSound) "Tap the lines to share. The video plays them with the song, each lit as it's sung." else "Tap the lines to share.",
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
            )
            when {
                lines == null -> Note("Looking for lyrics…")
                lines.isEmpty() -> Note("No lyrics for this song: its story has the cover.")
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 40.dp)) {
                    itemsIndexed(lines) { i, line ->
                        val on = i in picked
                        // picked lines share one highlight: round only where it starts and ends
                        val top = if (on && (i - 1) in picked) 0.dp else 14.dp
                        val bottom = if (on && (i + 1) in picked) 0.dp else 14.dp
                        Text(
                            line.text,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.45f),
                            fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                                .background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable {
                                    val msg = pick(picked, i, lines.map { it.text.length })
                                    if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A tap on line [i]: an end of the picked lines comes off; the line just before or
 * after joins them (if the characters allow); any other line starts again from it.
 * What to say when it can't, else null.
 */
internal fun pick(picked: MutableList<Int>, i: Int, lengths: List<Int>): String? {
    val most = StoryCard.MOST_CHARS
    val sorted = picked.sorted()
    when {
        i in picked && (i == sorted.first() || i == sorted.last()) -> picked.remove(i)
        i in picked -> { picked.clear(); picked.add(i) } // from the middle: just this one
        picked.isEmpty() || i == sorted.first() - 1 || i == sorted.last() + 1 -> {
            if (picked.sumOf { lengths[it] } + lengths[i] > most) return if (picked.isEmpty()) "That line is longer than $most characters" else "$most characters at most"
            picked.add(i)
        }
        else -> {
            if (lengths[i] > most) return "That line is longer than $most characters"
            picked.clear(); picked.add(i)
        }
    }
    return null
}

@Composable
private fun Chip(label: String, on: Boolean, icon: (@Composable (Boolean) -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { it(on); Spacer(Modifier.width(6.dp)) }
        Text(label, color = if (on) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A round app icon with its name under it (Save and More drawn plainly). */
@Composable
private fun TargetButton(target: Targets.Target, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            when {
                target.icon != null -> Image(target.icon.asImageBitmap(), null, Modifier.size(54.dp).clip(CircleShape))
                target.kind == Targets.Kind.SAVE -> Icon(Icons.Rounded.Download, null, tint = Color.White)
                target.kind == Targets.Kind.MORE -> Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White)
                else -> Icon(Icons.Rounded.MusicNote, null, tint = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(target.label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
