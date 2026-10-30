package io.github.radianceadm.music.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.radianceadm.music.R
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Http
import okhttp3.Request

/**
 * A song as a story (1080 × 1920, what Instagram and Facebook stories are):
 * its cover big on a soft blur of itself, the title and who sings it; or,
 * with lyrics picked, the lines big on a rounded card with the cover small
 * above them. Soft all over: rounded corners, glows, no hard edges.
 */
object StoryCard {
    const val W = 1080
    const val H = 1920
    /** Lines of lyrics a card holds at most (a quote, as Spotify's cards). */
    /** As Spotify's: this many characters of lyrics at most, on lines next to each other. */
    const val MOST_CHARS = 150

    /** Instagram's and Facebook's bars cover the top and the bottom of a story: everything stays between. */
    private const val SAFE_TOP = 250
    private const val FOOTER_Y = H - 330

    /** The shade over the blurred cover, and the card's colour without a cover (no navy: the app's own dark and Stone). */
    private const val SHADE = 0xFF000000.toInt()
    private const val STONE = 0xFF292524.toInt()
    private const val SUNSET = 0xFF7A3410.toInt()
    private const val ORANGE = 0xFFF38A35.toInt()

    /** The song's cover (the saved one, else from the web), or null. Worker thread. */
    fun cover(context: Context, t: Track): Bitmap? {
        val saved = Saver.coverFile(context, t)
        if (saved.exists()) BitmapFactory.decodeFile(saved.path)?.let { return it }
        if (t.art.isEmpty()) return null
        return runCatching {
            Http.client.newCall(Request.Builder().url(t.art).header("User-Agent", Http.USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
            }
        }.getOrNull()
    }

    /** The card. [lyrics]: the lines picked (none: the cover big). */
    /** Where the app is got (the Radiance Music page). */
    const val GET_IT = "music.radianceph.com"

    private var tileBack: android.graphics.drawable.Drawable? = null
    private var tileFront: android.graphics.drawable.Drawable? = null

    /** Radiance Music's icon (the sunset with four parts) as a rounded tile, [size] px, at [x], [y]. */
    private fun mark(c: Canvas, x: Float, y: Float, size: Float) {
        val save = c.save()
        val r = size * 0.235f
        c.clipPath(android.graphics.Path().apply { addRoundRect(RectF(x, y, x + size, y + size), r, r, android.graphics.Path.Direction.CW) })
        // the icon's layers are 108 across, of which a phone shows the middle 72: so is the tile
        val over = size * 0.25f
        for (d in listOfNotNull(tileBack, tileFront)) {
            d.setBounds((x - over).toInt(), (y - over).toInt(), (x + size + over).toInt(), (y + size + over).toInt())
            d.draw(c)
        }
        c.restoreToCount(save)
    }

    /**
     * The card: [tone] a colour picked for it (else the cover's own, over its glow);
     * [lit] the line being sung (a video's frame: -1 none yet; null: all lit).
     */
    fun draw(context: Context, t: Track, art: Bitmap?, lyrics: List<String>, tone: Int? = null, lit: Int? = null): Bitmap {
        tileBack = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
        tileFront = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val picked = tone
        val tone = picked ?: art?.let { toneOf(it) } ?: STONE
        background(c, if (picked == null) art else null, tone)
        if (lyrics.isEmpty()) coverCard(c, t, art) else lyricsCard(c, t, art, lyrics, tone, lit)
        footer(context, c)
        return out
    }

    /** The cover's own colour, made deep enough for white text on it. */
    private fun toneOf(art: Bitmap): Int {
        val tiny = Bitmap.createScaledBitmap(art, 8, 8, true)
        var r = 0; var g = 0; var b = 0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = tiny.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); b += Color.blue(p)
        }
        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(r / 64, g / 64, b / 64, hsl)
        hsl[1] = hsl[1].coerceIn(0.25f, 0.7f)
        hsl[2] = hsl[2].coerceIn(0.16f, 0.3f)
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Colours to pick for the card: the cover's own (its main colours, deep enough
     * for white words), then black, stone and Radiance's sunset.
     */
    fun swatches(art: Bitmap?): List<Int> {
        val own = mutableListOf<Int>()
        if (art != null) {
            own += toneOf(art)
            val tiny = Bitmap.createScaledBitmap(art, 24, 24, true)
            // the cover's colours by hue (12 of them), the most common first
            val buckets = HashMap<Int, MutableList<FloatArray>>()
            val hsl = FloatArray(3)
            for (x in 0 until 24) for (y in 0 until 24) {
                val p = tiny.getPixel(x, y)
                ColorUtils.RGBToHSL(Color.red(p), Color.green(p), Color.blue(p), hsl)
                if (hsl[1] < 0.22f || hsl[2] < 0.08f || hsl[2] > 0.92f) continue
                buckets.getOrPut((hsl[0] / 30f).toInt() % 12) { mutableListOf() } += hsl.copyOf()
            }
            for (b in buckets.values.sortedByDescending { it.size }.take(3)) {
                val h = b.map { it[0] }.average().toFloat()
                val sat = b.map { it[1] }.average().toFloat().coerceIn(0.3f, 0.75f)
                val c = ColorUtils.HSLToColor(floatArrayOf(h, sat, 0.26f))
                if (own.none { distance(it, c) < 60 }) own += c
            }
        }
        return (own + listOf(SHADE, STONE, SUNSET)).distinct()
    }

    private fun distance(a: Int, b: Int) = kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))

    private fun background(c: Canvas, art: Bitmap?, tone: Int) {
        c.drawColor(tone)
        if (art != null) {
            // the cover blurred to a glow: made tiny, then drawn big (smoothly), twice
            val tiny = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(art, 24, 24, true), 12, 21, true)
            val soft = Bitmap.createScaledBitmap(tiny, W / 8, H / 8, true)
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
            c.drawBitmap(soft, null, Rect(-60, -60, W + 60, H + 60), p)
        }
        // deeper at the top and the bottom, where the words are
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(ColorUtils.setAlphaComponent(SHADE, 110), ColorUtils.setAlphaComponent(SHADE, 40), ColorUtils.setAlphaComponent(SHADE, 190)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), shade)
        // a warm glow low down (Radiance's)
        val glow = Paint().apply {
            shader = RadialGradient(W * 0.5f, H * 1.02f, W * 0.9f, ColorUtils.setAlphaComponent(ORANGE, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow)
    }

    private fun drawArt(c: Canvas, art: Bitmap?, box: RectF, corner: Float) {
        // a soft shadow under it
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(Color.BLACK, 120); maskFilter = BlurMaskFilter(corner * 0.9f, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(RectF(box.left + 6, box.top + 22, box.right - 6, box.bottom + 30), corner, corner, shadow)
        val save = c.save()
        val path = android.graphics.Path().apply { addRoundRect(box, corner, corner, android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        if (art != null) {
            // square, from the middle of the cover
            val side = minOf(art.width, art.height)
            val src = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
            c.drawBitmap(art, src, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            mark(c, box.left, box.top, box.width())
        }
        c.restoreToCount(save)
    }

    private fun text(size: Float, bold: Boolean, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, alpha)
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) 800 else 500, false)
    }

    private fun layout(s: String, paint: TextPaint, width: Int, align: Layout.Alignment, maxLines: Int, spacing: Float = 1.12f): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, spacing)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun StaticLayout.drawAt(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); draw(c); c.restore()
    }

    /** No lyrics: the cover big, the title and who sings it under it, centred. */
    private fun coverCard(c: Canvas, t: Track, art: Bitmap?) {
        val side = 800f
        val width = W - 160
        val title = layout(t.title, text(70f, true), width, Layout.Alignment.ALIGN_CENTER, 2)
        val by = layout(t.artist, text(44f, false, 205), width, Layout.Alignment.ALIGN_CENTER, 2)
        val block = side + 80 + title.height + 18 + by.height
        val top = SAFE_TOP + (FOOTER_Y - 40 - SAFE_TOP - block) / 2f
        val box = RectF((W - side) / 2, top, (W + side) / 2, top + side)
        drawArt(c, art, box, 56f)
        title.drawAt(c, 80f, box.bottom + 80)
        by.drawAt(c, 80f, box.bottom + 80 + title.height + 18)
    }

    /** Lyrics: a rounded card in the cover's colour, the cover small with the song beside it, then the lines big. */
    private fun lyricsCard(c: Canvas, t: Track, art: Bitmap?, lines: List<String>, tone: Int, lit: Int?) {
        val left = 70f
        val right = W - 70f
        val pad = 64f
        val inner = (right - left - 2 * pad).toInt()
        val thumb = 168f
        // as big as fits (large, for a phone screen), smaller for long lines; a gap between lines
        var size = 74f
        var lyric: List<StaticLayout>
        var lyricH: Int
        while (true) {
            val paint = text(size, true)
            lyric = lines.map { layout(it, paint, inner, Layout.Alignment.ALIGN_NORMAL, 4, 1.12f) }
            lyricH = lyric.sumOf { it.height } + (lyric.size - 1) * gapOf(size)
            if (lyricH <= 860 || size <= 44f) break
            size -= 4f
        }
        val head = thumb
        val height = pad + head + 56 + lyricH + pad
        val top = (SAFE_TOP + (FOOTER_Y - 40 - SAFE_TOP - height) / 2f).coerceAtLeast(SAFE_TOP.toFloat())
        val card = RectF(left, top, right, top + height)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(Color.BLACK, 110); maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(RectF(card.left + 10, card.top + 30, card.right - 10, card.bottom + 36), 64f, 64f, shadow)
        c.drawRoundRect(card, 64f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(tone, 236) })
        drawArt(c, art, RectF(left + pad, top + pad, left + pad + thumb, top + pad + thumb), 30f)
        val besideX = left + pad + thumb + 36
        val besideW = (right - pad - besideX).toInt()
        val title = layout(t.title, text(48f, true), besideW, Layout.Alignment.ALIGN_NORMAL, 2)
        val by = layout(t.artist, text(36f, false, 205), besideW, Layout.Alignment.ALIGN_NORMAL, 1)
        val textTop = top + pad + (thumb - title.height - 8 - by.height) / 2
        title.drawAt(c, besideX, textTop)
        by.drawAt(c, besideX, textTop + title.height + 8)
        var y = top + pad + head + 56
        for ((i, line) in lines.withIndex()) {
            // a video's frame: the sung line white, the rest dimmer (sung ones a little less)
            val alpha = when {
                lit == null || i == lit -> 255
                i < lit -> 150
                else -> 105
            }
            val l = if (alpha == 255) lyric[i] else layout(line, text(size, true, alpha), inner, Layout.Alignment.ALIGN_NORMAL, 4, 1.12f)
            l.drawAt(c, left + pad, y)
            y += l.height + gapOf(size)
        }
    }

    private fun gapOf(size: Float) = (size * 0.32f).toInt()

    /** Radiance Music's logo (on its own, no tile) and name, and where to get it, at the bottom (above Instagram's reply bar). */
    private fun footer(context: Context, c: Canvas) {
        val name = text(40f, true, 240)
        val link = text(30f, false, 215)
        val label = "Radiance Music"
        val get = "Get it free at $GET_IT"
        // the R as tall as the two lines beside it
        val logoH = 88f
        val logoW = Logo.widthFor(logoH)
        val gap = 22f
        val textW = maxOf(name.measureText(label), link.measureText(get))
        val x = (W - (logoW + gap + textW)) / 2
        val y = FOOTER_Y - 10f
        Logo.draw(c, x, y + 12f, logoH)
        c.drawText(label, x + logoW + gap, y + 46f, name)
        c.drawText(get, x + logoW + gap, y + 90f, link)
    }
}
