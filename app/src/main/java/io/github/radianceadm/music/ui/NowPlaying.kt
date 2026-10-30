package io.github.radianceadm.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.clock
import io.github.radianceadm.music.play.Effects
import io.github.radianceadm.music.source.Line
import io.github.radianceadm.music.source.Lyrics
import io.github.radianceadm.music.split.SplitService
import io.github.radianceadm.music.split.Splits
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The art's own colours, blurred, flowing like a lava lamp: the whole cover
 * slowly turning, with soft patches of it wandering over it.
 */
@Composable
fun Backdrop(art: String?) {
    val flow = rememberInfiniteTransition(label = "flow")
    val turn by flow.animateFloat(0f, 360f, infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "turn")
    val drift by flow.animateFloat(-1f, 1f, infiniteRepeatable(tween(11_000), RepeatMode.Reverse), label = "drift")
    val sway by flow.animateFloat(-1f, 1f, infiniteRepeatable(tween(13_000), RepeatMode.Reverse), label = "sway")
    val soft = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Navy)) {
        val side = maxOf(maxWidth, maxHeight)
        if (!art.isNullOrEmpty()) {
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.Center).size(side * 1.15f).graphicsLayer { rotationZ = turn }.then(if (soft) Modifier.blur(80.dp) else Modifier),
            )
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(side * 0.7f).offset(x = maxWidth * (0.05f + 0.12f * drift), y = maxHeight * (0.08f + 0.1f * sway))
                    .graphicsLayer { rotationZ = -turn * 1.6f; alpha = 0.75f }.clip(CircleShape).then(if (soft) Modifier.blur(70.dp) else Modifier),
            )
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(side * 0.6f).offset(x = maxWidth * (0.4f - 0.1f * sway), y = maxHeight * (0.55f + 0.08f * drift))
                    .graphicsLayer { rotationZ = turn * 1.3f + 90f; alpha = 0.7f }.clip(CircleShape).then(if (soft) Modifier.blur(70.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (soft) 0.3f else 0.6f), Color.Black.copy(alpha = 0.7f)))))
    }
}

