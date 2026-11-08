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
    private var provider: ProcessCameraProvider? = null
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
            detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setMinFaceSize(0.2f)
                    .build()
            )
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
        provider?.unbindAll()
        fps = 0f
    }

    fun close() {
        stop()
        detector?.close(); detector = null
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
                    ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
        // Ask the sensor for a low frame rate to save power, if the device supports one.
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
        val ranges = info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
        ranges?.filter { it.upper >= TARGET_FPS }?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { -it.lower })?.let {
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
        val analysis = builder.build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        p.unbindAll()
        p.bindToLifecycle(owner, selector, analysis)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val interval = if (faceSeen) 1000L / TARGET_FPS else SEARCH_INTERVAL_MS
        val media = proxy.image
        val det = detector
        if (!running || media == null || det == null || now - lastProcessed < interval - 5) { proxy.close(); return }
        lastProcessed = now
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        det.process(image)
            .addOnSuccessListener(callbackExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                faceSeen = face != null
                countFrame(now)
                onResult(face?.headEulerAngleY?.toDouble())
            }
            .addOnFailureListener(callbackExecutor) { Log.w(TAG, "Detection failed: $it") }
            .addOnCompleteListener { proxy.close() }
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
        const val SEARCH_INTERVAL_MS = 330L
    }
}
