package io.github.radianceadm.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Sheet)) {
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

/**
 * Now Playing, as Apple Music's (owner, 2026-10-31): the cover big, a little smaller
 * while paused; swipe it left or right for the next or previous song, and down to
 * close. Under it the song, a thin white bar, white controls, and a row of Lyrics
 * (a quote bubble, not a mic), Mixer, Share and Queue. With lyrics on, the cover
 * goes small at the top beside the song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val hub = App.instance.player
    val current by hub.current.collectAsState()
    val playing by hub.playing.collectAsState()
    val around by hub.around.collectAsState()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<List<String>?>(null) } // open, with the lines to start with
    var showMixer by remember { mutableStateOf(false) }
    val mixable by App.instance.mixer.available.collectAsState()
    val t = current
    val (position, duration) = rememberPosition()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // pulled down (from the top or the cover): far enough closes it
    val pull = remember { Animatable(0f) }
    val pulled = { dy: Float -> scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) }; Unit }
    val let = { velocity: Float ->
        scope.launch {
            if (pull.value > with(density) { 140.dp.toPx() } || velocity > 1800f) onClose() else pull.animateTo(0f, spring(dampingRatio = 0.8f))
        }
        Unit
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull.value }.clip(RoundedCornerShape(if (pull.value > 1f) 28.dp else 0.dp))) {
        Backdrop(t?.art)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp)) {
            Grabber(onClose, pulled, let)
            if (t != null && showLyrics) {
                Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(60.dp).shadow(10.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Brand.Card)) {
                        if (!t.art.isNullOrEmpty()) AsyncImage(t.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(14.dp))
                    SongTitle(t, Modifier.weight(1f), small = true)
                    Heart(t)
                    More(t, onShare = { sharing = emptyList() }, onMixer = { showMixer = true })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { LyricsView(t, position, onShareLine = { sharing = listOf(it) }) }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SwipeCover(t, around, playing, pulled, let)
                }
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    SongTitle(t, Modifier.weight(1f), small = false)
                    if (t != null) {
                        Heart(t)
                        More(t, onShare = { sharing = emptyList() }, onMixer = { showMixer = true })
                    }
                }
            }
            Scrubber(position, duration, t) { hub.seek((it * duration).toLong()) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hub.previous() }, modifier = Modifier.size(72.dp)) { Icon(Icons.Rounded.FastRewind, "Previous", tint = Color.White, modifier = Modifier.size(44.dp)) }
                IconButton(onClick = { hub.toggle() }, modifier = Modifier.size(88.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(64.dp))
                }
                IconButton(onClick = { hub.next() }, modifier = Modifier.size(72.dp)) { Icon(Icons.Rounded.FastForward, "Next", tint = Color.White, modifier = Modifier.size(44.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundToggle(showLyrics, "Lyrics", { showLyrics = !showLyrics }) { on -> LyricsIcon(on) }
                RoundToggle(false, "Mixer", { if (t != null) showMixer = true }) { Icon(Icons.Rounded.Tune, null, tint = Color.White.copy(alpha = 0.85f)) }
                RoundToggle(false, "Share", { if (t != null) sharing = emptyList() }) { Icon(Icons.Rounded.IosShare, null, tint = Color.White.copy(alpha = 0.85f)) }
                RoundToggle(false, "Queue", { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, tint = Color.White.copy(alpha = 0.85f)) }
            }
        }
    }
    if (t != null) sharing?.let { start -> ShareSheet(t, start, onDismiss = { sharing = null }) }
    if (showQueue) ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = Brand.Sheet) { QueueSheet() }
    if (showMixer && t != null) ModalBottomSheet(onDismissRequest = { showMixer = false }, containerColor = Brand.Sheet) { MixerSheet(t, mixable) }
}

/** The handle at the top: tap it, or pull it down, to close. */
@Composable
private fun Grabber(onClose: () -> Unit, pulled: (Float) -> Unit, let: (Float) -> Unit) {
    val tracker = remember { VelocityTracker() }
    Box(
        Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onClose).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onVerticalDrag = { ch, dy -> tracker.addPosition(ch.uptimeMillis, ch.position); pulled(dy); ch.consume() },
                onDragEnd = { let(tracker.calculateVelocity().y) },
                onDragCancel = { let(0f) },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
    }
}

