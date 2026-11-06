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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.radianceadm.music.server.Servers
import io.github.radianceadm.music.split.Setup

class MainActivity : ComponentActivity() {
    // your library from the computer: read once it may be (see Import)
    private val readMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Import.waiting(this)) App.instance.importLibrary() else App.instance.findSaved(auto = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        App.instance.player.connect()
        Setup.start(App.instance) // the splitter's model, the first time the app opens (or going on): the start screen shows it
        setContent { RadianceTheme { Radiance() } }
        opened(intent)
        val needed = readMediaPermissions()
        if (Import.waiting(this)) readMedia.launch(needed)
        else if (App.instance.library.liked.value.isEmpty()) {
            // Liked Songs empty (a new phone, or the app uninstalled and on again): the songs saved here come back
            val prefs = getSharedPreferences("found", MODE_PRIVATE)
            if (needed.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) App.instance.findSaved(auto = true)
            else if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                readMedia.launch(needed)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** A Radiance Music share's QR read by the phone's camera: straight to it. A server's invite (its join page): Settings → Servers, to join. */
    private fun opened(intent: Intent?) {
        // the sharing notification, tapped: its sheet (what's moving, Stop)
        if (intent?.getBooleanExtra(io.github.radianceadm.music.share.ShareService.OPEN, false) == true) {
            if (App.instance.shareOpen.value == null) App.instance.shareOpen.value = App.ShareAsk(null)
            return
        }
        val data = intent?.data ?: return
        if (data.scheme != "radiancemusic") return
        Servers.joinLink(data.toString())?.let {
            Servers.asked.value = it
            return
        }
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
    // the listening party's screen (you're in one): the mini player opens it in place of Now Playing
    var party by rememberSaveable { mutableStateOf(false) }
    // a server's invite opened from its join page: Settings, where Servers asks to join it
    val asked by Servers.asked.collectAsState()
    LaunchedEffect(asked) { if (asked != null) settings = true }
    val ctx = LocalContext.current
    // how to use it: the first time the app opens (under the start's splash, seen as it opens)
    var guide by rememberSaveable { mutableStateOf(Guide.due(ctx)) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Brand.Night,
            bottomBar = {
                Column {
                    OfflinePill() // offline: a small pill over the song playing
                    MiniPlayer(onOpen = { if (App.instance.party.joined) party = true else nowPlaying = true })
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
                Tab.Library -> LibraryScreen(pad, onSettings = { settings = true }, onParty = { party = true })
            }
        }
        AnimatedVisibility(nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlayingScreen(onClose = { nowPlaying = false })
        }
        AnimatedVisibility(settings, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            SettingsScreen(onClose = { settings = false }, onGuide = { guide = true }, onOpenServer = { s ->
                // its library, in Library
                Servers.open.value = s.first
                tab = Tab.Library
                settings = false
            })
        }
        AnimatedVisibility(party, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PartyScreen(onClose = { party = false })
        }
        Notices() // what to tell you (in place of toasts), at the top, over every screen
        if (guide) GuideScreen(onDone = { Guide.seen(ctx); guide = false })
        val sharing by App.instance.shareOpen.collectAsState()
        sharing?.let { ask -> ShareSongsSheet(ask.songs, ask.scan, onDismiss = { App.instance.shareOpen.value = null }) }
        // the start's sunrise: once a cold start (not with animations turned off, unless the splitter's still getting ready)
        val context = LocalContext.current
        val still = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        // the splitter getting ready (the first time the app opens): the start screen stays, showing how far along
        var splash by remember { mutableStateOf(!App.instance.splashed && (!still || Setup.waiting())) }
        LaunchedEffect(Unit) { if (!splash) App.instance.splashed = true } // no splash this time: notices may show now
        if (splash) SetupSplash(still, onDone = { App.instance.splashed = true; splash = false })
        // a newer version out: the pop-up (after the splash and the first-time guide)
        UpdatePrompt(shown = !splash && !guide)
        // now and then, at a free moment: are you enjoying it? (never over the splash or the guide)
        FeedbackPrompt(shown = !splash && !guide)
        // the music videos moved into the app: their old copies out of Gallery, asked once (android#26)
        OldVideosPrompt(shown = !splash && !guide)
    }
}

/** What reading the songs and videos saved on this phone takes. */
fun readMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
