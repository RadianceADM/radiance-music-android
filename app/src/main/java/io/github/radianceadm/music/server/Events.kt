package io.github.radianceadm.music.server

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * A server's changes as they happen, over its events WebSocket
 * (/api/events), in place of asking again and again: its library changed,
 * its split queue moved on, a party started or ended, or something about you
 * (the owner's switches for you, or this phone removed). Kinds it doesn't
 * know are passed over. A screen showing a server listens while it's
 * open ([watch]): one line per server, shared, joining again (more slowly
 * each time, as the party does) when it drops. While it's down ([up] false)
 * the screens look again now and then by themselves.
 */
object Events {
    sealed class Event {
        /** Connected: the server's own id and its name. */
        data class Hello(val id: String?, val name: String?) : Event()
        /** Its songs, or their parts, changed: fetch them again. */
        object Library : Event()
        /** The split queue now. */
        data class Queued(val queue: Queue) : Event()
        /** Your switches changed, or this phone was removed: ask who you are again. */
        object Me : Event()
        /** A listening party started ([on], hosted by [host]) or ended. */
        data class Party(val on: Boolean, val host: String?) : Event()
    }

    private class Line(var server: Server) {
        var users = 0
        var socket: WebSocket? = null
        var round = 0
        var backoff = firstBackoff
        val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
        val up = MutableStateFlow(false)
    }

    private val lines = HashMap<String, Line>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The first wait before joining again (doubling up to 15 s). */
    @VisibleForTesting
    internal var firstBackoff = 1000L

    private fun line(s: Server): Line = synchronized(lines) { lines.getOrPut(s.base) { Line(s) }.also { it.server = s } }

    /** [s]'s changes, while collected (the line opens with the first listener, and closes after the last). */
    fun watch(s: Server): Flow<Event> = flow {
        val line = line(s)
        var opened = false
        try {
            // listening first, then the line opens: nothing it says is missed
            emitAll(line.events.onSubscription {
                open(s)
                opened = true
            })
        } finally {
            if (opened) close(s.base)
        }
    }

    /** Whether [base]'s line is up now (its changes come at once); down: look again now and then. */
    fun up(base: String): StateFlow<Boolean> = synchronized(lines) { lines.getOrPut(base) { Line(Server(base, "", base)) }.up }

    private fun open(s: Server): Line = synchronized(lines) {
        val line = line(s)
        line.users++
        if (line.socket == null && line.users == 1) {
            line.backoff = firstBackoff
            connect(line)
        }
        line
    }

    private fun close(base: String) = synchronized(lines) {
        val line = lines[base] ?: return@synchronized
        line.users = (line.users - 1).coerceAtLeast(0)
        if (line.users == 0) {
            line.round++
            line.socket?.close(1000, "done")
            line.socket = null
            line.up.value = false
        }
    }

    private fun connect(line: Line) {
        val r = ++line.round
        val s = line.server
        if (s.token.isEmpty()) return
        line.socket = s.rms.eventsSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lines) {
                if (r != line.round) return@synchronized
                line.backoff = firstBackoff
                line.up.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                val e = when (o.optString("type")) {
                    "hello" -> Event.Hello(o.optJSONObject("server")?.str("id"), o.optJSONObject("server")?.str("name"))
                    "library" -> Event.Library
                    "queue" -> Event.Queued(Queue.from(o))
                    "me" -> Event.Me
                    "party" -> Event.Party(o.optBoolean("on"), o.str("host"))
                    else -> return
                }
                synchronized(lines) { if (r == line.round) line.events.tryEmit(e) }
            }

            // the server closes it (it's stopping, or this phone was removed): closed from here too, then joined again
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(line, r, response?.code == 401)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(line, r, false)
        })
    }

    /** The line dropped: joining again in a while (unless nobody listens now, or this phone was removed). */
    private fun dropped(line: Line, r: Int, removed: Boolean) = synchronized(lines) {
        if (r != line.round) return@synchronized
        line.socket = null
        line.up.value = false
        if (removed) {
            // its key doesn't work there any more: said, and not tried again
            Servers.heard(line.server, Refused(401, "removed"))
            line.events.tryEmit(Event.Me)
            return@synchronized
        }
        if (line.users == 0) return@synchronized
        val wait = line.backoff
        line.backoff = (line.backoff * 2).coerceAtMost(15_000)
        scope.launch {
            delay(wait)
            synchronized(lines) { if (line.users > 0 && line.round == r && line.socket == null) connect(line) }
        }
    }
}
