package dev.headtracker

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var faceMode: Boolean
        get() = sp.getBoolean(K_FACE_MODE, true)
        set(v) = sp.edit().putBoolean(K_FACE_MODE, v).apply()
    var strength: Int
        get() = sp.getInt(K_STRENGTH, 60)
        set(v) = sp.edit().putInt(K_STRENGTH, v).apply()
    var smoothness: Int
        get() = sp.getInt(K_SMOOTH, 50)
        set(v) = sp.edit().putInt(K_SMOOTH, v).apply()
    var invert: Boolean
        get() = sp.getBoolean(K_INVERT, false)
        set(v) = sp.edit().putBoolean(K_INVERT, v).apply()
    var autoRecenter: Boolean
        get() = sp.getBoolean(K_RECENTER, true)
        set(v) = sp.edit().putBoolean(K_RECENTER, v).apply()
    var global: Boolean
        get() = sp.getBoolean(K_GLOBAL, false)
        set(v) = sp.edit().putBoolean(K_GLOBAL, v).apply()
    var pauseScreenOff: Boolean
        get() = sp.getBoolean(K_SCREEN, true)
        set(v) = sp.edit().putBoolean(K_SCREEN, v).apply()

    companion object {
        const val K_FACE_MODE = "face_mode"
        const val K_STRENGTH = "strength"
        const val K_SMOOTH = "smoothness"
        const val K_INVERT = "invert"
        const val K_RECENTER = "auto_recenter"
        const val K_GLOBAL = "global"
        const val K_SCREEN = "pause_screen_off"
    }
}
