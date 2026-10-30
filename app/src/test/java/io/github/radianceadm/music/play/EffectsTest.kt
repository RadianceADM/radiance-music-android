package io.github.radianceadm.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EffectsTest {
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    @Test fun convolverMatchesDirectConvolution() {
        val rnd = Random(7)
        val ir = FloatArray(3000) { rnd.nextFloat() * 2 - 1 }
        val b = Convolver.BLOCK
        val x = FloatArray(b * 6) { rnd.nextFloat() * 2 - 1 }
        val conv = Convolver(ir)
        val y = FloatArray(x.size)
        val outBlk = FloatArray(b)
        for (k in 0 until 6) {
            conv.block(x.copyOfRange(k * b, (k + 1) * b), outBlk)
            System.arraycopy(outBlk, 0, y, k * b, b)
        }
        var worst = 0.0
        for (n in x.indices) {
            var s = 0.0
            for (k in 0..minOf(n, ir.size - 1)) s += ir[k] * x[n - k]
            worst = maxOf(worst, kotlin.math.abs(s - y[n]))
        }
        assertTrue("worst error $worst", worst < 1e-2)
    }

    @Test fun roomHasUnitEnergy() {
        val ir = Room.ir(48000, 0)
        assertEquals(48000 * 16 / 10, ir.size)
        assertEquals(1.0, sqrt(ir.sumOf { (it * it).toDouble() }), 1e-3)
    }

    private fun sine(hz: Double, rate: Int, frames: Int) = FloatArray(frames * 2) { 0.5f * sin(2 * PI * hz * (it / 2) / rate).toFloat() }

    @Test fun lowCutTakesOutTheBass() {
        Effects.lowCut = 500.0; Effects.highCut = Effects.HIGH_OFF; Effects.reverb = 0.0
        val rate = 48000
        val lowTone = sine(50.0, rate, rate)
        EffectsChain(rate, 2).process(lowTone, rate)
        val highTone = sine(5000.0, rate, rate)
        EffectsChain(rate, 2).process(highTone, rate)
        val inRms = 0.5 / sqrt(2.0)
        assertTrue("50 Hz kept ${rms(lowTone, rate) / inRms}", rms(lowTone, rate) / inRms < 0.02)
        assertTrue("5 kHz lost ${rms(highTone, rate) / inRms}", rms(highTone, rate) / inRms > 0.95)
    }

    @Test fun highCutTakesOutTheHighs() {
        Effects.lowCut = Effects.LOW_OFF; Effects.highCut = 1500.0; Effects.reverb = 0.0
        val rate = 48000
        val hi = sine(12000.0, rate, rate)
        EffectsChain(rate, 2).process(hi, rate)
        val lo = sine(200.0, rate, rate)
        EffectsChain(rate, 2).process(lo, rate)
        val inRms = 0.5 / sqrt(2.0)
        assertTrue("12 kHz kept ${rms(hi, rate) / inRms}", rms(hi, rate) / inRms < 0.03)
        assertTrue("200 Hz lost ${rms(lo, rate) / inRms}", rms(lo, rate) / inRms > 0.95)
    }

    @Test fun reverbNeverGetsLouder() {
        Effects.lowCut = Effects.LOW_OFF; Effects.highCut = Effects.HIGH_OFF
        val rate = 44100
        val rnd = Random(3)
        val frames = 1024 * 172 // about 4 s
        // music-like: noise bursts with gaps
        val input = FloatArray(frames * 2) { i -> if ((i / 2) / 4410 % 2 == 0) (rnd.nextFloat() * 2 - 1) * 0.3f else 0f }
        for (r in listOf(0.3, 1.0)) {
            Effects.reverb = r
            val chain = EffectsChain(rate, 2)
            // the room is made on its own thread: give it a moment, as the player would
            chain.process(FloatArray(2048 * 2), 2048)
            Thread.sleep(500)
            val out = input.copyOf()
            for (f in 0 until frames step 1024) {
                val chunk = out.copyOfRange(f * 2, f * 2 + 2048)
                chain.process(chunk, 1024)
                System.arraycopy(chunk, 0, out, f * 2, 2048)
            }
            assertTrue(out.all { it.isFinite() })
            val gain = rms(out, rate * 2) / rms(input, rate * 2)
            assertTrue("reverb $r: ${"%.2f".format(gain)}x as loud", gain in 0.6..1.25)
            // there is reverb: sound in the gaps
            val gap = (0 until 4410 * 2).sumOf { val v = out[rate * 2 + 4410 * 2 + it]; (v * v).toDouble() }
            assertTrue("reverb $r: no tail", gap > 0.0)
            println("reverb $r: ${"%.2f".format(gain)}x as loud")
        }
    }
}
