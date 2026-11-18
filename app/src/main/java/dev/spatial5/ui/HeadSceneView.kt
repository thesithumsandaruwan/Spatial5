package dev.spatial5.ui

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time 3D scene: a white dummy head (procedurally sculpted bust) that turns with your head,
 * surrounded by virtual speakers fixed in the room that glow with the music.
 * Renders only when something changes (no idle GPU work).
 */
class HeadSceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    class Speaker(val azimuth: Float, val level: Float, val ambient: Boolean)

    private val renderer = SceneRenderer { context.assets.open("head.bin") }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBackground(argb: Int, dark: Boolean) {
        renderer.bg = floatArrayOf(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f)
        renderer.dark = dark
        requestRender()
    }

    /** Head yaw (deg, + = user's left) and the speaker layout. Cheap to call every frame. */
    fun update(yaw: Float, speakers: List<Speaker>, active: Boolean) {
        val changed = abs(yaw - renderer.yaw) > 0.05f || speakers.size != renderer.speakers.size ||
            speakers.zip(renderer.speakers).any { (a, b) -> abs(a.level - b.level) > 0.01f || a.azimuth != b.azimuth } ||
            active != renderer.active
        if (!changed) return
        renderer.yaw = yaw; renderer.speakers = speakers; renderer.active = active
        requestRender()
    }

    private companion object {
        const val RING = 2.15f
        fun HeadMesh.Data.toMesh() = Mesh(floatBuffer(vertices), shortBuffer(indices), indices.size)
        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        fun shortBuffer(a: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }
    }

    private class MsaaChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            fun pick(samples: Int): EGLConfig? {
                val attrs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
                val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                return if (egl.eglChooseConfig(display, attrs, out, 1, n) && n[0] > 0) out[0] else null
            }
            return pick(4) ?: pick(0) ?: throw IllegalStateException("no EGL config")
        }
    }

    // ------------------------------------------------------------------ renderer

    private class Mesh(val vbo: FloatBuffer, val ibo: ShortBuffer, val count: Int)

    private class SceneRenderer(private val headAsset: () -> java.io.InputStream) : Renderer {
        @Volatile var yaw = 0f
        @Volatile var speakers: List<Speaker> = emptyList()
        @Volatile var active = false
        @Volatile var bg = floatArrayOf(0f, 0f, 0f)
        @Volatile var dark = true

        private var prog = 0; private var flatProg = 0
        private lateinit var head: Mesh
        private lateinit var sphere: Mesh
        private lateinit var ring: FloatBuffer
        private var ringCount = 0
        private lateinit var halo: FloatBuffer
        private val proj = FloatArray(16); private val view = FloatArray(16)
        private val model = FloatArray(16); private val mvp = FloatArray(16); private val tmp = FloatArray(16)
        private val eye = floatArrayOf(0f, 2.6f, 6.2f)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            prog = program(HeadMesh.VS, HeadMesh.FS); flatProg = program(HeadMesh.FLAT_VS, HeadMesh.FLAT_FS)
            head = (try { HeadMesh.load(headAsset()) } catch (t: Throwable) { HeadMesh.procedural() }).toMesh()
            sphere = HeadMesh.sphere(18, 12).toMesh()
            buildRing(); buildHalo()
            glEnable(GL_DEPTH_TEST)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            glViewport(0, 0, w, h)
            val aspect = w.toFloat() / max(1, h)
            // Fit the speaker ring horizontally whatever the shape of the view; head sits in the lower part,
            // leaving room for the title overlay.
            val hHalf = Math.toRadians(19.0)
            val vfov = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.tan(hHalf) / aspect)).toFloat().coerceIn(30f, 62f)
            Matrix.perspectiveM(proj, 0, vfov, aspect, 1f, 30f)
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0.05f, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: GL10?) {
            glClearColor(bg[0], bg[1], bg[2], 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            // Soft contact shadow + orbit ring (transparent, no depth write).
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
            Matrix.setIdentityM(model, 0)
            val ringAlpha = if (active) 0.35f else 0.18f
            flat(ring, ringCount, GL_TRIANGLE_STRIP, if (dark) floatArrayOf(1f, 1f, 1f, ringAlpha) else floatArrayOf(0f, 0f, 0f, ringAlpha * 0.6f))
            glDepthMask(true); glDisable(GL_BLEND)

            // Head (rotates with the listener). +yaw = turned to the user's left = toward screen right (mirror view).
            Matrix.setRotateM(model, 0, yaw, 0f, 1f, 0f)
            val skin = if (dark) floatArrayOf(0.95f, 0.94f, 0.92f) else floatArrayOf(0.93f, 0.92f, 0.90f)
            draw(head, skin, 0f, floatArrayOf(0f, 0f, 0f))

            // Speakers (fixed in the room).
            for (s in speakers) {
                val a = Math.toRadians(s.azimuth.toDouble())
                val r = RING
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, (sin(a) * r).toFloat(), 0f, (cos(a) * r).toFloat())
                val size = (if (s.ambient) 0.09f else 0.12f) * (1f + 0.3f * s.level)
                Matrix.scaleM(model, 0, size, size, size)
                val glow = if (s.ambient) floatArrayOf(0.39f, 0.82f, 1f) else floatArrayOf(0.04f, 0.52f, 1f)
                val body = if (dark) floatArrayOf(0.20f, 0.20f, 0.22f) else floatArrayOf(0.80f, 0.80f, 0.83f)
                draw(sphere, body, if (active) 0.35f + 0.9f * s.level else 0.12f, glow)
            }
            if (active) {
                // Additive glow reads as light on a dark background; on a light one it just washes
                // out to white, so blend normally there.
                glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, if (dark) GL_ONE else GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
                for (s in speakers) {
                    val a = Math.toRadians(s.azimuth.toDouble())
                    val x = (sin(a) * RING).toFloat(); val z = (cos(a) * RING).toFloat()
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, x, 0f, z)
                    // Billboard: face the camera.
                    Matrix.rotateM(model, 0, Math.toDegrees(kotlin.math.atan2((eye[0] - x).toDouble(), (eye[2] - z).toDouble())).toFloat(), 0f, 1f, 0f)
                    Matrix.rotateM(model, 0, -Math.toDegrees(kotlin.math.atan2(eye[1].toDouble(), kotlin.math.hypot((eye[0] - x).toDouble(), (eye[2] - z).toDouble()))).toFloat(), 1f, 0f, 0f)
                    val r = (if (s.ambient) 0.32f else 0.42f) * (0.7f + 0.8f * s.level)
                    Matrix.scaleM(model, 0, r, r, r)
                    val k = if (dark) 1f else 0.45f
                    val c = if (s.ambient) floatArrayOf(0.39f, 0.82f, 1f, (0.22f + 0.4f * s.level) * k) else floatArrayOf(0.1f, 0.55f, 1f, (0.25f + 0.5f * s.level) * k)
                    flat(halo, 34, GL_TRIANGLE_FAN, c)
                }
                glDepthMask(true); glDisable(GL_BLEND)
            }
        }

        private fun draw(m: Mesh, color: FloatArray, emissive: Float, glow: FloatArray) {
            glUseProgram(prog)
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
            glUniformMatrix4fv(glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
            glUniformMatrix4fv(glGetUniformLocation(prog, "uModel"), 1, false, model, 0)
            glUniform3fv(glGetUniformLocation(prog, "uColor"), 1, color, 0)
            glUniform3fv(glGetUniformLocation(prog, "uEye"), 1, eye, 0)
            glUniform3fv(glGetUniformLocation(prog, "uGlow"), 1, glow, 0)
            glUniform1f(glGetUniformLocation(prog, "uEmissive"), emissive)
            glUniform3fv(glGetUniformLocation(prog, "uBg"), 1, bg, 0)
            val pos = glGetAttribLocation(prog, "aPos"); val nor = glGetAttribLocation(prog, "aNor")
            m.vbo.position(0); glVertexAttribPointer(pos, 3, GL_FLOAT, false, 24, m.vbo); glEnableVertexAttribArray(pos)
            m.vbo.position(3); glVertexAttribPointer(nor, 3, GL_FLOAT, false, 24, m.vbo); glEnableVertexAttribArray(nor)
            m.ibo.position(0)
            glDrawElements(GL_TRIANGLES, m.count, GL_UNSIGNED_SHORT, m.ibo)
        }

        private fun flat(buf: FloatBuffer, count: Int, mode: Int, rgba: FloatArray) {
            glUseProgram(flatProg)
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
            glUniformMatrix4fv(glGetUniformLocation(flatProg, "uMvp"), 1, false, mvp, 0)
            glUniform4fv(glGetUniformLocation(flatProg, "uColor"), 1, rgba, 0)
            val pos = glGetAttribLocation(flatProg, "aPos"); val al = glGetAttribLocation(flatProg, "aAlpha")
            buf.position(0); glVertexAttribPointer(pos, 3, GL_FLOAT, false, 16, buf); glEnableVertexAttribArray(pos)
            buf.position(3); glVertexAttribPointer(al, 1, GL_FLOAT, false, 16, buf); glEnableVertexAttribArray(al)
            glDrawArrays(mode, 0, count)
        }

        private fun buildRing() {
            val n = 128; val r = RING; val w = 0.010f
            val f = FloatArray((n + 1) * 2 * 4)
            for (i in 0..n) {
                val a = 2 * PI * i / n
                for (j in 0..1) {
                    val rr = r + if (j == 0) -w else w
                    val o = (i * 2 + j) * 4
                    f[o] = (sin(a) * rr).toFloat(); f[o + 1] = 0f; f[o + 2] = (cos(a) * rr).toFloat(); f[o + 3] = 1f
                }
            }
            ring = floatBuffer(f); ringCount = (n + 1) * 2
        }

        /** Unit disc in the XY plane, alpha 1 at the centre fading to 0: a soft glow sprite. */
        private fun buildHalo() {
            val n = 32; val f = FloatArray((n + 2) * 4)
            f[3] = 1f
            for (i in 0..n) { val a = 2 * PI * i / n; val o = (i + 1) * 4; f[o] = cos(a).toFloat(); f[o + 1] = sin(a).toFloat() }
            halo = floatBuffer(f)
        }

        companion object {

            private fun program(vs: String, fs: String): Int {
                fun shader(type: Int, src: String) = glCreateShader(type).also { glShaderSource(it, src); glCompileShader(it) }
                return glCreateProgram().also {
                    glAttachShader(it, shader(GL_VERTEX_SHADER, vs)); glAttachShader(it, shader(GL_FRAGMENT_SHADER, fs)); glLinkProgram(it)
                }
            }

        }
    }
}
