package io.github.radianceadm.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.clock
import io.github.radianceadm.music.model.matches
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.server.Party
import io.github.radianceadm.music.server.PartySong
import io.github.radianceadm.music.server.Reaction
import io.github.radianceadm.music.server.ServerSong
import io.github.radianceadm.music.server.Servers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The listening party you're in (owner's choice, 2026-10-31): the song
 * everyone hears now and how well this phone keeps time with it, reactions
 * (soft pictures that float up), up next (anyone adds a song), who's
 * listening, and the host's controls. It plays on with the screen off;
 * Leave goes back to your own music.
 */
@Composable
fun PartyScreen(onClose: () -> Unit) {
    val party = App.instance.party
    val now by party.now.collectAsState()
    BackHandler(onBack = onClose)
    // left, or it ended: back to where you were
    LaunchedEffect(now == null) { if (now == null) onClose() }
    val n = now ?: return
    PartyView(n, position = { party.position() }, floats = party.floats, onClose = onClose)
}

/** The party's screen for [n]; [position]: where its song is now (ms), as this phone's clock has it. */
@Composable
fun PartyView(n: Party.Now, position: () -> Long?, floats: Flow<Reaction> = emptyFlow(), onClose: () -> Unit = {}) {
    val party = App.instance.party
    val p = n.state
    var adding by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var entryMenu by remember { mutableStateOf<PartySong?>(null) }
    Box(Modifier.fillMaxSize().background(Brand.Night)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text("Listening party", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(n.server.name, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { party.leave() }) { Labeled(Icons.AutoMirrored.Rounded.Logout, "Leave", Brand.Orange) }
                }
            }
            item { NowInParty(n, position) }
            item { ReactionRow(onReact = { party.react(it) }) }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { adding = true }, colors = tonal()) { Labeled(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add a song") }
                }
            }
            if (p.queue.isEmpty()) item { Note("Nothing waiting. Anyone in the party can add a song from ${n.server.name}.") }
            itemsIndexed(p.queue, key = { _, e -> e.entry }) { _, e ->
                val mine = e.addedBy?.id == n.me?.id
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(e.cover, 46.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(e.artist.ifBlank { null }, e.addedBy?.let { "added by ${if (mine) "you" else it.name}" }).joinToString(" · "), color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (n.controls || mine) IconButton(onClick = { entryMenu = e }) { Icon(Icons.Rounded.MoreHoriz, "More for “${e.title}”", tint = Brand.Dim) }
                }
            }
            item { SectionTitle("Listening", Modifier.padding(top = 18.dp)) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.members.isEmpty()) Text("Nobody is listening right now.", color = Brand.Dim)
                    p.members.forEach { m ->
                        Row(Modifier.clip(RoundedCornerShape(50)).background(Brand.Card).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Brand.Orange.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                                Text(m.name.take(1).uppercase(), color = Brand.Glow, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (m.id == n.me?.id) "${m.name} (you)" else m.name, color = Color.White)
                            if (m.host) {
                                Spacer(Modifier.width(6.dp))
                                Text("Host", color = Brand.Night, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clip(RoundedCornerShape(50)).background(Brand.Orange).padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
            if (n.host || n.server.me?.owner == true) item { HostCard(n, onEnd = { ending = true }) }
        }
        Floats(floats)
    }
    if (adding) AddToPartySheet(n, onDone = { adding = false })
    entryMenu?.let { e -> EntrySheet(n, e, onDone = { entryMenu = null }) }
    if (ending) EndSheet(n, onDone = { ending = false })
}

/** The song everyone hears: its cover, who added it, how far along, and this phone keeping time with it. */
@Composable
private fun NowInParty(n: Party.Now, position: () -> Long?) {
    val party = App.instance.party
    val p = n.state
    val s = p.song
    var at by remember { mutableLongStateOf(position() ?: 0L) }
    LaunchedEffect(p.version, s?.entry) {
        while (true) {
            at = position() ?: 0L
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Gap(10.dp)
        Artwork(s?.cover, 240.dp, corner = 24.dp)
        Gap(18.dp)
        val pill = when {
            s == null -> "Quiet"
            p.playing -> "Playing"
            else -> "Paused"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pill, color = if (p.playing && s != null) Brand.Night else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (p.playing && s != null) Brand.Orange else Brand.Card).padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (n.host) "You're the host" else "Hosted by ${p.host?.name ?: "someone"}", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
        }
        Gap(10.dp)
        Text(s?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (s == null) "Add a song: it starts at once." else listOfNotNull(s.artist.ifBlank { null }, s.addedBy?.let { "added by ${if (it.id == n.me?.id) "you" else it.name}" }).joinToString(" · "),
            color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (s != null) {
            val length = s.durationMs ?: 0L
            Gap(14.dp)
            LinearProgressIndicator(
                progress = { if (length > 0) (at.toFloat() / length).coerceIn(0f, 1f) else 0f },
                color = Color.White, trackColor = Color.White.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(clock(at.coerceAtLeast(0) / 1000), color = Brand.Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (length > 0) Text(clock(length / 1000), color = Brand.Dim, style = MaterialTheme.typography.labelMedium)
            }
        }
        Gap(12.dp)
        // what this phone is doing: in step (and by how much), catching up, paused by the host…
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!n.linked) CircularProgressIndicator(color = Brand.Orange, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            if (!n.linked) Spacer(Modifier.width(8.dp))
            Text(n.sync + (n.clockMs?.let { " · clock ±$it ms" } ?: ""), color = if (n.linked) Brand.Dim else Brand.Glow, style = MaterialTheme.typography.bodySmall)
        }
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (n.controls) {
                if (p.playing && s != null) Button(onClick = { party.pause() }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.Rounded.Pause, "Pause") }
                else Button(onClick = { party.play() }, enabled = s != null || p.queue.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.Rounded.PlayArrow, "Play") }
                FilledTonalButton(onClick = { party.skip() }, enabled = s != null, colors = tonal()) { Labeled(Icons.Rounded.SkipNext, "Skip") }
            } else {
                Text("${p.host?.name ?: "The host"} (the host) plays, pauses and skips.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Gap(8.dp)
        // this phone only: the party goes on either way
        if (n.listening) TextButton(onClick = { party.listenHere(false) }) { Labeled(Icons.AutoMirrored.Rounded.VolumeOff, "Stop listening here", Brand.Dim) }
        else FilledTonalButton(onClick = { party.listenHere(true) }, colors = tonal()) { Labeled(Icons.Rounded.Headphones, "Listen here") }
    }
}

/** The six reactions, as round buttons with their soft pictures. */
@Composable
private fun ReactionRow(onReact: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Reactions.all.forEach { r ->
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Brand.Card).clickable { onReact(r.emoji) }.semantics { contentDescription = r.name },
                contentAlignment = Alignment.Center,
            ) { ReactionIcon(r.emoji, 30.dp) }
        }
    }
}

