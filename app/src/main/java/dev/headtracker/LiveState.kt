package dev.headtracker

/** Lock-free snapshot of what the service is doing, polled by the UI only while it is visible. */
object LiveState {
    @Volatile var running = false
    @Volatile var active = false
    @Volatile var yaw = 0f
    @Volatile var yawVelocity = 0f       // deg/s
    @Volatile var faceVisible = false
    @Volatile var fps = 0f
    @Volatile var status = ""
    @Volatile var sessions = ""
    @Volatile var render = ""
    @Volatile var immersiveRunning = false
    @Volatile var dumpAvailable = false

    /** Head yaw predicted [leadSec] ahead (to cancel Bluetooth / buffer latency). */
    fun predictedYaw(leadSec: Double): Float {
        val lead = leadSec.coerceIn(0.0, MAX_LEAD).toFloat()
        return yaw + (yawVelocity * lead).coerceIn(-25f, 25f)
    }

    private const val MAX_LEAD = 0.15
}