@Composable
private fun SongTitle(t: Track?, modifier: Modifier, small: Boolean) {
    Column(modifier) {
        Text(
            t?.title ?: "Nothing playing", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        )
        Text(t?.byline().orEmpty(), color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
    }
}

/** ⋯ beside the song: share it, send it to a phone, the Mixer. */
@Composable
private fun More(t: Track, onShare: () -> Unit, onMixer: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, "More", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Brand.Card) {
            DropdownMenuItem(text = { Text("Share to a story…") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, onClick = { open = false; onShare() })
            val lib = App.instance.library
            if (t.file != null || lib.best(t).file != null) DropdownMenuItem(
                text = { Text("Send to a phone…") }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) },
                onClick = { open = false; App.instance.shareOpen.value = App.ShareAsk(listOf(lib.best(t))) },
            )
            DropdownMenuItem(text = { Text("Mixer") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) }, onClick = { open = false; onMixer() })
        }
    }
}

/**
 * The cover, with the songs either side of it a swipe away: it follows your
 * finger, and let go past a third (or flicked) it slides on to that song. At
 * either end it only gives a little. Pulled down instead, Now Playing closes.
 */
@Composable
private fun SwipeCover(t: Track?, around: Pair<Track?, Track?>, playing: Boolean, pulled: (Float) -> Unit, let: (Float) -> Unit) {
    val hub = App.instance.player
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val scale by animateFloatAsState(if (playing || t == null) 1f else 0.8f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")
    val (prev, next) = around
    // the new song is in the middle now: no slide left over
    LaunchedEffect(t?.key) { offset.snapTo(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val w = constraints.maxWidth.toFloat()
        val gap = w * 1.12f
        val shape = RoundedCornerShape(14.dp)
        val gesture = Modifier.pointerInput(prev?.key, next?.key, t?.key) {
            val tracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                tracker.resetTracking()
                var axis = 0 // 1: across (songs), 2: down (close)
                var moved = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent()
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.positionChange()
                    moved += d
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    if (axis == 0 && moved.getDistance() > viewConfiguration.touchSlop) axis = if (abs(moved.x) >= abs(moved.y)) 1 else 2
                    when (axis) {
                        1 -> {
                            val blocked = (offset.value + d.x > 0 && prev == null) || (offset.value + d.x < 0 && next == null)
                            scope.launch { offset.snapTo(offset.value + if (blocked) d.x * 0.3f else d.x) }
                            ch.consume()
                        }
                        2 -> { pulled(d.y); ch.consume() }
                    }
                }
                val v = tracker.calculateVelocity()
                when (axis) {
                    1 -> {
                        val go = when {
                            next != null && (offset.value < -w * 0.33f || v.x < -1500f) -> -1
                            prev != null && (offset.value > w * 0.33f || v.x > 1500f) -> 1
                            else -> 0
                        }
                        scope.launch {
                            if (go == 0) {
                                offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                            } else {
                                val was = t?.key
                                offset.animateTo(go * gap, tween(230, easing = FastOutSlowInEasing))
                                if (go < 0) hub.next() else hub.previousSong()
                                delay(1500)
                                if (hub.current.value?.key == was) offset.animateTo(0f) // it didn't change: back
                            }
                        }
                    }
                    2 -> let(v.y)
                }
            }
        }
        Box(Modifier.fillMaxSize().then(gesture)) {
            prev?.let { Cover(it.art, shape, Modifier.graphicsLayer { translationX = offset.value - gap; scaleX = 0.8f; scaleY = 0.8f }) }
            next?.let { Cover(it.art, shape, Modifier.graphicsLayer { translationX = offset.value + gap; scaleX = 0.8f; scaleY = 0.8f }) }
            Cover(t?.art, shape, Modifier.graphicsLayer { translationX = offset.value; scaleX = scale; scaleY = scale })
        }
    }
}

@Composable
private fun Cover(art: String?, shape: RoundedCornerShape, modifier: Modifier) {
    Box(modifier.fillMaxSize().shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(Brand.Card), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Brand.Dim.copy(alpha = 0.5f), modifier = Modifier.size(96.dp))
        if (!art.isNullOrEmpty()) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A thin white bar (thicker while you drag it), the time gone and left, and the quality in the middle. */
@Composable
private fun Scrubber(position: Long, duration: Long, t: Track?, onSeek: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thick by animateDpAsState(if (drag != null) 12.dp else 7.dp, label = "bar")
    Column(Modifier.padding(top = 14.dp)) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(duration) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f); ch.consume() },
                        onDragEnd = { drag?.let(onSeek); drag = null },
                        onDragCancel = { drag = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(thick).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(fraction).height(thick).clip(CircleShape).background(Color.White.copy(alpha = if (drag != null) 1f else 0.7f)))
        }
        val at = if (duration > 0) (fraction * duration).toLong() else position
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(at / 1000), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (t != null) Quality(t)
            Text("-" + clock(((duration - at).coerceAtLeast(0)) / 1000), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** A button in the bottom row: lit (white, round) while it's on. */
@Composable
private fun RoundToggle(on: Boolean, label: String, onClick: () -> Unit, icon: @Composable (Boolean) -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            icon(on)
        }
    }
}

