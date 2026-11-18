package dev.spatial5.audio

import android.media.AudioManager
import android.media.audiofx.AudioEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Experiment: can an ordinary app attach the phone's own spatializer engine (e.g. Dolby's) to an
 * audio session and talk to it? If yes, it could be steered with our head pose in place, with no
 * capture at all. Runs only on a fresh, empty session (touches no app's audio) and releases at once.
 * Blocking; call from a background thread.
 */
object SpatializerProbe {

    // effect_spatializer.h parameter ids
    private const val PARAM_SUPPORTED_LEVELS = 0
    private const val PARAM_LEVEL = 1
    private const val PARAM_HEADTRACKING_SUPPORTED = 2
    private const val PARAM_HEADTRACKING_MODE = 3
    private const val PARAM_SUPPORTED_CHANNEL_MASKS = 4
    private const val PARAM_SUPPORTED_MODES = 5

    fun run(audio: AudioManager): String {
        val descs = try { AudioEffect.queryEffects()?.toList() ?: emptyList() } catch (t: Throwable) { return "query failed: $t" }
        val targets = descs.filter { it.name.contains("spatializ", ignoreCase = true) }
        if (targets.isEmpty()) return "no spatializer effect on this phone"
        return targets.joinToString("\n") { d -> d.name + " [" + d.implementor + "]: " + probe(audio, d) }
    }

    private fun probe(audio: AudioManager, d: AudioEffect.Descriptor): String {
        val ctor = try {
            AudioEffect::class.java.getDeclaredConstructor(UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        } catch (t: Throwable) { return "constructor not accessible (${t.javaClass.simpleName})" }
        val session = audio.generateAudioSessionId()
        val fx = try {
            ctor.newInstance(d.type, d.uuid, 0, session) as AudioEffect
        } catch (t: Throwable) {
            val c = t.cause ?: t
            return "create refused on session $session: ${c.javaClass.simpleName} ${c.message?.take(60) ?: ""}"
        }
        return try {
            val out = StringBuilder("CREATED on session $session · control ${fx.hasControl()}")
            out.append(" · levels ").append(read(fx, PARAM_SUPPORTED_LEVELS))
            out.append(" · level ").append(read(fx, PARAM_LEVEL))
            out.append(" · headtracking ").append(read(fx, PARAM_HEADTRACKING_SUPPORTED))
            out.append(" · ht mode ").append(read(fx, PARAM_HEADTRACKING_MODE))
            out.append(" · masks ").append(read(fx, PARAM_SUPPORTED_CHANNEL_MASKS))
            out.append(" · modes ").append(read(fx, PARAM_SUPPORTED_MODES))
            out.append(" · enable ").append(try { fx.setEnabled(true) } catch (t: Throwable) { t.javaClass.simpleName })
            out.toString()
        } finally {
            try { fx.release() } catch (_: Throwable) {}
        }
    }

    /** getParameter(byte[] param, byte[] value) is hidden; returns the raw reply as hex, or why it failed. */
    private fun read(fx: AudioEffect, param: Int): String = try {
        val m = AudioEffect::class.java.getDeclaredMethod("getParameter", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        val p = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(param).array()
        val v = ByteArray(64)
        val n = m.invoke(fx, p, v) as Int
        if (n < 0) "err $n" else v.take(n.coerceAtMost(16)).joinToString("") { "%02x".format(it) }.ifEmpty { "ok(0)" }
    } catch (t: Throwable) { (t.cause ?: t).javaClass.simpleName }
}
