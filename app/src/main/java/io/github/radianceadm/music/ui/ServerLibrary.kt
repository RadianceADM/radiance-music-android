package io.github.radianceadm.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.clock
import io.github.radianceadm.music.model.matches
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.server.Job
import io.github.radianceadm.music.server.PartyState
import io.github.radianceadm.music.server.Queue
import io.github.radianceadm.music.server.Refused
import io.github.radianceadm.music.server.Server
import io.github.radianceadm.music.server.ServerSong
import io.github.radianceadm.music.server.ServerSongs
import io.github.radianceadm.music.server.Servers
import io.github.radianceadm.music.ui.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A quiet button's colours: a soft white on the dark (not Material's own purple-grey). */
@Composable
internal fun tonal() = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White)

/** What a server's library shows: its songs (null: not here yet), why not, its party and its split queue. */
data class ShelfUi(
    val songs: List<ServerSong>? = null,
    val problem: String? = null,
    val offline: Boolean = false,
    /** This phone was removed there: why, in plain words. */
    val removed: String? = null,
    val party: PartyState? = null,
    val queue: Queue? = null,
)

/**
 * A server's library, as a section of Library with the server's name (the
 * design: "Rad's server", next to your own songs): its songs with a search,
 * Play and Shuffle; its listening party (join it, or start one); its split
 * queue; and each song's actions in a bottom sheet (play, save to this phone,
 * split on the server, add to the party). Your liked songs go to it with Add
 * a song, when its owner lets you.
 */
@Composable
fun ServerLibrary(pad: PaddingValues, server: Server, header: @Composable () -> Unit, onParty: () -> Unit) {
    var ui by remember(server.base) { mutableStateOf(ShelfUi(songs = Servers.lastSongs(server))) }
    var tries by remember(server.base) { mutableIntStateOf(0) }
    var poke by remember(server.base) { mutableIntStateOf(0) }
    val online by Connectivity.online.collectAsState()
    // its songs (again on Refresh, or when a split finished)
    LaunchedEffect(server.base, tries) {
        if (tries > 0 || ui.songs == null) ui = ui.copy(problem = null)
        val r = withContext(Dispatchers.IO) { runCatching { Servers.library(server) } }
        ui = r.fold(
            { ui.copy(songs = it, problem = null, offline = false, removed = null) },
            { e -> if (e is Refused && e.removed) ui.copy(removed = "This phone was removed from ${server.name}. Ask ${Servers.ownerOf(server)} for a new invite.") else ui.copy(problem = Errors.server(e, server.name), offline = Errors.offline(e)) },
        )
    }
    // its party and its split queue, looked at again while this is on screen (often while songs are being split)
    LaunchedEffect(server.base, poke) {
        var working = emptySet<Long>()
        while (isActive) {
            val party = withContext(Dispatchers.IO) { runCatching { server.rms.party() }.getOrNull() }
            val queue = withContext(Dispatchers.IO) { runCatching { server.rms.queue() }.getOrNull() }
            ui = ui.copy(party = party ?: ui.party, queue = queue ?: ui.queue)
            val open = queue?.jobs?.filter { it.open }?.map { it.id }?.toSet() ?: working
            // a song finished splitting: its parts are in the list now
            if ((working - open).isNotEmpty()) tries++
            working = open
            delay(if (open.isNotEmpty()) 4_000 else 15_000)
        }
    }
    // back online: it looks again
    LaunchedEffect(online) { if (online && ui.offline) tries++ }
    ServerLibraryView(pad, server, ui, header, onRetry = { tries++; poke++ }, onChanged = { poke++ }, onParty = onParty)
}

