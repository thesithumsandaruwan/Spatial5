package dev.headtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import androidx.lifecycle.LifecycleService
import dev.headtracker.audio.SpatialEffectManager
import dev.headtracker.tracking.FaceYawSource
import dev.headtracker.tracking.HeadPoseEngine
import dev.headtracker.tracking.PhoneMotionSource

/**
 * Foreground service: runs the tracker and steers the audio effect while media is playing on
 * headphones. Everything sleeps (camera off, sensors off, no ticks) when nothing is playing.
 *
 * Threads: main = camera binding + system callbacks; "tracker" = engine, sensors, ML results, effects.
 */
class TrackerService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var tracker: Handler
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager

    private val engine = HeadPoseEngine()
    private val effects = SpatialEffectManager()
    private lateinit var face: FaceYawSource
    private lateinit var motion: PhoneMotionSource

    private var strength = 0.6
    private var active = false
    private var cameraOn = false
    private var screenOn = true
    private var lastStatus = ""

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        thread = HandlerThread("tracker", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        tracker = HandlerCompat.createAsync(thread.looper)
        face = FaceYawSource(this, this, { tracker.post(it) }) { yaw ->
            if (yaw != null) engine.onFace(yaw, now()) else engine.onFaceMissing()
        }
        motion = PhoneMotionSource(this, tracker) { engine.onPhoneYaw(it) }
        screenOn = getSystemService(PowerManager::class.java).isInteractive

        createChannel()
        goForeground()
        applyPrefs()

        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(this, sessionReceiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        audio.registerAudioPlaybackCallback(playbackCallback, main)
        audio.registerAudioDeviceCallback(deviceCallback, main)

        LiveState.running = true
        evaluate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RECENTER -> tracker.post { engine.recenter() }
            ACTION_REFRESH -> { goForeground(); evaluate() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveState.running = false; LiveState.active = false
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterReceiver(sessionReceiver)
        unregisterReceiver(screenReceiver)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        face.close()
        tracker.removeCallbacksAndMessages(null)
        tracker.post {
            motion.stop()
            effects.releaseAll()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- activity gating

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun headphonesConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    /** Decide whether tracking should be running right now. Main thread. */
    private fun evaluate() {
        val playing = audio.isMusicActive
        val phones = headphonesConnected()
        val shouldRun = playing && phones
        val faceMode = prefs.faceMode && hasCamera()
        val wantCamera = shouldRun && faceMode && (screenOn || !prefs.pauseScreenOff)

        if (wantCamera != cameraOn) {
            cameraOn = wantCamera
            if (wantCamera) face.start() else face.stop()
        }
        if (shouldRun != active) {
            active = shouldRun
            tracker.post {
                if (shouldRun) { motion.start(); tracker.removeCallbacks(tick); tracker.post(tick) }
                else {
                    motion.stop(); tracker.removeCallbacks(tick)
                    effects.update(0.0, strength)           // neutral while idle
                    LiveState.yaw = 0f
                }
            }
        }
        LiveState.active = active
        val status = when {
            !phones -> "Paused: connect your earbuds"
            !playing -> "Idle: waiting for media to play"
            prefs.faceMode && !hasCamera() -> "Camera permission missing: using phone motion"
            faceMode && !wantCamera -> "Screen off: camera paused"
            faceMode -> "Tracking your head with the front camera"
            else -> "Tracking with phone motion"
        }
        LiveState.status = status
        if (status != lastStatus) { lastStatus = status; notifyStatus(status) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = now()
            val yaw = engine.tick(t)
            effects.update(-yaw, strength)      // world-fixed front is at -yaw relative to the head
            LiveState.yaw = yaw.toFloat()
            LiveState.faceVisible = engine.faceVisible
            LiveState.fps = face.fps
            LiveState.sessions = effects.description
            tracker.postDelayed(this, TICK_MS)
        }
    }

    private fun applyPrefs() {
        val faceMode = prefs.faceMode && hasCamera()
        val s = prefs.strength / 100.0 * 1.2
        val smooth = prefs.smoothness / 100.0
        val inv = prefs.invert; val recenter = prefs.autoRecenter; val global = prefs.global
        tracker.post {
            engine.mode = if (faceMode) HeadPoseEngine.Mode.FACE else HeadPoseEngine.Mode.MOTION
            engine.smoothness = smooth; engine.invert = inv; engine.autoRecenter = recenter
            strength = s
            effects.setGlobal(global)
            LiveState.sessions = effects.description
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        applyPrefs()
        if (key == Prefs.K_FACE_MODE) goForeground()
        evaluate()
    }

    // ---------------------------------------------------------------- system callbacks

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioManager.ERROR)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "player"
            if (id == AudioManager.ERROR) return
            val open = intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
            tracker.post {
                if (open) effects.onSessionOpened(id, pkg) else effects.onSessionClosed(id)
                LiveState.sessions = effects.description
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            evaluate()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // isMusicActive can lag the callback slightly; check now and once more shortly after.
            evaluate()
            main.postDelayed(::evaluate, 600)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = evaluate()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = evaluate()
    }

    // ---------------------------------------------------------------- notification

    private fun goForeground() {
        val type = if (prefs.faceMode && hasCamera())
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val fgType = if (Build.VERSION.SDK_INT >= 34) type else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(LiveState.status.ifEmpty { "Starting…" }), fgType)
        } catch (t: Throwable) {
            // e.g. camera type refused while in background: fall back to motion-only foreground type.
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Tracking with phone motion"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val recenter = PendingIntent.getService(this, 1, Intent(this, TrackerService::class.java).setAction(ACTION_RECENTER), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, TrackerService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.recenter), recenter)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "dev.headtracker.STOP"
        const val ACTION_RECENTER = "dev.headtracker.RECENTER"
        const val ACTION_REFRESH = "dev.headtracker.REFRESH"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 20L     // 50 Hz control rate
        private val HEADPHONE_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }

        private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java).setAction(ACTION_REFRESH))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackerService::class.java).setAction(action))
        }
    }
}
