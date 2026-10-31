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
 * there and what its owner lets you do (as last heard). Its [id] is its own,
 * the same at any address: the owner adding a domain moves it, it doesn't
 * make it another server. As the computer app does, its songs keep the
 * address it was [first] joined at in their ids (so their keys never change);
 * what's asked of it goes to [base], where it is now.
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
    /** The server's own id (from 0.1.2; null: not heard yet). */
    val id: String? = null,
    /** Where it was when this phone joined it: its songs' addresses keep it. */
    val first: String = base,
) {
    val rms get() = Rms(base, token, first = first)

    /** One of its addresses (a song's id: at the address first joined) where it is now. */
    fun here(url: String): String = if (first != base && url.startsWith("$first/")) base + url.removePrefix(first) else url
    /** Its address as you'd say it: "music.example.com". */
    val host get() = base.substringAfter("://")

    fun toJson(): JSONObject = JSONObject().put("base", base).put("token", token).put("name", name)
        .put("me", me?.toJson() ?: JSONObject.NULL).put("device", device).put("can", can.toJson()).put("joined_ms", joinedMs).put("id", id ?: JSONObject.NULL).put("first_address", first)

    companion object {
        fun from(o: JSONObject): Server? {
            val base = o.str("base") ?: return null
            val token = o.str("token") ?: return null
            return Server(base, token, o.str("name") ?: base.substringAfter("://"), Person.from(o.optJSONObject("me")), o.optLong("device"), Can.from(o.optJSONObject("can")), o.optLong("joined_ms"), o.str("id"), o.str("first_address") ?: base)
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

    /** An invite opened from outside (the server's join page: radiancemusic://join?u=…): Settings → Servers asks to join it. */
    val asked = MutableStateFlow<String?>(null)

    /** Songs each server had when last asked, by the song's key (and the server's first address): for the Mixer's parts, the lyrics, saving. */
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

    /** The joined server first joined at [first] (wherever it is now). */
    fun byFirst(first: String): Server? = _list.value.firstOrNull { it.first == first }

    /** The joined server [url] is on (its scheme, host and port, where it is now or was first joined), if it's one. */
    fun of(url: String): Server? = url.toHttpUrlOrNull()?.let(::of)

    private fun of(u: HttpUrl): Server? {
        val list = _list.value
        if (list.isEmpty()) return null
        val origin = Rms.origin(u.toString()) ?: return null
        return list.firstOrNull { it.base == origin } ?: list.firstOrNull { it.first == origin }
    }

    /** [url] where it is now (a song's address keeps the server's first one). */
    fun here(url: String): String = of(url)?.here(url) ?: url

    /** The server a song came from (a server's song: its file's address). */
    fun of(t: Track): Server? = if (t.source == Source.SERVER) of(t.id) else null

    /**
     * A joined server's files (covers, downloads, parts) go to where it is now
     * (a song's address keeps its first one) and carry its key, unless they
     * have one already; the API's calls carry their own (Rms). Other
     * addresses are left alone.
     */
    val auth = Interceptor { chain ->
        val req = chain.request()
        val s = if (req.url.encodedPath.startsWith("/files/")) of(req.url) else null
        if (s == null) return@Interceptor chain.proceed(req)
        val asked = req.url.toString()
        val b = req.newBuilder()
        s.here(asked).takeIf { it != asked }?.let { b.url(it) }
        if (req.header("Authorization") == null && req.url.queryParameter("t") == null) b.header("Authorization", "Bearer ${s.token}")
        chain.proceed(b.build())
    }

    /** A joined server's file address where it is now, with this phone's key in it (`?t=`), for the player; any other address as it is. */
    fun sign(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        if (!u.encodedPath.startsWith("/files/")) return url
        val s = of(u) ?: return url
        val now = s.here(url).toHttpUrlOrNull() ?: return url
        return if (now.queryParameter("t") != null) now.toString() else now.newBuilder().setQueryParameter("t", s.token).build().toString()
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
        // the join page's "Open in Radiance Music": the invite inside it
        joinLink(text.trim())?.let { if (it != text.trim()) return invite(it) }
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

    /** The invite in an app link, `radiancemusic://join?u=<the invite's address>` (null: it isn't one). */
    fun joinLink(link: String): String? {
        if (!link.startsWith("radiancemusic://join")) return null
        val q = link.substringAfter('?', "")
        val u = q.split('&').firstNotNullOfOrNull { kv -> kv.substringAfter("u=", "").takeIf { kv.startsWith("u=") } } ?: return null
        return runCatching { java.net.URLDecoder.decode(u, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Joins the server in [text] (an invite's link or QR) as this phone:
     * its key, its name, who you are there. Waits for the network. Joined
     * there already: the new key takes the old one's place (and the old one
     * is given back). As the computer app does: the same server (its id) at a
     * new address moves there, keeping its first address for its songs; its
     * key still working there, the invite isn't used.
     */
    fun join(text: String, deviceName: String = deviceName()): Server {
        val inv = invite(text) ?: fail("That isn't an invite. It looks like https://music.example.com/join/ABCD-EFGH-JKLM: paste the whole link, or scan its QR.")
        val status = runCatching { Rms(inv.base).status() }.getOrNull()
        val known = byBase(inv.base) ?: status?.id?.let { i -> _list.value.firstOrNull { it.id == i } }
        // the same server at a new address, and this phone's key works there: it moves, the invite stays unused
        if (known != null && known.base != inv.base) {
            val there = runCatching { Rms(inv.base, known.token).me() }.getOrNull()
            if (there != null) return moved(known, known.copy(base = inv.base, name = there.server ?: status?.server ?: known.name, me = there.person, can = there.can, id = known.id ?: there.serverId))
        }
        val joined = Rms(inv.base).join(inv.code, deviceName)
        val rms = Rms(inv.base, joined.token)
        val me = runCatching { rms.me() }.getOrNull()
        val name = joined.server ?: me?.server ?: status?.server ?: inv.base.substringAfter("://")
        val id = joined.serverId ?: me?.serverId ?: status?.id
        val server = Server(inv.base, joined.token, name, me?.person ?: joined.person, me?.device ?: joined.device, me?.can ?: Can(), System.currentTimeMillis(), id, first = known?.first ?: inv.base)
        // joined here already (a new invite for this phone): the old key goes, the new one takes its place
        if (known != null && known.token != joined.token) runCatching { Rms(inv.base, known.token).leave(known.device) }
        if (known != null && known.base != inv.base) return moved(known, server)
        save(if (known == null) _list.value + server else _list.value.map { if (it === known) server else it })
        healthy(server.base, Health.Answering)
        return server
    }

    /**
     * [s] is at [address] now (its owner added a domain, say): the same server
     * (its id says so) with this phone's same key, at its new address. Waits
     * for the network; not the same server, or not answering: why.
     */
    fun move(s: Server, address: String): Server {
        val typed = address.trim().let { if ("://" in it) it else "https://$it" }
        val base = Rms.origin(typed) ?: fail("That isn't an address. It looks like https://music.example.com.")
        if (base == s.base) return s
        val there = Rms(base, s.token)
        val id = there.status().id
        if (s.id == null || id != s.id) fail("That's another server, not ${s.name}. Check the address with ${ownerOf(s)}.")
        val me = there.me()
        return moved(s, s.copy(base = base, name = me.server ?: s.name, me = me.person, can = me.can))
    }

    /**
     * [was] is [now] (at its new address, its first one kept): in its place.
     * Its songs' keys don't change, so nothing else does; what's fetched goes
     * to its new address (Server.here).
     */
    private fun moved(was: Server, now: Server): Server {
        save(_list.value.map { if (it.first == was.first) now else it })
        _health.value = _health.value - was.base
        healthy(now.base, Health.Answering)
        return now
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
        save(_list.value.filterNot { it.first == s.first })
        _health.value = _health.value - s.base
        songs.entries.removeIf { it.value.first == s.first }
        lists.remove(s.first)
        if (open.value == s.first) open.value = null
    }

    /** Asks [s] who this phone is now (its name, what the owner lets it do): kept, and how it answered. */
    fun refresh(s: Server): Server {
        val me = try {
            s.rms.me()
        } catch (e: Exception) {
            heard(s, e)
            throw e
        }
        val now = s.copy(name = me.server ?: s.name, me = me.person, device = me.device.takeIf { it > 0 } ?: s.device, can = me.can, id = s.id ?: me.serverId)
        if (now != s) save(_list.value.map { if (it.first == s.first) now else it })
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

    fun remember(s: Server, list: List<ServerSong>, whole: Boolean = true) {
        if (whole) lists[s.first] = list
        for (song in list) songs[song.track().key] = s.first to song
    }

    /** [s]'s songs as last asked, or null (not asked yet). */
    fun lastSongs(s: Server): List<ServerSong>? = lists[s.first]

    /** A server's song as last listed (by the song's key), with its server. */
    fun song(t: Track): Pair<Server, ServerSong>? {
        if (t.source != Source.SERVER) return null
        val (first, song) = songs[t.key] ?: return null
        return byFirst(first)?.let { it to song }
    }

    /**
     * Like [song], asking the server when it isn't known yet: a search for its
     * file's name (not the whole library). Waits for the network.
     */
    fun find(t: Track): Pair<Server, ServerSong>? {
        song(t)?.let { return it }
        val s = of(t) ?: return null
        val name = java.net.URLDecoder.decode(t.id.substringAfterLast("/files/").substringBefore('?'), "UTF-8")
        val found = runCatching { s.rms.page(q = name.substringBeforeLast('.')) }.getOrNull()?.songs ?: return null
        found.firstOrNull { it.url == t.id }?.let { remember(s, listOf(it), whole = false); return s to it }
        return null
    }

    /** [id] on [s] as it is now (one song, not the whole library), kept. Waits for the network. */
    fun fresh(s: Server, id: Long): ServerSong {
        val song = try {
            s.rms.song(id)
        } catch (e: Exception) {
            heard(s, e)
            throw e
        }
        songs[song.track().key] = s.first to song
        lists[s.first]?.let { list -> lists[s.first] = list.map { if (it.id == id) song else it } }
        return song
    }

    /** A page of [s]'s songs (a big library: some at a time, or a search there), kept. Waits for the network. */
    fun page(s: Server, q: String? = null, offset: Int = 0, limit: Int? = null): Page {
        val page = try {
            s.rms.page(q, offset, limit)
        } catch (e: Exception) {
            heard(s, e)
            throw e
        }
        healthy(s.base, Health.Answering)
        for (song in page.songs) songs[song.track().key] = s.first to song
        return page
    }
}
