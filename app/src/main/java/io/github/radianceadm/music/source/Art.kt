package io.github.radianceadm.music.source

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.pxOrElse

/**
 * Covers at the size they're shown (android#36; owner, 2026-11-08: "ang low quality ng album art", "nakakahiya ishare sa soc
 * med"). YouTube Music's search offers a song's cover at 60 and 120 px only, and the app kept the 120: blown up on
 * Now Playing and on an 800 px story. Its image server keeps the original (1400 px for most albums) and gives any
 * size the address asks for (…=w1200-h1200-l90-rj). So a row asks for a small one (little data), and Now Playing,
 * the saved cover and the story for a big one.
 */
object Art {
    /** Now Playing's, the saved cover's, the story's: sharp on any screen, a few hundred KB. */
    const val BIG = 1200
    /** The lock screen's and the notification's. */
    const val MEDIUM = 544
    /** The steps asked for, so one cover is fetched (and kept) once per step, not once per pixel size. */
    private val STEPS = listOf(120, 226, MEDIUM, BIG)
    private val GOOGLE = Regex("""^https://(lh\d|yt\d)\.(googleusercontent|ggpht)\.com/""")
    /** The size part at the end of such an address: "=w120-h120-l90-rj", "=s120-c-k-c0x00ffffff-no-rj". */
    private val SIZE = Regex("""=[a-z][^/=]*$""")

    /** Whether a bigger copy of [url] can be asked for (YouTube Music's covers). */
    fun grows(url: String) = GOOGLE.containsMatchIn(url)

    /** [url] asking for at least [px] across (rounded up to a step, at most [BIG]); any other address as it is. */
    fun sized(url: String, px: Int): String {
        if (!grows(url)) return url
        val want = STEPS.firstOrNull { it >= px } ?: BIG
        val base = SIZE.find(url)?.let { url.substring(0, it.range.first) } ?: url
        return "$base=w$want-h$want-l90-rj"
    }

    /** For Coil: each cover fetched at the size it's drawn (a row's small, Now Playing's big). */
    object Sizing : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data as? String ?: return chain.proceed()
            if (!grows(url)) return chain.proceed()
            val px = maxOf(chain.size.width.pxOrElse { BIG }, chain.size.height.pxOrElse { BIG })
            val sized = sized(url, px)
            return if (sized == url) chain.proceed() else chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
        }
    }
}
