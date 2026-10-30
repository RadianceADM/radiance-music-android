package io.github.radianceadm.music.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

/**
 * How to use it, the first time the app opens (owner, 2026-10-31: "add a easy how
 * to use, upon first enter on app"): five cards to swipe through, a few plain
 * words each. Skip any time; Settings → How to use shows them again.
 */
object Guide {
    private const val PREFS = "guide"

    /** Not seen yet: the first time the app opens. */
    fun due(context: Context) = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("seen", false)

    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seen", true).apply()

    data class Card(val icon: ImageVector, val title: String, val words: String)

    val CARDS = listOf(
        Card(Icons.Rounded.Search, "Search any song", "Type a song or an artist. Lossless plays first when there is one; YouTube Music has the rest."),
        Card(Icons.Rounded.Favorite, "Like it, it's yours", "Tap the heart. The song, its cover, its lyrics and its music video are saved on your phone and play with no internet."),
        Card(Icons.AutoMirrored.Rounded.CallSplit, "Split it, mix it", "In Now Playing, open the Mixer and split a liked song into drums, bass, other and vocals, right on your phone. No vocals makes it karaoke."),
        Card(Icons.Rounded.IosShare, "Put it on your story", "Tap Share: the cover, or up to 150 characters of lyrics. With sound, your story plays the song itself."),
        Card(Icons.Rounded.SwipeLeft, "Swipe, send, update", "Swipe the cover for the next or previous song. Send songs to another phone by QR, and get updates, in Settings."),
    )
}

@Composable
fun GuideScreen(onDone: () -> Unit) {
    val pages = Guide.CARDS
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }
    Box(Modifier.fillMaxSize().background(Brand.Night)) {
        // the sunset's glow, low down, as the start screen's
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Brand.Orange.copy(alpha = 0.22f), Color.Transparent), radius = 1400f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("How to use it", color = Brand.Dim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                if (!last) TextButton(onClick = onDone) { Text("Skip", color = Color.White) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val card = pages[i]
                // the card moving away fades and settles back a little
                val off = (pager.currentPage - i + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                Column(
                    Modifier.fillMaxSize().graphicsLayer { alpha = 1f - off * 0.6f; scaleX = 1f - off * 0.08f; scaleY = 1f - off * 0.08f }.padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(168.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Brand.Glow.copy(alpha = 0.55f), Brand.Orange.copy(alpha = 0.18f), Color.Transparent))), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(104.dp).clip(CircleShape).background(Brand.Card), contentAlignment = Alignment.Center) {
                            Icon(card.icon, null, tint = Brand.Orange, modifier = Modifier.size(52.dp))
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    Text(card.title, color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(card.words, color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
                }
            }
            // where you are: a dot each, the current one long
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    val on = i == pager.currentPage
                    val w by animateDpAsState(if (on) 24.dp else 8.dp, label = "dot")
                    val a by animateFloatAsState(if (on) 1f else 0.3f, label = "dotAlpha")
                    Box(Modifier.padding(horizontal = 4.dp).size(w, 8.dp).clip(CircleShape).background(Color.White.copy(alpha = a)))
                }
            }
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Brand.Orange)
                    .clickable { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (last) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (last) "Start listening" else "Next", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

