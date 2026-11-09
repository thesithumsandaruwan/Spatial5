package dev.headtracker.tracking

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Estimates head yaw relative to the phone from the front camera using ML Kit's fast face detector
 * on tiny (320x240) frames. Frame processing is throttled: ~24 fps while a face is visible and
 * ~3 fps while searching, so the CPU mostly sleeps.
 *
 * Callback: yaw in degrees (+ = head turned to the user's left) or null when no face is found.
 */
class FaceYawSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val callbackExecutor: Executor,
    private val onResult: (yawDeg: Double?) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "face-analysis") }
    private var detector: FaceDetector? = null
    /** Slower but more robust model, used while the fast one keeps missing (dark rooms, odd angles). */
    private var accurate: FaceDetector? = null
    private var camera: androidx.camera.core.Camera? = null
    private val lowLight = LowLight()
    @Volatile private var misses = 0
    @Volatile private var inFlight = false
    private var frameNo = 0
    private var lastMeterMs = 0L
    private var evIndex = 0
    /** Mean camera brightness 0..255 and whether low-light processing is active (diagnostics / UI). */
    @Volatile var brightness = 0; private set
    @Volatile var lowLightActive = false; private set
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)

    /**
     * The face detector needs upright faces, so frames must be rotated for how the screen is turned
     * *now* (a movie in landscape), not how it was when tracking started.
     */
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) {
            if (id == android.view.Display.DEFAULT_DISPLAY) analysis?.targetRotation = displayRotation()
        }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    private fun displayRotation(): Int =
        displays.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
    @Volatile private var running = false
    @Volatile private var faceSeen = false
    private var lastProcessed = 0L
    @Volatile var fps = 0f
        private set
    private var fpsCount = 0
    private var fpsStart = 0L

    /** Must be called on the main thread. */
    fun start() {
        if (running) return
        running = true
        if (detector == null) {
            fun options(mode: Int) = FaceDetectorOptions.Builder()
                .setPerformanceMode(mode)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.1f)                // phone further away (movies in landscape)
                .build()
            detector = FaceDetection.getClient(options(FaceDetectorOptions.PERFORMANCE_MODE_FAST))
            accurate = FaceDetection.getClient(options(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE))
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get()
                provider = p
                bind(p)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera unavailable", t)
                running = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Must be called on the main thread. */
    fun stop() {
        if (!running) return
        running = false
        displays.unregisterDisplayListener(displayListener)
        analysis = null; camera = null; evIndex = 0
        provider?.unbindAll()
        fps = 0f; lowLightActive = false
    }

    fun close() {
        stop()
        detector?.close(); detector = null
        accurate?.close(); accurate = null
        analysisExecutor.shutdown()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(p: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        if (!p.hasCamera(selector)) { Log.e(TAG, "No front camera"); running = false; return }

        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    // 640x480: enough detail for a face at arm's length in the dark; still cheap.
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
        // Frame-rate range: enough for tracking, but with the LOWEST minimum on offer, so auto-exposure
        // may lengthen each frame in a dark room (a fixed 24 fps caps exposure at 1/24 s).
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
        val ranges = info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
        ranges?.filter { it.upper in TARGET_FPS..30 }?.minWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })?.let {
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
        builder.setTargetRotation(displayRotation())
        val analysis = builder.build()
        this.analysis = analysis
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        displays.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        p.unbindAll()
        camera = p.bindToLifecycle(owner, selector, analysis)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val interval = if (faceSeen) 1000L / TARGET_FPS else SEARCH_INTERVAL_MS
        val media = proxy.image
        val fast = detector; val slow = accurate
        if (!running || media == null || fast == null || slow == null || inFlight || now - lastProcessed < interval - 5) {
            proxy.close(); return
        }
        lastProcessed = now
        frameNo++
        val rot = proxy.imageInfo.rotationDegrees
        val w = proxy.width; val h = proxy.height
        val plane = proxy.planes[0]
        val luma = lowLight.meanLuma(plane.buffer, plane.rowStride, w, h)
        brightness = luma
        adjustExposure(luma)
        // Dark frame, or the detector keeps missing: feed a contrast-stretched grey image instead.
        val enhance = luma < LowLight.DARK || misses >= 3
        lowLightActive = enhance
        // Still missing: alternate in the slower, more robust model until the face is found again.
        val det = if (misses >= 6 && frameNo % 2 == 0) slow else fast
        val image = if (enhance) {
            val nv21 = lowLight.enhance(plane.buffer, plane.rowStride, w, h)
            proxy.close()
            InputImage.fromByteArray(nv21, w, h, rot, InputImage.IMAGE_FORMAT_NV21)
        } else InputImage.fromMediaImage(media, rot)
        inFlight = true
        det.process(image)
            .addOnSuccessListener(callbackExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                faceSeen = face != null
                misses = if (face != null) 0 else misses + 1
                countFrame(now)
                if (face != null) meterOnFace(face.boundingBox, w, h, rot, now)
                onResult(face?.headEulerAngleY?.toDouble())
            }
            .addOnFailureListener(callbackExecutor) { Log.w(TAG, "Detection failed: $it") }
            .addOnCompleteListener { inFlight = false; if (!enhance) proxy.close() }
    }

    /** Brighter exposure in the dark (+1 EV), back to normal in good light. */
    private fun adjustExposure(luma: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val target = when {
            luma < 55 -> (1f / state.exposureCompensationStep.toFloat()).toInt()
            luma > 100 -> 0
            else -> return
        }.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        if (target != evIndex) { evIndex = target; cam.cameraControl.setExposureCompensationIndex(target) }
    }

    /** Face-priority exposure: meter on the face, not on a dark room or a bright screen behind you. */
    private fun meterOnFace(box: android.graphics.Rect, w: Int, h: Int, rot: Int, now: Long) {
        if (now - lastMeterMs < 1500) return
        val cam = camera ?: return; val use = analysis ?: return
        lastMeterMs = now
        // ML Kit's box is in upright coordinates; the metering factory wants buffer coordinates.
        val u = box.exactCenterX(); val v = box.exactCenterY()
        val (x, y) = when (rot) {
            90 -> v to (h - u)
            180 -> (w - u) to (h - v)
            270 -> (w - v) to u
            else -> u to v
        }
        try {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(w.toFloat(), h.toFloat(), use)
            val size = (maxOf(box.width(), box.height()).toFloat() / maxOf(w, h)).coerceIn(0.1f, 0.6f)
            val point = factory.createPoint(x.coerceIn(0f, w.toFloat()), y.coerceIn(0f, h.toFloat()), size)
            cam.cameraControl.startFocusAndMetering(
                androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()
            )
        } catch (_: Throwable) { /* camera without AE regions: fine */ }
    }

    private fun countFrame(now: Long) {
        if (fpsStart == 0L) fpsStart = now
        fpsCount++
        if (now - fpsStart >= 1000) {
            fps = fpsCount * 1000f / (now - fpsStart)
            fpsCount = 0; fpsStart = now
        }
    }

    private companion object {
        const val TAG = "FaceYaw"
        const val TARGET_FPS = 24
        const val SEARCH_INTERVAL_MS = 200L
    }
}
