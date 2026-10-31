package io.github.radianceadm.music.server

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A Radiance Music Server this phone joined (Settings → Servers): where it
 * is, this phone's key for it, its name ("Bassi's server"), who you are
 * there and what its owner lets you do (as last heard).
 */
data class Server(
    /** "https://music.example.com" (or "http://203.0.113.5:8080"). */
    val base: String,
    val token: String,
    val name: String,
    val me: Person? = null,
    /** This phone's device number there (to leave). */
    val device: Long = 0,
    val can: Can = Can(),
    val joinedMs: Long = 0,
) {
    val rms get() = Rms(base, token)
    /** Its address as you'd say it: "music.example.com". */
    val host get() = base.substringAfter("://")

    fun toJson(): JSONObject = JSONObject().put("base", base).put("token", token).put("name", name)
        .put("me", me?.toJson() ?: JSONObject.NULL).put("device", device).put("can", can.toJson()).put("joined_ms", joinedMs)

    companion object {
        fun from(o: JSONObject): Server? {
            val base = o.str("base") ?: return null
            val token = o.str("token") ?: return null
            return Server(base, token, o.str("name") ?: base.substringAfter("://"), Person.from(o.optJSONObject("me")), o.optLong("device"), Can.from(o.optJSONObject("can")), o.optLong("joined_ms"))
        }
    }
}

/** How a server is doing, as last heard from it. */
sealed class Health {
    object Unknown : Health()
    object Answering : Health()
    /** It didn't answer: [why] in plain words; [offline]: the phone is. */
    data class Down(val why: String, val offline: Boolean) : Health()
    /** Its owner removed this phone (or you): its key doesn't work any more. */
    data class Removed(val why: String) : Health()
}

/**
 * The servers this phone joined, kept in the app's preferences: each one's
 * address, key and name. Joining reads an invite (its link, or its QR) and
 * asks the server for this phone's key; leaving gives it back.
 *
 * Everything the app asks a joined server carries its key: API calls their
 * own (Rms), covers and downloads through Http's client ([auth]); the
 * player's songs and the party's WebSocket take it as `?t=`.
 */
object Servers {
    private const val PREFS = "servers"
    private var prefs: android.content.SharedPreferences? = null

    private val _list = MutableStateFlow<List<Server>>(emptyList())
    /** Joined servers, the first joined first. */
    val list: StateFlow<List<Server>> = _list

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    /** Each server's last answer (by its base address). */
    val health: StateFlow<Map<String, Health>> = _health

    /** The server whose library to show (Settings → "Open its library"): Library opens on it. */
    val open = MutableStateFlow<String?>(null)

