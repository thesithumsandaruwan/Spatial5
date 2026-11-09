package dev.headtracker

import dev.headtracker.audio.DumpSessionScanner
import dev.headtracker.dsp.BinauralRenderer
import dev.headtracker.dsp.Fft
import dev.headtracker.dsp.Hrtf
import dev.headtracker.tracking.HeadPoseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class RendererTest {
    private val sr = 48000
    private val hrtf by lazy { Hrtf.build(File("src/main/assets/hrtf_kemar.bin").inputStream(), BinauralRenderer.FFT_SIZE, sr) }

    /** Renders [seconds] of input produced by [gen] (frame index, channel) with a fixed yaw. */
    private fun render(channels: Int, seconds: Double, yaw: Float, room: Float = 0f, gen: (Int, Int) -> Float): Pair<FloatArray, FloatArray> {
        val r = BinauralRenderer(sr, hrtf).apply { roomAmount = room }
        val hop = r.hop
        val blocks = (seconds * sr / hop).toInt()
        val l = FloatArray(blocks * hop); val rr = FloatArray(blocks * hop)
        val inp = FloatArray(hop * channels); val out = FloatArray(hop * 2)
        for (b in 0 until blocks) {
            for (i in 0 until hop) for (c in 0 until channels) inp[i * channels + c] = gen(b * hop + i, c)
            r.process(inp, channels, yaw, out)
            for (i in 0 until hop) { l[b * hop + i] = out[2 * i]; rr[b * hop + i] = out[2 * i + 1] }
        }
        return l to rr
    }

    private fun rms(x: FloatArray, from: Int = x.size / 4) = sqrt(x.drop(from).sumOf { (it * it).toDouble() } / (x.size - from))
    private fun db(a: Double, b: Double) = 20 * log10(a / b)
    private val noise = Random(1).let { rnd -> FloatArray(sr * 3) { (rnd.nextGaussian() * 0.1).toFloat() } }

    /** Lag (samples) maximizing cross-correlation; positive = right ear later than left. */
    private fun lag(l: FloatArray, r: FloatArray, max: Int = 48): Int {
        var best = 0; var bestV = -1e30
        val from = l.size / 4; val to = l.size - max
        for (d in -max..max) {
            var s = 0.0
            for (i in from until to) s += l[i] * r[i + d]
            if (s > bestV) { bestV = s; best = d }
        }
        return best
    }

    @Test fun fftMatchesDft() {
        val n = 64; val f = Fft(n)
        val re = FloatArray(n) { sin(0.3 * it).toFloat() + 0.2f * it % 3 }; val im = FloatArray(n)
        val r0 = re.copyOf()
        f.transform(re, im)
        for (k in 0 until n) {
            var sr_ = 0.0; var si = 0.0
            for (t in 0 until n) { sr_ += r0[t] * cos(2 * PI * k * t / n); si -= r0[t] * sin(2 * PI * k * t / n) }
            assertEquals(sr_, re[k].toDouble(), 1e-3); assertEquals(si, im[k].toDouble(), 1e-3)
        }
    }

    @Test fun centreSourceIsBalancedFacingForward() {
        val (l, r) = render(2, 1.5, 0f) { i, _ -> noise[i] }
        assertEquals(0.0, db(rms(l), rms(r)), 0.5)
        assertEquals(0, lag(l, r))
    }

    @Test fun turningLeftMovesCentreSourceToRightEar() {
        val (l, r) = render(2, 1.5, 40f) { i, _ -> noise[i] }       // head turned 40 deg left
        assertTrue("right ear louder: ${db(rms(r), rms(l))} dB", db(rms(r), rms(l)) > 3)
        val d = lag(l, r)
        assertTrue("left ear later (negative lag): $d", d < -8)
    }

    @Test fun hardLeftInstrumentStaysOnTheLeft() {
        val (l, r) = render(2, 1.5, 0f) { i, c -> if (c == 0) noise[i] else 0f }
        assertTrue(db(rms(l), rms(r)) > 4)
    }

    @Test fun eachInstrumentKeepsItsOwnDirection() {
        // Left instrument = low noise band, right instrument = high band; a head turn to the right
        // (yaw -40) must push the LEFT instrument towards the front, the right one further right.
        val (l0, r0) = render(2, 1.5, 0f) { i, c -> if (c == 0) noise[i] else 0f }
        val (l1, r1) = render(2, 1.5, -40f) { i, c -> if (c == 0) noise[i] else 0f }
        val ild0 = db(rms(l0), rms(r0)); val ild1 = db(rms(l1), rms(r1))
        assertTrue("left source: ILD $ild0 -> $ild1 dB should grow when it moves to the side", ild1 > ild0 + 1)
    }

    @Test fun cinemaPinsDialogueToTheScreen() {
        // A voice mixed slightly left of centre (as in many film mixes).
        val voice: (Int, Int) -> Float = { i, c -> noise[i] * (if (c == 0) 1.0f else 0.8f) }
        fun ild(lock: Float): Double {
            val r = BinauralRenderer(sr, hrtf).apply { roomAmount = 0f; dialogueLock = lock; stageHalfWidth = 45f }
            val hop = r.hop; val inp = FloatArray(hop * 2); val out = FloatArray(hop * 2)
            val l = ArrayList<Float>(); val rr = ArrayList<Float>()
            for (b in 0 until (1.5 * sr / hop).toInt()) {
                for (i in 0 until hop) { inp[2 * i] = voice(b * hop + i, 0); inp[2 * i + 1] = voice(b * hop + i, 1) }
                r.process(inp, 2, 0f, out)
                for (i in 0 until hop) { l += out[2 * i]; rr += out[2 * i + 1] }
            }
            return db(rms(l.toFloatArray()), rms(rr.toFloatArray()))
        }
        val free = ild(0f); val locked = ild(1f)
        println("voice slightly left: ILD free ${"%.1f".format(free)} dB, cinema ${"%.1f".format(locked)} dB")
        assertTrue(abs(locked) < 0.6 && abs(free) > abs(locked) + 0.5)
    }

    @Test fun screenAnchoredTakesFrontFromFirstFace() {
        // Landscape: the front camera is at the side, so a user looking at the screen reads as ~12 deg.
        val e = HeadPoseEngine().apply { worldAnchor = false; autoRecenter = false }
        var t = 0.0; var y = 0.0
        repeat(40) { e.onFace(12.0, t); y = e.tick(t); t += 1 / 24.0 }
        assertEquals(0.0, y, 1.0)
        repeat(40) { e.onFace(42.0, t); y = e.tick(t); t += 1 / 24.0 }   // then turns 30 deg left
        assertEquals(30.0, y, 3.0)
    }

    @Test fun levelIsComparableToInput() {
        val (l, r) = render(2, 1.5, 0f, room = 0.35f) { i, c -> noise[(i + c * 7919) % noise.size] }
        val outDb = 20 * log10((rms(l) + rms(r)) / 2); val inDb = 20 * log10(0.1)
        assertEquals(inDb, outDb, 4.0)
        assertTrue(l.all { it.isFinite() } && r.all { it.isFinite() })
    }

    @Test fun sourceBehindIsDarkerThanInFront() {
        fun highShare(yaw: Float): Double {
            val (l, _) = render(2, 1.0, yaw) { i, _ -> noise[i] }
            // crude spectral tilt: energy of first difference vs signal energy
            val d = FloatArray(l.size - 1) { l[it + 1] - l[it] }
            return rms(d) / rms(l)
        }
        assertTrue(highShare(180f) < highShare(0f))
    }

    @Test fun surroundInputUsesVirtualSpeakers() {
        val r = BinauralRenderer(sr, hrtf)
        val ch = 8; val inp = FloatArray(r.hop * ch); val out = FloatArray(r.hop * 2)
        repeat(200) { b -> for (i in 0 until r.hop) { inp[i * ch] = noise[b * r.hop + i]; inp[i * ch + 1] = noise[b * r.hop + i] }
            r.process(inp, ch, 0f, out) }
        assertTrue("stereo content in 8ch capture -> up-mix", !r.surround)
        repeat(200) { b -> for (i in 0 until r.hop) inp[i * ch + 5] = noise[(b * r.hop + i) % noise.size]
            r.process(inp, ch, 0f, out) }
        assertTrue("real surround -> virtual speakers", r.surround)
    }

    @Test fun fasterThanRealTime() {
        val r = BinauralRenderer(sr, hrtf).apply { roomAmount = 0.35f }
        val inp = FloatArray(r.hop * 2) { noise[it] }; val out = FloatArray(r.hop * 2)
        repeat(500) { r.process(inp, 2, 10f, out) }                 // warm-up / JIT
        val blocks = (10.0 * sr / r.hop).toInt()
        val t0 = System.nanoTime()
        for (b in 0 until blocks) r.process(inp, 2, (b % 90).toFloat(), out)
        val sec = (System.nanoTime() - t0) / 1e9
        println("10 s of audio rendered in ${"%.3f".format(sec)} s (${"%.0f".format(10 / sec)}x real time)")
        assertTrue(sec < 2.0)
    }

    @Test fun worldAnchorKeepsSceneFixedWhenPhoneMoves() {
        val e = HeadPoseEngine().apply { autoRecenter = false; worldAnchor = true }
        var t = 0.0
        e.onPhoneYaw(0.0)
        repeat(30) { e.onFace(0.0, t); e.tick(t); t += 1 / 24.0 }
        // User turns head AND phone together by 30 deg left (e.g. turning in a swivel chair):
        // camera still sees a frontal face, but the head turned in the room.
        e.onPhoneYaw(30.0)
        var y = 0.0
        repeat(30) { e.onFace(0.0, t); y = e.tick(t); t += 1 / 24.0 }
        assertEquals(30.0, y, 2.0)
    }

    @Test fun parsesDumpsysPlayers() {
        val text = """
  ID:15 -- type:android.media.AudioTrack -- u/pid:10172/12345 -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null -- session:2345
  ID:16 -- type:android.media.SoundPool -- u/pid:1000/1863 -- state:idle -- attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x800 tags= bundle=null -- session:0
  AudioPlaybackConfiguration piid:31 deviceId:0 type:android.media.AudioTrack u/pid:10201/999 state:paused attr:AudioAttributes: usage=USAGE_GAME content=CONTENT_TYPE_UNKNOWN sessionId:777
"""
        val found = DumpSessionScanner.parse(text, ownUid = 10500)
        assertEquals(listOf(DumpSessionScanner.Found(10172, 2345), DumpSessionScanner.Found(10201, 777)), found)
    }
}
