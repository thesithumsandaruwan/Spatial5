package dev.headtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import dev.headtracker.ui.HeadSceneView
import dev.headtracker.ui.SegmentedControl
import dev.headtracker.ui.Springs
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var scene: HeadSceneView
    private lateinit var status: TextView
    private lateinit var statusDot: ImageView
    private lateinit var yawValue: TextView
    private lateinit var yawCaption: TextView
    private lateinit var toggle: TextView
    private lateinit var enableImmersive: TextView
    private lateinit var audioMode: SegmentedControl
    private lateinit var trackMode: SegmentedControl
    private lateinit var audioCaption: TextView
    private lateinit var trackCaption: TextView
    private lateinit var autoTune: Switch
    private lateinit var widthBar: SeekBar
    private lateinit var roomBar: SeekBar
    private lateinit var widthValue: TextView
    private lateinit var roomValue: TextView
    private lateinit var render: TextView
    private lateinit var apps: LinearLayout
    private var visible = false
    private var lastSessions: String? = null
    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        scene = findViewById(R.id.scene)
        status = findViewById(R.id.status); statusDot = findViewById(R.id.status_dot)
        yawValue = findViewById(R.id.yaw_value); yawCaption = findViewById(R.id.yaw_caption)
        toggle = findViewById(R.id.toggle); enableImmersive = findViewById(R.id.enable_immersive)
        audioMode = findViewById(R.id.audio_mode); trackMode = findViewById(R.id.track_mode)
        audioCaption = findViewById(R.id.audio_caption); trackCaption = findViewById(R.id.track_caption)
        autoTune = findViewById(R.id.auto_tune)
        widthBar = findViewById(R.id.width); roomBar = findViewById(R.id.room)
        widthValue = findViewById(R.id.width_value); roomValue = findViewById(R.id.room_value)
        render = findViewById(R.id.render); apps = findViewById(R.id.apps)
        findViewById<TextView>(R.id.title).text = android.text.SpannableString(getString(R.string.title)).apply {
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), length - 1, length, 0)
        }

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        scene.setBackground(getColor(R.color.hero_bg), dark)
        applyInsets()

        // Primary action
        Springs.pressable(toggle, 0.97f); Springs.pressable(enableImmersive, 0.97f)
        toggle.setOnClickListener {
            when {
                !LiveState.running -> if (ensurePermissions()) begin()
                LiveState.paused -> TrackerService.send(this, TrackerService.ACTION_RESUME)   // permission kept: no prompt
                else -> TrackerService.send(this, TrackerService.ACTION_PAUSE)
            }
        }
        findViewById<View>(R.id.quit).setOnClickListener { TrackerService.send(this, TrackerService.ACTION_STOP) }
        enableImmersive.setOnClickListener { if (ensurePermissions()) begin() }
        findViewById<View>(R.id.recenter).apply {
            Springs.pressable(this, 0.9f)
            setOnClickListener {
                performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
                if (LiveState.running) TrackerService.send(this@MainActivity, TrackerService.ACTION_RECENTER)
            }
        }

        // Audio
        audioMode.segments = listOf(getString(R.string.mode_immersive), getString(R.string.mode_standard))
        audioMode.select(if (prefs.immersive) 0 else 1, false)
        audioMode.onChange = { i ->
            prefs.immersive = i == 0
            updateCaptions()
            if (prefs.immersive && LiveState.running && !LiveState.immersiveRunning && ensurePermissions()) begin()
        }
        bindSwitch(R.id.auto_tune, prefs.autoTune) { prefs.autoTune = it; updateTuning() }
        bindSeek(widthBar, prefs.width) { prefs.width = it }
        bindSeek(roomBar, prefs.room) { prefs.room = it }

        // Movies
        val movie = findViewById<SegmentedControl>(R.id.movie_mode)
        movie.segments = listOf(getString(R.string.movie_auto), getString(R.string.movie_on), getString(R.string.movie_off))
        movie.select(prefs.movieMode, false)
        movie.onChange = { i -> prefs.movieMode = i; updateCaptions() }

        // Tracking
        trackMode.segments = listOf(getString(R.string.track_face), getString(R.string.track_motion))
        trackMode.select(if (prefs.faceMode) 0 else 1, false)
        trackMode.onChange = { i ->
            prefs.faceMode = i == 0
            updateCaptions()
            if (prefs.faceMode && ensurePermissions(REQ_PERMS_ONLY) && LiveState.running) TrackerService.start(this)
        }
        bindSwitch(R.id.world, prefs.worldAnchor) { prefs.worldAnchor = it }
        bindSwitch(R.id.auto_recenter, prefs.autoRecenter) { prefs.autoRecenter = it }

        // Advanced (collapsed by default: common path first)
        val card = findViewById<View>(R.id.advanced_card)
        val chevron = findViewById<View>(R.id.advanced_chevron)
        findViewById<View>(R.id.advanced_header).setOnClickListener {
            val open = card.visibility != View.VISIBLE
            card.visibility = if (open) View.VISIBLE else View.GONE
            Springs.to(chevron, DynamicAnimation.ROTATION, if (open) 90f else 0f, 0.3f)
        }
        bindSeek(findViewById(R.id.smoothness), prefs.smoothness, findViewById(R.id.smoothness_value)) { prefs.smoothness = it }
        bindSeek(findViewById(R.id.strength), prefs.strength, findViewById(R.id.strength_value)) { prefs.strength = it }
        bindSwitch(R.id.invert, prefs.invert) { prefs.invert = it }
        bindSwitch(R.id.pause_screen, prefs.pauseScreenOff) { prefs.pauseScreenOff = it }

        findViewById<View>(R.id.diag_copy).setOnClickListener {
            val text = deviceReport + "\n" + spatializerProbe + "\n" + LiveState.diag
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Spatial5 diagnostics", text))
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            android.widget.Toast.makeText(this, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show()
        }

        updateCaptions(); updateTuning(); updateText()
        handleRestore(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleRestore(intent) }

    /** Opened from "Immersive 3D was stopped by Android · tap to restore": ask again right away. */
    private fun handleRestore(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_RESTORE, false) != true) return
        i.removeExtra(EXTRA_RESTORE)
        if (prefs.immersive && ensurePermissions()) begin()
    }

    /** Result of the spatializer-engine experiment (filled in the background the first time Diagnostics is shown). */
    @Volatile private var spatializerProbe = ""
    private var probeStarted = false

    /** Device facts: Android's own spatializer / head-tracking support and the audio effects on offer. */
    private val deviceReport: String by lazy {
        buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SDK_INT >= 32) {
                val sp = getSystemService(android.media.AudioManager::class.java).spatializer
                val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                fun fmt(mask: Int) = android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(mask).build()
                append("\nAndroid spatializer: level ").append(when (sp.immersiveAudioLevel) {
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "none"
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "multichannel"
                    else -> sp.immersiveAudioLevel.toString()
                })
                append(" · available ").append(sp.isAvailable).append(" · enabled ").append(sp.isEnabled)
                append(" · head tracker ").append(if (Build.VERSION.SDK_INT >= 33) sp.isHeadTrackerAvailable.toString() else "n/a")
                append("\nspatializes stereo ").append(sp.canBeSpatialized(attrs, fmt(android.media.AudioFormat.CHANNEL_OUT_STEREO)))
                    .append(" · 5.1 ").append(sp.canBeSpatialized(attrs, fmt(android.media.AudioFormat.CHANNEL_OUT_5POINT1)))
            } else append("\nAndroid spatializer: not on this Android version")
            val fx = try { android.media.audiofx.AudioEffect.queryEffects()?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
            append("\neffects (${fx.size}): ").append(fx.joinToString { "${it.name} [${it.implementor.take(18)}]" })
        }
    }

    /** Edge-to-edge: content flows under the status/navigation bars; overlays keep clear of them. */
    private fun applyInsets() {
        val heroTop = findViewById<View>(R.id.hero_top)
        val controls = findViewById<View>(R.id.controls)
        val content = findViewById<View>(R.id.content)
        val baseTop = heroTop.paddingTop; val baseBottom = controls.paddingBottom
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION") val left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION") val right = insets.systemWindowInsetRight
            heroTop.setPadding(heroTop.paddingLeft, baseTop + top, heroTop.paddingRight, heroTop.paddingBottom)
            controls.setPadding(controls.paddingLeft, controls.paddingTop, controls.paddingRight, baseBottom + bottom)
            content.setPadding(left, 0, right, 0)
            v.onApplyWindowInsets(insets)
        }
        // Readable column on tablets / wide windows.
        controls.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val max = (640 * d).toInt(); val w = r - l
            val side = if (w > max) (w - max) / 2 else 0
            if (v.paddingLeft != side) v.post { v.setPadding(side, v.paddingTop, side, v.paddingBottom) }
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    private fun updateCaptions() {
        audioCaption.setText(if (prefs.immersive) R.string.caption_immersive else R.string.caption_standard)
        findViewById<TextView>(R.id.dolby_tip).setText(if (prefs.immersive) R.string.tip_dolby_immersive else R.string.tip_dolby_standard)
        trackCaption.setText(if (prefs.faceMode) R.string.caption_face else R.string.caption_motion)
        findViewById<TextView>(R.id.movie_caption).setText(when (prefs.movieMode) {
            Prefs.MOVIE_ON -> R.string.caption_movie_on
            Prefs.MOVIE_OFF -> R.string.caption_movie_off
            else -> R.string.caption_movie_auto
        })
        val immersive = prefs.immersive
        for (id in intArrayOf(R.id.auto_row, R.id.width_row, R.id.room_row)) findViewById<View>(id).alpha = if (immersive) 1f else 0.4f
        autoTune.isEnabled = immersive
        updateTuning()
    }

    private fun updateTuning() {
        val manual = !prefs.autoTune && prefs.immersive
        widthBar.isEnabled = manual; roomBar.isEnabled = manual
        widthBar.alpha = if (manual) 1f else 0.45f; roomBar.alpha = widthBar.alpha
    }

    /** Starts the service; in immersive mode asks Android for audio-capture consent first. */
    private fun begin() {
        val canCapture = prefs.immersive && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canCapture && !LiveState.immersiveRunning) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_CAPTURE)
                return
            } catch (_: Throwable) { /* fall back to standard */ }
        }
        TrackerService.start(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) TrackerService.startWithProjection(this, resultCode, data)
        else TrackerService.start(this)
    }

    private fun bindSeek(bar: SeekBar, value: Int, label: TextView? = null, set: (Int) -> Unit) {
        bar.progress = value
        label?.text = "$value%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                set(p); label?.text = "$p%"
                if (p == 0 || p == 100 || p % 25 == 0) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) = findViewById<Switch>(id).apply {
        isChecked = value
        setOnCheckedChangeListener { v, c -> v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); set(c) }
        // Tapping anywhere on the row toggles it (bigger target).
        (parent as? View)?.setOnClickListener { if (isEnabled) toggle() }
    }

    private fun ensurePermissions(code: Int = REQ_PERMS): Boolean {
        val needed = buildList {
            if (prefs.faceMode && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (prefs.immersive && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), code)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERMS) begin()
        else if (LiveState.running) TrackerService.start(this)
    }

    override fun onResume() {
        super.onResume(); visible = true
        scene.onResume()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() { super.onPause(); visible = false; scene.onPause() }

    // ------------------------------------------------------------------ live updates

    private var lastText = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!visible) return
            scene.update(LiveState.yaw, speakers(), LiveState.running && LiveState.active)
            if (now - lastText > 150_000_000L) { lastText = now; updateText() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun speakers(): List<HeadSceneView.Speaker> {
        val lv = LiveState.levels
        val on = LiveState.running && LiveState.active
        fun l(i: Int, fallback: Float = 0.25f) = if (!on) 0f else lv?.getOrNull(i) ?: fallback
        val w = LiveState.width
        return when (LiveState.layout) {
            LiveState.LAYOUT_SURROUND -> listOf(
                HeadSceneView.Speaker(30f, l(0), false), HeadSceneView.Speaker(-30f, l(1), false), HeadSceneView.Speaker(0f, l(2), false),
                HeadSceneView.Speaker(90f, l(6), true), HeadSceneView.Speaker(-90f, l(7), true),
                HeadSceneView.Speaker(145f, l(4), true), HeadSceneView.Speaker(-145f, l(5), true))
            LiveState.LAYOUT_UPMIX -> if (LiveState.cinema) listOf(
                HeadSceneView.Speaker(45f, l(0), false), HeadSceneView.Speaker(-45f, l(1), false), HeadSceneView.Speaker(0f, l(2), false),
                HeadSceneView.Speaker(120f, l(4), true), HeadSceneView.Speaker(-120f, l(5), true)) else listOf(
                HeadSceneView.Speaker(w, l(0), false), HeadSceneView.Speaker(-w, l(1), false), HeadSceneView.Speaker(0f, l(2), false),
                HeadSceneView.Speaker(110f, l(4), true), HeadSceneView.Speaker(-110f, l(5), true))
            else -> listOf(HeadSceneView.Speaker(30f, l(0), false), HeadSceneView.Speaker(-30f, l(1), false))
        }
    }

    private fun updateText() {
        val running = LiveState.running
        val paused = running && LiveState.paused
        toggle.setText(when { !running -> R.string.start; paused -> R.string.resume; else -> R.string.pause })
        val primary = !running || paused
        (toggle.background.mutate() as? GradientDrawable)?.setColor(getColor(if (primary) R.color.accent else R.color.fill))
        toggle.setTextColor(if (primary) 0xFFFFFFFF.toInt() else getColor(R.color.accent))
        findViewById<View>(R.id.quit).visibility = if (running) View.VISIBLE else View.GONE
        enableImmersive.visibility = if (running && prefs.immersive && !LiveState.immersiveRunning) View.VISIBLE else View.GONE

        val yaw = LiveState.yaw
        yawValue.text = "${abs(yaw).roundToInt()}°"
        yawCaption.text = when {
            !running -> "Not tracking"
            abs(yaw) < 3 -> "Facing forward"
            yaw > 0 -> "Turned left"
            else -> "Turned right"
        }
        status.text = if (running) shortStatus() else "Off"
        statusDot.setColorFilter(getColor(when {
            running && LiveState.active -> R.color.green
            running -> R.color.orange
            else -> R.color.label3
        }))

        if (prefs.autoTune && prefs.immersive) {
            widthBar.progress = ((LiveState.width - 20f) / 0.5f).roundToInt().coerceIn(0, 100)
            roomBar.progress = (LiveState.room * 100).roundToInt().coerceIn(0, 100)
        }
        widthValue.text = "${(if (prefs.autoTune && prefs.immersive) LiveState.width else prefs.stageHalfWidthDeg).roundToInt() * 2}°" +
            if (prefs.autoTune && prefs.immersive) " · Auto" else ""
        roomValue.text = "${(if (prefs.autoTune && prefs.immersive) LiveState.room * 100 else prefs.room.toFloat()).roundToInt()}%" +
            if (prefs.autoTune && prefs.immersive) " · Auto" else ""

        render.text = if (running) LiveState.render else "Head tracking is off"
        val diag = findViewById<TextView>(R.id.diag)
        if (diag.isShown) {
            if (!probeStarted) {
                probeStarted = true; spatializerProbe = "spatializer engine test: running…"
                Thread { spatializerProbe = "spatializer engine test: " +
                    dev.headtracker.audio.SpatializerProbe.run(getSystemService(android.media.AudioManager::class.java)) }.start()
            }
            diag.text = deviceReport + "\n" + spatializerProbe + "\n" + (if (running) LiveState.diag else "service stopped")
        }
        val sessions = if (running) LiveState.sessions else ""
        if (sessions != lastSessions) { lastSessions = sessions; buildAppRows(sessions) }
    }

    private fun shortStatus(): String {
        val s = LiveState.status
        return when {
            LiveState.paused -> "Paused"
            s.startsWith("Paused") -> "Connect earbuds"
            s.startsWith("Idle") -> "Waiting for music"
            s.startsWith("Screen off") -> "Camera paused"
            s.startsWith("Camera permission") -> "Tracking · Motion"
            prefs.faceMode && LiveState.active -> if (!LiveState.faceVisible) "Looking for you…"
                else (if (LiveState.cinema) "Cinema · Face" else "Tracking · Face") + if (LiveState.lowLight) " · Low light" else ""
            else -> if (LiveState.cinema) "Cinema · Motion" else "Tracking · Motion"
        }
    }

    private fun buildAppRows(sessions: String) {
        apps.removeAllViews()
        for (line in sessions.split('\n').filter { it.isNotBlank() }) {
            val (name, mode) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
            apps.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.separator))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (0.5f * d).toInt().coerceAtLeast(1)).apply { marginStart = (16 * d).toInt() }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48 * d).toInt()
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            }
            row.addView(TextView(this).apply {
                text = name; setTextColor(getColor(R.color.label)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val is3d = mode == "3D"
            row.addView(TextView(this).apply {
                text = mode
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(getColor(if (is3d) R.color.accent else R.color.label2))
                setPadding((9 * d).toInt(), (3 * d).toInt(), (9 * d).toInt(), (3 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 100 * d; setColor(getColor(R.color.fill)) }
            })
            apps.addView(row)
        }
    }

    companion object {
        const val EXTRA_RESTORE = "restore_immersive"
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
        private const val REQ_PERMS_ONLY = 3
    }
}