/** The server's library as [ui] has it (drawn as it is: the tests' pictures too). */
@Composable
fun ServerLibraryView(pad: PaddingValues, server: Server, ui: ShelfUi, header: @Composable () -> Unit, onRetry: () -> Unit = {}, onChanged: () -> Unit = {}, onParty: () -> Unit = {}) {
    var filter by rememberSaveable(server.base) { mutableStateOf("") }
    var menu by remember { mutableStateOf<ServerSong?>(null) }
    var adding by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val hub = App.instance.player
    val saving by ServerSongs.saving.collectAsState()
    val liked by App.instance.library.liked.collectAsState()
    val inParty by App.instance.party.now.collectAsState()
    val songs = ui.songs.orEmpty()
    val shown = if (filter.isBlank()) songs else songs.filter { matches(it.track(), filter) }
    val tracks = shown.map { it.track() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 12.dp)) {
        item { header() }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, null, tint = Brand.Orange, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(server.host, ui.songs?.let { "${it.size} song${if (it.size == 1) "" else "s"}" }).joinToString(" · "), color = Brand.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Look again", tint = Brand.Dim) }
            }
        }
        if (ui.removed != null) {
            item { Removed(server, ui.removed.orEmpty()) }
            return@LazyColumn
        }
        item { PartyCard(server, ui.party, joinedHere = inParty?.server?.base == server.base, onOpen = onParty, onJoin = { App.instance.party.join(server); onParty() }, onStart = { starting = true }) }
        item {
            TextField(
                value = filter, onValueChange = { filter = it }, placeholder = { Text("Search ${server.name}") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(Icons.Rounded.PlayArrow, "Play", tracks.isNotEmpty(), Modifier.weight(1f)) { hub.play(tracks, 0) }
                BigButton(Icons.Rounded.Shuffle, "Shuffle", tracks.isNotEmpty(), Modifier.weight(1f)) { hub.play(tracks.shuffled(), 0) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (server.can.add) FilledTonalButton(onClick = { adding = true }, colors = tonal()) { Labeled(Icons.Rounded.LibraryAdd, "Add one of your songs") }
                else Text("${Servers.ownerOf(server)} turned off adding songs for you.", color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        ui.queue?.let { q -> if (q.jobs.any { it.open }) item { QueueCard(server, q) } }
        val list = ui.songs
        when {
            ui.problem != null && list == null -> item { Problem(ui.problem.orEmpty(), ui.offline, onRetry = onRetry) }
            list == null -> item {
                Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Brand.Orange, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Getting ${server.name}'s songs…", color = Brand.Dim)
                }
            }
            list.isEmpty() -> item {
                Note(if (server.can.add) "No songs on ${server.name} yet. Add one of your liked songs: everyone there can play it then." else "No songs on ${server.name} yet. Ask ${Servers.ownerOf(server)} to add some.")
            }
            shown.isEmpty() -> item { Note("Nothing on ${server.name} matches “$filter”.") }
            else -> {
                // the list still shows while it couldn't look again; why, above it
                if (ui.problem != null) item { Problem(ui.problem.orEmpty(), ui.offline, onRetry = onRetry) }
                itemsIndexed(shown, key = { _, s -> s.id }) { i, song ->
                    val t = tracks[i]
                    ServerSongRow(
                        song, ui.queue?.of(song.id), saving = t.key in saving, saved = liked.any { it.key == t.key && it.file != null },
                        onPlay = { hub.play(tracks, i) }, onMore = { menu = song },
                    )
                }
            }
        }
    }
    menu?.let { song -> SongSheet(server, song, tracks, ui.queue, inParty?.server?.base == server.base, onChanged, onDone = { menu = null }) }
    if (adding) AddToServerSheet(server, songs, onAdded = onRetry, onDone = { adding = false })
    if (starting) StartPartySheet(server, onStarted = onParty, onDone = { starting = false })
}

/** This phone was removed from the server: say so, and let it go. */
@Composable
private fun Removed(server: Server, why: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Block, null, tint = Brand.Glow, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(why, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Gap(12.dp)
        FilledTonalButton(onClick = { runCatching { Servers.leave(server, onServer = false) } }, colors = tonal()) { Labeled(Icons.Rounded.DeleteOutline, "Forget this server") }
    }
}

/** The party: on now (join it, or open it if you're in), or start one (when the owner lets you). */
@Composable
private fun PartyCard(server: Server, party: PartyState?, joinedHere: Boolean, onOpen: () -> Unit, onJoin: () -> Unit, onStart: () -> Unit) {
    val on = party?.on == true
    if (party == null && !joinedHere) return
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Brand.Card, Color(0xFF5C2A12), Brand.Orange.copy(alpha = if (on || joinedHere) 0.75f else 0.35f))))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Celebration, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    joinedHere -> "You're in the listening party"
                    on -> "A listening party is on"
                    else -> "Listening party"
                },
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
        }
        Gap(6.dp)
        val p = party
        Text(
            when {
                p != null && p.on -> listOfNotNull(
                    p.host?.let { "Hosted by ${it.name}" },
                    "${p.members.size} listening",
                    p.song?.let { "now: ${it.title}" },
                ).joinToString(" · ")
                server.can.host -> "Everyone in it hears the same song at the same moment. Anyone adds songs and reacts."
                else -> "No party on right now. ${Servers.ownerOf(server)} turned off starting parties for you; you can join one when it's on."
            },
            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
        )
        Gap(12.dp)
        when {
            joinedHere -> Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Labeled(Icons.Rounded.Headphones, "Open the party") }
            on -> Button(onClick = onJoin, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Labeled(Icons.AutoMirrored.Rounded.Login, "Join the party") }
            server.can.host -> Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Labeled(Icons.Rounded.Celebration, "Start a party") }
        }
    }
}