/** Reactions as they come float up the screen and fade. */
@Composable
private fun Floats(floats: Flow<Reaction>) {
    val flying = remember { mutableStateListOf<Pair<Long, Reaction>>() }
    var n by remember { mutableLongStateOf(0L) }
    LaunchedEffect(floats) { floats.collect { flying += (n++) to it; if (flying.size > 12) flying.removeAt(0) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val w = maxWidth
        flying.forEach { (id, r) ->
            key(id) {
                val rise = remember { Animatable(0f) }
                val x = remember { 0.15f + Random.nextFloat() * 0.7f }
                LaunchedEffect(Unit) {
                    rise.animateTo(1f, tween(2700, easing = LinearEasing))
                    flying.removeAll { it.first == id }
                }
                Column(
                    Modifier.offset(x = w * x - 24.dp, y = h * (0.85f - 0.6f * rise.value)).graphicsLayer { alpha = 1f - rise.value; scaleX = 0.8f + 0.4f * rise.value; scaleY = scaleX },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReactionIcon(r.emoji, 48.dp)
                    r.by?.let { Text(it.name, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

/** For the host (and the server's owner): who controls the party, and ending it. */
@Composable
private fun HostCard(n: Party.Now, onEnd: () -> Unit) {
    val party = App.instance.party
    SettingsGroup(if (n.host) "You're the host" else "Your server") {
        Column(Modifier.padding(16.dp)) {
            if (n.host) {
                Text(if (n.state.everyoneControls) "Everyone can play, pause and skip." else "Only you play, pause and skip. Everyone can add songs and react.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
                FilledTonalButton(onClick = { party.allow(!n.state.everyoneControls) }, colors = tonal()) {
                    if (n.state.everyoneControls) Labeled(Icons.Rounded.Person, "Only I control it") else Labeled(Icons.Rounded.Groups, "Let everyone control it")
                }
                Gap(6.dp)
            }
            TextButton(onClick = onEnd) { Labeled(Icons.Rounded.StopCircle, "End the party for everyone", Brand.Orange) }
        }
    }
}

/** Asks first: ending stops the party on every phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndSheet(n: Party.Now, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            Text("End the party?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("It stops for everyone listening (${n.state.members.size}). The songs stay on ${n.server.name}.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { App.instance.party.end(); onDone() }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.Rounded.StopCircle, "End it") }
                FilledTonalButton(onClick = onDone, colors = tonal()) { Labeled(Icons.Rounded.Close, "Keep it on") }
            }
        }
    }
}

/** A waiting song's actions: to the top, up, down, out (out: your own, or anyone's if you control the party). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(n: Party.Now, e: PartySong, onDone: () -> Unit) {
    val party = App.instance.party
    val i = n.state.queue.indexOfFirst { it.entry == e.entry }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(e.cover, 48.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(e.artist, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Gap(6.dp)
            if (n.controls && i > 0) {
                SheetAction(Icons.Rounded.VerticalAlignTop, "Play it next") { party.reorder(e.entry, 0); onDone() }
                SheetAction(Icons.Rounded.ArrowUpward, "Move up") { party.reorder(e.entry, i - 1); onDone() }
            }
            if (n.controls && i in 0 until n.state.queue.size - 1) SheetAction(Icons.Rounded.ArrowDownward, "Move down") { party.reorder(e.entry, i + 1); onDone() }
            SheetAction(Icons.Rounded.RemoveCircle, "Take it out of the party") { party.remove(e.entry); onDone() }
        }
    }
}

/** One action in a bottom sheet: its icon, then its words. */
@Composable
fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, note: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Brand.Orange else Brand.Dim, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = if (enabled) Color.White else Brand.Dim, style = MaterialTheme.typography.titleMedium)
            note?.let { Text(it, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Anyone adds a song from the server's library: it joins the queue (it starts at once when nothing's playing). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPartySheet(n: Party.Now, onDone: () -> Unit) {
    val s = n.server
    var songs by remember { mutableStateOf<List<ServerSong>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var tries by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var big by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Pair<String, List<ServerSong>>?>(null) }
    // its songs (a big library: its first page; a search finds the rest there)
    LaunchedEffect(tries) {
        problem = null
        withContext(Dispatchers.IO) { runCatching { Servers.page(s, limit = PAGE) } }
            .fold({ songs = it.songs; big = it.more }, { problem = Errors.server(it, s.name); offline = Errors.offline(it) })
    }
    LaunchedEffect(filter, big) {
        val q = filter.trim()
        if (!big || q.isEmpty()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        withContext(Dispatchers.IO) { runCatching { Servers.page(s, q = q, limit = 200).songs } }.onSuccess { found = q to it }
    }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Add a song to the party", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
            Text("From ${s.name}. It plays after the songs before it.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp))
            TextField(
                value = filter, onValueChange = { filter = it }, placeholder = { Text("Search ${s.name}") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            val list = songs
            when {
                problem != null -> Problem(problem.orEmpty(), offline, onRetry = { tries++ })
                list == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Orange) }
                list.isEmpty() -> Note("${s.name} has no songs yet.")
                else -> {
                    val q = filter.trim()
                    val shown = when {
                        q.isEmpty() -> list
                        big -> found?.takeIf { it.first == q }?.second.orEmpty()
                        else -> list.filter { matches(it.track(), filter) }
                    }
                    if (shown.isEmpty()) Note(if (big && found?.first != q) "Searching ${s.name}…" else "Nothing on ${s.name} matches “$filter”.")
                    LazyColumn(Modifier.height(420.dp)) {
                        itemsIndexed(shown, key = { _, x -> x.id }) { _, song ->
                            Row(
                                Modifier.fillMaxWidth().clickable { App.instance.party.add(song.id); onDone() }.padding(horizontal = 18.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(song.cover, 46.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add “${song.title}”", tint = Brand.Orange)
                            }
                        }
                    }
                }
            }
        }
    }
}
