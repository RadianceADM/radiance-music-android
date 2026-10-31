package io.github.radianceadm.music.net

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * A problem in the app's own plain words: [words] are said to you as they are;
 * [detail] (an HTTP code, a byte count) only goes to the log.
 */
class Plain(val words: String, detail: String? = null) : Exception(if (detail == null) words else "$words ($detail)")

/** Stops here with [words] (plain words, said to you as they are). */
fun fail(words: String, detail: String? = null): Nothing = throw Plain(words, detail)

/**
 * What went wrong, as a short sentence anyone understands: never an
 * exception's own text ("Unable to resolve host…", "ENOSPC…"). The app's own
 * words ([Plain]) come through as they are.
 */
object Errors {
    const val OFFLINE = "You're offline."
    const val SLOW = "The connection is too slow right now."
    const val CONNECT = "Couldn't connect. Check your Wi-Fi or mobile data."
    const val DROPPED = "The connection dropped. Try again."
    const val SAFE = "Couldn't connect safely. If this Wi-Fi has a sign-in page, sign in first."
    const val FULL = "Your phone is full. Free up some space and try again."
    const val NOT_ALLOWED = "Radiance Music isn't allowed to do that. Check its permissions in the phone's Settings."
    const val MEMORY = "The phone ran out of memory for this."
    const val GONE = "The file isn't on this phone any more."
    const val ROBOT = "YouTube wants to check you're not a robot. Try again in a few minutes."
    const val COUNTRY = "This song isn't available in your country."
    const val ADULTS = "YouTube keeps this song for signed-in adults, so it can't play here."
    const val UNAVAILABLE = "This song isn't available on YouTube."
    const val YOUTUBE = "YouTube didn't answer as expected. Try again later."
    const val STOPPED = "Stopped."
    const val SOMETHING = "Something went wrong. Try again."

    /**
     * [e] in plain words. [offline]: the phone has no internet now (as
     * [Connectivity] says), so any connection problem is said as "You're offline".
     */
    fun say(e: Throwable?, offline: Boolean = !Connectivity.online.value): String {
        val chain = chain(e)
        if (offline && chain.any(::network)) return OFFLINE
        chain.firstNotNullOfOrNull { (it as? Plain)?.words }?.let { return it }
        chain.firstNotNullOfOrNull(::youtube)?.let { return it }
        chain.firstNotNullOfOrNull(::connection)?.let { return it }
        chain.firstNotNullOfOrNull(::phone)?.let { return it }
        if (chain.any { it is CancellationException }) return STOPPED
        if (chain.any { it is ExtractionException }) return YOUTUBE
        chain.firstNotNullOfOrNull { (it as? PlaybackException)?.let(::playback) }?.let { return it }
        return SOMETHING
    }

    /** It failed because the phone is offline (not for another reason that'd stay once it's back). */
    fun offline(e: Throwable?, online: Boolean = Connectivity.online.value): Boolean = !online && chain(e).any(::network)

    // the exception and what caused it, outermost first (a few deep: a cycle can't loop)
    private fun chain(e: Throwable?) = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(12).toList()

    /** A connection problem (no network, too slow, dropped), not the phone's or the song's. */
    @OptIn(UnstableApi::class)
    private fun network(t: Throwable) = t is UnknownHostException || t is SocketException || t is InterruptedIOException || t is SSLException ||
        t is HttpDataSource.HttpDataSourceException ||
        (t is PlaybackException && (t.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || t.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))

    private fun youtube(t: Throwable): String? = when (t) {
        is ReCaptchaException, is SignInConfirmNotBotException -> ROBOT
        is GeographicRestrictionException, is UnsupportedContentInCountryException -> COUNTRY
        is AgeRestrictedContentException -> ADULTS
        is ContentNotAvailableException -> UNAVAILABLE
        else -> null
    }

    private fun connection(t: Throwable): String? = when (t) {
        is UnknownHostException -> OFFLINE
        is InterruptedIOException -> SLOW // a socket's or a call's time ran out
        is SSLException -> SAFE
        is ConnectException, is NoRouteToHostException, is PortUnreachableException -> CONNECT
        is SocketException -> DROPPED
        else -> null
    }

    private fun phone(t: Throwable): String? {
        val m = t.message.orEmpty()
        return when {
            "ENOSPC" in m || "No space left" in m -> FULL
            "EACCES" in m || "Permission denied" in m || t is SecurityException -> NOT_ALLOWED
            t is FileNotFoundException -> GONE
            t is OutOfMemoryError -> MEMORY
            else -> null
        }
    }

    /** The player's own reasons (its error codes), when nothing under them says more. */
    private fun playback(p: PlaybackException): String = when (p.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> CONNECT
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> SLOW
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This song's file isn't on this phone any more."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Radiance Music isn't allowed to open this song's file."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "This song can't be played right now: where it comes from wouldn't send it."
        in 3000..4999 -> "This phone can't play this song's file." // reading or decoding it
        in 5000..5999 -> "The phone's sound wouldn't start. Try again." // its audio output
        else -> "This song can't be played."
    }
}