/** Songs being split on the server: the one at work, how many wait, and whose turn is next. */
@Composable
private fun QueueCard(server: Server, q: Queue) {
    val working = q.jobs.firstOrNull { it.working }
    val waiting = q.jobs.count { it.waiting }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.CallSplit, null, tint = Brand.Orange, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Splitting on ${server.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Gap(6.dp)
        working?.let { j ->
            Text("“${j.title}”: ${j.note}", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Gap(6.dp)
            LinearProgressIndicator(progress = { j.progress.toFloat() }, color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
            Gap(6.dp)
        }
        if (working == null && !q.splitterReady && q.splitterMessage.isNotBlank()) Text(q.splitterMessage, color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
        if (waiting > 0) Text(
            listOfNotNull("$waiting waiting", q.nextTurn?.let { "next turn: ${if (it.id == server.me?.id) "yours" else it.name}" }).joinToString(" · "),
            color = Brand.Dim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A song on the server: tap plays from it; ⋯ for its actions (a bottom sheet). Its split, if it's in the queue, shows under it. */
@Composable
private fun ServerSongRow(song: ServerSong, job: Job?, saving: Boolean, saved: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPlay).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.cover, 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (job != null) Text(job.note, style = MaterialTheme.typography.bodyMedium, color = Brand.Glow, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.split) {
                    Text("Split", style = MaterialTheme.typography.labelSmall, color = Brand.Lossless, modifier = Modifier.clip(RoundedCornerShape(50)).background(Brand.Lossless.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            saving -> CircularProgressIndicator(color = Brand.Orange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            saved -> Icon(Icons.Rounded.DownloadDone, "Saved on this phone", tint = Brand.Lossless, modifier = Modifier.size(18.dp))
        }
        song.durationMs?.let { Text(clock(it / 1000), style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, modifier = Modifier.padding(start = 8.dp)) }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreHoriz, "More for “${song.title}”", tint = Brand.Dim) }
    }
}

/** A server song's actions: play, play next, queue, add to the party, save to this phone, split on the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongSheet(server: Server, song: ServerSong, tracks: List<Track>, queue: Queue?, inParty: Boolean, onChanged: () -> Unit, onDone: () -> Unit) {
    val app = App.instance
    val hub = app.player
    val t = song.track()
    val saving by ServerSongs.saving.collectAsState()
    val liked by app.library.liked.collectAsState()
    val saved = liked.any { it.key == t.key && it.file != null }
    val job = queue?.of(song.id)
    val owner = Servers.ownerOf(server)
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.cover, 56.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                    val about = listOfNotNull(
                        song.addedBy?.let { "added by ${if (it.id == server.me?.id) "you" else it.name}" },
                        "split".takeIf { song.split },
                        (if (song.wordTimes) "lyrics, every word timed" else "lyrics").takeIf { song.lyrics },
                    )
                    if (about.isNotEmpty()) Text(about.joinToString(" · ").replaceFirstChar { it.uppercase() }, color = Brand.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Gap(6.dp)
            SheetAction(Icons.Rounded.PlayArrow, "Play") { hub.play(tracks, tracks.indexOfFirst { it.key == t.key }.coerceAtLeast(0)); onDone() }
            SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Play next") { hub.playNext(t); onDone() }
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to the queue") { hub.enqueue(t); onDone() }
            if (inParty) SheetAction(Icons.Rounded.Celebration, "Add to the party", "Everyone in the party hears it when its turn comes") { app.party.add(song.id); onDone() }
            when {
                saved -> SheetAction(Icons.Rounded.DownloadDone, "Saved on this phone", "It's in your Liked Songs", enabled = false) {}
                t.key in saving -> SheetAction(Icons.Rounded.Download, "Saving…", "Its song, parts, lyrics and cover", enabled = false) {}
                else -> SheetAction(Icons.Rounded.Download, "Save to this phone", "With its ${if (song.split) "parts, " else ""}lyrics and cover, into your Liked Songs") { ServerSongs.save(app, server, song); onDone() }
            }
            when {
                song.split -> SheetAction(Icons.Rounded.TaskAlt, "Split already", "Its parts are on ${server.name}: play it and open the Mixer", enabled = false) {}
                job != null -> {
                    SheetAction(Icons.Rounded.HourglassTop, if (job.working) "Splitting on ${server.name}" else "Waiting to be split", job.note + (queue.nextTurn?.takeIf { job.waiting }?.let { " Next turn: ${if (it.id == server.me?.id) "yours" else it.name}." } ?: ""), enabled = false) {}
                    if (job.askedBy?.id == server.me?.id || server.me?.owner == true) SheetAction(Icons.Rounded.Close, "Take it out of the queue") {
                        app.scope.launch {
                            runCatching { server.rms.unqueue(job.id) }.onFailure { app.tell(Notice("Couldn't take it out. ${Errors.server(it, server.name)}")) }
                            withContext(Dispatchers.Main) { onChanged() }
                        }
                        onDone()
                    }
                }
                server.can.split -> SheetAction(Icons.AutoMirrored.Rounded.CallSplit, "Split on ${server.name}", "Into drums, bass, other and vocals, for everyone there. Songs take turns.") {
                    askSplit(server, song, onChanged)
                    onDone()
                }
                else -> SheetAction(Icons.Rounded.Block, "Split on ${server.name}", "$owner turned off splitting for you.", enabled = false) {}
            }
        }
    }
}

/** Asks [server] to split [song]: where it is in the queue, said. */
internal fun askSplit(server: Server, song: ServerSong, then: () -> Unit) {
    val app = App.instance
    app.scope.launch {
        try {
            val a = server.rms.split(song.id)
            app.tell(Notice(when {
                a.alreadySplit -> "“${song.title}” is split already on ${server.name}."
                a.job != null -> "${server.name} splits “${song.title}”. ${a.job.note}"
                else -> "Asked ${server.name} to split “${song.title}”."
            }))
        } catch (e: Exception) {
            Servers.heard(server, e)
            app.tell(Notice("Couldn't ask ${server.name} to split “${song.title}”. ${Errors.server(e, server.name)}", Notice.retry { askSplit(server, song, then) }))
        }
        withContext(Dispatchers.Main) { then() }
    }
}

/** Starting a party: who plays, pauses and skips (only you, or everyone). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartPartySheet(server: Server, onStarted: () -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Start a listening party", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
            Text("On ${server.name}. Everyone in it hears the same song at the same moment; anyone adds songs and reacts. Who plays, pauses and skips?", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp))
            Gap(8.dp)
            SheetAction(Icons.Rounded.Person, "Only me", "You're the host; you can let everyone later") { App.instance.party.start(server, everyone = false, then = onStarted); onDone() }
            SheetAction(Icons.Rounded.Groups, "Everyone") { App.instance.party.start(server, everyone = true, then = onStarted); onDone() }
        }
    }
}

/** One of your liked songs (saved here) to the server, with its cover and lyrics; the ones it has show so. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToServerSheet(server: Server, onServer: List<ServerSong>, onAdded: () -> Unit, onDone: () -> Unit) {
    val app = App.instance
    val liked by app.library.liked.collectAsState()
    val sending by ServerSongs.sending.collectAsState()
    var filter by remember { mutableStateOf("") }
    val mine = liked.filter { it.file != null && (filter.isBlank() || matches(it, filter)) }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = Brand.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Add to ${server.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
            Text("One of your liked songs, with its cover and lyrics. Everyone there can play it then.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp))
            TextField(
                value = filter, onValueChange = { filter = it }, placeholder = { Text("Search your liked songs") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when {
                liked.none { it.file != null } -> Note("Like a song first: it's saved on this phone, then it can go to ${server.name}.")
                mine.isEmpty() -> Note("None of your liked songs match “$filter”.")
                else -> LazyColumn(Modifier.height(440.dp)) {
                    itemsIndexed(mine, key = { _, t -> t.key }) { _, t ->
                        val there = ServerSongs.has(onServer, t)
                        val going = sending[t.key]
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !there && going == null) { ServerSongs.add(app, server, t, then = onAdded) }.padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(t.art, 46.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when {
                                there -> Text("On the server", color = Brand.Lossless, style = MaterialTheme.typography.labelMedium)
                                going != null -> Text("${(going * 100).toInt()} %", color = Brand.Orange, style = MaterialTheme.typography.labelMedium)
                                else -> Icon(Icons.Rounded.CloudUpload, "Add “${t.title}”", tint = Brand.Orange)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Mixer for a server's song not split here: split on the server (when
 * its owner lets you), its place in the queue and whose turn it is, then its
 * parts fetched from the server to mix. Splitting turned off for you: the
 * phone's own splitter ([phone]) instead.
 */
@Composable
internal fun ServerSplitPanel(t: Track, phone: @Composable () -> Unit) {
    val app = App.instance
    val server = Servers.of(t) ?: return phone()
    var song by remember(t.key) { mutableStateOf(Servers.song(t)?.second) }
    var queue by remember(t.key) { mutableStateOf<Queue?>(null) }
    var problem by remember(t.key) { mutableStateOf<String?>(null) }
    var offline by remember(t.key) { mutableStateOf(false) }
    var poke by remember(t.key) { mutableIntStateOf(0) }
    val fetching by ServerSongs.fetching.collectAsState()
    LaunchedEffect(t.key, poke) {
        problem = null
        // it was in the queue: once it isn't, the song's looked at again (its parts are there then)
        var waited = false
        var fresh = song == null
        while (isActive) {
            if (fresh) {
                fresh = false
                val found = withContext(Dispatchers.IO) { runCatching { Servers.library(server); Servers.song(t)?.second } }
                found.onFailure { problem = Errors.server(it, server.name); offline = Errors.offline(it) }
                if (found.isSuccess) {
                    song = found.getOrNull()
                    if (song == null) problem = "This song isn't on ${server.name} any more."
                }
            }
            val s = song ?: break
            if (s.split) {
                // its parts, from the server, for the Mixer
                if (ServerSongs.parts(app, t).isEmpty()) {
                    val e = withContext(Dispatchers.IO) { runCatching { ServerSongs.fetchParts(app, t) }.exceptionOrNull() }
                    if (e != null) {
                        problem = "Couldn't get the parts. ${Errors.server(e, server.name)}"
                        offline = Errors.offline(e)
                        break
                    }
                }
                app.mixer.recheck()
                break
            }
            val q = withContext(Dispatchers.IO) { runCatching { server.rms.queue() } }
            q.onSuccess { queue = it; problem = null }.onFailure { problem = Errors.server(it, server.name); offline = Errors.offline(it) }
            val job = q.getOrNull()?.of(s.id)
            if (job == null && waited && q.isSuccess) {
                waited = false
                fresh = true
                continue
            }
            if (job != null) waited = true
            delay(if (job != null) 3_000 else 15_000)
        }
    }
    val s = song
    val job = s?.let { queue?.of(it.id) }
    Column {
        when {
            problem != null -> Problem(problem.orEmpty(), offline, onRetry = { poke++ })
            s == null -> Text("Looking for it on ${server.name}…", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            s.split -> {
                Text("Getting its parts from ${server.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Split there already: its four parts come to this phone, then each one's slider is here.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(8.dp)
                val done = fetching[t.key]
                if (done != null) LinearProgressIndicator(progress = { done }, color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
            }
            job != null -> {
                Text(if (job.working) "Splitting on ${server.name}" else "Waiting on ${server.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(job.note + (queue?.nextTurn?.takeIf { job.waiting }?.let { " Next turn: ${if (it.id == server.me?.id) "yours" else it.name}." } ?: ""), color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(8.dp)
                if (job.working) LinearProgressIndicator(progress = { job.progress.toFloat() }, color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
                Gap(6.dp)
                Text("The server does it: you can close this, or turn the phone off. The parts come here when it's done.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
                if (job.askedBy?.id == server.me?.id || server.me?.owner == true) {
                    Gap(8.dp)
                    FilledTonalButton(onClick = {
                        app.scope.launch {
                            runCatching { server.rms.unqueue(job.id) }.onFailure { app.tell(Notice("Couldn't take it out. ${Errors.server(it, server.name)}")) }
                            withContext(Dispatchers.Main) { poke++ }
                        }
                    }, colors = tonal()) { Labeled(Icons.Rounded.Close, "Don't split it") }
                }
            }
            server.can.split -> {
                Text("Split this song into drums, bass, other and vocals on ${server.name}, quicker than on this phone. Its parts are there for everyone then.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                queue?.takeIf { !it.splitterReady && it.splitterMessage.isNotBlank() }?.let { Text(it.splitterMessage, color = Brand.Glow, style = MaterialTheme.typography.labelSmall) }
                Gap(8.dp)
                Button(onClick = { askSplit(server, s) { poke++ } }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Labeled(Icons.AutoMirrored.Rounded.CallSplit, "Split on ${server.name}") }
            }
            else -> {
                Text("${Servers.ownerOf(server)} turned off splitting on ${server.name} for you, so it's split here, on this phone.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
                phone()
            }
        }
    }
}