    /** Songs each server had when last asked, by the song's key: for the Mixer's parts, the lyrics, saving. */
    private val songs = ConcurrentHashMap<String, Pair<String, ServerSong>>()
    /** Each server's list as last asked (its library shows it at once, then looks again). */
    private val lists = ConcurrentHashMap<String, List<ServerSong>>()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _list.value = runCatching {
            val a = JSONArray(p.getString("list", "[]"))
            (0 until a.length()).mapNotNull { Server.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Server>) {
        _list.value = list
        prefs?.edit()?.putString("list", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    /** For the tests' pictures: these servers, as told (nothing kept). */
    @VisibleForTesting
    fun pretend(list: List<Server>, health: Map<String, Health> = emptyMap()) {
        _list.value = list
        _health.value = health
    }

    fun byBase(base: String): Server? = _list.value.firstOrNull { it.base == base }

    /** The joined server [url] is on (its scheme, host and port), if it's one. */
    fun of(url: String): Server? = url.toHttpUrlOrNull()?.let(::of)

    private fun of(u: HttpUrl): Server? {
        val list = _list.value
        if (list.isEmpty()) return null
        val origin = Rms.origin(u.toString()) ?: return null
        return list.firstOrNull { it.base == origin }
    }

    /** The server a song came from (a server's song: its file's address). */
    fun of(t: Track): Server? = if (t.source == Source.SERVER) of(t.id) else null

    /**
     * A joined server's files (covers, downloads, parts) carry its key, unless
     * they have one already; the API's calls carry their own (Rms). Other
     * addresses are left alone.
     */
    val auth = Interceptor { chain ->
        val req = chain.request()
        val file = req.url.encodedPath.startsWith("/files/")
        val s = if (file && req.header("Authorization") == null && req.url.queryParameter("t") == null) of(req.url) else null
        chain.proceed(if (s == null) req else req.newBuilder().header("Authorization", "Bearer ${s.token}").build())
    }

    /** A joined server's file address with this phone's key in it (`?t=`), for the player; any other address as it is. */
    fun sign(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        if (!u.encodedPath.startsWith("/files/") || u.queryParameter("t") != null) return url
        val s = of(u) ?: return url
        return u.newBuilder().setQueryParameter("t", s.token).build().toString()
    }

    /** This phone as the server lists it: its maker and model ("Samsung SM-S928B"). */
    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return when {
            model.isBlank() -> maker.ifBlank { "A phone" }
            maker.isBlank() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }

    /** An invite: the server's address and its one-time code. */
    data class Invite(val base: String, val code: String)

    /**
     * An invite as pasted or scanned: `https://HOST/join/CODE` (anywhere in
     * the words, with or without https://). null: there's no invite in it.
     */
    fun invite(text: String): Invite? {
        val found = Regex("""(?:(https?)://)?([A-Za-z0-9.\-]+|\[[0-9A-Fa-f:.]+])(:\d{1,5})?/join/([A-Za-z0-9\-]{4,40})""").find(text.trim()) ?: return null
        val (scheme, host, port, code) = found.destructured
        // a link without its https://: a name gets https, a bare address (a server without a domain) http
        val bare = host.startsWith("[") || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val s = scheme.ifEmpty { if (bare || host == "localhost") "http" else "https" }
        val base = Rms.origin("$s://$host$port/") ?: return null
        val plain = code.filter { it.isLetterOrDigit() }
        if (plain.length < 4) return null
        return Invite(base, code)
    }

    /**
     * Joins the server in [text] (an invite's link or QR) as this phone:
     * its key, its name, who you are there. Waits for the network. Joined
     * there already: the new key takes the old one's place (and the old one
     * is given back).
     */
    fun join(text: String, deviceName: String = deviceName()): Server {
        val inv = invite(text) ?: fail("That isn't an invite. It looks like https://music.example.com/join/ABCD-EFGH-JKLM: paste the whole link, or scan its QR.")
        val joined = Rms(inv.base).join(inv.code, deviceName)
        val rms = Rms(inv.base, joined.token)
        val me = runCatching { rms.me() }.getOrNull()
        val name = joined.server ?: me?.server ?: runCatching { rms.status().server }.getOrNull() ?: inv.base.substringAfter("://")
        val server = Server(inv.base, joined.token, name, me?.person ?: joined.person, me?.device ?: joined.device, me?.can ?: Can(), System.currentTimeMillis())
        val before = byBase(inv.base)
        // joined here already (a new invite for this phone): the old key goes
        if (before != null && before.token != joined.token) runCatching { before.rms.leave(before.device) }
        save(_list.value.filterNot { it.base == inv.base } + server)
        healthy(server.base, Health.Answering)
        return server
    }

    /**
     * Leaves [s]: this phone's key stops working there ([onServer]), and the
     * phone forgets it. The server said no, or didn't answer: it's kept, and
     * why comes back (forget it here anyway with [onServer] false).
     */
    fun leave(s: Server, onServer: Boolean = true) {
        if (onServer) {
            val gone = runCatching { s.rms.leave(s.device) }.exceptionOrNull()
            // already removed there: nothing to give back
            if (gone != null && !(gone is Refused && gone.removed)) throw gone
        }
        save(_list.value.filterNot { it.base == s.base })
        _health.value = _health.value - s.base
        songs.entries.removeIf { it.value.first == s.base }
        lists.remove(s.base)
        if (open.value == s.base) open.value = null
    }

    /** Asks [s] who this phone is now (its name, what the owner lets it do): kept, and how it answered. */
    fun refresh(s: Server): Server {
        val me = try {
            s.rms.me()
        } catch (e: Exception) {
            heard(s, e)
            throw e
        }
        val now = s.copy(name = me.server ?: s.name, me = me.person, device = me.device.takeIf { it > 0 } ?: s.device, can = me.can)
        if (now != s) save(_list.value.map { if (it.base == s.base) now else it })
        healthy(s.base, Health.Answering)
        return now
    }

    /** [s] answered (or not, [e]): its health, for the screens. */
    fun heard(s: Server, e: Throwable?) {
        healthy(s.base, when {
            e == null -> Health.Answering
            e is Refused && e.removed -> Health.Removed("This phone was removed from ${s.name}. Ask ${ownerOf(s)} for a new invite.")
            else -> Health.Down(Errors.server(e, s.name), Errors.offline(e))
        })
    }

    /** "Bassi" from "Bassi's server" (the owner's name), else "its owner". */
    fun ownerOf(s: Server): String = ownerOf(s.name)

    fun ownerOf(name: String): String = name.removeSuffix("'s server").removeSuffix("’s server").takeIf { it != name && it.isNotBlank() } ?: "its owner"

    private fun healthy(base: String, h: Health) {
        _health.value = _health.value + (base to h)
    }

    /** The songs [s] has now (kept for the Mixer, the lyrics, saving). Waits for the network. */
    fun library(s: Server): List<ServerSong> {
        val list = try {
            s.rms.library()
        } catch (e: Exception) {
            heard(s, e)
            throw e
        }
        healthy(s.base, Health.Answering)
        remember(s, list)
        return list
    }

    fun remember(s: Server, list: List<ServerSong>) {
        lists[s.base] = list
        for (song in list) songs[song.track().key] = s.base to song
    }

    /** [s]'s songs as last asked, or null (not asked yet). */
    fun lastSongs(s: Server): List<ServerSong>? = lists[s.base]

    /** A server's song as last listed (by the song's key), with its server. */
    fun song(t: Track): Pair<Server, ServerSong>? {
        if (t.source != Source.SERVER) return null
        val (base, song) = songs[t.key] ?: return null
        return byBase(base)?.let { it to song }
    }

    /** Like [song], asking the server again when it isn't known yet (waits for the network). */
    fun find(t: Track): Pair<Server, ServerSong>? {
        song(t)?.let { return it }
        val s = of(t) ?: return null
        runCatching { library(s) }
        return song(t)
    }
}
