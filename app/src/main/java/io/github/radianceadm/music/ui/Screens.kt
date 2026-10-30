package io.github.radianceadm.music.ui

import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.foundation.background
import io.github.radianceadm.music.update.Updates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.matches
import io.github.radianceadm.music.source.Archive
import io.github.radianceadm.music.source.YouTube
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Searching as you type waits this long after the last key (as every search here). */
const val SEARCH_DEBOUNCE_MS = 300L

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(pad: PaddingValues, onSearch: () -> Unit, onSettings: () -> Unit) {
    val lib = App.instance.library
    val liked by lib.liked.collectAsState()
    val history by lib.history.collectAsState()
    val hub = App.instance.player
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp)) {
        item { PageTitle("Home", onSettings) }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Brand.Card, Color(0xFF5C2A12), Brand.Orange.copy(alpha = 0.8f))))
                    .padding(24.dp),
            ) {
                Text(greeting(), style = MaterialTheme.typography.displaySmall, color = Color.White)
                Gap(8.dp)
                Text(
                    if (liked.isEmpty()) "Search for a song, like it and it's saved on your phone." else "Your mix: ${liked.size} songs you liked, shuffled.",
                    color = Color.White.copy(alpha = 0.85f),
                )
                Gap(18.dp)
                if (liked.isEmpty()) {
                    Button(onClick = onSearch) { Labeled(Icons.Rounded.Search, "Search for music") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { hub.play(liked.shuffled(), 0) }) { Labeled(Icons.Rounded.Shuffle, "Play your mix") }
                        FilledTonalButton(onClick = { hub.play(liked, 0) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)) { Labeled(Icons.Rounded.SortByAlpha, "Liked, A to Z") }
                    }
                }
            }
        }
        val recent = history.distinctBy { it.key }.take(20)
        if (recent.isNotEmpty()) {
            item { SectionTitle("Played lately", Modifier.padding(top = 14.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(recent) { i, t -> Card(t, onPlay = { hub.play(recent, i) }) }
                }
            }
        }
        if (liked.isNotEmpty()) {
            val newest = liked.takeLast(20).reversed()
            item { SectionTitle("Liked songs", Modifier.padding(top = 14.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(newest) { i, t -> Card(t, onPlay = { hub.play(newest, i) }) }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(pad: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var results by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val lib = App.instance.library
    val liked by lib.liked.collectAsState()
    val hub = App.instance.player
    // suggestions as you type (after a pause)
    LaunchedEffect(query) {
        if (query.isBlank() || query == asked) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        suggestions = withContext(Dispatchers.IO) { YouTube.suggestions(query) }.take(8)
    }
    LaunchedEffect(asked) {
        if (asked.isBlank()) return@LaunchedEffect
        busy = true
        suggestions = emptyList()
        // lossless first: the Internet Archive, then YouTube Music
        results = withContext(Dispatchers.IO) {
            coroutineScope {
                val archive = async { runCatching { Archive.search(asked) }.getOrDefault(emptyList()) }
                val youtube = async { runCatching { YouTube.search(asked) }.getOrDefault(emptyList()) }
                archive.await() + youtube.await()
            }
        }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search any song or artist") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = null; asked = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (suggestions.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { s -> SuggestionChip(onClick = { query = s; asked = s }, label = { Text(s) }) }
            }
        }
        val list = results
        when {
            busy -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list == null -> {
                val mine = if (query.isBlank()) emptyList() else liked.filter { matches(it, query) }
                if (mine.isNotEmpty()) {
                    SectionTitle("In your liked songs")
                    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = pad.calculateBottomPadding())) {
                        itemsIndexed(mine) { i, t -> TrackRow(t, onPlay = { hub.play(mine, i) }) }
                    }
                } else {
                    Note("Lossless FLAC from the Internet Archive comes first; YouTube Music when it's not there. Like a song and it's saved to Music/Radiance Music on your phone.")
                }
            }
            list.isEmpty() -> Note("Nothing found for “$asked”.")
            else -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = pad.calculateBottomPadding())) {
                // your liked matches first
                val mine = liked.filter { matches(it, asked) }
                val all = mine + list.filter { r -> mine.none { it.key == r.key } }
                itemsIndexed(all) { i, t ->
                    TrackRow(t, onPlay = { hub.play(all, i) }, trailing = {
                        Quality(t)
                        Heart(t)
                    })
                }
            }
        }
    }
}

/**
 * Library, roomy (owner, 2026-10-31: "ang panget ng library", "ang sikip sa taas"):
 * a big title with Settings beside it, the two lists as pills, the search, then
 * Play and Shuffle side by side, all scrolling away with the songs. Sharing songs
 * by QR lives in Settings now.
 */
@Composable
fun LibraryScreen(pad: PaddingValues, onSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    val lib = App.instance.library
    val liked by lib.liked.collectAsState()
    val history by lib.history.collectAsState()
    val hub = App.instance.player
    val saving by App.instance.status.collectAsState()
    val shown = if (filter.isBlank()) liked else liked.filter { matches(it, filter) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 12.dp),
    ) {
        item { PageTitle("Library", onSettings) }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Liked Songs", if (liked.isEmpty()) null else liked.size, tab == 0) { tab = 0 }
                Chip("History", null, tab == 1) { tab = 1 }
            }
        }
        saving?.let { item { Note(it) } }
        if (tab == 0) {
            item {
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Search your liked songs") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            if (liked.isEmpty()) {
                item { Note("Like a song (the heart) and it's saved here, with its cover, lyrics and music video.") }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton(Icons.Rounded.PlayArrow, "Play", shown.isNotEmpty(), Modifier.weight(1f)) { hub.play(shown, 0) }
                        BigButton(Icons.Rounded.Shuffle, "Shuffle", shown.isNotEmpty(), Modifier.weight(1f)) { hub.play(shown.shuffled(), 0) }
                    }
                }
                item {
                    val n = if (filter.isBlank()) "${liked.size} song${if (liked.size == 1) "" else "s"}, saved on this phone" else "${shown.size} of ${liked.size} songs"
                    Text(n, color = Brand.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
                }
            }
            itemsIndexed(shown, key = { _, t -> t.key }) { i, t ->
                Box(Modifier.padding(horizontal = 8.dp)) { TrackRow(t, onPlay = { hub.play(shown, i) }) }
            }
        } else {
            if (history.isEmpty()) item { Note("Songs you play show up here, newest first.") }
            itemsIndexed(history) { i, t ->
                Box(Modifier.padding(horizontal = 8.dp)) { TrackRow(lib.best(t), onPlay = { hub.play(history.drop(i).map { lib.best(it) }, 0) }) }
            }
        }
    }
}

/** A page's big title, with Settings at the right (a dot on it when an update is out). */
@Composable
fun PageTitle(title: String, onSettings: () -> Unit) {
    val update by Updates.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) {
            BadgedBox(badge = { if (update is Updates.State.Available) Badge(containerColor = Brand.Orange) }) {
                Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
            }
        }
    }
}

@Composable
private fun Chip(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Brand.Card).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (on) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        count?.let { Text("  $it", color = if (on) Color.Black.copy(alpha = 0.55f) else Brand.Dim, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Play or Shuffle: wide, side by side, as Apple Music's. */
@Composable
private fun BigButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brand.Card).clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand.Orange, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Brand.Orange, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
