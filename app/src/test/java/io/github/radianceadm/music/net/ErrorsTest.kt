package io.github.radianceadm.music.net

import android.app.Application
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

/** Every problem said in plain words: never an exception's own text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ErrorsTest {
    private fun say(e: Throwable?, offline: Boolean = false) = Errors.say(e, offline)

    @Test fun connectionProblems() {
        assertEquals("You're offline.", say(UnknownHostException("Unable to resolve host \"www.youtube.com\": No address associated with hostname")))
        assertEquals("The connection is too slow right now.", say(SocketTimeoutException("timeout")))
        assertEquals(Errors.CONNECT, say(ConnectException("failed to connect to /142.250.1.1 (port 443) after 15000ms")))
        assertEquals(Errors.SAFE, say(SSLHandshakeException("Chain validation failed")))
        assertEquals(Errors.DROPPED, say(SocketException("Connection reset")))
        // wrapped in something else: what's under it counts
        assertEquals(Errors.OFFLINE, say(IOException("load failed", UnknownHostException("x"))))
        assertEquals(Errors.OFFLINE, say(ExtractionException("couldn't get the page", UnknownHostException("x"))))
    }

    @Test fun offlineSaysOfflineForAnyConnectionProblem() {
        assertEquals(Errors.OFFLINE, say(SocketTimeoutException("timeout"), offline = true))
        assertEquals(Errors.OFFLINE, say(ConnectException("refused"), offline = true))
        assertEquals(Errors.OFFLINE, say(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED), offline = true))
        // not a connection problem: said as it is, offline or not
        assertEquals(Errors.FULL, say(IOException("write failed: ENOSPC (No space left on device)"), offline = true))
        assertTrue(Errors.offline(UnknownHostException("x"), online = false))
        assertFalse(Errors.offline(UnknownHostException("x"), online = true))
        assertFalse(Errors.offline(IOException("write failed: ENOSPC (No space left on device)"), online = false))
    }

    @Test fun thePhonesProblems() {
        assertEquals(Errors.FULL, say(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(Errors.NOT_ALLOWED, say(FileNotFoundException("/storage/emulated/0/Music/a.flac: open failed: EACCES (Permission denied)")))
        assertEquals(Errors.NOT_ALLOWED, say(SecurityException("Permission Denial: reading com.android.providers.media")))
        assertEquals(Errors.GONE, say(FileNotFoundException("/storage/emulated/0/Music/a.flac: open failed: ENOENT (No such file or directory)")))
        assertEquals(Errors.MEMORY, say(OutOfMemoryError("Failed to allocate a 268435472 byte allocation")))
    }

    @Test fun youtubesProblems() {
        assertEquals(Errors.ROBOT, say(ReCaptchaException("YouTube asks to confirm you're not a bot", "https://www.youtube.com")))
        assertEquals(Errors.COUNTRY, say(GeographicRestrictionException("This video is not available in your country")))
        assertEquals(Errors.ADULTS, say(AgeRestrictedContentException("Sign in to confirm your age")))
        assertEquals(Errors.UNAVAILABLE, say(ContentNotAvailableException("Video unavailable")))
        assertEquals(Errors.YOUTUBE, say(ParsingException("Could not get initial data")))
    }

    @Test fun theAppsOwnWordsComeThrough() {
        val e = Plain("The other device said no. Scan its QR again.", "HTTP 403")
        assertEquals("The other device said no. Scan its QR again.", say(e))
        assertTrue(e.message!!.contains("403")) // the log keeps the detail
        assertEquals("YouTube didn't give this song's sound.", say(IOException("resolve", Plain("YouTube didn't give this song's sound."))))
        assertEquals("“Moolah” didn't come.", runCatching { fail("“Moolah” didn't come.", "HTTP 500") }.exceptionOrNull().let { say(it) })
    }

    @Test fun thePlayersProblems() {
        fun p(code: Int, cause: Throwable? = null) = PlaybackException("Source error", cause, code)
        assertEquals("This song's file isn't on this phone any more.", say(p(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
        assertEquals("This phone can't play this song's file.", say(p(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertEquals("This phone can't play this song's file.", say(p(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
        assertEquals("This song can't be played right now: where it comes from wouldn't send it.", say(p(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertEquals("The phone's sound wouldn't start. Try again.", say(p(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)))
        assertEquals("This song can't be played.", say(p(PlaybackException.ERROR_CODE_UNSPECIFIED)))
        // what's under the player's own code says more
        assertEquals(Errors.OFFLINE, say(p(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, UnknownHostException("x"))))
        assertEquals(Errors.COUNTRY, say(p(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(GeographicRestrictionException("x")))))
        assertEquals("YouTube didn't give this song's sound.", say(p(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, Plain("YouTube didn't give this song's sound."))))
    }

    @Test fun neverTheRawText() {
        val raw = listOf(
            RuntimeException("java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference"),
            IllegalStateException("the download stopped (HTTP 403)"),
            IOException("unexpected end of stream on https://rr3---sn-abc.googlevideo.com/..."),
        )
        for (e in raw) {
            val said = say(e)
            assertEquals(Errors.SOMETHING, said)
            assertFalse(said.contains(e.message!!))
        }
        assertEquals(Errors.SOMETHING, say(null))
        assertEquals(Errors.STOPPED, say(CancellationException("Job was cancelled")))
    }

    @Test fun aCauseGoingRoundInCirclesStillEnds() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(Errors.SOMETHING, say(a))
    }

    @Test fun everySentenceEndsLikeOne() {
        val all = listOf(Errors.OFFLINE, Errors.SLOW, Errors.CONNECT, Errors.DROPPED, Errors.SAFE, Errors.FULL, Errors.NOT_ALLOWED, Errors.MEMORY, Errors.GONE,
            Errors.ROBOT, Errors.COUNTRY, Errors.ADULTS, Errors.UNAVAILABLE, Errors.YOUTUBE, Errors.STOPPED, Errors.SOMETHING)
        assertTrue(all.all { it.first().isUpperCase() && it.endsWith(".") })
    }
}
