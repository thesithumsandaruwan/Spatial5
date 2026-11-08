package dev.headtracker.tracking

import kotlin.math.abs

/** One Euro filter (Casiez et al. 2012): low jitter when still, low lag when moving fast. */
class OneEuroFilter(var minCutoff: Double, var beta: Double, private val dCutoff: Double = 1.0) {
    private var initialized = false
    private var x = 0.0
    private var dx = 0.0
    private var t = 0.0

    /** Filtered rate of change, units per second. */
    val velocity get() = dx

    fun reset() { initialized = false; dx = 0.0 }

    fun filter(value: Double, timeSec: Double): Double {
        if (!initialized) {
            initialized = true; x = value; dx = 0.0; t = timeSec
            return x
        }
        val dt = (timeSec - t).coerceAtLeast(1e-3)
        t = timeSec
        val rawDx = (value - x) / dt
        dx += alpha(dCutoff, dt) * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
