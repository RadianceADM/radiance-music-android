package io.github.radianceadm.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Group
import io.github.radianceadm.music.data.Shelf
import io.github.radianceadm.music.data.ShelfKind

/*
 * Albums, Artists and Genres (android#38; owner, 2026-11-08: "asan na yung genres / artists / songs like this"), as
 * the computer app has them: Library's pills beside Liked Songs and History, each a grid of cards; a card opens its
 * songs, with Play and Shuffle; Home has a row of each. The grid's columns follow the width (a phone two or three,
 * a tablet or a car's stereo more).
 */

/** How many songs, in words. */
private fun songs(n: Int) = "$n song${if (n == 1) "" else "s"}"

/** The line under a card: an album's artist (when it's one), else how many songs. */
private fun under(kind: ShelfKind, g: Group): String {
    if (kind == ShelfKind.Albums) g.songs.map { it.artist }.distinct().singleOrNull()?.let { return it }
    return songs(g.songs.size)
}

/** A genre's tile colour: one of Radiance's warm ones, the same for the same genre every time. */
private val GENRE_TONES = listOf(Color(0xFF8A3B12), Color(0xFF5C2A12), Color(0xFF7A4A10), Color(0xFF3F2A55), Color(0xFF1F4D45), Color(0xFF6B1F2E), Color(0xFF2D3E66), Color(0xFF4B5320))

private fun toneOf(name: String) = GENRE_TONES[Math.floorMod(name.lowercase().hashCode(), GENRE_TONES.size)]

/**
 * An album (its cover), an artist (a song's cover in a circle: there are no artist photos) or a genre (a warm tile
 * with a cover leaning in its corner), its name and a line under it.
 */
@Composable
fun GroupCard(kind: ShelfKind, g: Group, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val art = g.songs.firstOrNull { it.art.isNotEmpty() }?.art
    Column(
        modifier.pressScale(press).clip(RoundedCornerShape(16.dp)).clickable(interactionSource = press, indication = null, onClick = onOpen).padding(bottom = 4.dp),
        horizontalAlignment = if (kind == ShelfKind.Artists) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        when (kind) {
            ShelfKind.Albums -> Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Brand.Card), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Album, null, tint = Brand.Dim.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            ShelfKind.Artists -> Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Brand.Card), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, tint = Brand.Dim.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            ShelfKind.Genres -> Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(toneOf(g.name), toneOf(g.name).copy(alpha = 0.55f).compositeOver(Color.Black)))),
            ) {
                if (art != null) AsyncImage(
                    art, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).aspectRatio(1f).offset(x = 14.dp, y = 6.dp)
                        .graphicsLayer { rotationZ = 22f }.clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    g.name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(14.dp).fillMaxWidth(0.85f),
                )
            }
        }
        if (kind != ShelfKind.Genres) Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(under(kind, g), style = MaterialTheme.typography.bodyMedium, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (kind == ShelfKind.Genres) 8.dp else 0.dp))
    }
}

/** Wider than its place by [by] on both sides (a grid's own padding undone for a full-width header). */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val p = by.roundToPx()
    val wide = constraints.maxWidth + 2 * p
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-p, 0) }
}

private fun Color.compositeOver(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

/**
 * Library's Albums, Artists or Genres: the grid of them under [header] (the page's title and pills), or the one
 * [opened], its songs. Back (or the arrow) goes back to the grid; the two crossfade (blueprint rule 16).
 */
@Composable
fun ShelfPage(pad: PaddingValues, kind: ShelfKind, header: @Composable () -> Unit, opened: String?, onOpen: (String?) -> Unit) {
    val liked by App.instance.library.liked.collectAsState()
    val groups = remember(liked, kind) { Shelf.groups(liked, kind) }
    val group = opened?.let { name -> groups.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    BackHandler(enabled = opened != null) { onOpen(null) }
    AnimatedContent(
        targetState = group,
        transitionSpec = { fadeIn(Motion.enter(Motion.PAGE)) togetherWith fadeOut(Motion.exit(Motion.PAGE * 8 / 10)) },
        contentKey = { it?.name },
        label = "shelf",
    ) { g ->
        if (g == null) ShelfGrid(pad, kind, groups, header, onOpen) else GroupSongs(pad, kind, g, onBack = { onOpen(null) })
    }
}

@Composable
private fun ShelfGrid(pad: PaddingValues, kind: ShelfKind, groups: List<Group>, header: @Composable () -> Unit, onOpen: (String?) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // the title and pills reach the page's edges, as on Liked Songs (the cards keep 16 dp from them)
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.bleed(16.dp)) { header() } }
        if (groups.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Note(
                    when (kind) {
                        ShelfKind.Albums -> "Like songs (the heart) and their albums show here."
                        ShelfKind.Artists -> "Like songs (the heart) and everyone on them shows here."
                        ShelfKind.Genres -> "Like songs (the heart) and their genres show here, looked up as they're saved."
                    },
                )
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${groups.size} ${kind.title.lowercase().let { if (groups.size == 1) it.removeSuffix("s") else it }}",
                    color = Brand.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
        }
        items(groups, key = { it.name.lowercase() }) { g -> GroupCard(kind, g) { onOpen(g.name) } }
    }
}

/** One album, artist or genre: its picture and name, Play and Shuffle, then its songs. */
@Composable
private fun GroupSongs(pad: PaddingValues, kind: ShelfKind, g: Group, onBack: () -> Unit) {
    val hub = App.instance.player
    val art = g.songs.firstOrNull { it.art.isNotEmpty() }?.art
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to ${kind.title}", tint = Color.White) }
                Text(kind.title, color = Brand.Dim, style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val shape = if (kind == ShelfKind.Artists) CircleShape else RoundedCornerShape(16.dp)
                Box(
                    Modifier.size(112.dp).clip(shape).background(if (kind == ShelfKind.Genres) toneOf(g.name) else Brand.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (kind != ShelfKind.Genres && art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else if (kind == ShelfKind.Genres && art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = 22f }.clip(RoundedCornerShape(8.dp)))
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(under(kind, g).let { if (kind == ShelfKind.Albums && it != songs(g.songs.size)) "$it · ${songs(g.songs.size)}" else it }, color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(Icons.Rounded.PlayArrow, "Play", g.songs.isNotEmpty(), Modifier.weight(1f)) { hub.play(g.songs, 0) }
                BigButton(Icons.Rounded.Shuffle, "Shuffle", g.songs.isNotEmpty(), Modifier.weight(1f)) { hub.play(g.songs.shuffled(), 0) }
            }
        }
        itemsIndexed(g.songs, key = { _, t -> t.key }) { i, t ->
            Box(Modifier.padding(horizontal = 8.dp)) { TrackRow(t, onPlay = { hub.play(g.songs, i) }) }
        }
    }
}

/** Home's row of albums, artists or genres (the real ones: no Singles, none still being looked up); a card opens it in Library. */
@Composable
fun ShelfRow(kind: ShelfKind, groups: List<Group>, size: Dp = 150.dp) {
    val shown = groups.filter { it.name != Shelf.SINGLES && it.name != Shelf.FINDING && it.name != Shelf.OTHER }.sortedByDescending { it.songs.size }.take(20)
    if (shown.isEmpty()) return
    Column {
        SectionTitle(kind.title, Modifier.padding(top = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.name.lowercase() }) { g -> GroupCard(kind, g, Modifier.width(size)) { Shelf.open.value = kind to g.name } }
        }
    }
}
