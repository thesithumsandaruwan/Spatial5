package dev.headtracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var indicator: HeadIndicatorView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        indicator = findViewById(R.id.indicator)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (LiveState.running) TrackerService.send(this, TrackerService.ACTION_STOP)
            else if (ensurePermissions()) TrackerService.start(this)
        }
        findViewById<Button>(R.id.recenter).setOnClickListener {
            if (LiveState.running) TrackerService.send(this, TrackerService.ACTION_RECENTER)
        }

        val face = findViewById<RadioButton>(R.id.mode_face)
        val motion = findViewById<RadioButton>(R.id.mode_motion)
        face.isChecked = prefs.faceMode; motion.isChecked = !prefs.faceMode
        face.setOnCheckedChangeListener { _, c ->
            prefs.faceMode = c
            if (c) ensurePermissions()
        }

        bindSeek(R.id.strength, prefs.strength) { prefs.strength = it }
        bindSeek(R.id.smoothness, prefs.smoothness) { prefs.smoothness = it }
        bindSwitch(R.id.invert, prefs.invert) { prefs.invert = it }
        bindSwitch(R.id.auto_recenter, prefs.autoRecenter) { prefs.autoRecenter = it }
        bindSwitch(R.id.global, prefs.global) { prefs.global = it }
        bindSwitch(R.id.pause_screen, prefs.pauseScreenOff) { prefs.pauseScreenOff = it }
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

    private fun ensurePermissions(): Boolean {
        val needed = buildList {
            if (prefs.faceMode && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Start either way: without the camera the service falls back to phone-motion tracking.
        if (requestCode == REQ_PERMS && !LiveState.running) TrackerService.start(this)
        else if (LiveState.running) TrackerService.start(this)   // refresh foreground type after camera grant
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
                toggle.setText(if (LiveState.running) R.string.stop else R.string.start)
                status.text = if (LiveState.running) LiveState.status else "Stopped"
                detail.text = if (!LiveState.running) "" else buildString {
                    append(String.format(Locale.US, "Head yaw %+.0f°", LiveState.yaw))
                    if (prefs.faceMode && LiveState.active) append(String.format(Locale.US, "  •  face %s  •  %.0f fps", if (LiveState.faceVisible) "found" else "searching", LiveState.fps))
                    append("\nAudio: ").append(LiveState.sessions)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private companion object { const val REQ_PERMS = 1 }
}
