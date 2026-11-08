package dev.headtracker.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * Optional: finds the audio sessions of ALL players (also ones that don't announce themselves) by
 * reading `dumpsys audio`. Needs the DUMP permission, granted once over adb:
 *   adb shell pm grant dev.headtracker android.permission.DUMP
 */
class DumpSessionScanner(private val context: Context) {
    data class Found(val uid: Int, val session: Int)

    val available get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Returns media sessions that are playing or paused, or null when unavailable. Blocking (~50-150 ms). */
    fun scan(): List<Found>? {
        if (!available) return null
        return try {
            val p = ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(2, TimeUnit.SECONDS)
            parse(text, Process.myUid())
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        const val PERMISSION = "android.permission.DUMP"
        const val ADB_COMMAND = "adb shell pm grant dev.headtracker android.permission.DUMP"
        private val LINE = Regex("""u/pid:(\d+)/\d+.*?state:(\w+).*?usage=(\w+).*?session(?:Id)?:(\d+)""")
        private val MEDIA = setOf("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN", "1", "14", "0")

        fun parse(text: String, ownUid: Int): List<Found> {
            val out = LinkedHashMap<Int, Found>()
            for (line in text.lineSequence()) {
                if (!line.contains("u/pid:")) continue
                val m = LINE.find(line) ?: continue
                val (uid, state, usage, session) = m.destructured
                val u = uid.toInt(); val s = session.toInt()
                if (u == ownUid || s <= 0 || usage !in MEDIA) continue
                if (state != "started" && state != "paused") continue
                out[s] = Found(u, s)
            }
            return out.values.toList()
        }
    }
}
