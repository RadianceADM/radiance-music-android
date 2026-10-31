package io.github.radianceadm.music.ui

import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import io.github.radianceadm.music.data.Library
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
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
    // nothing found because it couldn't look (offline, or why), not because there's nothing; null: it looked
    var problem by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var tries by remember { mutableIntStateOf(0) } // Try again: the same search once more
    val online by Connectivity.online.collectAsState()
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
    LaunchedEffect(asked, tries) {
        if (asked.isBlank()) return@LaunchedEffect
        busy = true
        suggestions = emptyList()
        // lossless first: the Internet Archive, then YouTube Music
        val (found, failed) = withContext(Dispatchers.IO) {
            coroutineScope {
                val archive = async { runCatching { Archive.search(asked) } }
                val youtube = async { runCatching { YouTube.search(asked) } }
                val (a, y) = archive.await() to youtube.await()
                (a.getOrDefault(emptyList()) + y.getOrDefault(emptyList())) to (y.exceptionOrNull() ?: a.exceptionOrNull())
            }
        }
        offline = found.isEmpty() && !Connectivity.online.value
        problem = when {
            found.isNotEmpty() -> null
            offline -> "You're offline. Connect to search."
            failed != null -> "Couldn't search. ${Errors.say(failed)}"
            else -> null
        }
        results = found
        busy = false
    }
    // back online after a search that couldn't look: it looks again
    LaunchedEffect(online) { if (online && offline && asked.isNotBlank()) tries++ }
    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search any song or artist") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = null; asked = ""; problem = null; offline = false }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (asked == query.trim()) tries++ else asked = query.trim() }), // the same again: it looks again
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
            list.isEmpty() && problem != null -> {
                Problem(problem.orEmpty(), offline, onRetry = { tries++ })
                // your liked songs play offline: the ones that match, here
                val mine = liked.filter { matches(it, asked) }
                if (mine.isNotEmpty()) {
                    SectionTitle("In your liked songs")
                    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = pad.calculateBottomPadding())) {
                        itemsIndexed(mine) { i, t -> TrackRow(t, onPlay = { hub.play(mine, i) }) }
                    }
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
    val list = rememberLazyListState()
    // the A to Z rail, as the computer's: on Liked Songs once there are enough to scroll
    val rail = tab == 0 && shown.size >= 12
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
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
                Box(Modifier.padding(start = 8.dp, end = if (rail) 30.dp else 8.dp)) { TrackRow(t, onPlay = { hub.play(shown, i) }) }
            }
        } else {
            if (history.isEmpty()) item { Note("Songs you play show up here, newest first.") }
            itemsIndexed(history) { i, t ->
                Box(Modifier.padding(horizontal = 8.dp)) { TrackRow(lib.best(t), onPlay = { hub.play(history.drop(i).map { lib.best(it) }, 0) }) }
            }
        }
    }
    if (rail) {
        // the songs are the list's last items: whatever comes before them is the header
        val head = (list.layoutInfo.totalItemsCount - shown.size).coerceAtLeast(0)
        AzRail(shown, list, head, Modifier.align(Alignment.CenterEnd).padding(top = pad.calculateTopPadding() + 40.dp, bottom = pad.calculateBottomPadding() + 8.dp, end = 2.dp))
    }
    }
}

/** The rail's letters, in the order songs are filed (as the computer's). */
private const val AZ = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"

/** A song's letter on the rail: its title's first letter as filed, or # for anything else. */
fun letterOf(t: Track): Char {
    val key = Library.fileKey(t.title).second
    val c = key.firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

/**
 * The A to Z rail beside Liked Songs: tap a letter, or slide along it, to jump
 * there (a big letter shows where you are); letters with no songs stay faint,
 * and the letter of the songs at the top is lit.
 */
@Composable
private fun AzRail(songs: List<Track>, list: LazyListState, head: Int, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val firsts = remember(songs) {
        val m = LinkedHashMap<Char, Int>()
        songs.forEachIndexed { i, t -> m.putIfAbsent(letterOf(t), i) }
        m
    }
    var held by remember { mutableStateOf<Char?>(null) }
    val top = (list.firstVisibleItemIndex - head).coerceIn(0, (songs.size - 1).coerceAtLeast(0))
    val lit = held ?: songs.getOrNull(top)?.let(::letterOf)
    fun jump(y: Float, height: Int) {
        val i = ((y / height) * AZ.length).toInt().coerceIn(0, AZ.length - 1)
        val letter = AZ[i]
        if (letter == held) return
        held = letter
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // that letter, or the next one with songs
        val at = AZ.substring(i).firstNotNullOfOrNull { firsts[it] } ?: return
        scope.launch { list.scrollToItem(head + at) }
    }
    Box(modifier) {
        Column(
            Modifier.width(26.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (held != null) 0.1f else 0.04f)).padding(vertical = 6.dp)
                .pointerInput(firsts, head) {
                    detectVerticalDragGestures(
                        onDragStart = { jump(it.y, size.height) },
                        onVerticalDrag = { ch, _ -> ch.consume(); jump(ch.position.y, size.height) },
                        onDragEnd = { held = null },
                        onDragCancel = { held = null },
                    )
                }
                .pointerInput(firsts, head) { detectTapGestures(onPress = { jump(it.y, size.height); tryAwaitRelease(); held = null }) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AZ.forEach { c ->
                val has = c in firsts
                Text(
                    c.toString(),
                    color = when {
                        c == lit -> Color.Black
                        has -> Brand.Orange
                        else -> Brand.Orange.copy(alpha = 0.28f)
                    },
                    fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.size(20.dp, 15.dp).clip(CircleShape).background(if (c == lit) Brand.Orange else Color.Transparent).wrapContentHeight(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    // where the slide is: the letter big in the middle of the screen
    held?.let { c ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Brand.Card.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                Text(c.toString(), color = Brand.Orange, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
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
            BadgedBox(badge = { if (update is Updates.State.Available || update is Updates.State.Ready) Badge(containerColor = Brand.Orange) }) {
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
