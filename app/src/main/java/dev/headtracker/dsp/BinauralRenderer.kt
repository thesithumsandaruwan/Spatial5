package dev.headtracker.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Head-tracked binaural renderer. Every sound in the mix gets a fixed position in the room and is
 * re-rendered through the HRTF at (position - headYaw), so turning your head moves the whole scene
 * the opposite way, front/back included.
 *
 * Stereo input is up-mixed per STFT bin (~47 Hz wide, every 5.3 ms): the correlated part of each
 * bin is a "direct" source whose direction follows its L/R panning (so each instrument keeps its
 * own spot on the stage); the uncorrelated / anti-phase part is ambience placed to the sides and
 * behind. 5.1 / 7.1 input is rendered as virtual speakers at the standard angles.
 *
 * Not thread-safe: one audio thread calls [process]; parameters are volatile.
 */
class BinauralRenderer(private val sampleRate: Int, private val hrtf: Hrtf, val fftSize: Int = 1024) {
    val hop = fftSize / 4
    val latencyFrames = fftSize - hop
    private val bins = fftSize / 2 + 1

    /** Half the angle covered by the front stage (deg). Real stereo speakers are at 30. */
    @Volatile var stageHalfWidth = 40f
    /** Where ambience sits (deg from front, both sides). */
    @Volatile var ambienceAzimuth = 110f
    /** 0..1 virtual-room amount. */
    @Volatile var roomAmount = 0.35f
        set(v) { field = v; room.amount = v }

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }
    private val outScale = 1f / (fftSize * 2f)        // 1/N for the IFFT, 1/2 for sqrt-Hann WOLA at 75 % overlap

    private val hist = Array(MAX_CH) { FloatArray(fftSize) }
    private val sRe = Array(MAX_CH) { FloatArray(bins) }
    private val sIm = Array(MAX_CH) { FloatArray(bins) }
    private val bRe = FloatArray(fftSize)
    private val bIm = FloatArray(fftSize)
    private val oRe = Array(2) { FloatArray(bins) }
    private val oIm = Array(2) { FloatArray(bins) }
    private val ola = Array(2) { FloatArray(fftSize) }

    private val pLL = FloatArray(bins)
    private val pRR = FloatArray(bins)
    private val pLR = FloatArray(bins)
    private val statAlpha = exp(-hop / (0.04 * sampleRate)).toFloat()

    private var surroundEnergy = 0.0
    private var frontEnergy = 1e-9
    private var sideEnergy = 0.0
    private var mcMix = 0f                       // 0 = stereo up-mix, 1 = discrete surround
    private val mixStep = hop / (0.3f * sampleRate)

    private val room = Room(sampleRate, latencyFrames)
    private val roomInL = FloatArray(hop)
    private val roomInR = FloatArray(hop)
    private val dryL = FloatArray(hop)
    private val dryR = FloatArray(hop)
    private val tmp = FloatArray(2)
    private var limEnv = 0f
    private val limRelease = exp(-1.0 / (0.08 * sampleRate)).toFloat()

    /** True while discrete surround input is being rendered (for the UI). */
    val surround get() = mcMix > 0.5f

    /**
     * @param input interleaved, [hop] frames x [channels] (2, 6 or 8; 5.1/7.1 in Android order:
     *        FL FR FC LFE BL BR [SL SR]).
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
        // Room input: plain stereo fold-down of the same block.
        for (i in 0 until hop) {
            var l = input[i * channels]; var r = input[i * channels + 1]
            if (ch >= 6) {
                val c = input[i * channels + 2] * 0.707f
                l += c + input[i * channels + 4] * 0.707f; r += c + input[i * channels + 5] * 0.707f
                if (ch >= 8) { l += input[i * channels + 6] * 0.707f; r += input[i * channels + 7] * 0.707f }
            }
            roomInL[i] = l; roomInR[i] = r
        }

        var c = 0
        while (c < ch) { analyzePair(c, if (c + 1 < ch) c + 1 else -1); c += 2 }

        for (e in 0..1) { oRe[e].fill(0f); oIm[e].fill(0f) }
        updateLayout(ch)
        if (mcMix < 1f) renderStereo(1f - mcMix, yawDeg)
        if (mcMix > 0f) renderSurround(mcMix, ch, yawDeg)
        synthesize()

        for (i in 0 until hop) { dryL[i] = ola[0][i]; dryR[i] = ola[1][i] }
        for (e in 0..1) {
            val o = ola[e]
            System.arraycopy(o, hop, o, 0, fftSize - hop)
            java.util.Arrays.fill(o, fftSize - hop, fftSize, 0f)
        }
        room.process(roomInL, roomInR, hop, yawDeg, dryL, dryR)

        // Peak limiter + safety clip.
        for (i in 0 until hop) {
            val l = dryL[i]; val r = dryR[i]
            val peak = max(abs(l), abs(r))
            limEnv = if (peak > limEnv) peak else limEnv * limRelease + peak * (1 - limRelease)
            val g = if (limEnv > LIMIT) LIMIT / limEnv else 1f
            out[2 * i] = (l * g).coerceIn(-1f, 1f)
            out[2 * i + 1] = (r * g).coerceIn(-1f, 1f)
        }
    }

    fun reset() {
        hist.forEach { it.fill(0f) }; ola.forEach { it.fill(0f) }
        pLL.fill(0f); pRR.fill(0f); pLR.fill(0f)
        room.reset()
    }

    /** Two real FFTs for the price of one complex FFT. */
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
        val width = stageHalfWidth
        val ambAz = ambienceAzimuth
        val ambLAz = ambAz - yaw; val ambRAz = -ambAz - yaw
        val dGain = gain * 0.7071f
        val aGain = gain * 0.85f
        val lr = sRe[0]; val li = sIm[0]; val rr = sRe[1]; val ri = sIm[1]
        val h = tmp
        for (k in 0 until bins) {
            val l2 = lr[k] * lr[k] + li[k] * li[k]
            val r2 = rr[k] * rr[k] + ri[k] * ri[k]
            pLL[k] = a * pLL[k] + b * l2
            pRR[k] = a * pRR[k] + b * r2
            pLR[k] = a * pLR[k] + b * (lr[k] * rr[k] + li[k] * ri[k])
            val tot = pLL[k] + pRR[k] + 1e-20f
            // Correlation (real part: anti-phase content counts as ambience, e.g. matrix surround).
            val phi = (pLR[k] / sqrt(pLL[k] * pRR[k] + 1e-20f)).coerceIn(0f, 1f)
            // Ambience = uncorrelated AND evenly spread. One-sided energy is a hard-panned source, not ambience.
            val balance = 1f - abs(pLL[k] - pRR[k]) / tot
            val amb = (1f - phi) * balance
            val gl = sqrt(pLL[k] / tot); val gr = sqrt(pRR[k] / tot)
            val dk = sqrt(1f - amb) * dGain
            val dr = dk * (gl * lr[k] + gr * rr[k]); val di = dk * (gl * li[k] + gr * ri[k])
            // Panning angle -> azimuth on the stage: full left = +width, centre = 0.
            val pan = atan2(gr, gl) / (PI.toFloat() / 4f)          // 0..2, 1 = centre
            val az = (1f - pan) * width - yaw
            val am = sqrt(amb) * aGain
            val alr = am * lr[k]; val ali = am * li[k]; val arr = am * rr[k]; val ari = am * ri[k]
            for (e in 0..1) {
                var or = 0f; var oi = 0f
                hrtf.lookup(e, az, k, h); or += dr * h[0] - di * h[1]; oi += dr * h[1] + di * h[0]
                hrtf.lookup(e, ambLAz, k, h); or += alr * h[0] - ali * h[1]; oi += alr * h[1] + ali * h[0]
                hrtf.lookup(e, ambRAz, k, h); or += arr * h[0] - ari * h[1]; oi += arr * h[1] + ari * h[0]
                oRe[e][k] += or; oIm[e][k] += oi
            }
        }
    }

    private fun renderSurround(gain: Float, ch: Int, yaw: Float) {
        val has71 = ch >= 8 && sideEnergy > 1e-4 * (frontEnergy + 1e-12)
        val back = if (has71) 145f else 115f
        val g = gain * 0.7071f
        speaker(0, 30f - yaw, g); speaker(1, -30f - yaw, g); speaker(2, -yaw, g)
        speaker(4, back - yaw, g); speaker(5, -back - yaw, g)
        if (ch >= 8) { speaker(6, 90f - yaw, g); speaker(7, -90f - yaw, g) }
        // LFE: no direction, straight to both ears.
        for (e in 0..1) for (k in 0 until bins) { oRe[e][k] += sRe[3][k] * g; oIm[e][k] += sIm[3][k] * g }
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

    /** IFFT of (left + j*right) gives both real ear signals in one transform. */
    private fun synthesize() {
        val lr = oRe[0]; val li = oIm[0]; val rr = oRe[1]; val ri = oIm[1]
        for (k in 0 until bins) { bRe[k] = lr[k] - ri[k]; bIm[k] = li[k] + rr[k] }
        for (k in 1 until fftSize / 2) {
            val n = fftSize - k
            // conj(L_k) + j*conj(R_k)
            bRe[n] = lr[k] + ri[k]; bIm[n] = -li[k] + rr[k]
        }
        fft.transform(bRe, bIm, inverse = true)
        val oL = ola[0]; val oR = ola[1]
        for (i in 0 until fftSize) {
            val w = window[i] * outScale
            oL[i] += bRe[i] * w; oR[i] += bIm[i] * w
        }
    }

    companion object {
        const val MAX_CH = 8
        private const val LIMIT = 0.95f
    }
}
