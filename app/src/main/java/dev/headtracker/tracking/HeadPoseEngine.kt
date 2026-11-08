package dev.headtracker.tracking

import kotlin.math.abs
import kotlin.math.exp

/**
 * Fuses the tracking inputs into one value: the listener's head yaw relative to the soundstage
 * (degrees, positive = head turned LEFT). All methods must be called from the same thread.
 *
 * FACE mode: the soundstage is anchored to the phone, like "fixed to device" spatial audio.
 *   Head-vs-phone yaw comes from the front camera at ~15 fps; between frames it is extrapolated
 *   with the face's velocity and corrected with the phone's own gyro rotation, then smoothed.
 * MOTION mode: the phone is assumed to move with your body (pocket / hand). Turning rotates the
 *   soundstage the other way, then it slowly re-centres in front of you.
 */
class HeadPoseEngine {
    enum class Mode { FACE, MOTION }

    var mode = Mode.FACE
        set(value) { if (field != value) { field = value; resetState() } }
    var invert = false
    var autoRecenter = true
    /** 0 = most responsive, 1 = smoothest. */
    var smoothness = 0.5
        set(value) {
            field = value.coerceIn(0.0, 1.0)
            faceFilter.minCutoff = 2.5 - 2.0 * field      // 2.5 Hz .. 0.5 Hz
            faceFilter.beta = 0.08 - 0.06 * field
        }

    private val faceFilter = OneEuroFilter(1.5, 0.05)

    private var faceYaw = 0.0
    private var faceTime = NONE
    private var phoneYawAtFace = 0.0
    private var centerOffset = 0.0

    private var phoneYaw = 0.0
    private var havePhone = false
    private var motionRef = 0.0

    private var output = 0.0
    private var lastTick = NONE

    var faceVisible = false
        private set

    init { smoothness = 0.5 }

    fun onFace(yawDeg: Double, timeSec: Double) {
        val y = if (invert) -yawDeg else yawDeg
        if (!faceVisible && faceTime != NONE && timeSec - faceTime > 0.5) faceFilter.reset()
        faceYaw = faceFilter.filter(y, timeSec)
        faceTime = timeSec
        phoneYawAtFace = phoneYaw
        faceVisible = true
    }

    fun onFaceMissing() { faceVisible = false }

    /** Unwrapped, continuous phone yaw about the world vertical axis (degrees, + = CCW / left). */
    fun onPhoneYaw(yawDeg: Double) {
        if (!havePhone) { havePhone = true; motionRef = yawDeg; phoneYawAtFace = yawDeg }
        phoneYaw = yawDeg
    }

    fun recenter() {
        when (mode) {
            Mode.FACE -> centerOffset = faceYaw
            Mode.MOTION -> motionRef = phoneYaw
        }
    }

    /** Advances the engine and returns head yaw relative to the soundstage. */
    fun tick(now: Double): Double {
        val dt = if (lastTick == NONE) 0.0 else (now - lastTick).coerceIn(0.0, 0.25)
        lastTick = now
        val target = when (mode) {
            Mode.FACE -> faceTarget(now, dt)
            Mode.MOTION -> motionTarget(dt)
        }
        if (target == null) {
            // Tracking lost: glide back to the centre.
            output += (0.0 - output) * (1 - exp(-dt / 0.7))
        } else {
            // Light final smoothing + slew limit so audio parameters never jump.
            val k = 1 - exp(-dt / 0.03)
            val step = ((target - output) * k).coerceIn(-MAX_SLEW * dt, MAX_SLEW * dt)
            output += step
        }
        output = output.coerceIn(-MAX_YAW, MAX_YAW)
        return output
    }

    private fun faceTarget(now: Double, dt: Double): Double? {
        if (faceTime == NONE) return null
        val since = now - faceTime
        val rel = faceYaw - centerOffset
        // Glanced away beyond the detector's range? Hold the last pose longer before giving up.
        val hold = if (abs(rel) > 25) 2.5 else 0.4
        if (since > hold) return null
        if (autoRecenter && faceVisible) centerOffset += (faceYaw - centerOffset) * (1 - exp(-dt / 40.0))
        val lead = (since + LATENCY_SEC).coerceAtMost(0.2)
        val predicted = if (faceVisible) (faceFilter.velocity * lead).coerceIn(-20.0, 20.0) else 0.0
        val phoneTurn = if (havePhone) phoneYaw - phoneYawAtFace else 0.0
        return rel + predicted - phoneTurn
    }

    private fun motionTarget(dt: Double): Double? {
        if (!havePhone) return null
        if (autoRecenter) {
            val d = phoneYaw - motionRef
            // Re-centre faster after a big, sustained turn (walking around a corner).
            val tau = if (abs(d) > 60) 2.5 else 7.0
            motionRef += d * (1 - exp(-dt / tau))
        }
        return phoneYaw - motionRef
    }

    private fun resetState() {
        faceFilter.reset(); faceTime = NONE; faceVisible = false
        motionRef = phoneYaw; centerOffset = 0.0
    }

    companion object {
        private const val NONE = -1.0
        const val MAX_YAW = 85.0
        private const val MAX_SLEW = 400.0        // deg/s
        private const val LATENCY_SEC = 0.06      // camera + detector latency to compensate
    }
}
