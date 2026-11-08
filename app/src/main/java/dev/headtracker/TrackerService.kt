package dev.headtracker

import android.app.Activity
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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.HandlerCompat
import androidx.lifecycle.LifecycleService
import dev.headtracker.audio.DumpSessionScanner
import dev.headtracker.audio.ImmersiveEngine
import dev.headtracker.audio.SpatialEffectManager
import dev.headtracker.audio.SpatialEffectManager.Mode
import dev.headtracker.tracking.FaceYawSource
import dev.headtracker.tracking.HeadPoseEngine
import dev.headtracker.tracking.PhoneMotionSource
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * Foreground service: head tracking + audio. Two audio paths:
 *  - Immersive: players' audio is captured (MediaProjection playback capture), their own output is
 *    muted, and [ImmersiveEngine] re-renders it binaurally with head tracking.
 *  - Light: per-ear EQ shaping applied in place to each player session (fallback for apps that
 *    block capture, or when immersive is off).
 * Everything sleeps when nothing plays on headphones.
 *
 * Threads: main = camera binding + system callbacks; "tracker" = engine, sensors, ML results,
 * effects and session bookkeeping; "immersive-audio" = real-time audio; "scan" = dumpsys.
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
    private lateinit var scanner: DumpSessionScanner
    private val scanExec = Executors.newSingleThreadExecutor { Thread(it, "scan") }

    // Tracker-thread state.
    private class Session(val pkg: String, val uid: Int, val fromDump: Boolean, var seen: Long)
    private val sessions = HashMap<Int, Session>()
    private val blockedUids = HashSet<Int>()
    private val verifiedUids = HashSet<Int>()
    private val failedSessions = HashSet<Int>()
    private var captureUids: Set<Int> = emptySet()
    private var strength = 0.6
    private var prevYaw = 0.0
    private var yawVel = 0.0
    private var lastTickTime = 0.0

    // Shared state.
    @Volatile private var headphones = false
    @Volatile private var immersive: ImmersiveEngine? = null
    private var projection: MediaProjection? = null
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
        scanner = DumpSessionScanner(this)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        headphones = headphonesConnected()

        createChannel()
        goForeground(withProjection = false)
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
        main.post(heartbeat)
        tracker.post(watchdog)
        requestScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RECENTER -> tracker.post { engine.recenter() }
            ACTION_REFRESH -> { goForeground(withProjection = projection != null); evaluate() }
            ACTION_PROJECTION -> {
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (code == Activity.RESULT_OK && data != null) startImmersive(code, data)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveState.running = false; LiveState.active = false; LiveState.immersiveRunning = false
        main.removeCallbacksAndMessages(null)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterReceiver(sessionReceiver)
        unregisterReceiver(screenReceiver)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        face.close()
        stopImmersive()
        scanExec.shutdownNow()
        tracker.removeCallbacksAndMessages(null)
        tracker.post {
            motion.stop()
            effects.releaseAll()          // un-mutes every player
            thread.quitSafely()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- immersive audio

    private fun startImmersive(code: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < 29) return
        stopImmersive()
        goForeground(withProjection = true)
        val p = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (t: Throwable) { Log.e(TAG, "projection refused", t); null } ?: return
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { if (projection === p) { stopImmersive(); evaluate() } } }
        }, main)
        projection = p
        val e = ImmersiveEngine(this, p) { lead -> LiveState.predictedYaw(lead) }
        e.stageHalfWidth = prefs.stageHalfWidthDeg
        e.roomAmount = prefs.room / 100f
        immersive = e
        e.start()
        LiveState.immersiveRunning = true
        tracker.post { reconcile() }
        evaluate()
    }

    private fun stopImmersive() {
        val e = immersive ?: return
        immersive = null
        LiveState.immersiveRunning = false
        e.stop()
        projection?.let { pr -> projection = null; try { pr.stop() } catch (_: Throwable) {} }
        tracker.post { reconcile() }
    }

    /** Decides per session: mute + re-render (immersive) or shape in place (light). Tracker thread. */
    private fun reconcile() {
        val e = immersive
        val immersiveOn = e != null && prefs.immersive && headphones
        val wanted = HashMap<Int, Mode>()
        val capture = HashSet<Int>()
        if (!immersiveOn && prefs.global) {
            wanted[0] = Mode.SHAPE
        } else {
            for ((id, s) in sessions) {
                if (id in failedSessions) continue
                if (immersiveOn && s.uid > 0 && s.uid !in blockedUids) { wanted[id] = Mode.MUTE; capture += s.uid }
                else wanted[id] = Mode.SHAPE
            }
        }
        failedSessions += effects.apply(wanted)
        // Only capture apps whose mute actually took effect, so nothing is ever heard twice.
        val muted = capture.filterTo(HashSet()) { uid -> sessions.any { (id, s) -> s.uid == uid && effects.modeOf(id) == Mode.MUTE } }
        captureUids = muted
        e?.uids = if (immersiveOn) muted else emptySet()
        LiveState.sessions = describe()
    }

    private fun describe(): String {
        if (!(immersive != null && prefs.immersive && headphones) && prefs.global) {
            return if (effects.isAttached(0)) "All apps (light)" else "Global mode not supported on this device"
        }
        if (sessions.isEmpty()) return if (scanner.available) "No player active" else "Waiting for a player to announce its audio"
        return sessions.entries.joinToString("\n") { (id, s) ->
            val name = s.pkg.substringAfterLast('.')
            when {
                id in failedSessions -> "$name: can't attach"
                effects.modeOf(id) == Mode.MUTE -> "$name: 3D"
                s.uid in blockedUids -> "$name: light (app blocks capture)"
                else -> "$name: light"
            }
        }
    }

    /** Detects apps that forbid capture (we'd hear silence) and falls back to light mode for them. */
    private val watchdog = object : Runnable {
        override fun run() {
            val e = immersive
            val now = SystemClock.uptimeMillis()
            if (e != null && captureUids.isNotEmpty() &&
                (!e.alive || (e.captureChannels == 0 && now - e.uidsSetMs > 1500))) {
                // Capture could not start: never leave a muted player silent.
                Log.w(TAG, "capture unavailable, falling back to light mode")
                main.post { stopImmersive(); evaluate() }
            } else if (e != null && captureUids.isNotEmpty() && e.captureChannels > 0) {
                val othersPlaying = audio.activePlaybackConfigurations.size - (if (e.trackPlaying) 1 else 0) > 0
                if (now - e.lastSignalMs < 500 && captureUids.size == 1) verifiedUids += captureUids
                if (othersPlaying && now - e.captureStartedMs > 2500 && now - e.lastSignalMs > 2500) {
                    if (e.captureChannels > 2 && !e.forceStereo) {
                        Log.i(TAG, "multichannel capture silent: retrying in stereo")
                        e.forceStereo = true
                    } else {
                        val suspects = captureUids - verifiedUids
                        if (suspects.isNotEmpty()) {
                            Log.i(TAG, "capture silent; uids $suspects block capture -> light mode")
                            blockedUids += suspects
                            reconcile()
                        }
                    }
                }
            }
            LiveState.render = renderDescription()
            tracker.postDelayed(this, 500)
        }
    }

    private fun renderDescription(): String {
        val e = immersive
        return when {
            e == null -> "Light (EQ head-shadow)"
            !prefs.immersive -> "Light (immersive paused)"
            e.captureChannels == 0 -> "Immersive 3D: waiting for a player"
            else -> {
                val layout = if (e.surround) "surround → virtual speakers" else "stereo → 3D up-mix"
                "Immersive 3D, $layout · ${(e.latencySec * 1000).toInt()} ms"
            }
        }
    }

    // ---------------------------------------------------------------- session discovery

    private fun addSession(id: Int, pkg: String, uid: Int, fromDump: Boolean) {
        if (id <= 0) return
        val existing = sessions[id]
        val now = SystemClock.uptimeMillis()
        if (existing != null && existing.uid == uid) { existing.seen = now; return }
        sessions[id] = Session(pkg, uid, fromDump, now)
        reconcile()
    }

    private fun requestScan() {
        if (!scanner.available) { LiveState.dumpAvailable = false; return }
        LiveState.dumpAvailable = true
        try {
            scanExec.execute {
                val found = scanner.scan() ?: return@execute
                tracker.post {
                    val now = SystemClock.uptimeMillis()
                    for (f in found) {
                        val pkg = packageManager.getNameForUid(f.uid) ?: "uid ${f.uid}"
                        addSession(f.session, pkg.substringBefore(':'), f.uid, true)
                    }
                    val ids = found.map { it.session }.toSet()
                    val stale = sessions.filter { (id, s) -> s.fromDump && id !in ids && now - s.seen > 5000 }.keys
                    if (stale.isNotEmpty()) { stale.forEach { sessions.remove(it); failedSessions.remove(it) }; reconcile() }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun uidOf(pkg: String): Int = try {
        packageManager.getPackageUid(pkg, 0)
    } catch (_: Throwable) { -1 }

    // ---------------------------------------------------------------- activity gating

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasCamera() = hasPermission(android.Manifest.permission.CAMERA)

    private fun headphonesConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    /** Our own output only plays while captured audio is non-silent, so any active player counts. */
    private fun somethingPlaying() = audio.activePlaybackConfigurations.isNotEmpty()

    /** Decide whether tracking should be running right now. Main thread. */
    private fun evaluate() {
        val phones = headphonesConnected()
        if (phones != headphones) { headphones = phones; tracker.post { reconcile() } }
        val playing = somethingPlaying()
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
                    LiveState.yaw = 0f; LiveState.yawVelocity = 0f
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

    /** Catches state changes that have no callback (e.g. our own output pausing). */
    private val heartbeat = object : Runnable {
        override fun run() { evaluate(); main.postDelayed(this, 1000) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = now()
            val yaw = engine.tick(t)
            val dt = (t - lastTickTime).coerceIn(1e-3, 0.1)
            lastTickTime = t
            val v = (yaw - prevYaw) / dt
            prevYaw = yaw
            yawVel += (v - yawVel) * (1 - exp(-dt / 0.05))
            LiveState.yaw = yaw.toFloat()
            LiveState.yawVelocity = yawVel.toFloat()
            // Light path: world-fixed front is at -yaw relative to the head (predicted for BT latency).
            effects.update(-LiveState.predictedYaw(LIGHT_LEAD).toDouble(), strength)
            LiveState.faceVisible = engine.faceVisible
            LiveState.fps = face.fps
            tracker.postDelayed(this, TICK_MS)
        }
    }

    private fun applyPrefs() {
        val faceMode = prefs.faceMode && hasCamera()
        val s = prefs.strength / 100.0 * 1.2
        val smooth = prefs.smoothness / 100.0
        val inv = prefs.invert; val recenter = prefs.autoRecenter; val world = prefs.worldAnchor
        immersive?.let { it.stageHalfWidth = prefs.stageHalfWidthDeg; it.roomAmount = prefs.room / 100f }
        tracker.post {
            engine.mode = if (faceMode) HeadPoseEngine.Mode.FACE else HeadPoseEngine.Mode.MOTION
            engine.smoothness = smooth; engine.invert = inv; engine.autoRecenter = recenter; engine.worldAnchor = world
            strength = s
            reconcile()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        applyPrefs()
        if (key == Prefs.K_FACE_MODE) goForeground(withProjection = projection != null)
        evaluate()
    }

    // ---------------------------------------------------------------- system callbacks

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioManager.ERROR)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "player"
            if (id == AudioManager.ERROR) return
            val open = intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
            val uid = uidOf(pkg)
            tracker.post {
                if (open) addSession(id, pkg, uid, false)
                else if (sessions.remove(id) != null) { failedSessions.remove(id); reconcile() }
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
            requestScan()               // a player just started/stopped: find & mute it right away
            evaluate()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = evaluate()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = evaluate()
    }

    // ---------------------------------------------------------------- notification

    private fun goForeground(withProjection: Boolean) {
        val text = LiveState.status.ifEmpty { "Starting…" }
        if (Build.VERSION.SDK_INT < 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), 0); return
        }
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val camera = if (Build.VERSION.SDK_INT >= 30 && prefs.faceMode && hasCamera()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        val mic = if (Build.VERSION.SDK_INT >= 30 && withProjection && hasPermission(android.Manifest.permission.RECORD_AUDIO))
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val proj = if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        // Most capable first; fall back if the system refuses a type (e.g. started from background).
        val candidates = listOf(special or camera or mic or proj, special or camera or proj, special or proj, special or camera, special)
        for (type in candidates.distinct()) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), if (type == 0 && Build.VERSION.SDK_INT < 34) 0 else type)
                return
            } catch (t: Throwable) { Log.w(TAG, "foreground type $type refused: $t") }
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
        private const val TAG = "TrackerService"
        const val ACTION_STOP = "dev.headtracker.STOP"
        const val ACTION_RECENTER = "dev.headtracker.RECENTER"
        const val ACTION_REFRESH = "dev.headtracker.REFRESH"
        const val ACTION_PROJECTION = "dev.headtracker.PROJECTION"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 10L          // 100 Hz control rate
        private const val LIGHT_LEAD = 0.12      // typical Bluetooth delay for the in-place path
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

        fun startWithProjection(context: Context, code: Int, data: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java)
                .setAction(ACTION_PROJECTION).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackerService::class.java).setAction(action))
        }
    }
}
