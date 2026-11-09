package dev.headtracker

import dev.headtracker.audio.HeadShadowModel
import dev.headtracker.tracking.HeadPoseEngine
import dev.headtracker.tracking.OneEuroFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ModelTest {
    private val model = HeadShadowModel()
    private fun run(az: Double): Pair<FloatArray, Array<FloatArray>> {
        val g = FloatArray(2); val b = Array(2) { FloatArray(model.bandCount) }
        model.compute(az, 0.72, g, b); return g to b
    }

    @Test fun neutralAtCentre() {
        val (g, b) = run(0.0)
        g.forEach { assertEquals(0f, it, 1e-4f) }
        b.forEach { ch -> ch.forEach { assertEquals(0f, it, 1e-4f) } }
    }

    @Test fun sourceOnLeftFavoursLeftEar() {
        val (g, b) = run(30.0)
        assertTrue(g[0] > g[1])
        val hi = model.bandCount - 3
        assertTrue("high band left ${b[0][hi]} > right ${b[1][hi]}", b[0][hi] > b[1][hi] + 3)
        // Low frequencies are barely shadowed by the head.
        assertTrue(abs(b[0][0] - b[1][0]) < 1f)
    }

    @Test fun mirrorSymmetric() {
        val (gl, bl) = run(40.0); val (gr, br) = run(-40.0)
        assertEquals(gl[0], gr[1], 1e-4f)
        for (i in 0 until model.bandCount) assertEquals(bl[0][i], br[1][i], 1e-4f)
    }

    @Test fun standardModeMovesTheStageClearly() {
        val (g30, _) = run(30.0)
        assertTrue("balance at 30 deg: ${g30[0] - g30[1]} dB", g30[0] - g30[1] > 5f)
        val (g80, _) = run(80.0)
        assertTrue("saturates, never extreme: ${g80[0] - g80[1]} dB", g80[0] - g80[1] < 9.5f)
        // Scene behind you: both ears lose high frequencies.
        val g = FloatArray(2); val b = Array(2) { FloatArray(model.bandCount) }
        model.compute(160.0, 0.72, g, b)
        val hi = model.bandCount - 1
        assertTrue("rear darkening: ${b[0][hi]} / ${b[1][hi]}", b[0][hi] < -2f && b[1][hi] < -2f)
    }

    @Test fun oneEuroSettles() {
        val f = OneEuroFilter(1.0, 0.02)
        var y = 0.0
        for (i in 0..200) y = f.filter(20.0, i / 15.0)
        assertEquals(20.0, y, 0.01)
    }

    @Test fun motionModeTurnsAndRecentres() {
        val e = HeadPoseEngine().apply { mode = HeadPoseEngine.Mode.MOTION }
        e.onPhoneYaw(0.0)
        var t = 0.0
        repeat(5) { e.tick(t); t += 0.02 }
        e.onPhoneYaw(40.0)
        var y = 0.0
        repeat(25) { y = e.tick(t); t += 0.02 }
        assertTrue("turned: $y", y > 30)
        repeat(1500) { y = e.tick(t); t += 0.02 }
        assertTrue("recentred: $y", abs(y) < 2)
    }

    @Test fun faceLostGlidesHome() {
        val e = HeadPoseEngine()
        var t = 0.0
        repeat(10) { e.onFace(0.0, t); e.tick(t); t += 1 / 15.0 }      // looking at the screen = front
        repeat(30) { e.onFace(10.0, t); e.tick(t); t += 1 / 15.0 }
        assertTrue(e.tick(t) > 8)
        e.onFaceMissing()
        var y = 0.0
        repeat(200) { y = e.tick(t); t += 0.02 }
        assertTrue("returned: $y", abs(y) < 1)
    }
}
