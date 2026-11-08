package dev.headtracker.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Head-tracked binaural renderer. Every sound in the mix gets a fixed position in the room and is
 * re-rendered through the HRTF at (position - headYaw), so turning your head moves the scene the
 * opposite way, front/back included.
 *
 * Fidelity first:
 *  - Bass (< ~150 Hz) passes through untouched: it is not localised anyway and rendering it makes
 *    it boomy and phasey.
 *  - "Timbre lock": while you face forward, a smoothed 1/3-octave EQ is learned that makes the 3D
 *    output's tonal balance match the original song. It freezes while you look away, so the real
 *    direction-dependent colour (e.g. darker from behind) is kept as a cue.
 *  - 512-point STFT (10.7 ms): drums stay sharp, ~8 ms of algorithmic delay.
 *  - Smoothed per-bin statistics (time and frequency) avoid "musical noise".
 *  - A look-ahead limiter with headroom instead of hard clipping.
 *
 * Stereo is up-mixed per bin: the correlated part is a direct source placed by its L/R panning
 * (each instrument keeps its own spot), the uncorrelated and evenly spread part is ambience to the
 * sides/back. 5.1/7.1 input is rendered as virtual speakers. Also measures the mix (width,
 * reverberance) to suggest the best stage width and room amount ([autoWidth], [autoRoom]).
 *
 * Not thread-safe: one audio thread calls [process]; parameters are volatile.
 */
class BinauralRenderer(private val sampleRate: Int, private val hrtf: Hrtf, val fftSize: Int = FFT_SIZE) {
    val hop = fftSize / 4
    val latencyFrames = fftSize - hop + LOOKAHEAD
    private val bins = fftSize / 2 + 1

    @Volatile var stageHalfWidth = 40f
    @Volatile var ambienceAzimuth = 110f
    /** 0..1 how strongly ambience is pulled out of the front stage. */
    @Volatile var separation = 0.8f
    @Volatile var roomAmount = 0.2f
        set(v) { field = v; room.amount = v }

