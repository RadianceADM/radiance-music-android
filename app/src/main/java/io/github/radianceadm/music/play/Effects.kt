package io.github.radianceadm.music.play

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Mixer's effects, as the desktop's Speaker EQ makes them: a low cut and a
 * high cut (one 12 dB/octave filter each, Q 0.7), and a room reverb (the same
 * synthetic 1.6 s room, convolved; dry 1 - 0.35 r, wet 0.6 r, so it never
 * gets louder). On every song: the player's and the Mixer's sound alike.
 */
object Effects {
    const val LOW_OFF = 20.0
    const val LOW_MOST = 500.0
    const val HIGH_OFF = 20000.0
    const val HIGH_MOST = 1500.0

    /** Hz (20: off), Hz (20 kHz: off), 0-1. */
    @Volatile var lowCut = LOW_OFF
    @Volatile var highCut = HIGH_OFF
    @Volatile var reverb = 0.0

    private const val PREFS = "effects"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lowCut = p.getFloat("low", LOW_OFF.toFloat()).toDouble()
        highCut = p.getFloat("high", HIGH_OFF.toFloat()).toDouble()
        reverb = p.getFloat("reverb", 0f).toDouble()
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("low", lowCut.toFloat()).putFloat("high", highCut.toFloat()).putFloat("reverb", reverb.toFloat()).apply()
    }

    /** A slider's place (0-1, off at the left) as Hz, on a log scale (as the desktop's). */
    fun lowAt(v: Float) = LOW_OFF * (LOW_MOST / LOW_OFF).pow(v.toDouble())
    fun highAt(v: Float) = HIGH_OFF * (HIGH_MOST / HIGH_OFF).pow(v.toDouble())
    fun lowSlider() = (kotlin.math.ln(lowCut / LOW_OFF) / kotlin.math.ln(LOW_MOST / LOW_OFF)).toFloat().coerceIn(0f, 1f)
    fun highSlider() = (kotlin.math.ln(highCut / HIGH_OFF) / kotlin.math.ln(HIGH_MOST / HIGH_OFF)).toFloat().coerceIn(0f, 1f)

    fun neutral() = lowCut <= LOW_OFF + 0.5 && highCut >= HIGH_OFF - 0.5 && reverb < 0.005
}

/** A biquad (RBJ), one per channel, its frequency changeable as it runs. */
internal class Biquad(private val highPass: Boolean, private val rate: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var freq = -1.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun tune(f: Double) {
        if (f == freq) return
        freq = f
        val w = 2 * PI * f.coerceIn(10.0, rate * 0.45) / rate
        val alpha = sin(w) / (2 * 0.7)
        val c = cos(w)
        val a0 = 1 + alpha
        if (highPass) {
            b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0
        } else {
            b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0
        }
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun run(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() { z1.fill(0.0); z2.fill(0.0) }
}

/** An in-place complex FFT (radix 2). */
internal class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }

    fun run(re: FloatArray, im: FloatArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cosT[k * step].toFloat()
                    val s = (if (inverse) sinT[k * step] else -sinT[k * step]).toFloat()
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len = len shl 1
        }
        if (inverse) { val k = 1f / n; for (i in 0 until n) { re[i] *= k; im[i] *= k } }
    }
}

/** One channel's convolution with the room: uniformly partitioned, overlap-save, block B. */
internal class Convolver(ir: FloatArray) {
    private val b = BLOCK
    private val n = 2 * BLOCK
    private val fft = Fft(n)
    private val parts = (ir.size + b - 1) / b
    private val hRe = Array(parts) { FloatArray(n) }
    private val hIm = Array(parts) { FloatArray(n) }
    private val xRe = Array(parts) { FloatArray(n) }
    private val xIm = Array(parts) { FloatArray(n) }
    private var newest = 0
    private val last = FloatArray(b)
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    init {
        for (p in 0 until parts) {
            for (i in 0 until b) hRe[p][i] = ir.getOrElse(p * b + i) { 0f }
            fft.run(hRe[p], hIm[p], false)
        }
    }

