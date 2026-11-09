package dev.headtracker

/** Lock-free snapshot of what the service is doing, polled by the UI only while it is visible. */
object LiveState {
    @Volatile var running = false
    @Volatile var active = false
    @Volatile var yaw = 0f
    @Volatile var yawVelocity = 0f       // deg/s
    @Volatile var faceVisible = false
    @Volatile var fps = 0f
    /** Camera brightness 0..255 and whether low-light face detection is active. */
    @Volatile var cameraLuma = 0
    @Volatile var lowLight = false
    @Volatile var status = ""
    @Volatile var sessions = ""
    @Volatile var render = ""
    @Volatile var immersiveRunning = false
    @Volatile var paused = false
    /** Cinema (movie) profile active. */
    @Volatile var cinema = false
    /** Android ended the capture permission on its own (not the user): offer a one-tap restore. */
    @Volatile var immersiveLost = false
    /** Stage half-width (deg) and room amount actually in use. */
    @Volatile var width = 40f
    @Volatile var room = 0.2f
    /** Per virtual-speaker activity 0..1 (FL FR C LFE BL BR SL SR), or null. */
    @Volatile var levels: FloatArray? = null
    @Volatile var layout = LAYOUT_STANDARD
    /** Session / capture state for the diagnostics panel. */
    @Volatile var diag = ""
    /** A session scan / app identification is running (Refresh button spinner). */
    @Volatile var scanning = false

    const val LAYOUT_STANDARD = 0
    const val LAYOUT_UPMIX = 1
    const val LAYOUT_SURROUND = 2

    /** Head yaw predicted [leadSec] ahead (to cancel Bluetooth / buffer latency). */
    fun predictedYaw(leadSec: Double): Float {
        val lead = leadSec.coerceIn(0.0, MAX_LEAD).toFloat()
        return yaw + (yawVelocity * lead).coerceIn(-25f, 25f)
    }

    private const val MAX_LEAD = 0.15
}
