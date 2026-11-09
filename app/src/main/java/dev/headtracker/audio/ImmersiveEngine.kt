package dev.headtracker.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.headtracker.dsp.BinauralRenderer
import dev.headtracker.dsp.Hrtf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Captures the audio of the [uids] apps (whose own output is muted by [SpatialEffectManager]),
 * renders it binaurally with head tracking and plays it on our own track.
 *
 * A second, silent "probe" capture listens to [probeUids] (known apps that are NOT muted). When
 * one of them turns out to be capturable while it plays, the service promotes it to 3D.
 *
 * Runs its own real-time thread; all public state is volatile.
 */
class ImmersiveEngine(
    private val context: Context,
    private val projection: MediaProjection,
    /** Head yaw (deg, + = left) predicted [leadSec] into the future. */
    private val yaw: (leadSec: Double) -> Float,
) {
    @Volatile var uids: Set<Int> = emptySet()
        set(v) { if (field != v) { field = v; uidsSetMs = SystemClock.uptimeMillis(); rebuild = true } }
    /** Capture every media app (usage based) instead of [uids]: used when players were found by session scan. */
    @Volatile var captureAll = false
        set(v) { if (field != v) { field = v; uidsSetMs = SystemClock.uptimeMillis(); rebuild = true } }
    /** In [captureAll] mode: known apps that are NOT muted (so they must not be captured). */
    @Volatile var excludeUids: Set<Int> = emptySet()
        set(v) { if (field != v) { field = v; if (captureAll) rebuild = true } }
    /** Our own output's audio session (never muted or scanned). */
    @Volatile var trackSession = 0; private set
    @Volatile var probeUids: Set<Int> = emptySet()
        set(v) { if (field != v) { field = v; rebuildProbe = true } }
    @Volatile var uidsSetMs = 0L; private set

    @Volatile var autoTune = true
    @Volatile var stageHalfWidth = 40f
    @Volatile var roomAmount = 0.2f
    /** What is actually in use (auto or manual), for the UI. */
    @Volatile var effectiveWidth = 40f; private set
    @Volatile var effectiveRoom = 0.2f; private set

    @Volatile var lastSignalMs = 0L; private set
    @Volatile var probeSignalMs = 0L; private set
    @Volatile var captureStartedMs = 0L; private set
    @Volatile var captureChannels = 0; private set
    @Volatile var trackPlaying = false; private set
    @Volatile var latencySec = 0.2; private set
    @Volatile var surround = false; private set
    @Volatile var levels = FloatArray(8); private set
    @Volatile var forceStereo = false
        set(v) { if (field != v) { field = v; rebuild = true } }

    val alive get() = thread?.isAlive == true

    @Volatile private var running = false
    @Volatile private var rebuild = true
    @Volatile private var rebuildProbe = true
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "immersive-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 32000..96000 } ?: 48000
        val fft = BinauralRenderer.FFT_SIZE
        val hrtf = try { Hrtf.build(context.assets.open("hrtf_kemar.bin"), fft, sr) } catch (t: Throwable) {
            Log.w(TAG, "HRTF asset missing, using model", t); Hrtf.build(null, fft, sr)
        }
        val renderer = BinauralRenderer(sr, hrtf)
        levels = renderer.levels
        val hop = renderer.hop
        val track = buildTrack(sr)
        trackSession = track.audioSessionId
        // Start with ~40 ms of output buffer; grow automatically if the system ever underruns.
        var bufFrames = min(track.bufferCapacityInFrames, (0.04 * sr).toInt())
        track.bufferSizeInFrames = bufFrames
        var underruns = 0
        val out = FloatArray(hop * 2)
        var input = FloatArray(hop * 2)
        val probeBuf = FloatArray(4096)
        var record: AudioRecord? = null
        var probe: AudioRecord? = null
        var channels = 2
        var written = 0L
        val ts = AudioTimestamp()
        var lastCheck = 0L

        try {
            while (running) {
                if (rebuildProbe) {
                    rebuildProbe = false
                    probe?.let { safeStop(it) }; probe = null
                    val set = probeUids
                    if (set.isNotEmpty()) probe = buildRecord(set, sr, hop, stereoOnly = true)?.first
                }
                probe?.let { p ->
                    val n = p.read(probeBuf, 0, probeBuf.size, AudioRecord.READ_NON_BLOCKING)
                    for (i in 0 until max(0, n)) if (abs(probeBuf[i]) > 1e-5f) { probeSignalMs = SystemClock.uptimeMillis(); break }
                }
                if (rebuild) {
                    rebuild = false
                    record?.let { safeStop(it) }; record = null
                    captureChannels = 0
                    val set = uids
                    if (set.isNotEmpty() || captureAll) {
                        val built = buildRecord(if (captureAll) emptySet() else set, sr, hop, stereoOnly = forceStereo)
                        record = built?.first; channels = built?.second ?: 2
                        if (record != null) {
                            input = FloatArray(hop * channels)
                            captureChannels = channels
                            captureStartedMs = SystemClock.uptimeMillis()
                            lastSignalMs = captureStartedMs
                            renderer.reset()
                        }
                    }
                }
                val rec = record
                if (rec == null) {
                    if (trackPlaying) { track.pause(); track.flush(); trackPlaying = false; written = 0; lastCheck = SystemClock.uptimeMillis() }
                    SystemClock.sleep(if (probe != null) 20 else 50)
                    continue
                }
                val n = rec.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (n < input.size) { if (n < 0) rebuild = true; SystemClock.sleep(5); continue }

                var peak = 0f
                for (v in input) { val a = abs(v); if (a > peak) peak = a }
                val now = SystemClock.uptimeMillis()
                if (peak > 1e-6f) lastSignalMs = now
                // Only stop our output after a long silence: short gaps between songs keep everything
                // running (no restart glitch, no re-measuring latency, tuning state kept).
                if (now - lastSignalMs > 8000) {
                    if (trackPlaying) {
                        track.pause(); track.flush(); trackPlaying = false; renderer.reset()
                        written = 0; lastCheck = now
                    }
                    continue
                }
                if (!trackPlaying) { track.play(); trackPlaying = true }

                if (autoTune) {
                    renderer.stageHalfWidth += (renderer.autoWidth - renderer.stageHalfWidth) * 0.002f
                    renderer.roomAmount += (renderer.autoRoom - renderer.roomAmount) * 0.002f
                } else {
                    renderer.stageHalfWidth = stageHalfWidth
                    renderer.roomAmount = roomAmount
                }
                effectiveWidth = renderer.stageHalfWidth; effectiveRoom = renderer.roomAmount
                val lead = latencySec + (renderer.latencyFrames + hop) / sr.toDouble()
                renderer.process(input, channels, yaw(lead), out)
                surround = renderer.surround
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                written += hop

                if (now - lastCheck > 500) {
                    lastCheck = now
                    val u = track.underrunCount
                    if (u > underruns && bufFrames < track.bufferCapacityInFrames) {
                        bufFrames = min(track.bufferCapacityInFrames, bufFrames + (0.02 * sr).toInt())
                        track.bufferSizeInFrames = bufFrames
                        Log.i(TAG, "underrun: output buffer now ${bufFrames * 1000 / sr} ms")
                    }
                    underruns = u
                    if (track.getTimestamp(ts)) {
                        val played = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                        val l = (written - played).toDouble() / sr
                        if (l in 0.0..1.0) latencySec = latencySec * 0.7 + l * 0.3
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio loop failed", t)
        } finally {
            record?.let { safeStop(it) }
            probe?.let { safeStop(it) }
            try { track.stop() } catch (_: Throwable) {}
            track.release()
            trackPlaying = false; captureChannels = 0
        }
    }

    private fun buildTrack(sr: Int): AudioTrack {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)   // never re-capture ourselves
                    .build()
            )
            .setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min * 2, (0.25 * sr).toInt() * 2 * 4))      // capacity 250 ms
            .build()
    }

    /** Tries 8-channel (7.1), then 6-channel, then stereo capture. */
    @SuppressLint("MissingPermission")
    private fun buildRecord(set: Set<Int>, sr: Int, hop: Int, stereoOnly: Boolean): Pair<AudioRecord, Int>? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            if (set.isEmpty()) {
                // Everything that plays media (our own track is excluded by its capture policy).
                addMatchingUsage(AudioAttributes.USAGE_MEDIA); addMatchingUsage(AudioAttributes.USAGE_GAME); addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                excludeUids.forEach { if (it > 0) excludeUid(it) }
            } else set.forEach { addMatchingUid(it) }
        }.build()
        val minStereo = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val layouts = if (stereoOnly) intArrayOf(2) else intArrayOf(8, 6, 2)
        for (ch in layouts) {
            try {
                val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).apply {
                    if (ch == 2) setChannelMask(AudioFormat.CHANNEL_IN_STEREO) else setChannelIndexMask((1 shl ch) - 1)
                }.build()
                val rec = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(max(minStereo * ch, (0.2 * sr).toInt() * ch * 4))   // 200 ms: no overruns
                    .build()
                if (rec.state != AudioRecord.STATE_INITIALIZED || rec.channelCount != ch) { rec.release(); continue }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); continue }
                Log.i(TAG, "capturing $ch ch @ $sr Hz from uids $set")
                return rec to ch
            } catch (t: Throwable) {
                Log.i(TAG, "$ch-channel capture unavailable: $t")
            }
        }
        return null
    }

    private fun safeStop(r: AudioRecord) {
        try { r.stop() } catch (_: Throwable) {}
        r.release()
    }

    private companion object { const val TAG = "Immersive" }
}
