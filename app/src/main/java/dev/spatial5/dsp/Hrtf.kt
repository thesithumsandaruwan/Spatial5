package dev.spatial5.dsp

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Complex head-related transfer functions for both ears on a 1-degree azimuth grid, sampled at the
 * STFT bin frequencies. Magnitudes come from the measured MIT KEMAR set (horizontal plane, diffuse-
 * field equalised); the interaural time difference comes from Woodworth's spherical-head formula.
 *
 * Azimuth convention: degrees, 0 = straight ahead, positive = to the LEFT, wraps at 360.
 */
class Hrtf private constructor(val bins: Int, private val re: FloatArray, private val im: FloatArray) {

    /** Writes the interpolated response of [ear] (0 = left, 1 = right) for [azimuthDeg] at bin [k] into out[0..1]. */
    fun lookup(ear: Int, azimuthDeg: Float, k: Int, out: FloatArray) {
        var a = azimuthDeg % 360f
        if (a < 0) a += 360f
        val i0 = a.toInt() % 360
        val f = a - a.toInt()
        val i1 = (i0 + 1) % 360
        val p0 = (ear * 360 + i0) * bins + k
        val p1 = (ear * 360 + i1) * bins + k
        out[0] = re[p0] + (re[p1] - re[p0]) * f
        out[1] = im[p0] + (im[p1] - im[p0]) * f
    }

    /** Index of the first value for (ear, integer azimuth); bins follow contiguously. Fast path for fixed sources. */
    fun offset(ear: Int, azimuthDeg: Int): Int = (ear * 360 + Math.floorMod(azimuthDeg, 360)) * bins
    fun re(i: Int) = re[i]
    fun im(i: Int) = im[i]

    companion object {
        private const val HEAD_RADIUS = 0.0875
        private const val SPEED_OF_SOUND = 343.0

        /** Interaural time difference (s), positive when the source is on the left. */
        fun itd(azimuthDeg: Double): Double {
            val lateral = asin(sin(Math.toRadians(azimuthDeg)))     // folds rear onto front
            return HEAD_RADIUS / SPEED_OF_SOUND * (lateral + sin(lateral))
        }

        /** Builds the table from the asset stream, or from a spherical-head model when [data] is null. */
        fun build(data: InputStream?, fftSize: Int, sampleRate: Int): Hrtf {
            val bins = fftSize / 2 + 1
            val mag = data?.use { loadMagnitudes(it, bins, fftSize, sampleRate) } ?: modelMagnitudes(bins, fftSize, sampleRate)
            val re = FloatArray(2 * 360 * bins)
            val im = FloatArray(2 * 360 * bins)
            val maxItd = itd(90.0)
            for (az in 0 until 360) {
                val half = itd(az.toDouble()) / 2
                // Constant base delay keeps both ears causal; the near ear leads.
                val delay = doubleArrayOf(maxItd / 2 - half, maxItd / 2 + half)
                for (ear in 0..1) {
                    val m = mag(ear, az)
                    val base = (ear * 360 + az) * bins
                    for (k in 0 until bins) {
                        val w = 2 * PI * k * sampleRate / fftSize
                        val ph = -w * delay[ear]
                        re[base + k] = (m[k] * cos(ph)).toFloat()
                        im[base + k] = (m[k] * sin(ph)).toFloat()
                    }
                }
            }
            return Hrtf(bins, re, im)
        }

        /** Returns (ear, azimuth) -> linear magnitudes per bin. */
        private fun loadMagnitudes(input: InputStream, bins: Int, fftSize: Int, sr: Int): (Int, Int) -> DoubleArray {
            val buf = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic) == "HRTF") { "bad HRTF asset" }
            buf.int // version
            val nAz = buf.int; val nFreq = buf.int; val step = buf.float
            val freqs = FloatArray(nFreq) { buf.float }
            val ipsi = Array(nAz) { FloatArray(nFreq) }
            val contra = Array(nAz) { FloatArray(nFreq) }
            for (a in 0 until nAz) { for (i in 0 until nFreq) ipsi[a][i] = buf.float; for (i in 0 until nFreq) contra[a][i] = buf.float }

            // Per-bin interpolation positions on the log-frequency grid.
            val lo = ln(freqs[0].toDouble()); val hi = ln(freqs[nFreq - 1].toDouble())
            val pos = DoubleArray(bins) { k ->
                val f = (k.toDouble() * sr / fftSize).coerceIn(freqs[0].toDouble(), freqs[nFreq - 1].toDouble())
                (ln(f) - lo) / (hi - lo) * (nFreq - 1)
            }
            fun sample(row: FloatArray, p: Double): Double {
                val i = p.toInt().coerceAtMost(nFreq - 2); val t = p - i
                return row[i] * (1 - t) + row[i + 1] * t
            }
            return { ear, az ->
                // KEMAR data covers the right half (0..180 to the right). Our az is + to the left.
                val signed = if (az > 180) az - 360 else az           // -179..180, + = left
                val lateralAz = abs(signed).toDouble()                // 0..180 from front
                val a = lateralAz / step
                val i0 = a.toInt().coerceAtMost(nAz - 2); val t = a - i0
                val sourceOnLeft = signed > 0
                val near = (ear == 0) == sourceOnLeft || signed == 0 || signed == 180
                val rows = if (near) ipsi else contra
                DoubleArray(bins) { k ->
                    val db = sample(rows[i0], pos[k]) * (1 - t) + sample(rows[i0 + 1], pos[k]) * t
                    10.0.pow(db / 20)
                }
            }
        }

        /** Fallback without the asset: Brown–Duda head shadow plus a mild rear high-frequency roll-off. */
        private fun modelMagnitudes(bins: Int, fftSize: Int, sr: Int): (Int, Int) -> DoubleArray = { ear, az ->
            val earAz = if (ear == 0) 90.0 else -90.0
            var theta = abs(az - earAz) % 360.0; if (theta > 180) theta = 360 - theta
            val alpha = 1.05 + 0.95 * cos(Math.toRadians(theta / 150.0 * 180.0))
            val rear = (-cos(Math.toRadians(az.toDouble()))).coerceAtLeast(0.0)
            DoubleArray(bins) { k ->
                val f = k.toDouble() * sr / fftSize
                val x = 2 * PI * f / (2 * SPEED_OF_SOUND / HEAD_RADIUS)
                val shadow = Math.sqrt((1 + alpha * alpha * x * x) / (1 + x * x))
                val rearCut = 1.0 / Math.sqrt(1 + rear * (f / 4000.0).pow(2))
                shadow * rearCut
            }
        }
    }
}
