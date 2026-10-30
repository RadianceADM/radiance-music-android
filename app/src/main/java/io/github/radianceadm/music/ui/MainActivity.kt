package io.github.radianceadm.music.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Import

class MainActivity : ComponentActivity() {
    // your library from the computer: read once it may be (see Import)
    private val readMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { App.instance.importLibrary() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        App.instance.player.connect()
        setContent { RadianceTheme { Radiance() } }
        opened(intent)
        if (Import.waiting(this)) {
            val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            readMedia.launch(needed)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** A Radiance Music share's QR read by the phone's camera: straight to it. */
    private fun opened(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "radiancemusic") return
        App.instance.share.scanned(data.toString())
        App.instance.shareOpen.value = App.ShareAsk(null)
    }

    override fun onResume() {
        super.onResume()
        App.instance.splits.start() // songs left waiting to be split go on
        io.github.radianceadm.music.update.Updates.resumed(this) // back from allowing updates to install
    }

    override fun onStop() {
        App.instance.player.saveSession() // where you left off
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Search("Search", Icons.Rounded.Search),
    Library("Library", Icons.Rounded.LibraryMusic),
}

@Composable
private fun Radiance() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    // how to use it: the first time the app opens (under the start's splash, seen as it opens)
    var guide by rememberSaveable { mutableStateOf(Guide.due(ctx)) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Brand.Night,
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nowPlaying = true })
                    NavigationBar(containerColor = Brand.Night) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Brand.Orange, indicatorColor = Brand.Orange.copy(alpha = 0.3f), unselectedIconColor = Brand.Dim, unselectedTextColor = Brand.Dim),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            when (tab) {
                Tab.Home -> HomeScreen(pad, onSearch = { tab = Tab.Search }, onSettings = { settings = true })
                Tab.Search -> SearchScreen(pad)
                Tab.Library -> LibraryScreen(pad, onSettings = { settings = true })
            }
        }
        AnimatedVisibility(nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlayingScreen(onClose = { nowPlaying = false })
        }
        AnimatedVisibility(settings, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            SettingsScreen(onClose = { settings = false }, onGuide = { guide = true })
        }
        if (guide) GuideScreen(onDone = { Guide.seen(ctx); guide = false })
        val sharing by App.instance.shareOpen.collectAsState()
        sharing?.let { ask -> ShareSongsSheet(ask.songs, ask.scan, onDismiss = { App.instance.shareOpen.value = null }) }
        // the start's sunrise: once a cold start (not with animations turned off)
        val context = LocalContext.current
        var splash by remember {
            val still = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            mutableStateOf(!App.instance.splashed && !still)
        }
        if (splash) StartSplash(onDone = { App.instance.splashed = true; splash = false })
    }
}
