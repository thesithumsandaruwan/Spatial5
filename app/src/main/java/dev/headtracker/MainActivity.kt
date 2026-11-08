package dev.headtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import dev.headtracker.audio.DumpSessionScanner
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var indicator: HeadIndicatorView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private lateinit var enableImmersive: Button
    private lateinit var dumpHelp: TextView
    private lateinit var dumpCmd: TextView
    private var visible = false

    private val immersiveSupported get() = Build.VERSION.SDK_INT >= 29

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        indicator = findViewById(R.id.indicator)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        toggle = findViewById(R.id.toggle)
        enableImmersive = findViewById(R.id.enable_immersive)
        dumpHelp = findViewById(R.id.dump_help)
        dumpCmd = findViewById(R.id.dump_cmd)
        dumpCmd.text = DumpSessionScanner.ADB_COMMAND

        toggle.setOnClickListener {
            if (LiveState.running) TrackerService.send(this, TrackerService.ACTION_STOP)
            else if (ensurePermissions()) begin()
        }
        enableImmersive.setOnClickListener { if (ensurePermissions()) begin() }
        findViewById<Button>(R.id.recenter).setOnClickListener {
            if (LiveState.running) TrackerService.send(this, TrackerService.ACTION_RECENTER)
        }

        val immersive = findViewById<RadioButton>(R.id.audio_immersive)
        val light = findViewById<RadioButton>(R.id.audio_light)
        immersive.isEnabled = immersiveSupported
        immersive.isChecked = prefs.immersive && immersiveSupported; light.isChecked = !immersive.isChecked
        immersive.setOnCheckedChangeListener { _, c ->
            prefs.immersive = c
            if (c && LiveState.running && !LiveState.immersiveRunning && ensurePermissions()) begin()
        }

        val face = findViewById<RadioButton>(R.id.mode_face)
        val motion = findViewById<RadioButton>(R.id.mode_motion)
        face.isChecked = prefs.faceMode; motion.isChecked = !prefs.faceMode
        face.setOnCheckedChangeListener { _, c ->
            prefs.faceMode = c
            if (c && ensurePermissions(REQ_PERMS_ONLY) && LiveState.running) TrackerService.start(this)
        }

        bindSeek(R.id.width, prefs.width) { prefs.width = it }
        bindSeek(R.id.room, prefs.room) { prefs.room = it }
        bindSeek(R.id.strength, prefs.strength) { prefs.strength = it }
        bindSeek(R.id.smoothness, prefs.smoothness) { prefs.smoothness = it }
        bindSwitch(R.id.world, prefs.worldAnchor) { prefs.worldAnchor = it }
        bindSwitch(R.id.invert, prefs.invert) { prefs.invert = it }
        bindSwitch(R.id.auto_recenter, prefs.autoRecenter) { prefs.autoRecenter = it }
        bindSwitch(R.id.global, prefs.global) { prefs.global = it }
        bindSwitch(R.id.pause_screen, prefs.pauseScreenOff) { prefs.pauseScreenOff = it }
    }

    /** Starts the service; in immersive mode asks Android for audio-capture consent first. */
    private fun begin() {
        val canCapture = immersiveSupported && prefs.immersive &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canCapture && !LiveState.immersiveRunning) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                return
            } catch (_: Throwable) { /* fall through to light mode */ }
        }
        TrackerService.start(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) TrackerService.startWithProjection(this, resultCode, data)
        else TrackerService.start(this)       // declined: light mode
    }

    private fun bindSeek(id: Int, value: Int, set: (Int) -> Unit) = findViewById<SeekBar>(id).apply {
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) set(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) = findViewById<CompoundButton>(id).apply {
        isChecked = value
        setOnCheckedChangeListener { _, c -> set(c) }
    }

    private fun ensurePermissions(code: Int = REQ_PERMS): Boolean {
        val needed = buildList {
            if (prefs.faceMode && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (immersiveSupported && prefs.immersive && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), code)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Continue either way: the service degrades gracefully (phone motion / light mode).
        if (requestCode == REQ_PERMS) begin()
        else if (LiveState.running) TrackerService.start(this)   // refresh foreground type after a grant
    }

    override fun onResume() { super.onResume(); visible = true; Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { super.onPause(); visible = false }

    private var lastText = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            indicator.yaw = LiveState.yaw
            indicator.tracking = LiveState.active && (LiveState.faceVisible || !prefs.faceMode)
            if (frameTimeNanos - lastText > 250_000_000L) {
                lastText = frameTimeNanos
                updateText()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateText() {
        val running = LiveState.running
        toggle.setText(if (running) R.string.stop else R.string.start)
        enableImmersive.visibility =
            if (running && immersiveSupported && prefs.immersive && !LiveState.immersiveRunning) View.VISIBLE else View.GONE
        status.text = if (running) LiveState.status else "Stopped"
        detail.text = if (!running) "" else buildString {
            append(String.format(Locale.US, "Head yaw %+.0f°", LiveState.yaw))
            if (prefs.faceMode && LiveState.active) append(String.format(Locale.US, "  •  face %s  •  %.0f fps",
                if (LiveState.faceVisible) "found" else "searching", LiveState.fps))
            append("\n").append(LiveState.render)
            append("\n").append(LiveState.sessions)
        }
        val dump = LiveState.dumpAvailable || checkSelfPermission(DumpSessionScanner.PERMISSION) == PackageManager.PERMISSION_GRANTED
        dumpHelp.text = if (dump) "Enhanced player detection is ON." else getString(R.string.dump_help)
        dumpCmd.visibility = if (dump) View.GONE else View.VISIBLE
    }

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_CAPTURE = 2
        const val REQ_PERMS_ONLY = 3
    }
}