/** The song's place now, a few times a second. */
@Composable
private fun rememberPosition(): Pair<Long, Long> {
    val hub = App.instance.player
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = hub.position
            duration = hub.duration
            delay(200)
        }
    }
    return position to duration
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val hub = App.instance.player
    val current by hub.current.collectAsState()
    val playing by hub.playing.collectAsState()
    val shuffle by hub.shuffle.collectAsState()
    val repeat by hub.repeat.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<List<String>?>(null) } // open, with the lines to start with
    var showMixer by remember { mutableStateOf(false) }
    val mixable by App.instance.mixer.available.collectAsState()
    val t = current
    val (position, duration) = rememberPosition()
    Box(Modifier.fillMaxSize()) {
        Backdrop(t?.art)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                if (t != null) IconButton(onClick = { showMixer = true }) { Icon(Icons.Rounded.Tune, "Mixer", tint = Color.White) }
                IconButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Rounded.Mic, "Lyrics", tint = if (showLyrics) Brand.Orange else Color.White) }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = Color.White) }
                if (t != null) IconButton(onClick = { sharing = emptyList() }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (t != null && showLyrics) {
                    LyricsView(t, position, onShareLine = { sharing = listOf(it) })
                } else {
                    Artwork(t?.art, 320.dp, corner = 22.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t?.byline().orEmpty(), color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (t != null) Heart(t)
            }
            if (t != null) Row(Modifier.padding(top = 6.dp)) { Quality(t) }
            var dragging by remember { mutableStateOf<Float?>(null) }
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = dragging ?: fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { hub.seek((it * duration).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.padding(top = 10.dp),
            )
            Row {
                Text(clock(position / 1000), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(clock(duration / 1000), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hub.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) Brand.Orange else Color.White.copy(alpha = 0.7f)) }
                IconButton(onClick = { hub.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(38.dp)) }
                FilledIconButton(onClick = { hub.toggle() }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.Orange)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { hub.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(38.dp)) }
                IconButton(onClick = { hub.cycleRepeat() }) {
                    Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (repeat != Player.REPEAT_MODE_OFF) Brand.Orange else Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
    if (t != null) sharing?.let { start -> ShareSheet(t, start, onDismiss = { sharing = null }) }
    if (showQueue) ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = Brand.Navy) { QueueSheet() }
    if (showMixer && t != null) ModalBottomSheet(onDismissRequest = { showMixer = false }, containerColor = Brand.Navy) { MixerSheet(t, mixable) }
}

/** Each part as loud as you like (a split song); the song stays as loud as it was whole. And the effects, on every song. */
@Composable
private fun MixerSheet(t: Track, mixable: Boolean) {
    val mixer = App.instance.mixer
    val gains by mixer.gains.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
        Text("Mixer", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        if (mixable) {
            Text("Each part as loud as you like. Turning one down keeps the song as loud as it was whole.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
            Gap(12.dp)
            PartSliders(gains)
        } else {
            SplitPanel(t)
        }
        Gap(16.dp)
        Text("Effects", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Gap(4.dp)
        EffectSliders()
    }
}

@Composable
private fun PartSliders(gains: Map<String, Float>) {
    val mixer = App.instance.mixer
    Column {
        for ((name, label) in listOf("drums" to "Drums", "bass" to "Bass", "other" to "Other", "vocals" to "Vocals")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, modifier = Modifier.width(72.dp))
                Slider(
                    value = gains[name] ?: 1f,
                    onValueChange = { mixer.set(name, it) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f),
                )
                Text("${((gains[name] ?: 1f) * 100).toInt()} %", color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { mixer.preset(vocals = 0f, rest = 1f) }) { Text("No vocals") }
            FilledTonalButton(onClick = { mixer.preset(vocals = 1f, rest = 0f) }) { Text("Vocals only") }
            FilledTonalButton(onClick = { mixer.preset(vocals = 1f, rest = 1f) }) { Text("Reset") }
        }
    }
}

/** Not split yet: split it here (one at a time, in the background), and how many cores that may use. */
@Composable
private fun SplitPanel(t: Track) {
    val app = App.instance
    val splits = app.splits
    val queue by splits.queue.collectAsState()
    val now by splits.now.collectAsState()
    val problem by splits.problem.collectAsState()
    var cores by remember { mutableIntStateOf(splits.cores) }
    val context = LocalContext.current
    val askNotes = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { splits.add(t) }
    val here = now?.takeIf { it.track.key == t.key }
    val place = queue.indexOfFirst { it.key == t.key }
    Column {
        when {
            here != null -> {
                Text("Splitting this song", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(here.step.what, "${(here.step.done * 100).toInt()} %", here.step.secondsLeft?.let { SplitService.left(it) }).joinToString(" · "), color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(8.dp)
                LinearProgressIndicator(progress = { here.step.done }, color = Brand.Orange, trackColor = Color.White.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth())
                Gap(8.dp)
                Text("It goes on with the screen off. The Mixer's parts appear here when it's done.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
                Gap(8.dp)
                FilledTonalButton(onClick = { splits.remove(t.key) }) { Text("Stop splitting") }
            }
            place >= 0 -> {
                Text("Waiting to be split", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(when (place) { 0 -> "Starting in a moment."; 1 -> "Next, after the song being split now."; else -> "$place songs before it." }, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                Gap(8.dp)
                FilledTonalButton(onClick = { splits.remove(t.key) }) { Text("Don't split it") }
            }
            else -> {
                Text("Split this song into drums, bass, other and vocals, here on this phone, to mix them.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                if (t.file == null) Text("It's liked and saved first.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall)
                Gap(8.dp)
                Button(onClick = {
                    if (!app.library.isLiked(t.key)) app.like(t)
                    val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (granted) splits.add(t) else askNotes.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange)) { Text("Split this song") }
            }
        }
        problem?.let { Gap(6.dp); Text(it, color = Brand.Orange, style = MaterialTheme.typography.labelSmall) }
        Gap(12.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cores", color = Color.White, modifier = Modifier.width(72.dp))
            Slider(
                value = cores.toFloat(),
                onValueChange = { cores = it.roundToInt().coerceIn(1, Splits.MAX_CORES) },
                onValueChangeFinished = { splits.cores = cores },
                valueRange = 1f..Splits.MAX_CORES.toFloat(),
                steps = (Splits.MAX_CORES - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                modifier = Modifier.weight(1f),
            )
            Text("$cores of ${Splits.MAX_CORES}", color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp).padding(start = 8.dp))
        }
        Text("More cores split sooner; fewer keep the phone cooler. The next split uses this.", color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 72.dp))
    }
}

/** Low cut, High cut, Reverb: as the computer's Mixer (and Speaker EQ) make them. Off at the left. */
@Composable
private fun EffectSliders() {
    val context = App.instance
    var low by remember { mutableStateOf(Effects.lowSlider()) }
    var high by remember { mutableStateOf(Effects.highSlider()) }
    var reverb by remember { mutableStateOf(Effects.reverb.toFloat()) }
    fun hz(v: Double) = if (v >= 1000) "%.1f kHz".format(v / 1000) else "%.0f Hz".format(v)
    val lowHz = Effects.lowAt(low)
    val highHz = Effects.highAt(high)
    @Composable
    fun row(label: String, value: Float, shown: String, explain: String, onChange: (Float) -> Unit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, modifier = Modifier.width(72.dp))
                Slider(
                    value = value,
                    onValueChange = onChange,
                    onValueChangeFinished = { Effects.save(context) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Orange, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f),
                )
                Text(shown, color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp).padding(start = 8.dp))
            }
            Text(explain, color = Brand.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 72.dp))
        }
    }
    Column {
        row("Low cut", low, if (low < 0.002f) "Off" else hz(lowHz), if (low < 0.002f) "Off: all the bass" else "Takes out the bass below ${hz(lowHz)}") {
            low = it
            Effects.lowCut = if (it < 0.002f) Effects.LOW_OFF else Effects.lowAt(it)
        }
        row("High cut", high, if (high < 0.002f) "Off" else hz(highHz), if (high < 0.002f) "Off: all the highs" else "Takes out the sounds above ${hz(highHz)}") {
            high = it
            Effects.highCut = if (it < 0.002f) Effects.HIGH_OFF else Effects.highAt(it)
        }
        row("Reverb", reverb, if (reverb < 0.005f) "Off" else "${(reverb * 100).toInt()} %", if (reverb < 0.005f) "Off: the music as it is" else "A room around the music") {
            reverb = it
            Effects.reverb = if (it < 0.005f) 0.0 else it.toDouble()
        }
        Gap(8.dp)
        FilledTonalButton(onClick = {
            low = 0f; high = 0f; reverb = 0f
            Effects.lowCut = Effects.LOW_OFF; Effects.highCut = Effects.HIGH_OFF; Effects.reverb = 0.0
            Effects.save(context)
        }) { Text("Effects off") }
    }
}

/** The lyrics, the line being sung drawn big and kept in view; tap a line to go there. */
@Composable
private fun LyricsView(t: Track, positionMs: Long, onShareLine: (String) -> Unit) {
    val context = App.instance
    var lyrics by remember(t.key) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(t.key) { lyrics = withContext(Dispatchers.IO) { runCatching { Saver.lyrics(context, t) }.getOrDefault(Lyrics.None) } }
    when (val l = lyrics) {
        null -> Text("Looking for lyrics…", color = Color.White.copy(alpha = 0.7f))
        is Lyrics.None -> Text("No lyrics found for this song.", color = Color.White.copy(alpha = 0.7f))
        is Lyrics.Plain -> LazyColumn(contentPadding = PaddingValues(vertical = 24.dp)) {
            item { Text(l.text, color = Color.White, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold) }
        }
        is Lyrics.Timed -> TimedLyrics(l.lines, positionMs, onShareLine)
    }
}

/** Tap a line to go there; hold it to share it on a story. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimedLyrics(lines: List<Line>, positionMs: Long, onShareLine: (String) -> Unit) {
    val hub = App.instance.player
    val at = positionMs / 1000.0 + 0.3 // a line shows just before it's sung
    val now = lines.indexOfLast { it.start <= at }.coerceAtLeast(0)
    val state = rememberLazyListState()
    LaunchedEffect(now) { state.animateScrollToItem((now - 2).coerceAtLeast(0)) }
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 120.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { i, line ->
            val sung = i == now
            val size by animateFloatAsState(if (sung) 34f else 21f, tween(350), label = "size")
            val alpha by animateFloatAsState(if (sung) 1f else 0.4f, tween(350), label = "alpha")
            Text(
                line.text,
                color = Color.White.copy(alpha = alpha),
                fontSize = size.sp,
                lineHeight = (size * 1.25f).sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { hub.seek((line.start * 1000).toLong()) }, onLongClick = { onShareLine(line.text) }).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QueueSheet() {
    val hub = App.instance.player
    val queue by hub.queue.collectAsState()
    val index by hub.index.collectAsState()
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SectionTitle("Queue")
        Note("${(queue.size - index - 1).coerceAtLeast(0)} songs next")
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp)) {
            itemsIndexed(queue) { i, t ->
                TrackRow(t, onPlay = { hub.playAt(i) }, highlight = i == index, trailing = {
                    if (i != index) {
                        IconButton(onClick = { if (i > 0) hub.move(i, i - 1) }) { Icon(Icons.Rounded.ArrowUpward, "Move up", tint = Brand.Dim) }
                        IconButton(onClick = { if (i < queue.size - 1) hub.move(i, i + 1) }) { Icon(Icons.Rounded.ArrowDownward, "Move down", tint = Brand.Dim) }
                        IconButton(onClick = { hub.remove(i) }) { Icon(Icons.Rounded.Close, "Remove from the queue", tint = Brand.Dim) }
                    }
                })
            }
        }
    }
}

/** Above the tabs: the song playing; tap it for Now Playing. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val hub = App.instance.player
    val current by hub.current.collectAsState()
    val playing by hub.playing.collectAsState()
    val t = current ?: return
    val (position, duration) = rememberPosition()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Card).clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.art, 44.dp, corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { hub.toggle() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
            IconButton(onClick = { hub.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White) }
        }
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            color = Brand.Orange,
            trackColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}
