package dev.headtracker.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Small virtual room: head-tracked early reflections (each a real direction in the room, rendered
 * with interaural delay + head-shadow filtering) plus a short, diffuse 4-line FDN tail. This is
 * what pushes the sound out of your head and makes positions feel physically "there".
 * Output is ADDED to the dry buffers; [alignDelay] lines it up behind the STFT path.
 */
class Room(private val sr: Int, private val alignDelay: Int) {
    @Volatile var amount = 0.35f

    private val size = Integer.highestOneBit(alignDelay + (0.08 * sr).toInt()) * 2
    private val mask = size - 1
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0

    // World-fixed reflections: source (0=L,1=R), azimuth (+ = left), delay ms, gain.
    private val src = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
    private val az = floatArrayOf(75f, 145f, -55f, 15f, -75f, -145f, 55f, -15f)
    private val delayMs = floatArrayOf(6.5f, 11.2f, 14.6f, 4.1f, 7.2f, 12.4f, 15.9f, 4.7f)
    private val gain = floatArrayOf(0.50f, 0.36f, 0.30f, 0.32f, 0.50f, 0.36f, 0.30f, 0.32f)
    private val n = src.size
    private val curDelay = Array(2) { FloatArray(n) }      // per ear, samples (smoothed)
    private val tgtDelay = Array(2) { FloatArray(n) }
    private val lpCoef = Array(2) { FloatArray(n) }
    private val lpState = Array(2) { FloatArray(n) }
    private var initialized = false

    // Late tail.
    private val fdnLen = floatArrayOf(17.3f, 21.1f, 24.7f, 29.3f).map { (it * sr / 1000).toInt() }.toIntArray()
    private val fdn = Array(4) { FloatArray(Integer.highestOneBit(fdnLen[it]) * 2) }
    private val fdnPos = IntArray(4)
    private val fdnGain = FloatArray(4) { 10f.pow(-3f * fdnLen[it] / (0.42f * sr)) }
    private val fdnLp = FloatArray(4)
    private val preDelay = alignDelay + (0.018 * sr).toInt()

    fun reset() {
        bufL.fill(0f); bufR.fill(0f); fdn.forEach { it.fill(0f) }; fdnLp.fill(0f)
        lpState.forEach { it.fill(0f) }
    }

    private fun computeTargets(yaw: Float) {
        val maxItd = Hrtf.itd(90.0)
        for (i in 0 until n) {
            val rel = az[i] - yaw
            val half = Hrtf.itd(rel.toDouble()) / 2
            val base = alignDelay + delayMs[i] * sr / 1000f
            tgtDelay[0][i] = base + ((maxItd / 2 - half) * sr).toFloat()
            tgtDelay[1][i] = base + ((maxItd / 2 + half) * sr).toFloat()
            val rear = (-cos(Math.toRadians(rel.toDouble()))).coerceAtLeast(0.0)
            for (e in 0..1) {
                val earAz = if (e == 0) 90.0 else -90.0
                var theta = abs(rel - earAz) % 360.0; if (theta > 180) theta = 360 - theta
                // Shadowed ear gets darker: 18 kHz when facing the ear, ~1.8 kHz from the far side.
                var fc = 18000.0 * (0.1).pow(((theta - 60).coerceAtLeast(0.0)) / 120.0)
                fc *= 1 - 0.45 * rear
                lpCoef[e][i] = exp(-2 * PI * fc / sr).toFloat()
            }
        }
        if (!initialized) { for (e in 0..1) System.arraycopy(tgtDelay[e], 0, curDelay[e], 0, n); initialized = true }
    }

    /** Fractional-delay read (linear interpolation). Integer index math only: no precision loss over time. */
    private fun read(buf: FloatArray, delay: Float): Float {
        val di = delay.toInt()
        val f = delay - di
        val a = buf[(w - di) and mask]; val b = buf[(w - di - 1) and mask]
        return a + (b - a) * f
    }

    fun process(inL: FloatArray, inR: FloatArray, frames: Int, yaw: Float, outL: FloatArray, outR: FloatArray) {
        val amt = amount
        if (amt <= 0.001f) {
            for (i in 0 until frames) { bufL[w and mask] = inL[i]; bufR[w and mask] = inR[i]; w++ }
            return
        }
        computeTargets(yaw)
        val step = 1f / frames
        val tail = 0.22f * amt
        for (i in 0 until frames) {
            bufL[w and mask] = inL[i]; bufR[w and mask] = inR[i]
            var l = 0f; var r = 0f
            for (j in 0 until n) {
                val buf = if (src[j] == 0) bufL else bufR
                val g = gain[j] * amt
                for (e in 0..1) {
                    val d = curDelay[e][j] + (tgtDelay[e][j] - curDelay[e][j]) * step
                    curDelay[e][j] = d
                    val x = read(buf, d) * g
                    val c = lpCoef[e][j]
                    val y = x * (1 - c) + lpState[e][j] * c
                    lpState[e][j] = y
                    if (e == 0) l += y else r += y
                }
            }
            // FDN tail (diffuse: not head-tracked).
            val inp = (read(bufL, preDelay.toFloat()) + read(bufR, preDelay.toFloat())) * 0.5f
            val y0 = fdnOut(0); val y1 = fdnOut(1); val y2 = fdnOut(2); val y3 = fdnOut(3)
            val s = 0.5f
            fdnIn(0, inp + s * (y0 + y1 + y2 + y3))
            fdnIn(1, inp + s * (y0 - y1 + y2 - y3))
            fdnIn(2, s * (y0 + y1 - y2 - y3))
            fdnIn(3, s * (y0 - y1 - y2 + y3))
            l += (y0 + y2) * tail; r += (y1 + y3) * tail
            outL[i] += l; outR[i] += r
            w++
        }
    }

    private fun fdnOut(i: Int): Float {
        val buf = fdn[i]
        val v = buf[(fdnPos[i] - fdnLen[i]) and (buf.size - 1)] * fdnGain[i]
        fdnLp[i] += 0.55f * (v - fdnLp[i])       // high-frequency damping
        return fdnLp[i]
    }

    private fun fdnIn(i: Int, v: Float) {
        val buf = fdn[i]
        buf[fdnPos[i] and (buf.size - 1)] = v
        fdnPos[i]++
    }
}
