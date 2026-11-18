package dev.spatial5

import dev.spatial5.ui.HeadMesh
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exports the dummy head mesh + shaders to build/preview/head.json for an off-device WebGL preview. */
class HeadMeshExportTest {
    @Test fun export() {
        val h = HeadMesh.load(File("src/main/assets/head.bin").inputStream()); val s = HeadMesh.sphere(18, 12)
        assertTrue(h.vertices.all { it.isFinite() } && h.vertices.size / 6 < 65536)
        fun q(x: String) = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val json = """{"hv":[${h.vertices.joinToString(",")}],"hi":[${h.indices.joinToString(",")}],
            "sv":[${s.vertices.joinToString(",")}],"si":[${s.indices.joinToString(",")}],
            "vs":${q(HeadMesh.VS)},"fs":${q(HeadMesh.FS)}}"""
        File("build/preview").mkdirs(); File("build/preview/head.json").writeText(json)
    }
}
