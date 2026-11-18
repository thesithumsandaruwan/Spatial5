package dev.spatial5

import dev.spatial5.tracking.LowLight
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LowLightTest {
    @Test fun darkFrameBecomesUsable() {
        val w = 64; val h = 48; val stride = 80
        // A dim scene: background ~8, a "face" patch ~30 (what a screen-lit face looks like at night).
        val buf = ByteBuffer.allocate(stride * h)
        for (y in 0 until h) for (x in 0 until w) buf.put(y * stride + x, (if (x in 20..43 && y in 12..35) 30 else 8 + (x % 3)).toByte())
        val ll = LowLight()
        val mean = ll.meanLuma(buf, stride, w, h)
        assertTrue("dark detected: $mean", mean < LowLight.DARK)
        val out = ll.enhance(buf, stride, w, h)
        val face = out[24 * w + 30].toInt() and 0xFF
        val bg = out[2 * w + 2].toInt() and 0xFF
        println("enhanced: face $face, background $bg (was 30 / 8)")
        assertTrue("face lifted and separated from background", face > 180 && face - bg > 120)
        assertTrue("chroma neutral", out[w * h].toInt() and 0xFF == 128 && out.size == w * h * 3 / 2)
    }
}
