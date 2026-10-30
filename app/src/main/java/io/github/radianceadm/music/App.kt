package io.github.radianceadm.music

import android.app.Application
import android.widget.Toast
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.data.Library
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.NewPipeDownloader
import io.github.radianceadm.music.play.Effects
import io.github.radianceadm.music.play.Mixer
import io.github.radianceadm.music.play.PlayerHub
import io.github.radianceadm.music.share.Session
import io.github.radianceadm.music.split.Splits
import io.github.radianceadm.music.split.Splitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var library: Library
    lateinit var player: PlayerHub
    lateinit var mixer: Mixer
    lateinit var splits: Splits
    lateinit var share: Session
    /** The Share songs sheet, open (for these songs; null: Liked Songs), or not. */
    val shareOpen = MutableStateFlow<ShareAsk?>(null)

    /** The start's splash has shown (once a process: a cold start). */
    var splashed = false

    private val saving = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    /** What's being saved now ("Saving “Song”…"), or null. */
    val status: StateFlow<String?> = _status

    override fun onCreate() {
        super.onCreate()
        instance = this
        NewPipe.init(NewPipeDownloader(), Localization("en", "US"))
        Effects.load(this)
        io.github.radianceadm.music.ui.Looks.load(this) // True Black or Stone
        library = Library(this)
        player = PlayerHub(this)
        mixer = Mixer(this)
        splits = Splits(this)
        share = Session(this)
        scope.launch { saveMissing() }
        scope.launch { Splitter.forgetDownloaded(this@App) } // the model comes with the app now
        io.github.radianceadm.music.update.Updates.atStart(this) // a newer version out? (over the air)
    }

    /** Like it, save it: liked now, saved in the background (one song at a time). */
    fun like(t: Track) {
        library.like(t)
        scope.launch { saveOne(t) }
    }

    fun unlike(t: Track) = library.unlike(t)

    private suspend fun saveOne(t: Track) = saving.withLock {
        val now = library.best(t)
        if (!library.isLiked(t.key)) return@withLock
        runCatching { Saver.save(this, now) { _status.value = it } }
            .onSuccess { saved ->
                library.update(saved)
                withContext(Dispatchers.Main) { player.refresh(saved) }
            }
            .onFailure { e -> withContext(Dispatchers.Main) { Toast.makeText(this@App, "Couldn't save “${t.title}”: ${e.message}", Toast.LENGTH_LONG).show() } }
        _status.value = null
    }

    /** Your library from the computer, read into Liked Songs (once it's copied; see Import). */
    fun importLibrary() {
        scope.launch {
            val n = runCatching { saving.withLock { Import.run(this@App, library) } }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@App, n.fold({ "Your library from the computer: $it songs in Liked Songs" }, { "Couldn't read your library: ${it.message}" }), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Liked songs not saved yet (the app closed meanwhile, or offline): saved now. */
    private suspend fun saveMissing() {
        for (t in library.liked.value.filter { it.file == null }) saveOne(t)
    }

    /** Songs to share (null: Liked Songs); [scan]: straight to reading another's QR. */
    data class ShareAsk(val songs: List<Track>?, val scan: Boolean = false)

    companion object {
        lateinit var instance: App
    }
}
