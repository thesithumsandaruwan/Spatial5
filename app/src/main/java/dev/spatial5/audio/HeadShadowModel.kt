package dev.spatial5.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Converts "where the listener's head points" into per-ear EQ + gain changes.
 *
 * The incoming audio was rendered (by Dolby / the mix) as if the listener faces the front of the
 * soundstage. When the head turns by `yaw`, a world-fixed front source sits at azimuth -yaw relative
 * to the head. We re-shape the two ears by the *difference* between the head-related response at the
 * new azimuth and the response at 0 deg, using the Brown & Duda spherical-head shadow model
 * (single pole/zero per ear), plus a small broadband level shift that stands in for the interaural
 * time difference (time/intensity trading), which a gain/EQ-only effect cannot reproduce directly.
 *
 * Conventions: azimuth in degrees, positive = source to the listener's LEFT.
 */
class HeadShadowModel(
    /** Upper edge of every EQ band, in Hz. */
    val bandCutoffs: FloatArray = floatArrayOf(200f, 500f, 1000f, 2000f, 3500f, 5500f, 8000f, 12000f, 20000f),
) {
    val bandCount get() = bandCutoffs.size

    private val centers = DoubleArray(bandCount) { i ->
        val lo = if (i == 0) 40.0 else bandCutoffs[i - 1].toDouble()
        sqrt(lo * bandCutoffs[i])
    }
    private val refDb = DoubleArray(bandCount) { shadowDb(centers[it], 90.0) }

    /**
     * @param azimuthDeg head-relative azimuth of the (world-fixed) front of the soundstage.
     * @param strength 0..1.5, user intensity.
     * @param outGain   size 2: broadband gain in dB for [left, right].
     * @param outBands  size 2 x bandCount: EQ gains in dB.
     */
    fun compute(azimuthDeg: Double, strength: Double, outGain: FloatArray, outBands: Array<FloatArray>) {
        val az = azimuthDeg.coerceIn(-MAX_AZ, MAX_AZ)
        // Head shadow from the true direction (also behind you); balance below uses the clamped one.
        val thetaL = angularDistance(azimuthDeg, 90.0)
        val thetaR = angularDistance(azimuthDeg, -90.0)
        val shadowScale = strength * 0.9
        // Scene behind you (motion mode / big turns): both ears lose "air", as with a real rear source.
        val rear = smooth((abs(azimuthDeg) - 70.0) / 80.0) * strength * REAR_DB
        for (b in 0 until bandCount) {
            val air = if (centers[b] > 2000) rear * ((centers[b] - 2000) / 6000).coerceAtMost(1.0) else 0.0
            outBands[0][b] = shape((shadowDb(centers[b], thetaL) - refDb[b]) * shadowScale - air)
            outBands[1][b] = shape((shadowDb(centers[b], thetaR) - refDb[b]) * shadowScale - air)
        }
        // Stage shift. Dolby (or any virtualiser after us) renders L/R as virtual speakers, so the image
        // only moves if the balance changes a lot: a saturating curve gives ~6 dB at 30 deg and ~8 dB
        // beyond 60 deg (default strength), applied mostly as a cut on the far side to keep headroom.
        val pan = BALANCE_DB * strength * tanh(1.6 * sin(Math.toRadians(az)))
        val near = abs(pan) * 0.25
        val far = -abs(pan) * 0.75
        if (pan >= 0) { outGain[0] = near.toFloat(); outGain[1] = far.toFloat() }
        else { outGain[0] = far.toFloat(); outGain[1] = near.toFloat() }
    }

    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    private fun shape(db: Double): Float = db.coerceIn(-MAX_CUT_DB, MAX_BOOST_DB).toFloat()

    companion object {
        const val MAX_AZ = 90.0
        const val BALANCE_DB = 12.0
        const val REAR_DB = 8.0
        const val MAX_BOOST_DB = 3.0
        const val MAX_CUT_DB = 18.0
        private const val HEAD_RADIUS_M = 0.0875
        private const val SPEED_OF_SOUND = 343.0
        private const val W0 = SPEED_OF_SOUND / HEAD_RADIUS_M
        private const val ALPHA_MIN = 0.1
        private const val THETA_MIN = 150.0

        /** Brown–Duda head shadow magnitude (dB) at [freqHz] for incidence angle [thetaDeg] from the ear axis. */
        fun shadowDb(freqHz: Double, thetaDeg: Double): Double {
            val alpha = (1 + ALPHA_MIN / 2) + (1 - ALPHA_MIN / 2) * cos(Math.toRadians(thetaDeg / THETA_MIN * 180.0))
            val x = 2 * Math.PI * freqHz / (2 * W0)
            return 10 * log10((1 + alpha * alpha * x * x) / (1 + x * x))
        }

        fun angularDistance(a: Double, b: Double): Double {
            var d = abs(a - b) % 360.0
            if (d > 180) d = 360 - d
            return d
        }
    }
}
