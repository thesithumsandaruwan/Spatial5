package dev.headtracker

import dev.headtracker.dsp.BinauralRenderer
import dev.headtracker.dsp.Hrtf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Renders a synthetic band with a scripted head turn; writes build/demo/head_tracking_demo.wav. */
class DemoRenderTest {
    private val sr = 48000

    private fun yawAt(t: Double): Float = when {
        t < 4 -> 0.0
        t < 6 -> (t - 4) / 2 * 60               // turn head left to 60
        t < 8 -> 60.0
        t < 11 -> 60 - (t - 8) / 3 * 120        // swing right to -60
        t < 13 -> -60.0
        t < 14 -> -60 + (t - 13) * 60
        t < 16 -> 0.0
        t < 24 -> (t - 16) / 8 * 360            // full turn: band goes all the way around you
        else -> 0.0
    }.toFloat()

    /** Stereo test mix: bass+kick centre, plucked melody left, hi-hats right, lead centre, wide pad. */
    private fun mix(seconds: Int): Array<FloatArray> {
        val n = sr * seconds
        val l = FloatArray(n); val r = FloatArray(n)
        val rnd = Random(7)
        val beat = sr / 2
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val tb = (i % beat).toDouble() / sr
            val kick = 0.5 * sin(2 * PI * (50 + 80 * exp(-tb * 30)) * tb) * exp(-tb * 8)
            val bass = 0.18 * sin(2 * PI * listOf(55.0, 55.0, 65.4, 49.0)[(i / (beat * 4)) % 4] * t)
            val lead = 0.10 * sin(2 * PI * listOf(440.0, 494.0, 523.3, 587.3, 523.3, 494.0)[(i / beat) % 6] * t + 0.3 * sin(2 * PI * 5 * t))
            val c = kick + bass + lead
            l[i] += c.toFloat() * 0.707f; r[i] += c.toFloat() * 0.707f
        }
        // Plucked melody (Karplus-Strong), panned 80 % left.
        val notes = doubleArrayOf(392.0, 523.3, 659.3, 784.0, 659.3, 523.3)
        var start = 0; var ni = 0
        while (start < n) {
            val p = (sr / notes[ni % notes.size]).toInt(); val buf = FloatArray(p) { (rnd.nextFloat() * 2 - 1) * 0.3f }
            for (k in 0 until minOf(sr / 4, n - start)) {
                val v = buf[k % p]; buf[k % p] = 0.996f * 0.5f * (buf[k % p] + buf[(k + 1) % p])
                l[start + k] += v * 0.95f; r[start + k] += v * 0.3f
            }
            start += sr / 4; ni++
        }
        // Hi-hats panned 80 % right.
        var prev = 0f
        for (i in 0 until n) {
            val tb = (i % (sr / 4)).toDouble() / sr
            val x = (rnd.nextFloat() * 2 - 1); val hp = x - prev; prev = x
            val v = (hp * 0.12 * exp(-tb * 60)).toFloat()
            l[i] += v * 0.3f; r[i] += v * 0.95f
        }
        // Wide, decorrelated pad (ambience).
        var al = 0f; var ar = 0f
        for (i in 0 until n) {
            al += 0.02f * ((rnd.nextFloat() * 2 - 1) - al); ar += 0.02f * ((rnd.nextFloat() * 2 - 1) - ar)
            l[i] += al * 0.25f; r[i] += ar * 0.25f
        }
        return arrayOf(l, r)
    }

    @Test fun renderDemoAndCheckForClicks() {
        val hrtf = Hrtf.build(File("src/main/assets/hrtf_kemar.bin").inputStream(), 1024, sr)
        val seconds = 26
        val (inL, inR) = mix(seconds)
        val rend = BinauralRenderer(sr, hrtf).apply { roomAmount = 0.35f; stageHalfWidth = 40f }
        val hop = rend.hop
        val inp = FloatArray(hop * 2); val out = FloatArray(hop * 2)
        val pcm = FloatArray(inL.size * 2)
        var b = 0
        while ((b + 1) * hop <= inL.size) {
            for (i in 0 until hop) { inp[2 * i] = inL[b * hop + i]; inp[2 * i + 1] = inR[b * hop + i] }
            rend.process(inp, 2, yawAt(b * hop.toDouble() / sr), out)
            System.arraycopy(out, 0, pcm, b * hop * 2, hop * 2)
            b++
        }
        writeWav(File("build/demo/head_tracking_demo.wav"), pcm)

        // Click test: a pure tone while the head whips around at 300 deg/s must stay smooth.
        val r2 = BinauralRenderer(sr, hrtf).apply { roomAmount = 0f }
        var maxJump = 0f; var prevL = 0f; var peak = 0f
        for (blk in 0 until 600) {
            for (i in 0 until hop) { val v = (0.3 * sin(2 * PI * 300 * (blk * hop + i) / sr)).toFloat(); inp[2 * i] = v; inp[2 * i + 1] = v }
            r2.process(inp, 2, (blk * hop * 300.0 / sr % 360).toFloat(), out)
            if (blk > 10) for (i in 0 until hop) { maxJump = maxOf(maxJump, abs(out[2 * i] - prevL)); peak = maxOf(peak, abs(out[2 * i])); prevL = out[2 * i] }
            else prevL = out[2 * hop - 2]
        }
        val smooth = peak * 2 * PI.toFloat() * 300 / sr      // largest step a clean 300 Hz sine can make
        println("max sample step ${"%.4f".format(maxJump)} vs clean-sine bound ${"%.4f".format(smooth)}")
        assertTrue("clicks detected", maxJump < smooth * 1.6f)
    }

    private fun writeWav(f: File, interleaved: FloatArray) {
        f.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(f).buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            val bytes = interleaved.size * 2
            o.writeBytes("RIFF"); le32(36 + bytes); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(2)
            le32(sr); le32(sr * 4); le16(4); le16(16); o.writeBytes("data"); le32(bytes)
            for (v in interleaved) le16((v.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }
}
