package io.github.radianceadm.music

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.radianceadm.music.data.Found
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.data.Library
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.server.Party
import io.github.radianceadm.music.server.Servers
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.radianceadm.music.net.NewPipeDownloader
import io.github.radianceadm.music.play.Effects
import io.github.radianceadm.music.play.Mixer
import io.github.radianceadm.music.play.PlayerHub
import io.github.radianceadm.music.share.Session
import io.github.radianceadm.music.split.Setup
import io.github.radianceadm.music.split.Splits
import io.github.radianceadm.music.ui.Notice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class App : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var library: Library
    lateinit var player: PlayerHub
    lateinit var mixer: Mixer
    lateinit var splits: Splits
    lateinit var share: Session
    /** The listening party this phone is in (on a server you joined), or none. */
    lateinit var party: Party
    /** The Share songs sheet, open (for these songs; null: Liked Songs), or not. */
    val shareOpen = MutableStateFlow<ShareAsk?>(null)

    /** The start's splash has shown (once a process: a cold start). Notices wait for it. */
    var splashed by mutableStateOf(false)

    private val saving = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    /** What's being saved now ("Saving “Song”…"), or null. */
    val status: StateFlow<String?> = _status

    // notices waiting for the screen (kept while the app is away; the oldest go past a few)
    private val _notices = Channel<Notice>(8, BufferOverflow.DROP_OLDEST)
    /** What to tell you, one at a time (the screen shows them: Notices). */
    val notices: Flow<Notice> = _notices.receiveAsFlow()

    /** Tells you [n] (a notice at the top of the screen, in place of a toast). */
    fun tell(n: Notice) {
        _notices.trySend(n)
    }

    /** "You're offline" said once while it lasts (not once a song). */
    @Volatile private var toldOffline = false
    private var missing: Job? = null
    @Volatile private var again = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        NewPipe.init(NewPipeDownloader(), Localization("en", "US"))
        Connectivity.start(this) // online or not, watched from now
        Servers.load(this) // the Radiance Music Servers you joined (their keys go with what's asked of them)
        Effects.load(this)
        io.github.radianceadm.music.ui.Looks.load(this) // True Black or Stone
        library = Library(this)
        player = PlayerHub(this)
        mixer = Mixer(this)
        splits = Splits(this)
        io.github.radianceadm.music.split.Ahead(this).start() // karaoke on: the song playing and the next one get split (android#20)
        share = Session(this)
        party = Party(this)
        saveMissing()
        // back online: liked songs that couldn't be saved meanwhile are saved now
        scope.launch {
            Connectivity.online.drop(1).filter { it }.collect {
                toldOffline = false
                saveMissing()
            }
        }
        Setup.check(this) // the splitter's model here or not (it downloads once the app opens: MainActivity)
        io.github.radianceadm.music.update.Updates.atStart(this) // a newer version out? (over the air)
    }

    /** Like it, save it: liked now, saved in the background (one song at a time). */
    fun like(t: Track) {
        library.like(t)
        scope.launch { saveOne(t) }
    }

    fun unlike(t: Track) = library.unlike(t)

    /** Like it and wait for it to be saved (a server's song, Save): saved or not (why: told). */
    suspend fun saveLiked(t: Track): Boolean {
        library.like(t)
        return saveOne(t)
    }

    private suspend fun saveOne(t: Track): Boolean = saving.withLock {
        val now = library.best(t)
        if (!library.isLiked(t.key)) return@withLock false
        // the song is in the library as soon as it's here (a split waiting for it starts then, not after its video)
        val done = runCatching { Saver.save(this, now, { _status.value = it }, { song -> library.update(song) }) }
            .onSuccess { saved ->
                library.update(saved)
                withContext(Dispatchers.Main) { player.refresh(saved) }
            }
            .onFailure { e -> couldntSave(t, e) }
        _status.value = null
        done.isSuccess
    }

    /** A liked song that couldn't be saved: why, in plain words. Offline, that's said once, and it's saved when you're back. */
    private fun couldntSave(t: Track, e: Throwable) {
        if (Errors.offline(e)) {
            if (!toldOffline) tell(Notice("You're offline. Liked songs are saved when you're back online.", Notice.connect()))
            toldOffline = true
        } else {
            // a server's song: why that server didn't send it ("Bassi's server isn't answering…")
            val why = Servers.of(t)?.let { Errors.server(e, it.name) } ?: Errors.say(e)
            tell(Notice("Couldn't save “${t.title}”. $why", Notice.retry { scope.launch { saveOne(t) } }))
        }
    }

    /** Your library from the computer, read into Liked Songs (once it's copied; see Import). */
    fun importLibrary() {
        scope.launch {
            val n = runCatching { saving.withLock { Import.run(this@App, library) } }
            tell(n.fold({ Notice("Your library from the computer: $it songs in Liked Songs.") }, { Notice("Couldn't read your library. ${Errors.say(it)}", Notice.retry { importLibrary() }) }))
        }
    }

    private var finding: Job? = null

    /**
     * The songs saved in Music/Radiance Music back into Liked Songs (see Found): by itself
     * at start when Liked Songs is empty ([auto]; the app was uninstalled, say), or from
     * Settings any time.
     */
    fun findSaved(auto: Boolean) {
        if (finding?.isActive == true) return
        if (auto && (library.liked.value.isNotEmpty() || Import.waiting(this))) return
        finding = scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    Found.run(this@App, library) { done, of -> if (of > 0) _status.value = "Bringing back your saved songs · $done of $of" }
                }
            }
            _status.value = null
            r.onSuccess { (back, of) ->
                when {
                    of == 0 -> if (!auto) tell(Notice("All the songs in Music/Radiance Music are in Liked Songs."))
                    back == of -> tell(Notice("$back songs from Music/Radiance Music are back in Liked Songs."))
                    else -> tell(Notice("$back of $of songs from Music/Radiance Music are back in Liked Songs. The rest come back later (Settings → Find songs saved on this phone).", Notice.retry { findSaved(false) }))
                }
            }.onFailure { tell(Notice("Couldn't bring back your saved songs. ${Errors.say(it)}", Notice.retry { findSaved(false) })) }
        }
    }

    /** Liked songs not saved yet (the app closed meanwhile, or offline): saved now (one go at a time; asked again meanwhile, it goes round once more). */
    private fun saveMissing() {
        again = true
        if (missing?.isActive == true) return
        missing = scope.launch {
            while (again) {
                again = false
                for (t in library.liked.value.filter { it.file == null }) saveOne(t)
            }
        }
    }

    /** Pictures (covers) come through Http's client: a server's covers get this phone's key with them. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }.build()

    /** Songs to share (null: Liked Songs); [scan]: straight to reading another's QR. */
    data class ShareAsk(val songs: List<Track>?, val scan: Boolean = false)

    companion object {
        lateinit var instance: App
    }
}
