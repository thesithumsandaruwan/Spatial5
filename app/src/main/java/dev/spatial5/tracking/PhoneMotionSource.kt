package dev.spatial5.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.atan2

/**
 * Phone yaw about the world's vertical axis from the gyro-based GAME_ROTATION_VECTOR (no magnetometer,
 * so no compass jumps). Rotation is accumulated from frame-to-frame world rotations, so it stays
 * continuous whatever way the phone is held (upright, flat, in a pocket).
 */
class PhoneMotionSource(context: Context, private val handler: Handler, private val onYaw: (Double) -> Unit) :
    SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var running = false
    private val r = FloatArray(9)
    private val prev = FloatArray(9)
    private var havePrev = false
    private var yaw = 0.0

    val available get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = true
        havePrev = false
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        if (havePrev) {
            // Q = R * prev^T : world-frame rotation since the last sample. Its z-component is the yaw step.
            val q00 = r[0] * prev[0] + r[1] * prev[1] + r[2] * prev[2]
            val q01 = r[0] * prev[3] + r[1] * prev[4] + r[2] * prev[5]
            val q10 = r[3] * prev[0] + r[4] * prev[1] + r[5] * prev[2]
            val q11 = r[3] * prev[3] + r[4] * prev[4] + r[5] * prev[5]
            yaw += Math.toDegrees(atan2((q10 - q01).toDouble(), (q00 + q11).toDouble()))
        }
        System.arraycopy(r, 0, prev, 0, 9)
        havePrev = true
        onYaw(yaw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
