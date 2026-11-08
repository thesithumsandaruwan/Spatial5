package dev.headtracker.audio

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.abs

/**
 * Owns one [DynamicsProcessing] instance per player audio session. Each session is either
 *  - [Mode.MUTE]: silenced, because the immersive engine re-renders that app's audio, or
 *  - [Mode.SHAPE]: "light" head tracking done in place with per-ear EQ (zero added latency).
 * Session 0 (global output mix) can be shaped in light-only setups. Tracker thread only.
 */
class SpatialEffectManager(private val model: HeadShadowModel = HeadShadowModel()) {
    enum class Mode { SHAPE, MUTE }

    private class Slot(val mode: Mode, val dp: DynamicsProcessing)

    private val slots = HashMap<Int, Slot>()
    private val gain = FloatArray(2)
    private val bands = Array(2) { FloatArray(model.bandCount) }
    private val applied = Array(2) { FloatArray(model.bandCount) { Float.NaN } }
    private val appliedGain = FloatArray(2) { Float.NaN }
    private val gainDirty = BooleanArray(CHANNELS)
    private val bandDirty = Array(CHANNELS) { BooleanArray(model.bandCount) }
    private var azimuth = 0.0
    private var strength = 0.6

    fun modeOf(session: Int): Mode? = slots[session]?.mode
    fun isAttached(session: Int) = slots.containsKey(session)

    /** Makes the attached effects match [wanted] (session -> mode). Returns sessions that failed. */
    fun apply(wanted: Map<Int, Mode>): Set<Int> {
        slots.keys.filter { wanted[it] != slots[it]?.mode }.forEach { id -> slots.remove(id)?.let { release(it.dp) } }
        val failed = HashSet<Int>()
        for ((id, mode) in wanted) {
            if (slots.containsKey(id)) continue
            val dp = create(id, mode)
            if (dp == null) failed += id else slots[id] = Slot(mode, dp)
        }
        invalidate()
        push(force = true)
        return failed
    }

    private fun create(session: Int, mode: Mode): DynamicsProcessing? = try {
        val builder = if (mode == Mode.MUTE) {
            DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, CHANNELS,
                false, 0, false, 0, false, 0, false)
        } else {
            val eq = DynamicsProcessing.Eq(true, true, model.bandCount)
            for (b in 0 until model.bandCount) {
                eq.getBand(b).apply { isEnabled = true; cutoffFrequency = model.bandCutoffs[b]; gain = 0f }
            }
            DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                true, model.bandCount, false, 0, false, 0, true)
                .setPreEqAllChannelsTo(eq)
                .setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        }
        val dp = DynamicsProcessing(PRIORITY, session, builder.setPreferredFrameDuration(10f).build())
        if (mode == Mode.MUTE) dp.setInputGainAllChannelsTo(MUTE_DB)
        dp.enabled = true
        dp
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot attach to session $session: $t")
        null
    }

    /** Updates the head-relative azimuth of the soundstage front (deg, + = left) for SHAPE sessions. */
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

    private fun push(force: Boolean) {
        if (slots.values.none { it.mode == Mode.SHAPE }) return
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
        for ((id, slot) in slots) {
            if (slot.mode != Mode.SHAPE) continue
            try {
                for (ch in 0 until CHANNELS) {
                    if (gainDirty[ch]) slot.dp.setInputGainbyChannel(ch, gain[ch])
                    for (b in 0 until model.bandCount) {
                        if (bandDirty[ch][b]) slot.dp.setPreEqBandByChannelIndex(ch, b,
                            DynamicsProcessing.EqBand(true, model.bandCutoffs[b], bands[ch][b]))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Session $id went away: $t"); dead += id
            }
        }
        dead.forEach { id -> slots.remove(id)?.let { release(it.dp) } }
    }

    private fun changed(old: Float, new: Float) = old.isNaN() || abs(old - new) >= 0.05f

    fun releaseAll() {
        slots.values.forEach { release(it.dp) }
        slots.clear()
    }

    private fun release(dp: DynamicsProcessing) = try { dp.release() } catch (_: Throwable) {}

    private companion object {
        const val TAG = "SpatialEffect"
        const val CHANNELS = 2
        const val PRIORITY = 1000
        const val MUTE_DB = -120f
    }
}
