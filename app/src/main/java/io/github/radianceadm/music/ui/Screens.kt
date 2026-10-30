package io.github.radianceadm.music.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
fun HomeScreen(pad: PaddingValues, onSearch: () -> Unit) {
    val lib = App.instance.library
    val liked by lib.liked.collectAsState()
    val history by lib.history.collectAsState()
    val hub = App.instance.player
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 16.dp)) {
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Brand.Navy, Color(0xFF3A2A44), Brand.Orange.copy(alpha = 0.75f))))
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
                    Button(onClick = onSearch) { Text("Search for music") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { hub.play(liked.shuffled(), 0) }) { Text("Play your mix") }
                        FilledTonalButton(onClick = { hub.play(liked, 0) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)) { Text("Liked, A to Z") }
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

@Composable
fun LibraryScreen(pad: PaddingValues) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    val lib = App.instance.library
    val liked by lib.liked.collectAsState()
    val history by lib.history.collectAsState()
    val hub = App.instance.player
    val saving by App.instance.status.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            listOf("Liked Songs", "History").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        saving?.let { Note(it) }
        if (tab == 0) {
            TextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search your liked songs") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val shown = if (filter.isBlank()) liked else liked.filter { matches(it, filter) }
            // how many, on a line of its own (it never squeezes beside the buttons on a narrow phone)
            Text("${liked.size} song${if (liked.size == 1) "" else "s"}, saved on this phone", color = Brand.Dim, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { hub.play(shown, 0) }, enabled = shown.isNotEmpty()) { Text("Play") }
                FilledTonalButton(onClick = { hub.play(shown.shuffled(), 0) }, enabled = shown.isNotEmpty()) { Text("Shuffle") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { App.instance.shareOpen.value = App.ShareAsk(null) }) { Icon(Icons.Rounded.QrCode2, "Share songs", tint = Color.White) }
            }
            if (liked.isEmpty()) Note("Like a song (the heart) and it's saved here, with its cover, lyrics and music video.")
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = pad.calculateBottomPadding())) {
                itemsIndexed(shown, key = { _, t -> t.key }) { i, t -> TrackRow(t, onPlay = { hub.play(shown, i) }) }
            }
        } else {
            if (history.isEmpty()) Note("Songs you play show up here, newest first.")
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = pad.calculateBottomPadding())) {
                itemsIndexed(history) { i, t -> TrackRow(lib.best(t), onPlay = { hub.play(history.drop(i).map { lib.best(it) }, 0) }) }
            }
        }
    }
}
