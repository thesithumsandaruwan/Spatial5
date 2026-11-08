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
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import dev.headtracker.dsp.BinauralRenderer
import dev.headtracker.dsp.Hrtf
import kotlin.math.abs
import kotlin.math.max

/**
 * Captures the audio of the given apps (whose own output is muted by [SpatialEffectManager]),
 * renders it binaurally with head tracking, and plays it on a low-latency track.
 * Runs its own real-time thread; all public state is volatile.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ImmersiveEngine(
    private val context: Context,
    private val projection: MediaProjection,
    /** Head yaw (deg, + = left) predicted [leadSec] into the future. */
    private val yaw: (leadSec: Double) -> Float,
) {
    @Volatile var uids: Set<Int> = emptySet()
        set(v) { if (field != v) { field = v; uidsSetMs = SystemClock.uptimeMillis(); rebuild = true } }
    @Volatile var uidsSetMs = 0L; private set
    /** False once the audio thread has exited (error or stop). */
    val alive get() = thread?.isAlive == true
    @Volatile var stageHalfWidth = 40f
    @Volatile var roomAmount = 0.35f

    @Volatile var lastSignalMs = 0L; private set
    @Volatile var captureStartedMs = 0L; private set
    @Volatile var captureChannels = 0; private set
    @Volatile var trackPlaying = false; private set
    @Volatile var latencySec = 0.2; private set
    @Volatile var surround = false; private set
    /** Set when multichannel capture turned out silent on this device. */
    @Volatile var forceStereo = false
        set(v) { if (field != v) { field = v; rebuild = true } }

    @Volatile private var running = false
    @Volatile private var rebuild = true
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
        val hrtf = try { Hrtf.build(context.assets.open("hrtf_kemar.bin"), 1024, sr) } catch (t: Throwable) {
            Log.w(TAG, "HRTF asset missing, using model", t); Hrtf.build(null, 1024, sr)
        }
        val renderer = BinauralRenderer(sr, hrtf)
        val hop = renderer.hop
        val track = buildTrack(sr, hop)
        val out = FloatArray(hop * 2)
        var input = FloatArray(hop * 2)
        var record: AudioRecord? = null
        var channels = 2
        var written = 0L
        val ts = AudioTimestamp()
        var lastLatencyCheck = 0L

        try {
            while (running) {
                if (rebuild) {
                    rebuild = false
                    record?.let { safeStop(it) }; record = null
                    captureChannels = 0
                    val set = uids
                    if (set.isNotEmpty()) {
                        val built = buildRecord(set, sr, hop)
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
                    if (trackPlaying) { track.pause(); track.flush(); trackPlaying = false }
                    SystemClock.sleep(50)
                    continue
                }
                val n = rec.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (n < input.size) { if (n < 0) rebuild = true; SystemClock.sleep(5); continue }

                var peak = 0f
                for (v in input) { val a = abs(v); if (a > peak) peak = a }
                val now = SystemClock.uptimeMillis()
                if (peak > 1e-6f) lastSignalMs = now
                val silentFor = now - lastSignalMs
                if (silentFor > 3000) {
                    // Nothing playing: stop the output so the phone can idle (and our track stops
                    // counting as "music playing").
                    if (trackPlaying) { track.pause(); track.flush(); trackPlaying = false; renderer.reset() }
                    continue
                }
                if (!trackPlaying) { track.play(); trackPlaying = true }

                renderer.stageHalfWidth = stageHalfWidth
                renderer.roomAmount = roomAmount
                val lead = latencySec + (renderer.latencyFrames + hop) / sr.toDouble()
                renderer.process(input, channels, yaw(lead), out)
                surround = renderer.surround
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                written += hop

                if (now - lastLatencyCheck > 1000 && track.getTimestamp(ts)) {
                    lastLatencyCheck = now
                    val played = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                    val l = (written - played).toDouble() / sr
                    if (l in 0.0..1.0) latencySec = latencySec * 0.7 + l * 0.3
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio loop failed", t)
        } finally {
            record?.let { safeStop(it) }
            try { track.stop() } catch (_: Throwable) {}
            track.release()
            trackPlaying = false; captureChannels = 0
        }
    }

    private fun buildTrack(sr: Int, hop: Int): AudioTrack {
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(min, hop * 2 * 4 * 3))
            .build()
    }

    /** Tries 8-channel (7.1), then 6-channel, then stereo capture. */
    @SuppressLint("MissingPermission")
    private fun buildRecord(set: Set<Int>, sr: Int, hop: Int): Pair<AudioRecord, Int>? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            set.forEach { addMatchingUid(it) }
        }.build()
        val minStereo = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val layouts = if (forceStereo) intArrayOf(2) else intArrayOf(8, 6, 2)
        for (ch in layouts) {
            try {
                val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).apply {
                    if (ch == 2) setChannelMask(AudioFormat.CHANNEL_IN_STEREO) else setChannelIndexMask((1 shl ch) - 1)
                }.build()
                val rec = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(max(minStereo * ch / 2, hop * ch * 4 * 4))
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
