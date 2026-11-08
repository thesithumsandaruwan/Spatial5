package dev.headtracker.audio

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.abs

/**
 * Owns one [DynamicsProcessing] instance per audio session we were told about (players broadcast
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION), or a single instance on the global output mix
 * (session 0) in global mode. Not thread-safe: call from the tracker thread only.
 */
class SpatialEffectManager(private val model: HeadShadowModel = HeadShadowModel()) {

    private val known = LinkedHashMap<Int, String>()     // sessionId -> package
    private val effects = HashMap<Int, DynamicsProcessing>()
    private var global = false

    private val gain = FloatArray(2)
    private val bands = Array(2) { FloatArray(model.bandCount) }
    private val applied = Array(2) { FloatArray(model.bandCount) { Float.NaN } }
    private val appliedGain = FloatArray(2) { Float.NaN }

    private var azimuth = 0.0
    private var strength = 0.6

    val attachedCount get() = effects.size
    val description: String
        get() = when {
            global -> if (effects.containsKey(0)) "Global output" else "Global mode unavailable on this device"
            known.isEmpty() -> "Waiting for a player to open an audio session"
            else -> known.entries.joinToString { (id, pkg) -> "${pkg.substringAfterLast('.')} #$id" + if (effects.containsKey(id)) "" else " (failed)" }
        }

    fun onSessionOpened(sessionId: Int, pkg: String) {
        if (sessionId <= 0) return
        known[sessionId] = pkg
        reconcile()
    }

    fun onSessionClosed(sessionId: Int) {
        known.remove(sessionId)
        reconcile()
    }

    fun setGlobal(enabled: Boolean) {
        if (global == enabled) return
        global = enabled
        reconcile()
    }

    private fun reconcile() {
        val wanted: Set<Int> = if (global) setOf(0) else known.keys
        effects.keys.filter { it !in wanted }.forEach { id -> effects.remove(id)?.let { safeRelease(it) } }
        wanted.filter { it !in effects }.forEach { id -> create(id)?.let { effects[id] = it } }
        invalidate()
        push(force = true)
    }

    private fun create(session: Int): DynamicsProcessing? = try {
        val eq = DynamicsProcessing.Eq(true, true, model.bandCount)
        for (b in 0 until model.bandCount) {
            eq.getBand(b).apply { isEnabled = true; cutoffFrequency = model.bandCutoffs[b]; gain = 0f }
        }
        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
            true, model.bandCount, false, 0, false, 0, true,
        ).setPreferredFrameDuration(10f)
            .setPreEqAllChannelsTo(eq)
            .setLimiterAllChannelsTo(limiter)
            .build()
        DynamicsProcessing(PRIORITY, session, config).also { it.enabled = true }
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot attach to session $session: $t")
        null
    }

    /** Updates the head-relative azimuth of the soundstage front (deg, + = left). */
    fun update(azimuthDeg: Double, strength: Double) {
        if (abs(azimuthDeg - azimuth) < 0.2 && strength == this.strength) return
        azimuth = azimuthDeg
        if (strength != this.strength) { this.strength = strength; invalidate() }
        push(force = false)
    }

    private fun invalidate() {
        appliedGain.fill(Float.NaN)
        applied.forEach { it.fill(Float.NaN) }
    }

    private val gainDirty = BooleanArray(CHANNELS)
    private val bandDirty = Array(CHANNELS) { BooleanArray(model.bandCount) }

    private fun push(force: Boolean) {
        if (effects.isEmpty()) return
        model.compute(azimuth, strength, gain, bands)
        var any = false
        for (ch in 0 until CHANNELS) {
            gainDirty[ch] = force || changed(appliedGain[ch], gain[ch])
            if (gainDirty[ch]) { appliedGain[ch] = gain[ch]; any = true }
            for (b in 0 until model.bandCount) {
                val d = force || changed(applied[ch][b], bands[ch][b])
                bandDirty[ch][b] = d
                if (d) { applied[ch][b] = bands[ch][b]; any = true }
            }
        }
        if (!any) return
        val dead = ArrayList<Int>(0)
        for ((id, dp) in effects) {
            try {
                for (ch in 0 until CHANNELS) {
                    if (gainDirty[ch]) dp.setInputGainbyChannel(ch, gain[ch])
                    for (b in 0 until model.bandCount) {
                        if (bandDirty[ch][b]) {
                            dp.setPreEqBandByChannelIndex(ch, b,
                                DynamicsProcessing.EqBand(true, model.bandCutoffs[b], bands[ch][b]))
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Session $id went away: $t")
                dead += id
            }
        }
        dead.forEach { id -> effects.remove(id)?.let { safeRelease(it) }; known.remove(id) }
    }

    private fun changed(old: Float, new: Float) = old.isNaN() || abs(old - new) >= 0.05f

    fun releaseAll() {
        effects.values.forEach { safeRelease(it) }
        effects.clear()
    }

    private fun safeRelease(dp: DynamicsProcessing) = try { dp.release() } catch (_: Throwable) {}

    private companion object {
        const val TAG = "SpatialEffect"
        const val CHANNELS = 2
        const val PRIORITY = 1000
    }
}
