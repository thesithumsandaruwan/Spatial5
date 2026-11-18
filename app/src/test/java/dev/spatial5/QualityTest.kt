package dev.spatial5

import dev.spatial5.dsp.BinauralRenderer
import dev.spatial5.dsp.Fft
import dev.spatial5.dsp.Hrtf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Fidelity checks: the 3D render must keep the song's tone, bass and cleanliness. */
class QualityTest {
    private val sr = 48000
    private val hrtf by lazy { Hrtf.build(File("src/main/assets/hrtf_kemar.bin").inputStream(), BinauralRenderer.FFT_SIZE, sr) }

    private fun run(seconds: Double, yaw: Float, room: Float, gen: (Int, Int) -> Float, r: BinauralRenderer = BinauralRenderer(sr, hrtf)): Array<FloatArray> {
        r.roomAmount = room
        val hop = r.hop; val blocks = (seconds * sr / hop).toInt()
        val l = FloatArray(blocks * hop); val rr = FloatArray(blocks * hop)
        val inp = FloatArray(hop * 2); val out = FloatArray(hop * 2)
        for (b in 0 until blocks) {
            for (i in 0 until hop) { inp[2 * i] = gen(b * hop + i, 0); inp[2 * i + 1] = gen(b * hop + i, 1) }
            r.process(inp, 2, yaw, out)
            for (i in 0 until hop) { l[b * hop + i] = out[2 * i]; rr[b * hop + i] = out[2 * i + 1] }
        }
        return arrayOf(l, rr)
    }

    /** Power spectrum of the last [n] samples (Hann). */
    private fun spectrum(x: FloatArray, n: Int = 65536): DoubleArray {
        val f = Fft(n); val re = FloatArray(n); val im = FloatArray(n)
        val off = x.size - n
        for (i in 0 until n) re[i] = x[off + i] * (0.5f - 0.5f * cos(2 * PI * i / n).toFloat())
        f.transform(re, im)
        return DoubleArray(n / 2) { (re[it] * re[it] + im[it] * im[it]).toDouble() }
    }

    private fun bandDb(p: DoubleArray, fc: Double, n: Int = 65536): Double {
        val lo = (fc * 2.0.pow(-1 / 6.0) * n / sr).toInt(); val hi = (fc * 2.0.pow(1 / 6.0) * n / sr).toInt()
        return 10 * log10((lo..hi).sumOf { p[it] } + 1e-30)
    }

    // A "mix": partly correlated pink-ish noise (centre + sides + ambience).
    private val rnd = Random(3)
    private val c = pink(sr * 8); private val s1 = pink(sr * 8); private val s2 = pink(sr * 8)
    private fun pink(n: Int): FloatArray {
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        return FloatArray(n) {
            val w = rnd.nextGaussian() * 0.05
            b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913
            (b0 + b1 + b2 + w * 0.1848).toFloat()
        }
    }
    private val mix: (Int, Int) -> Float = { i, ch -> c[i] * 0.7f + (if (ch == 0) s1[i] else s2[i]) * 0.5f }

    @Test fun tonalBalanceMatchesOriginal() {
        val (l, r) = run(8.0, 0f, 0f, mix)
        val inL = FloatArray(l.size) { mix(it, 0) }; val inR = FloatArray(l.size) { mix(it, 1) }
        val po = spectrum(l).zip(spectrum(r)) { a, b -> a + b }.toDoubleArray()
        val pi = spectrum(inL).zip(spectrum(inR)) { a, b -> a + b }.toDoubleArray()
        val bands = listOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 12500.0)
        val diffs = bands.map { bandDb(po, it) - bandDb(pi, it) }
        val mean = diffs.average()
        val dev = diffs.map { it - mean }
        println("tone deviation per band (dB): " + bands.zip(dev).joinToString { (f, d) -> "${f.toInt()}:${"%+.1f".format(d)}" } + "  overall ${"%+.1f".format(mean)} dB")
        assertTrue("tonal balance off: $dev", dev.all { abs(it) < 1.5 })
        assertTrue("loudness preserved: $mean", abs(mean) < 2.0)
    }

    @Test fun noAudibleDistortion() {
        val f0 = 1000.0
        val tone: (Int, Int) -> Float = { i, _ -> (0.5 * sin(2 * PI * f0 * i / sr)).toFloat() }   // -6 dBFS, centre
        val (l, _) = run(3.0, 0f, 0f, tone)
        val p = spectrum(l)
        val fund = bandDb(p, f0)
        val h = listOf(2000.0, 3000.0, 4000.0, 5000.0).maxOf { bandDb(p, it) } - fund
        println("worst harmonic: ${"%.1f".format(h)} dB")
        assertTrue("harmonics too high: $h dB", h < -60)
    }

    @Test fun bassPassesThroughUntouched() {
        val bass: (Int, Int) -> Float = { i, ch -> if (ch == 0) (0.5 * sin(2 * PI * 60 * i / sr)).toFloat() else 0f }
        val (l, r) = run(3.0, 40f, 0.3f, bass)          // even with the head turned and room on
        fun rms(x: FloatArray) = sqrt(x.takeLast(sr).sumOf { (it * it).toDouble() } / sr)
        val inRms = 0.5 / sqrt(2.0)
        println("60 Hz: left ${"%.2f".format(20 * log10(rms(l) / inRms))} dB, right ${"%.1f".format(20 * log10(rms(r) / inRms + 1e-9))} dB")
        assertTrue(abs(20 * log10(rms(l) / inRms)) < 1.5)
        assertTrue(20 * log10(rms(r) / inRms + 1e-9) < -20)
    }

    @Test fun autoTuneAdaptsToTheMix() {
        val dry = BinauralRenderer(sr, hrtf)
        run(4.0, 0f, 0.2f, { i, ch -> if ((i / 12000) % 2 == ch) c[i] else c[i] * 0.2f }, dry)  // panned, dry sources
        val wet = BinauralRenderer(sr, hrtf)
        run(4.0, 0f, 0.2f, { i, ch -> if (ch == 0) s1[i] else s2[i] }, wet)                   // fully diffuse / reverberant
        println("auto room: dry ${"%.2f".format(dry.autoRoom)}, wet ${"%.2f".format(wet.autoRoom)}; width dry ${dry.autoWidth}, wet ${wet.autoWidth}")
        assertTrue(dry.autoRoom > wet.autoRoom + 0.08)
    }
}
