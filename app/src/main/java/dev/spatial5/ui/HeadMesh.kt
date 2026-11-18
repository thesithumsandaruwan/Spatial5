package dev.spatial5.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dummy head: a real human head-and-shoulders scan ("Lee Perry-Smith" by Infinite Realities,
 * CC BY 3.0) rendered as a white studio mannequin, plus the shaders used to light it. A procedural
 * sculpt is kept as a fallback. Pure Kotlin, so it can also be exported and previewed off-device.
 * Vertices are interleaved: x y z nx ny nz. Face looks toward +z, +x is the head's left, ears at y = 0.
 */
object HeadMesh {
    class Data(val vertices: FloatArray, val indices: ShortArray)

    /** Loads the compact scan asset written by tools/make_head.py. */
    fun load(input: java.io.InputStream): Data {
        val buf = java.nio.ByteBuffer.wrap(input.use { it.readBytes() }).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic) == "HEAD") { "bad head asset" }
        val vc = buf.int; val ic = buf.int
        val v = FloatArray(vc * 6)
        val q = 2.5f / 32767f
        for (i in 0 until vc) {
            val o = i * 6
            v[o] = buf.short * q; v[o + 1] = buf.short * q; v[o + 2] = buf.short * q
            var nx = buf.get() / 127f; var ny = buf.get() / 127f; var nz = buf.get() / 127f; buf.get()
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
            v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz
        }
        val idx = ShortArray(ic) { buf.short }
        return Data(v, idx)
    }


    /** Fallback when the scan asset is unavailable. */
    fun procedural(): Data = merge(listOf(
        grid(112, 80, { doubleArrayOf(0.0, 0.0, 0.0) }) { u, v -> skull(u, v) },
        grid(28, 20, { doubleArrayOf(0.75, -0.06, -0.06) }) { u, v -> ear(u, v, 1f) },
        grid(28, 20, { doubleArrayOf(-0.75, -0.06, -0.06) }) { u, v -> ear(u, v, -1f) },
        grid(64, 28, { p -> doubleArrayOf(0.0, p[1], -0.06) }, closedV = false) { u, v -> bust(u, v) },
    ))

    private fun gauss(x: Double, s: Double) = exp(-x * x / (2 * s * s))
    private fun sstep(a: Double, b: Double, x: Double): Double { val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** Skull + face: an ellipsoid sculpted with jaw taper and facial features. u = around (seam at the back), v = top..bottom. */
    private fun skull(u: Double, v: Double): DoubleArray {
        val th = v * PI; val ph = u * 2 * PI + PI
        val dx = sin(th) * sin(ph); val dy = cos(th); val dz = sin(th) * cos(ph)
        var x = dx * 0.76; var y = dy * 0.98; var z = dz * 0.90
        if (y > 0.35) y = 0.35 + (y - 0.35) * 0.93                 // rounder, slightly flatter crown
        val jaw = sstep(-0.10, -0.98, y)
        x *= 1 - 0.24 * jaw * jaw
        z *= if (z > 0) 1 - 0.06 * jaw else 1 - 0.38 * jaw
        if (z < 0 && y > -0.35) z *= 1.06                          // occipital curve
        val front = sstep(0.40, 0.85, dz)
        if (front > 0) {
            val ax = abs(x)
            var d = 0.0
            val noseW = 0.034 + 0.036 * sstep(0.05, -0.30, y)
            d += 0.17 * gauss(x, noseW) * sstep(0.12, -0.24, y) * (1 - sstep(-0.25, -0.33, y))
            d += 0.03 * gauss(y - 0.18, 0.05) * gauss(x, 0.34)                    // brow
            d -= 0.032 * gauss(ax - 0.19, 0.075) * gauss(y - 0.06, 0.06)           // soft eye sockets
            d += 0.012 * gauss(ax - 0.19, 0.04) * gauss(y - 0.05, 0.025)           // closed eyelids
            d += 0.02 * gauss(ax - 0.28, 0.09) * gauss(y + 0.10, 0.09)             // cheekbones
            d += 0.024 * gauss(x, 0.10) * gauss(y + 0.46, 0.035)                   // lips
            d -= 0.010 * gauss(x, 0.11) * gauss(y + 0.485, 0.010)                  // mouth line
            d += 0.035 * gauss(x, 0.16) * gauss(y + 0.74, 0.08)                    // chin
            z += d * front
        }
        return doubleArrayOf(x, y, z)
    }

    private fun ear(u: Double, v: Double, side: Float): DoubleArray {
        val th = v * PI; val ph = u * 2 * PI
        val x = sin(th) * sin(ph) * 0.075; val y = cos(th) * 0.22; val z = sin(th) * cos(ph) * 0.13
        return doubleArrayOf(side * 0.72 + x * side + 0.03 * side, y - 0.10 - 0.25 * z, z - 0.08)
    }

    /** Neck flowing into sloped shoulders. u = around, v = top..bottom. */
    private fun bust(u: Double, v: Double): DoubleArray {
        val ph = u * 2 * PI + PI
        val y = -0.50 - v * 1.05
        val sh = sstep(-0.95, -1.55, y)
        val rx = 0.36 + 0.82 * sh * sh
        val rz = 0.37 + 0.20 * sh
        val sp = sin(ph)
        return doubleArrayOf(sp * rx, y - 0.28 * sh * sh * sp * sp + 0.06 * sh, cos(ph) * rz - 0.08 + 0.05 * sh)
    }

    private class Part(val pos: FloatArray, val nor: FloatArray, val idx: ShortArray)

    private fun grid(nu: Int, nv: Int, center: (DoubleArray) -> DoubleArray, closedV: Boolean = true, f: (Double, Double) -> DoubleArray): Part {
        val p = Array(nv + 1) { j -> Array(nu + 1) { i -> f(i.toDouble() / nu, j.toDouble() / nv) } }
        val pos = FloatArray((nu + 1) * (nv + 1) * 3); val nor = FloatArray(pos.size)
        for (j in 0..nv) for (i in 0..nu) {
            val a = p[j][(i + 1).coerceAtMost(nu)]; val b = p[j][(i - 1).coerceAtLeast(0)]
            val c = p[(j + 1).coerceAtMost(nv)][i]; val d = p[(j - 1).coerceAtLeast(0)][i]
            val tu = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
            val tv = doubleArrayOf(c[0] - d[0], c[1] - d[1], c[2] - d[2])
            var nx = tu[1] * tv[2] - tu[2] * tv[1]; var ny = tu[2] * tv[0] - tu[0] * tv[2]; var nz = tu[0] * tv[1] - tu[1] * tv[0]
            val pt = p[j][i]; val cc = center(pt)
            if (nx * (pt[0] - cc[0]) + ny * (pt[1] - cc[1]) + nz * (pt[2] - cc[2]) < 0) { nx = -nx; ny = -ny; nz = -nz }
            var l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-9) { nx = 0.0; ny = if (j == 0 && closedV) 1.0 else -1.0; nz = 0.0; l = 1.0 }
            val o = (j * (nu + 1) + i) * 3
            pos[o] = pt[0].toFloat(); pos[o + 1] = pt[1].toFloat(); pos[o + 2] = pt[2].toFloat()
            nor[o] = (nx / l).toFloat(); nor[o + 1] = (ny / l).toFloat(); nor[o + 2] = (nz / l).toFloat()
        }
        val idx = ShortArray(nu * nv * 6); var k = 0
        for (j in 0 until nv) for (i in 0 until nu) {
            val a = j * (nu + 1) + i; val b = a + 1; val c = a + nu + 1; val d = c + 1
            idx[k++] = a.toShort(); idx[k++] = c.toShort(); idx[k++] = b.toShort()
            idx[k++] = b.toShort(); idx[k++] = c.toShort(); idx[k++] = d.toShort()
        }
        return Part(pos, nor, idx)
    }

    private fun merge(parts: List<Part>): Data {
        val vCount = parts.sumOf { it.pos.size / 3 }
        val v = FloatArray(vCount * 6); val idx = ShortArray(parts.sumOf { it.idx.size })
        var vo = 0; var io = 0
        for (p in parts) {
            val base = vo
            for (i in 0 until p.pos.size / 3) {
                val o = (vo + i) * 6
                v[o] = p.pos[i * 3]; v[o + 1] = p.pos[i * 3 + 1]; v[o + 2] = p.pos[i * 3 + 2]
                v[o + 3] = p.nor[i * 3]; v[o + 4] = p.nor[i * 3 + 1]; v[o + 5] = p.nor[i * 3 + 2]
            }
            for (i in p.idx.indices) idx[io + i] = (p.idx[i] + base).toShort()
            vo += p.pos.size / 3; io += p.idx.size
        }
        return Data(v, idx)
    }

    fun sphere(nu: Int, nv: Int): Data {
        val part = grid(nu, nv, { doubleArrayOf(0.0, 0.0, 0.0) }) { u, w ->
            val th = w * PI; val ph = u * 2 * PI
            doubleArrayOf(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
        }
        return merge(listOf(part))
    }

    const val VS = """
            uniform mat4 uMvp; uniform mat4 uModel;
            attribute vec3 aPos; attribute vec3 aNor;
            varying vec3 vN; varying vec3 vW; varying float vY;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vW = w.xyz; vN = mat3(uModel) * aNor; vY = aPos.y;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        // Studio lighting on a white satin material: wrapped key light, cool fill, rim, soft AO.
    const val FS = """
            precision mediump float;
            uniform vec3 uColor; uniform vec3 uEye; uniform vec3 uGlow; uniform float uEmissive; uniform vec3 uBg;
            varying vec3 vN; varying vec3 vW; varying float vY;
            void main() {
                vec3 n = normalize(vN);
                vec3 v = normalize(uEye - vW);
                vec3 key = normalize(vec3(-0.55, 0.75, 0.65));
                vec3 fill = normalize(vec3(0.8, 0.15, 0.45));
                vec3 rim = normalize(vec3(0.2, 0.5, -1.0));
                float wrapKey = max((dot(n, key) + 0.4) / 1.4, 0.0);
                float f = max(dot(n, fill), 0.0) * 0.28;
                float r = pow(1.0 - max(dot(n, v), 0.0), 3.0) * (0.25 + 0.35 * max(dot(n, rim), 0.0));
                float ao = mix(0.72, 1.0, smoothstep(-1.8, 0.3, vW.y));
                vec3 h = normalize(key + v);
                float spec = pow(max(dot(n, h), 0.0), 36.0) * 0.16;
                vec3 c = uColor * (0.20 + 0.78 * wrapKey + f) * ao;
                c += vec3(spec) + r * vec3(0.85, 0.92, 1.0);
                c = mix(c, uGlow, clamp(uEmissive, 0.0, 1.0) * 0.85) + uGlow * max(uEmissive - 1.0, 0.0) * 0.4;
                // The bust dissolves into the background below the shoulders (museum-display look).
                c = mix(uBg, c, smoothstep(-2.25, -0.95, vW.y));
                gl_FragColor = vec4(c, 1.0);
            }"""

    const val FLAT_VS = """
            uniform mat4 uMvp; attribute vec3 aPos; attribute float aAlpha; varying float vA;
            void main() { vA = aAlpha; gl_Position = uMvp * vec4(aPos, 1.0); }"""

    const val FLAT_FS = """
            precision mediump float; uniform vec4 uColor; varying float vA;
            void main() { gl_FragColor = vec4(uColor.rgb, uColor.a * vA); }"""
}
