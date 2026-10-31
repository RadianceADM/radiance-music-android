package io.github.radianceadm.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.radianceadm.music.server.Can
import io.github.radianceadm.music.server.Health
import io.github.radianceadm.music.server.Job
import io.github.radianceadm.music.server.Member
import io.github.radianceadm.music.server.Party
import io.github.radianceadm.music.server.PartySong
import io.github.radianceadm.music.server.PartyState
import io.github.radianceadm.music.server.Person
import io.github.radianceadm.music.server.Queue
import io.github.radianceadm.music.server.Reaction
import io.github.radianceadm.music.server.Server
import io.github.radianceadm.music.server.ServerFile
import io.github.radianceadm.music.server.ServerSong
import io.github.radianceadm.music.server.Servers
import io.github.radianceadm.music.App
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Radiance Music Servers in the app, drawn on a phone-sized screen to look at
 * on the build server: Settings → Servers (one answering, one not), joining,
 * a server's library (its party, its split queue, its songs), and a listening
 * party as its host and as a listener; each checked to say what it should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = App::class, qualifiers = "w412dp-h892dp-xxhdpi")
class ServersTest {
    @get:Rule val rule = createComposeRule()

    @After fun clear() = Servers.pretend(emptyList())

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { RadianceTheme { Surface(color = Brand.Night, contentColor = Color.White) { content() } } }
        rule.waitForIdle()
        save(name)
    }

    private fun save(name: String, node: SemanticsNodeInteraction = rule.onRoot()) {
        val dir = File(System.getenv("SCREENS_DIR") ?: "/srv/build/android/screens")
        if (!dir.canWrite()) return
        val bmp: Bitmap = node.captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A bottom sheet's inside, on the sheet's colour (the sheet itself is a window of its own, which can't be drawn here). */
    private fun sheet(name: String, content: @Composable () -> Unit) = shot(name) {
        Surface(color = Brand.Sheet, contentColor = Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.padding(top = 120.dp)) {
            Column(Modifier.padding(top = 28.dp)) { content() }
        }
    }

    private val bassi = Server("https://music.bassi.ph", "t1", "Bassi's server", Person(2, "Ana"), 5, Can(add = true, split = true, host = true))
    private val rad = Server("http://203.0.113.5:8080", "t2", "Rad's server", Person(9, "Ana"), 3, Can(add = false, split = true, host = false))

    private fun song(id: Long, artist: String, title: String, seconds: Long, split: Boolean = false, by: String? = null) = ServerSong(
        id, artist, title, "$artist - $title.opus", "https://music.bassi.ph/files/$id.opus", seconds * 1000, split,
        if (split) listOf("drums", "bass", "other", "vocals") else emptyList(), lyrics = true, wordTimes = split, cover = null,
        files = mapOf("song" to ServerFile("$artist - $title.opus", 3_000_000, "https://music.bassi.ph/files/$id.opus")), addedBy = by?.let { Person(1, it) },
    )

    private val songs = listOf(
        song(1, "Ben&Ben", "Araw-Araw", 245, split = true, by = "Bassi"),
        song(2, "Dionela", "Sining", 201, by = "Rad"),
        song(3, "JVKE", "Golden Hour", 209, split = true, by = "Bassi"),
        song(4, "Maki", "Dilaw", 228, by = "Ana"),
        song(5, "SunKissed Lola", "Pasilyo", 262, split = true),
        song(6, "Up Dharma Down", "Tadhana", 274, by = "Bassi"),
        song(7, "Zack Tabudlo", "Binibini", 236),
    )

    private fun partySong(entry: Long, s: ServerSong, by: Person) = PartySong(entry, s.id, s.artist, s.title, s.file, s.url, null, s.durationMs, by)

    private val host = Person(1, "Bassi", "owner")
    private val ana = Person(2, "Ana")
    private fun party(playing: Boolean = true) = PartyState(
        on = true, id = "p1", host = host, everyoneControls = false,
        song = partySong(3, songs[3], ana), playing = playing, startedAtServerMs = if (playing) 1_000 else null, pausedAtMs = if (playing) null else 84_000,
        serverMs = 85_000, queue = listOf(partySong(4, songs[1], host), partySong(5, songs[6], ana)),
        members = listOf(Member(1, "Bassi", 1, true), Member(2, "Ana", 2, false), Member(9, "Rad", 1, false)),
        reactions = listOf(Reaction("🔥", ana, 80_000)), version = 12,
    )

    @Test fun settingsServers() {
        Servers.pretend(listOf(bassi, rad), mapOf(bassi.base to Health.Answering, rad.base to Health.Down("Rad's server isn't answering. It may be turned off; ask its owner.", offline = false)))
        shot("server-1-settings") { SettingsScreen(onClose = {}, checkServers = false) }
        rule.onNodeWithText("Join a server").assertExists()
        rule.onNodeWithText("music.bassi.ph · Play, add songs, split songs and start parties").assertExists()
        rule.onNodeWithText("Not answering. Rad's server isn't answering. It may be turned off; ask its owner.").assertExists()
    }

    @Test fun joinSheet() {
        sheet("server-2-join") { JoinPanel(onOpen = {}, onDone = {}) }
        rule.onNodeWithText("Join a server").assertExists()
        rule.onNodeWithText("Scan its QR").assertExists()
    }

    @Test fun joinSheetSaysWhatIsntAnInvite() {
        sheet("server-3-join-not-an-invite") {
            JoinPanel(onOpen = {}, onDone = {}, start = JoinState.Failed("That isn't an invite. It looks like https://music.example.com/join/ABCD-EFGH-JKLM: paste the whole link, or scan its QR.", offline = false))
        }
        rule.onNodeWithText("That isn't an invite. It looks like https://music.example.com/join/ABCD-EFGH-JKLM: paste the whole link, or scan its QR.").assertExists()
    }

    @Test fun joined() {
        sheet("server-4-joined") { JoinPanel(onOpen = {}, onDone = {}, start = JoinState.Joined(bassi)) }
        rule.onNodeWithText("You joined Bassi's server").assertExists()
        rule.onNodeWithText("Open its library").assertExists()
    }

    @Test fun aServersSheet() {
        Servers.pretend(listOf(rad))
        sheet("server-10-sheet") { ServerPanel(rad, Health.Answering, onOpen = {}, onDone = {}) }
        rule.onNodeWithText("You're Ana there.").assertExists()
        // adding songs and starting parties: off for Ana there
        assertEquals(2, rule.onAllNodesWithText("Turned off for you by Rad").fetchSemanticsNodes().size)
        rule.onNodeWithText("Leave this server").assertExists()
    }

    @Composable
    private fun header() {
        PageTitle("Library", onSettings = {})
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Liked Songs", 14, false) {}
            Chip("History", null, false) {}
            Chip("Bassi's server", null, true) {}
        }
    }

    @Test fun serverLibrary() {
        Servers.pretend(listOf(bassi))
        val queue = Queue(
            listOf(
                Job(30, 1, "Ben&Ben", "Araw-Araw", host, "splitting", 0.42, "Splitting (42 %)."),
                Job(31, 4, "Maki", "Dilaw", ana, "waiting", 0.0, "Waiting: 1 song before it."),
                Job(32, 2, "Dionela", "Sining", Person(9, "Rad"), "waiting", 0.0, "Next up."),
            ),
            nextTurn = Person(9, "Rad"), splitterReady = true, splitterMessage = "Ready.", modelsMessage = "Ready.",
        )
        shot("server-5-library") { ServerLibraryView(PaddingValues(), bassi, ShelfUi(songs = songs, party = party(), queue = queue), header = { header() }) }
        rule.onNodeWithText("A listening party is on").assertExists()
        rule.onNodeWithText("Join the party").assertExists()
        rule.onNodeWithText("Splitting on Bassi's server").assertExists()
        rule.onNodeWithText("2 waiting · next turn: Rad").assertExists()
        rule.onNodeWithText("Add one of your songs").assertExists()
    }

    @Test fun serverLibraryStartAParty() {
        Servers.pretend(listOf(bassi))
        shot("server-6-library-no-party") { ServerLibraryView(PaddingValues(), bassi, ShelfUi(songs = songs.take(3), party = PartyState.OFF), header = { header() }) }
        rule.onNodeWithText("Start a party").assertExists()
    }

    @Test fun serverLibraryNotAnswering() {
        Servers.pretend(listOf(bassi))
        shot("server-7-library-not-answering") {
            ServerLibraryView(PaddingValues(), bassi, ShelfUi(problem = "Bassi's server isn't answering. It may be turned off; ask its owner."), header = { header() })
        }
        rule.onNodeWithText("Try again").assertExists()
        rule.onNodeWithText("Bassi's server isn't answering. It may be turned off; ask its owner.").assertExists()
    }

    @Test fun serverLibraryEmptyOrRemoved() {
        Servers.pretend(listOf(rad))
        shot("server-8-library-empty") { ServerLibraryView(PaddingValues(), rad, ShelfUi(songs = emptyList(), party = PartyState.OFF), header = { header() }) }
        rule.onNodeWithText("No songs on Rad's server yet. Ask Rad to add some.").assertExists()
        rule.onNodeWithText("Rad turned off adding songs for you.").assertExists()
    }

    @Test fun removedFromTheServer() {
        Servers.pretend(listOf(rad))
        shot("server-9-library-removed") {
            ServerLibraryView(PaddingValues(), rad, ShelfUi(removed = "This phone was removed from Rad's server. Ask Rad for a new invite."), header = { header() })
        }
        rule.onNodeWithText("Forget this server").assertExists()
    }

    @Test fun partyAsHost() {
        val now = Party.Now(bassi.copy(me = host), party(), host, linked = true, listening = true, sync = "In step with the party (+12 ms)", clockMs = 4)
        // reactions as they come float up (caught on their way)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RadianceTheme {
                Surface(color = Brand.Night, contentColor = Color.White) {
                    PartyView(now, position = { 84_000 }, floats = flowOf(Reaction("🔥", ana, 1), Reaction("❤️", Person(9, "Rad"), 2), Reaction("🎉", ana, 3)))
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.onNodeWithText("You're the host").assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onNodeWithText("Skip").assertExists()
        rule.onNodeWithText("In step with the party (+12 ms) · clock ±4 ms").assertExists()
        rule.onNodeWithContentDescription("Love").assertExists()
        save("party-1-host")
    }

    @Test fun partyAsListener() {
        val now = Party.Now(bassi, party(), ana, linked = true, listening = true, sync = "Catching up (−230 ms)", clockMs = 9)
        shot("party-2-listener") { PartyView(now, position = { 84_000 }) }
        rule.onNodeWithText("Bassi (the host) plays, pauses and skips.").assertExists()
        rule.onNodeWithText("Hosted by Bassi").assertExists()
        rule.onNodeWithText("Stop listening here").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Skip").fetchSemanticsNodes().size)
    }

    @Test fun partyPausedNotListening() {
        val now = Party.Now(bassi, party(playing = false), ana, linked = false, listening = false, sync = "Lost the party: joining again…", clockMs = 9)
        shot("party-3-paused-reconnecting") { PartyView(now, position = { 84_000 }) }
        rule.onNodeWithText("Listen here").assertExists()
        rule.onNodeWithText("Paused").assertExists()
    }

    @Test fun reactionsAreSoftPictures() {
        shot("party-4-reactions") {
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Reactions.all.forEach { ReactionIcon(it.emoji, 56.dp) } }
                // one it doesn't know: its emoji as text
                ReactionIcon("🦄", 56.dp)
            }
        }
        assertEquals(Reactions.of("❤"), Reactions.of("❤️"))
        rule.onNodeWithText("🦄").assertExists()
    }
}
