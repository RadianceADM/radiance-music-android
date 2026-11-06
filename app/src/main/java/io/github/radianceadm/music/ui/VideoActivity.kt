package io.github.radianceadm.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.play.MusicVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A song's music video, full screen (the button on its cover in Now Playing,
 * android#26). The song pauses while it plays; Close (or Back) comes back to it.
 * It turns with the phone, as the phone's own rotation setting allows.
 */
@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent.getStringExtra(TRACK)?.let { runCatching { Track.fromJson(JSONObject(it)) }.getOrNull() }
        if (t == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        App.instance.player.pause()
        // a film's sound: it takes the phone's audio from the song (which pauses)
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        player = p
        setContent { RadianceTheme { VideoScreen(t, p, onClose = ::finish) } }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val TRACK = "track"

        fun open(context: Context, t: Track) {
            context.startActivity(Intent(context, VideoActivity::class.java).putExtra(TRACK, t.toJson().toString()))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoScreen(t: Track, player: ExoPlayer, onClose: () -> Unit) {
    val context = LocalContext.current
    // in the middle while there's no picture yet: finding it, or why it won't play
    var note by remember { mutableStateOf<String?>("Finding “${t.title}”'s music video…") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(t.key) {
        val source = runCatching { withContext(Dispatchers.IO) { MusicVideo.source(context, t) } }
        source.onSuccess { s ->
            if (s == null) {
                busy = false
                note = if (Connectivity.online.value) "“${t.title}” has no music video." else "You're offline, and “${t.title}”'s music video isn't on this phone yet."
            } else {
                player.setMediaSource(s)
                player.prepare()
                player.play()
            }
        }.onFailure {
            busy = false
            note = "Couldn't get the music video. ${Errors.say(it)}"
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                busy = false
                note = null
            }

            override fun onPlayerError(error: PlaybackException) {
                busy = false
                note = "The music video stopped. ${Errors.say(error)}"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(false)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        note?.let { text ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy) CircularProgressIndicator(color = Color.White)
                Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
        }
    }
}
