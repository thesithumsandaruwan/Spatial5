package dev.spatial5.tracking

import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Low-light helper for face detection: measures frame brightness and, when it's dark, turns the
 * camera's luma plane into a contrast-stretched, gamma-lifted grey image (NV21 with neutral
 * chroma) that the face detector can work with. Allocation-free after the first frame of a size.
 */
class LowLight {
    private var nv21 = ByteArray(0)
    private val lut = ByteArray(256)
    private val hist = IntArray(256)

    /** Mean luma 0..255, sampled on a sparse grid (cheap). */
    fun meanLuma(y: ByteBuffer, rowStride: Int, w: Int, h: Int): Int {
        var sum = 0L; var n = 0
        var row = 0
        while (row < h) {
            var x = 0
            val base = row * rowStride
            while (x < w) { sum += y.get(base + x).toInt() and 0xFF; n++; x += 8 }
            row += 8
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /**
     * Returns an NV21 buffer of [w] x [h]: luma stretched from its 1st..99th percentile to full range
     * with a gamma lift, chroma neutral. The buffer is reused: don't hold on to it across frames.
     */
    fun enhance(y: ByteBuffer, rowStride: Int, w: Int, h: Int, gamma: Double = GAMMA): ByteArray {
        val size = w * h
        if (nv21.size != size * 3 / 2) {
            nv21 = ByteArray(size * 3 / 2)
            java.util.Arrays.fill(nv21, size, nv21.size, 128.toByte())
        }
        // Percentiles from a sparse histogram.
        hist.fill(0)
        var n = 0
        var row = 0
        while (row < h) {
            var x = 0; val base = row * rowStride
            while (x < w) { hist[y.get(base + x).toInt() and 0xFF]++; n++; x += 4 }
            row += 4
        }
        val lo = percentile(n, 0.01); val hi = maxOf(percentile(n, 0.99), lo + 16)
        val span = (hi - lo).toFloat()
        for (v in 0 until 256) {
            val t = ((v - lo) / span).coerceIn(0f, 1f)
            lut[v] = (255f * t.toDouble().pow(gamma).toFloat()).toInt().coerceIn(0, 255).toByte()
        }
        for (r in 0 until h) {
            val src = r * rowStride; val dst = r * w
            for (x in 0 until w) nv21[dst + x] = lut[y.get(src + x).toInt() and 0xFF]
        }
        return nv21
    }

    private fun percentile(n: Int, p: Double): Int {
        val target = (n * p).toInt()
        var acc = 0
        for (v in 0 until 256) { acc += hist[v]; if (acc > target) return v }
        return 255
    }

    companion object {
        /** Below this mean luma the frame is treated as "dark". */
        const val DARK = 80
        private const val GAMMA = 0.65
    }
}
