package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.share.Share
import io.github.radianceadm.music.share.StoryCard
import io.github.radianceadm.music.source.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share a song as a story: its card (cover, title, who sings it), with up to
 * five lines of its lyrics if you pick them, to Instagram, Facebook,
 * Messenger… (Android's share menu), or saved to the phone's photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(t: Track, startLines: List<String> = emptyList(), onDismiss: () -> Unit) {
    val context = App.instance
    val scope = rememberCoroutineScope()
    var art by remember(t.key) { mutableStateOf<Bitmap?>(null) }
    var artLoaded by remember(t.key) { mutableStateOf(false) }
    var lines by remember(t.key) { mutableStateOf<List<String>?>(null) }
    val picked = remember(t.key) { mutableStateListOf<Int>() }
    var card by remember(t.key) { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(t.key) {
        art = withContext(Dispatchers.IO) { StoryCard.cover(context, t) }
        artLoaded = true
        val all = withContext(Dispatchers.IO) {
            when (val l = runCatching { Saver.lyrics(context, t) }.getOrDefault(Lyrics.None)) {
                is Lyrics.Timed -> l.lines.map { it.text }
                is Lyrics.Plain -> l.text.lines()
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
        }
        lines = all
        // a line long-pressed in the lyrics comes picked
        for (s in startLines) all.indexOf(s.trim()).takeIf { it >= 0 && it !in picked }?.let { picked.add(it) }
    }
    // the card again whenever the lines picked change
    val chosen = picked.sorted().mapNotNull { lines?.getOrNull(it) }
    LaunchedEffect(t.key, artLoaded, chosen) {
        if (!artLoaded) return@LaunchedEffect
        card = withContext(Dispatchers.Default) { StoryCard.draw(context, t, art, chosen) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Brand.Navy) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            Text("Share", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("A story with its cover, and the lines you pick. Instagram and Facebook stories are in the share menu.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            Gap(12.dp)
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.width(150.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    val c = card
                    if (c != null) Image(c.asImageBitmap(), "The story", Modifier.fillMaxWidth()) else CircularProgressIndicator(color = Brand.Orange, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lyrics on it", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    val l = lines
                    when {
                        l == null -> Text("Looking for lyrics…", color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
                        l.isEmpty() -> Text("No lyrics for this song. The story has its cover.", color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
                        else -> {
                            Text("Pick up to ${StoryCard.MOST_LINES} lines${if (picked.isNotEmpty()) " · ${picked.size} picked" else ""}", color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
                            if (picked.isNotEmpty()) TextButton(onClick = { picked.clear() }) { Text("No lyrics", color = Brand.Orange) }
                        }
                    }
                }
            }
            val l = lines
            if (!l.isNullOrEmpty()) {
                Gap(10.dp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f))) {
                    itemsIndexed(l) { i, line ->
                        val on = i in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (on) picked.remove(i)
                                else if (picked.size < StoryCard.MOST_LINES) picked.add(i)
                                else Toast.makeText(context, "${StoryCard.MOST_LINES} lines at most", Toast.LENGTH_SHORT).show()
                            }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) Brand.Orange else Brand.Dim, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(line, color = if (on) Color.White else Color.White.copy(alpha = 0.75f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { card?.let { Share.send(context, t, it) } },
                    enabled = card != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange),
                ) { Text("Share") }
                FilledTonalButton(
                    onClick = {
                        val c = card ?: return@FilledTonalButton
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { Share.save(context, t, c) } }
                            busy = false
                            Toast.makeText(context, ok.fold({ "Saved in Photos (Pictures/Radiance Music)" }, { "Couldn't save it: ${it.message}" }), Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = card != null && !busy,
                ) { Text("Save to Photos") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
