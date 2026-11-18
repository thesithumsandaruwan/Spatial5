package dev.spatial5.audio

import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log

/**
 * Finds the audio sessions of players that don't announce them, without root or adb.
 *
 * Android hands out session ids from one increasing counter (step 8), so a player's session is one
 * of the most recently allocated ids. A passive [Visualizer] attached to a candidate session
 * measures whether audio is flowing through it. Batched, read-only, and released immediately.
 * Needs RECORD_AUDIO. Blocking: call from a background thread.
 */
class SessionProber(private val audio: AudioManager) {

    /** Last scan, for the diagnostics panel. */
    @Volatile var lastReport = "No scan yet"
        private set

    /**
     * Returns the sessions among the newest [depth] candidates that are currently playing audio,
     * loudest first. [skip] = sessions to ignore (known, ours, rejected).
     */
    fun findPlaying(depth: Int, skip: Set<Int>): List<Int> {
        val newest = try { audio.generateAudioSessionId() } catch (_: Throwable) { return emptyList() }
        if (newest <= 0) return emptyList()
        val candidates = ArrayList<Int>(depth)
        var id = newest - STEP
        while (candidates.size < depth && id > 0) { if (id !in skip) candidates += id; id -= STEP }
        created = 0; failed = 0; lastError = ""
        val found = measureLevels(candidates)
        lastReport = "newest id $newest · scanned ${candidates.size} · visualizers ok $created / failed $failed" +
            (if (lastError.isNotEmpty()) " ($lastError)" else "") +
            " · playing: " + (if (found.isEmpty()) "none" else found.joinToString { "#${it.first} (${it.second / 100} dB)" })
        if (found.isNotEmpty()) Log.i(TAG, "playing sessions: $found (newest id $newest)")
        return found.sortedByDescending { it.second }.map { it.first }
    }

    /** Returns which of [sessions] are playing right now. */
    fun measure(sessions: List<Int>): List<Int> = if (sessions.isEmpty()) emptyList() else measureLevels(sessions).map { it.first }

    private var created = 0
    private var failed = 0
    private var lastError = ""

    private fun measureLevels(candidates: List<Int>): List<Pair<Int, Int>> {
        val found = ArrayList<Pair<Int, Int>>()
        for (batch in candidates.chunked(BATCH)) {
            val vis = batch.mapNotNull { sid ->
                try {
                    (Visualizer(sid).apply { measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS; enabled = true } to sid).also { created++ }
                } catch (t: Throwable) { failed++; lastError = t.javaClass.simpleName + (t.message?.let { ": " + it.take(40) } ?: ""); null }
            }
            if (vis.isEmpty()) continue
            SystemClock.sleep(WAIT_MS)
            val m = Visualizer.MeasurementPeakRms()
            for ((v, sid) in vis) {
                try {
                    if (v.getMeasurementPeakRms(m) == Visualizer.SUCCESS && m.mPeak > ACTIVE_MB) found += sid to m.mRms
                } catch (_: Throwable) {}
                try { v.release() } catch (_: Throwable) {}
            }
        }
        return found
    }

    companion object {
        private const val TAG = "SessionProber"
        private const val STEP = 8                // AUDIO_UNIQUE_ID_USE_MAX
        private const val BATCH = 16
        private const val WAIT_MS = 140L
        private const val ACTIVE_MB = -6000       // peak above -60 dBFS = audio flowing
        const val QUICK_DEPTH = 48
        const val FULL_DEPTH = 700
    }
}