    /** [x] (B samples in) → [y] (B samples of reverb out). */
    fun block(x: FloatArray, y: FloatArray) {
        newest = (newest + 1) % parts
        val xr = xRe[newest]; val xi = xIm[newest]
        System.arraycopy(last, 0, xr, 0, b)
        System.arraycopy(x, 0, xr, b, b)
        xi.fill(0f)
        fft.run(xr, xi, false)
        System.arraycopy(x, 0, last, 0, b)
        re.fill(0f); im.fill(0f)
        for (p in 0 until parts) {
            val s = ((newest - p) % parts + parts) % parts
            val ar = xRe[s]; val ai = xIm[s]; val br = hRe[p]; val bi = hIm[p]
            for (k in 0 until n) {
                re[k] += ar[k] * br[k] - ai[k] * bi[k]
                im[k] += ar[k] * bi[k] + ai[k] * br[k]
            }
        }
        fft.run(re, im, true)
        System.arraycopy(re, b, y, 0, b)
    }

    fun reset() {
        xRe.forEach { it.fill(0f) }; xIm.forEach { it.fill(0f) }; last.fill(0f)
    }

    companion object { const val BLOCK = 1024 }
}

/** The effects on a stream of interleaved stereo (or mono) float samples. */
class EffectsChain(private val rate: Int, private val channels: Int) {
    private val hp = Biquad(true, rate)
    private val lp = Biquad(false, rate)
    @Volatile private var rooms: Array<Convolver>? = null
    private var building = false
    private var roomUsed = false
    // the reverb's own stream: samples in, its sound out a block later
    private val inQ = Array(channels) { FloatArray(Convolver.BLOCK) }
    private val outQ = Array(channels) { FloatArray(Convolver.BLOCK) }
    private var filled = 0
    private var dryG = 1.0
    private var wetG = 0.0
    private val blk = FloatArray(Convolver.BLOCK)
    private val res = FloatArray(Convolver.BLOCK)

    /** [s] ([frames] frames, interleaved) through the effects, in place. */
    fun process(s: FloatArray, frames: Int) {
        val low = Effects.lowCut
        val high = Effects.highCut
        val r = Effects.reverb.coerceIn(0.0, 1.0)
        val useLow = low > Effects.LOW_OFF + 0.5
        val useHigh = high < Effects.HIGH_OFF - 0.5
        if (useLow) hp.tune(low)
        if (useHigh) lp.tune(high)
        val dryTo = 1 - 0.35 * r
        val wetTo = 0.6 * r
        if (wetTo > 0 && rooms == null && !building) {
            // the room is made off the sound's thread (a moment), then the reverb comes in
            building = true
            Thread {
                val made = Array(channels) { c -> Convolver(Room.ir(rate, if (c == 0) 0 else 233 * rate / 48000)) }
                rooms = made
            }.start()
        }
        for (f in 0 until frames) {
            dryG += (dryTo - dryG) * 0.0005
            wetG += (wetTo - wetG) * 0.0005
            for (c in 0 until channels) {
                val i = f * channels + c
                var x = s[i]
                if (useLow) x = hp.run(x, c.coerceAtMost(1))
                if (useHigh) x = lp.run(x, c.coerceAtMost(1))
                var wet = 0f
                val room = rooms
                if (room != null && (wetG > 1e-4 || wetTo > 0)) {
                    inQ[c][filled] = x
                    wet = outQ[c][filled]
                }
                s[i] = (x * dryG + wet * wetG).toFloat()
            }
            val room = rooms
            if (room != null && wetTo == 0.0 && wetG < 1e-4) {
                if (roomUsed) {
                    // the reverb's off: its room is emptied (no old tail when it's back)
                    room.forEach { it.reset() }
                    inQ.forEach { it.fill(0f) }; outQ.forEach { it.fill(0f) }
                    filled = 0
                    roomUsed = false
                }
            } else if (room != null && (wetG > 1e-4 || wetTo > 0)) {
                roomUsed = true
                filled++
                if (filled == Convolver.BLOCK) {
                    for (c in 0 until channels) {
                        System.arraycopy(inQ[c], 0, blk, 0, Convolver.BLOCK)
                        room[c].block(blk, res)
                        System.arraycopy(res, 0, outQ[c], 0, Convolver.BLOCK)
                    }
                    filled = 0
                }
            }
        }
    }