    /** Suggestions from the content analysis (auto-tune). */
    @Volatile var autoWidth = 40f; private set
    @Volatile var autoRoom = 0.2f; private set
    /** Per virtual speaker activity 0..1 for the UI: FL FR C LFE BL BR SL SR (stereo uses 0,1,2 + 4,5 for ambience). */
    val levels = FloatArray(8)

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }
    private val outScale = 1f / (fftSize * 2f)

    private val hist = Array(MAX_CH) { FloatArray(fftSize) }
    private val sRe = Array(MAX_CH) { FloatArray(bins) }
    private val sIm = Array(MAX_CH) { FloatArray(bins) }
    private val bRe = FloatArray(fftSize)
    private val bIm = FloatArray(fftSize)
    private val oRe = Array(2) { FloatArray(bins) }
    private val oIm = Array(2) { FloatArray(bins) }
    private val ola = Array(2) { FloatArray(fftSize) }

    // Up-mix statistics (raw recursive + frequency-smoothed).
    private val pLL = FloatArray(bins); private val pRR = FloatArray(bins); private val pLR = FloatArray(bins)
    private val qLL = FloatArray(bins); private val qRR = FloatArray(bins); private val qLR = FloatArray(bins)
    private val statAlpha = exp(-hop / (0.03 * sampleRate)).toFloat()

    // Bass bypass and base delay (keeps bypassed bins time-aligned with rendered ones).
    private val binHz = sampleRate.toFloat() / fftSize
    private val renderWeight = FloatArray(bins) { k -> smooth((k * binHz - 90f) / 110f) }
    private val baseRe = FloatArray(bins); private val baseIm = FloatArray(bins)

    // Timbre lock.
    private val eIn = FloatArray(bins); private val eOut = FloatArray(bins)
    private val lockGain = FloatArray(bins) { 1f }
    private val cumIn = DoubleArray(bins + 1); private val cumOut = DoubleArray(bins + 1)
    private val lockAlpha = exp(-hop / (0.6 * sampleRate)).toFloat()
    private var lockWarm = 0

    private var surroundEnergy = 0.0
    private var frontEnergy = 1e-9
    private var sideEnergy = 0.0
    private var mcMix = 0f
    private val mixStep = hop / (0.3f * sampleRate)

    // Content analysis for auto-tune.
    private var spread = 0.4
    private var ambience = 0.3
    private var bassShare = 0.3
    private var analysisFrames = 0

    private val room = Room(sampleRate, fftSize - hop)   // aligned with the STFT path (limiter delays both)
    private val roomInL = FloatArray(hop); private val roomInR = FloatArray(hop)
    private val dryL = FloatArray(hop); private val dryR = FloatArray(hop)
    private val tmp = FloatArray(2)
    private val meterAcc = FloatArray(8)

    // Look-ahead limiter.
    private val laL = FloatArray(LOOKAHEAD); private val laR = FloatArray(LOOKAHEAD)
    private val laGain = FloatArray(LOOKAHEAD) { 1f }
    private var laPos = 0
    private var limGain = 1f
    private val limRelease = exp(-1.0 / (0.15 * sampleRate)).toFloat()
    private val limAttack = exp(-1.0 / (LOOKAHEAD / 3.0)).toFloat()

    val surround get() = mcMix > 0.5f

    init {
        val base = Hrtf.itd(90.0) / 2
        for (k in 0 until bins) {
            val ph = -2 * PI * k * binHz * base
            baseRe[k] = cos(ph).toFloat(); baseIm[k] = kotlin.math.sin(ph).toFloat()
        }
    }

    /**
     * @param input interleaved, [hop] frames x [channels] (2, 6 or 8; Android order FL FR FC LFE BL BR [SL SR]).
     * @param yawDeg head yaw relative to the scene, + = turned left.
     * @param out interleaved stereo, [hop] frames.
     */
    fun process(input: FloatArray, channels: Int, yawDeg: Float, out: FloatArray) {
        val ch = channels.coerceAtMost(MAX_CH)
        for (c in 0 until ch) {
            val h = hist[c]
            System.arraycopy(h, hop, h, 0, fftSize - hop)
            var i = 0
            while (i < hop) { h[fftSize - hop + i] = input[i * channels + c]; i++ }
        }
        for (i in 0 until hop) {
            var l = input[i * channels]; var r = input[i * channels + 1]
            if (ch >= 6) {
                val c = input[i * channels + 2] * 0.707f
                l += c + input[i * channels + 4] * 0.707f; r += c + input[i * channels + 5] * 0.707f
                if (ch >= 8) { l += input[i * channels + 6] * 0.707f; r += input[i * channels + 7] * 0.707f }
            }
            roomInL[i] = l; roomInR[i] = r
        }
        meter(input, channels, ch)

        var c = 0
        while (c < ch) { analyzePair(c, if (c + 1 < ch) c + 1 else -1); c += 2 }

        for (e in 0..1) { oRe[e].fill(0f); oIm[e].fill(0f) }
        updateLayout(ch)
        if (mcMix < 1f) renderStereo(1f - mcMix, yawDeg)
        if (mcMix > 0f) renderSurround(mcMix, ch, yawDeg)
        timbreLock(ch, abs(yawDeg) < 20f)
        bassBypass(ch)
        synthesize()

        for (i in 0 until hop) { dryL[i] = ola[0][i]; dryR[i] = ola[1][i] }
        for (e in 0..1) {
            val o = ola[e]
            System.arraycopy(o, hop, o, 0, fftSize - hop)
            java.util.Arrays.fill(o, fftSize - hop, fftSize, 0f)
        }
        room.process(roomInL, roomInR, hop, yawDeg, dryL, dryR)
        limit(out)
    }

    fun reset() {
        hist.forEach { it.fill(0f) }; ola.forEach { it.fill(0f) }
        pLL.fill(0f); pRR.fill(0f); pLR.fill(0f)
        room.reset()
        laL.fill(0f); laR.fill(0f); laGain.fill(1f); limGain = 1f
        levels.fill(0f)
    }

    private fun analyzePair(a: Int, b: Int) {
        val ha = hist[a]
        for (i in 0 until fftSize) {
            bRe[i] = ha[i] * window[i]
            bIm[i] = if (b >= 0) hist[b][i] * window[i] else 0f
        }
        fft.transform(bRe, bIm)
        for (k in 0 until bins) {
            val nk = (fftSize - k) and (fftSize - 1)
            val xr = bRe[k]; val xi = bIm[k]; val yr = bRe[nk]; val yi = bIm[nk]
            sRe[a][k] = 0.5f * (xr + yr); sIm[a][k] = 0.5f * (xi - yi)
            if (b >= 0) { sRe[b][k] = 0.5f * (xi + yi); sIm[b][k] = 0.5f * (yr - xr) }
        }
    }

    private fun updateLayout(ch: Int) {
        if (ch < 6) { mcMix = 0f; return }
        var front = 0.0; var sur = 0.0; var side = 0.0
        for (k in 1 until bins) {
            front += (sRe[0][k] * sRe[0][k] + sIm[0][k] * sIm[0][k] + sRe[1][k] * sRe[1][k] + sIm[1][k] * sIm[1][k]).toDouble()
            sur += (sRe[2][k] * sRe[2][k] + sIm[2][k] * sIm[2][k] + sRe[4][k] * sRe[4][k] + sIm[4][k] * sIm[4][k] +
                sRe[5][k] * sRe[5][k] + sIm[5][k] * sIm[5][k]).toDouble()
            if (ch >= 8) side += (sRe[6][k] * sRe[6][k] + sIm[6][k] * sIm[6][k] + sRe[7][k] * sRe[7][k] + sIm[7][k] * sIm[7][k]).toDouble()
        }
        val a = 0.98
        frontEnergy = a * frontEnergy + (1 - a) * front
        surroundEnergy = a * surroundEnergy + (1 - a) * (sur + side)
        sideEnergy = a * sideEnergy + (1 - a) * side
        val discrete = surroundEnergy > 1e-4 * (frontEnergy + 1e-12) && surroundEnergy > 1e-10
        mcMix = if (discrete) (mcMix + mixStep).coerceAtMost(1f) else (mcMix - mixStep).coerceAtLeast(0f)
    }

    private fun renderStereo(gain: Float, yaw: Float) {
        val a = statAlpha; val b = 1 - a
        val lr = sRe[0]; val li = sIm[0]; val rr = sRe[1]; val ri = sIm[1]
        for (k in 0 until bins) {
            pLL[k] = a * pLL[k] + b * (lr[k] * lr[k] + li[k] * li[k])
            pRR[k] = a * pRR[k] + b * (rr[k] * rr[k] + ri[k] * ri[k])
            pLR[k] = a * pLR[k] + b * (lr[k] * rr[k] + li[k] * ri[k])
        }
        // Smooth statistics across neighbouring bins: stabler panning/ambience decisions.
        for (k in 0 until bins) {
            val k0 = max(0, k - 1); val k1 = min(bins - 1, k + 1)
            qLL[k] = 0.25f * pLL[k0] + 0.5f * pLL[k] + 0.25f * pLL[k1]
            qRR[k] = 0.25f * pRR[k0] + 0.5f * pRR[k] + 0.25f * pRR[k1]
            qLR[k] = 0.25f * pLR[k0] + 0.5f * pLR[k] + 0.25f * pLR[k1]
        }
        val width = stageHalfWidth
        val sep = separation
        val ambLAz = ambienceAzimuth - yaw; val ambRAz = -ambienceAzimuth - yaw
        val dGain = gain * 0.7071f
        val aGain = gain * 0.7071f
        val h = tmp
        var wSpread = 0.0; var wAmb = 0.0; var wTot = 0.0
        for (k in 0 until bins) {
            val tot = qLL[k] + qRR[k] + 1e-20f
            val phi = (qLR[k] / sqrt(qLL[k] * qRR[k] + 1e-20f)).coerceIn(0f, 1f)
            val balance = 1f - abs(qLL[k] - qRR[k]) / tot
            val amb = (1f - phi) * balance * sep
            val gl = sqrt(qLL[k] / tot); val gr = sqrt(qRR[k] / tot)
            val dk = sqrt(1f - amb) * dGain
            val dr = dk * (gl * lr[k] + gr * rr[k]); val di = dk * (gl * li[k] + gr * ri[k])
            val pan = atan2(gr, gl) / (PI.toFloat() / 4f)          // 0..2, 1 = centre
            val az = (1f - pan) * width - yaw
            val am = sqrt(amb) * aGain
            val alr = am * lr[k]; val ali = am * li[k]; val arr = am * rr[k]; val ari = am * ri[k]
            for (e in 0..1) {
                var or = 0f; var oi = 0f
                hrtf.lookup(e, az, k, h); or += dr * h[0] - di * h[1]; oi += dr * h[1] + di * h[0]
                if (am > 1e-4f) {
                    hrtf.lookup(e, ambLAz, k, h); or += alr * h[0] - ali * h[1]; oi += alr * h[1] + ali * h[0]
                    hrtf.lookup(e, ambRAz, k, h); or += arr * h[0] - ari * h[1]; oi += arr * h[1] + ari * h[0]
                }
                oRe[e][k] += or; oIm[e][k] += oi
            }
            val f = k * binHz
            if (f in 300f..8000f) {
                wTot += tot; wSpread += tot * abs(1f - pan); wAmb += tot * (1f - phi) * balance
            }
        }
        analyse(wTot, wSpread, wAmb)
    }

    /** Slowly tracks how wide and how reverberant the mix is, and suggests stage/room settings. */
    private fun analyse(tot: Double, spreadSum: Double, ambSum: Double) {
        if (tot < 1e-9) return
        analysisFrames++
        val tau = if (analysisFrames < sampleRate * 2 / hop) 0.8 else 6.0   // settle fast on a new song
        val k = 1 - exp(-hop / (tau * sampleRate))
        spread += (spreadSum / tot - spread) * k
        ambience += (ambSum / tot - ambience) * k
        // Narrow mixes get opened up a little; wide mixes keep a natural speaker-like stage.
        autoWidth = (30 + 26 * (1 - spread.coerceIn(0.0, 1.0))).toFloat().coerceIn(30f, 55f)
        // Dry recordings need more room to leave your head; reverberant or bass-heavy ones need less.
        var r = 0.32 - 0.5 * ambience
        if (bassShare > 0.45) r *= 0.7
        autoRoom = r.toFloat().coerceIn(0.06f, 0.30f)
    }

    private fun renderSurround(gain: Float, ch: Int, yaw: Float) {
        val has71 = ch >= 8 && sideEnergy > 1e-4 * (frontEnergy + 1e-12)
        val back = if (has71) 145f else 115f
        val g = gain * 0.7071f
        speaker(0, 30f - yaw, g); speaker(1, -30f - yaw, g); speaker(2, -yaw, g)
        speaker(4, back - yaw, g); speaker(5, -back - yaw, g)
        if (ch >= 8) { speaker(6, 90f - yaw, g); speaker(7, -90f - yaw, g) }
        for (e in 0..1) for (k in 0 until bins) {
            oRe[e][k] += (sRe[3][k] * baseRe[k] - sIm[3][k] * baseIm[k]) * g
            oIm[e][k] += (sRe[3][k] * baseIm[k] + sIm[3][k] * baseRe[k]) * g
        }
        autoRoom = 0.12f
    }

    private fun speaker(c: Int, az: Float, g: Float) {
        var a = az % 360f; if (a < 0) a += 360f
        val i0 = a.toInt() % 360; val f = a - a.toInt()
        val xr = sRe[c]; val xi = sIm[c]
        for (e in 0..1) {
            val p0 = hrtf.offset(e, i0); val p1 = hrtf.offset(e, i0 + 1)
            val r = oRe[e]; val im = oIm[e]
            for (k in 0 until bins) {
                val hr = hrtf.re(p0 + k) + (hrtf.re(p1 + k) - hrtf.re(p0 + k)) * f
                val hi = hrtf.im(p0 + k) + (hrtf.im(p1 + k) - hrtf.im(p0 + k)) * f
                r[k] += g * (xr[k] * hr - xi[k] * hi)
                im[k] += g * (xr[k] * hi + xi[k] * hr)
            }
        }
    }

    /** Learns (while facing forward) an EQ making the output's tonal balance equal the original's. */
    private fun timbreLock(ch: Int, adapt: Boolean) {
        if (!adapt) return
        val a = lockAlpha; val b = 1 - a
        var bass = 0.0; var all = 0.0
        for (k in 0 until bins) {
            var ein = 0f
            for (c in 0 until min(ch, 2)) ein += sRe[c][k] * sRe[c][k] + sIm[c][k] * sIm[c][k]
            if (ch >= 6) for (c in 2 until ch) ein += 0.5f * (sRe[c][k] * sRe[c][k] + sIm[c][k] * sIm[c][k])
            val eout = oRe[0][k] * oRe[0][k] + oIm[0][k] * oIm[0][k] + oRe[1][k] * oRe[1][k] + oIm[1][k] * oIm[1][k]
            eIn[k] = a * eIn[k] + b * ein
            eOut[k] = a * eOut[k] + b * eout
            all += ein; if (k * binHz < 200f) bass += ein
        }
        if (all > 1e-12) bassShare += (bass / all - bassShare) * 0.002
        lockWarm++
        if (lockWarm < 20) return
        for (k in 0 until bins) { cumIn[k + 1] = cumIn[k] + eIn[k]; cumOut[k + 1] = cumOut[k] + eOut[k] }
        for (k in 0 until bins) {
            val w = max(1, (k * 0.12f).toInt())          // ~1/3 octave
            val lo = max(0, k - w); val hi = min(bins, k + w + 1)
            val ei = cumIn[hi] - cumIn[lo]; val eo = cumOut[hi] - cumOut[lo]
            if (eo < 1e-14 || ei < 1e-14) continue
            lockGain[k] = sqrt(ei / eo).toFloat().coerceIn(0.5f, 2f)
        }
    }

    /** Applies the timbre lock and swaps the rendered bass for the original (time-aligned) bass. */
    private fun bassBypass(ch: Int) {
        for (k in 0 until bins) {
            val g = lockGain[k]
            val w = renderWeight[k]
            var lr = 0f; var li = 0f; var rr = 0f; var ri = 0f
            if (w < 1f) {
                var xl = sRe[0][k]; var yl = sIm[0][k]; var xr = sRe[1][k]; var yr = sIm[1][k]
                if (ch >= 6) {
                    val cr = (sRe[2][k] + sRe[3][k]) * 0.707f; val ci = (sIm[2][k] + sIm[3][k]) * 0.707f
                    xl += cr + 0.707f * sRe[4][k]; yl += ci + 0.707f * sIm[4][k]
                    xr += cr + 0.707f * sRe[5][k]; yr += ci + 0.707f * sIm[5][k]
                }
                lr = xl * baseRe[k] - yl * baseIm[k]; li = xl * baseIm[k] + yl * baseRe[k]
                rr = xr * baseRe[k] - yr * baseIm[k]; ri = xr * baseIm[k] + yr * baseRe[k]
            }
            val gw = g * w; val bw = 1f - w
            oRe[0][k] = oRe[0][k] * gw + lr * bw; oIm[0][k] = oIm[0][k] * gw + li * bw
            oRe[1][k] = oRe[1][k] * gw + rr * bw; oIm[1][k] = oIm[1][k] * gw + ri * bw
        }
    }

    private fun synthesize() {
        val lr = oRe[0]; val li = oIm[0]; val rr = oRe[1]; val ri = oIm[1]
        for (k in 0 until bins) { bRe[k] = lr[k] - ri[k]; bIm[k] = li[k] + rr[k] }
        for (k in 1 until fftSize / 2) {
            val n = fftSize - k
            bRe[n] = lr[k] + ri[k]; bIm[n] = -li[k] + rr[k]
        }
        fft.transform(bRe, bIm, inverse = true)
        val oL = ola[0]; val oR = ola[1]
        for (i in 0 until fftSize) {
            val w = window[i] * outScale
            oL[i] += bRe[i] * w; oR[i] += bIm[i] * w
        }
    }

    /** Look-ahead peak limiter: gain is lowered *before* a peak arrives, smoothly, so nothing clips or crackles. */
    private fun limit(out: FloatArray) {
        for (i in 0 until hop) {
            val l = dryL[i] * HEADROOM; val r = dryR[i] * HEADROOM
            val peak = max(abs(l), abs(r))
            laGain[laPos] = if (peak > CEILING) CEILING / peak else 1f
            var target = 1f
            for (g in laGain) if (g < target) target = g
            limGain = if (target < limGain) target + (limGain - target) * limAttack else target + (limGain - target) * limRelease
            val ol = laL[laPos]; val orr = laR[laPos]
            laL[laPos] = l; laR[laPos] = r
            laPos = (laPos + 1) % LOOKAHEAD
            out[2 * i] = (ol * limGain).coerceIn(-1f, 1f)
            out[2 * i + 1] = (orr * limGain).coerceIn(-1f, 1f)
        }
    }

    private fun meter(input: FloatArray, channels: Int, ch: Int) {
        val acc = meterAcc; acc.fill(0f)
        for (i in 0 until hop) {
            val base = i * channels
            if (ch >= 6) for (c in 0 until ch) { val v = input[base + c]; acc[c] += v * v }
            else {
                val l = input[base]; val r = input[base + 1]
                acc[0] += l * l; acc[1] += r * r
                val m = (l + r) * 0.5f; val s = (l - r) * 0.5f
                acc[2] += m * m; acc[4] += s * s; acc[5] += s * s
            }
        }
        for (c in 0 until 8) {
            val v = (sqrt(acc[c] / hop) * 4f).coerceAtMost(1f)
            levels[c] = if (v > levels[c]) v else levels[c] * 0.93f + v * 0.07f
        }
    }

    companion object {
        const val FFT_SIZE = 512
        const val MAX_CH = 8
        private const val LOOKAHEAD = 48
        private const val HEADROOM = 0.89f      // -1 dB
        private const val CEILING = 0.97f

        private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    }
}