/** Lyrics: a speech bubble with quote marks in it (as Apple Music's), not a mic. */
@Composable
private fun LyricsIcon(on: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Icon(if (on) Icons.Rounded.ChatBubble else Icons.Rounded.ChatBubbleOutline, null, tint = if (on) Color.Black else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
        Icon(Icons.Rounded.FormatQuote, null, tint = if (on) Color.White else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp).offset(y = (-1.5).dp))
    }
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
    LaunchedEffect(now) { state.animateScrollToItem((now - 1).coerceAtLeast(0)) }
    LazyColumn(state = state, contentPadding = PaddingValues(top = 40.dp, bottom = 240.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { i, line ->
            val sung = i == now
            val alpha by animateFloatAsState(if (sung) 1f else if (i < now) 0.28f else 0.38f, tween(400), label = "alpha")
            val grow by animateFloatAsState(if (sung) 1f else 0.94f, tween(400), label = "grow")
            Text(
                line.text,
                color = Color.White.copy(alpha = alpha),
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = grow; scaleY = grow; transformOrigin = TransformOrigin(0f, 0.5f) }
                    .clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { hub.seek((line.start * 1000).toLong()) }, onLongClick = { onShareLine(line.text) }).padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun QueueSheet() {
    val hub = App.instance.player
    val queue by hub.queue.collectAsState()
    val index by hub.index.collectAsState()
    val shuffle by hub.shuffle.collectAsState()
    val repeat by hub.repeat.collectAsState()
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Playing next")
                Text("${(queue.size - index - 1).coerceAtLeast(0)} songs", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp))
            }
            QueueToggle(shuffle, "Shuffle", { hub.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, null, tint = it) }
            Spacer(Modifier.width(8.dp))
            QueueToggle(repeat != Player.REPEAT_MODE_OFF, "Repeat", { hub.cycleRepeat() }) {
                Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = it)
            }
        }
        Gap(8.dp)
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

/** Shuffle or Repeat in the queue's header: a rounded square, lit while on. */
@Composable
private fun QueueToggle(on: Boolean, label: String, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Box(
        Modifier.size(44.dp, 34.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon(if (on) Color.Black else Color.White) }
}

/** Above the tabs: the song playing; tap it for Now Playing, swipe it for the next or previous song. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val hub = App.instance.player
    val current by hub.current.collectAsState()
    val playing by hub.playing.collectAsState()
    val around by hub.around.collectAsState()
    val t = current ?: return
    val (position, duration) = rememberPosition()
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Card).clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(8.dp).pointerInput(t.key, around.first?.key, around.second?.key) {
                val w = size.width.toFloat()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { ch, dx -> scope.launch { slide.snapTo(slide.value + dx) }; ch.consume() },
                    onDragEnd = {
                        scope.launch {
                            val go = when {
                                slide.value < -w * 0.25f && around.second != null -> -1
                                slide.value > w * 0.25f && around.first != null -> 1
                                else -> 0
                            }
                            if (go == 0) { slide.animateTo(0f, spring(dampingRatio = 0.8f)); return@launch }
                            val was = t.key
                            slide.animateTo(go * w, tween(160))
                            if (go < 0) hub.next() else hub.previousSong()
                            withTimeoutOrNull(1500) { hub.current.first { it?.key != was } }
                            slide.snapTo(-go * w) // the new song comes in from the other side
                            slide.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                        }
                    },
                    onDragCancel = { scope.launch { slide.animateTo(0f) } },
                )
            }.graphicsLayer { translationX = slide.value; alpha = 1f - (abs(slide.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.7f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(t.art, 44.dp, corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { hub.toggle() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
            IconButton(onClick = { hub.next() }) { Icon(Icons.Rounded.FastForward, "Next", tint = Color.White) }
        }
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            color = Brand.Orange,
            trackColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}