    fun reset() {
        hp.reset(); lp.reset(); rooms?.forEach { it.reset() }
        inQ.forEach { it.fill(0f) }; outQ.forEach { it.fill(0f) }; filled = 0
    }
}

/** Speaker EQ's room (config.rs ensure_reverb_ir), made at [rate], read from [offset]. */
internal object Room {
    fun ir(rate: Int, offset: Int): FloatArray {
        val n = rate * 16 / 10
        val ir = FloatArray(n)
        var seed = 0x2545F491L
        fun noise(): Float {
            seed = seed xor ((seed shl 13) and 0xFFFFFFFFL)
            seed = seed xor (seed ushr 17)
            seed = seed xor ((seed shl 5) and 0xFFFFFFFFL)
            return (seed.toFloat() / 4294967295f) * 2f - 1f
        }
        val pre = rate * 15 / 1000
        for ((ms, g) in listOf(11.0 to 0.6f, 19.0 to -0.45f, 27.0 to 0.35f, 37.0 to -0.3f, 49.0 to 0.22f)) {
            ir[pre + (ms * rate / 1000.0).toInt()] += g
        }
        var lp = 0f
        for (i in pre until n) {
            val t = (i - pre).toFloat() / rate
            val decay = exp(-6.9078f * t / 1.4f)
            val damping = (0.55f - 0.45f * (t / 1.6f)).coerceIn(0.08f, 0.55f)
            lp += damping * (noise() - lp)
            ir[i] += 0.5f * lp * decay
        }
        val energy = sqrt(ir.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(n - offset) { ir[it + offset] / energy }
    }
}

/** The effects in the player (every song it plays itself), in the song's own format (16, 24, 32-bit or float). */
@OptIn(UnstableApi::class)
class EffectsProcessor : BaseAudioProcessor() {
    private var chain: EffectsChain? = null
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (bytesOf(inputAudioFormat.encoding) == 0) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val format = inputAudioFormat
        val out = replaceOutputBuffer(size)
        if (Effects.neutral() && chain == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val enc = format.encoding
        val width = bytesOf(enc)
        val count = size / width
        if (floats.size < count) floats = FloatArray(count)
        val at = src.position()
        when (enc) {
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) floats[i] = src.getFloat(at + 4 * i)
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) floats[i] = src.getShort(at + 2 * i) / 32768f
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val p = at + 3 * i
                val v = (src.get(p).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8) or (src.get(p + 2).toInt() shl 16)
                floats[i] = v / 8388608f
            }
            else -> for (i in 0 until count) floats[i] = (src.getInt(at + 4 * i) / 2147483648.0).toFloat()
        }
        inputBuffer.position(inputBuffer.limit())
        val ch = format.channelCount
        val c = chain ?: EffectsChain(format.sampleRate, ch).also { chain = it }
        c.process(floats, count / ch)
        out.order(ByteOrder.LITTLE_ENDIAN)
        when (enc) {
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) out.putFloat(floats[i])
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) out.putShort((floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val v = (floats[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                out.put(v.toByte()); out.put((v shr 8).toByte()); out.put((v shr 16).toByte())
            }
            else -> for (i in 0 until count) out.putInt((floats[i].coerceIn(-1f, 1f) * 2147483647.0).toInt())
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        chain?.reset()
    }

    override fun onReset() {
        chain = null
    }

    private fun bytesOf(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
}
