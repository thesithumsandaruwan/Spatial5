package dev.spatial5

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
        get() = sp.getBoolean(K_RECENTER, false)
        set(v) = sp.edit().putBoolean(K_RECENTER, v).apply()
    var global: Boolean
        get() = sp.getBoolean(K_GLOBAL, false)
        set(v) = sp.edit().putBoolean(K_GLOBAL, v).apply()
    var pauseScreenOff: Boolean
        get() = sp.getBoolean(K_SCREEN, true)
        set(v) = sp.edit().putBoolean(K_SCREEN, v).apply()

    var immersive: Boolean
        get() = sp.getBoolean(K_IMMERSIVE, true)
        set(v) = sp.edit().putBoolean(K_IMMERSIVE, v).apply()
    var worldAnchor: Boolean
        get() = sp.getBoolean(K_WORLD, true)
        set(v) = sp.edit().putBoolean(K_WORLD, v).apply()
    /** 0..100 -> stage half-width 20..70 degrees. */
    var width: Int
        get() = sp.getInt(K_WIDTH, 40)
        set(v) = sp.edit().putInt(K_WIDTH, v).apply()
    var room: Int
        get() = sp.getInt(K_ROOM, 20)
        set(v) = sp.edit().putInt(K_ROOM, v).apply()

    val stageHalfWidthDeg get() = 20f + width * 0.5f

    /** Movie (Cinema) profile: Auto = whenever the screen is in landscape. */
    var movieMode: Int
        get() = sp.getInt(K_MOVIE, MOVIE_AUTO)
        set(v) = sp.edit().putInt(K_MOVIE, v).apply()

    /** Finds the best stage width and room for each song automatically. */
    var autoTune: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** Apps learned to block audio capture: they start in standard mode (no muting blip). */
    val blocked: Set<String> get() = sp.getStringSet(K_BLOCKED, emptySet()) ?: emptySet()
    fun addBlocked(pkgs: Set<String>) { if (pkgs.isNotEmpty()) sp.edit().putStringSet(K_BLOCKED, blocked + pkgs).apply() }
    /** Apps proven capturable (they produced audio while muted): never switched out on a gap between songs. */
    val verified: Set<String> get() = sp.getStringSet(K_VERIFIED, emptySet()) ?: emptySet()
    fun addVerified(pkgs: Set<String>) { val n = pkgs - verified; if (n.isNotEmpty()) sp.edit().putStringSet(K_VERIFIED, verified + n).apply() }

    /** Every app that has announced an audio session: first candidates when identifying a scanned player. */
    val knownPlayers: Set<String> get() = sp.getStringSet(K_PLAYERS, emptySet()) ?: emptySet()
    fun addKnownPlayer(pkg: String) { if (pkg.isNotEmpty() && pkg !in knownPlayers) sp.edit().putStringSet(K_PLAYERS, knownPlayers + pkg).apply() }

    fun clearBlocked() { sp.edit().remove(K_BLOCKED).apply() }

    fun removeBlocked(pkgs: Set<String>) { if (pkgs.any { it in blocked }) sp.edit().putStringSet(K_BLOCKED, blocked - pkgs).apply() }

    companion object {
        const val K_IMMERSIVE = "immersive"
        const val K_AUTO = "auto_tune"
        const val K_MOVIE = "movie_mode"
        const val MOVIE_AUTO = 0
        const val MOVIE_ON = 1
        const val MOVIE_OFF = 2
        const val K_BLOCKED = "blocked_pkgs"
        const val K_VERIFIED = "verified_pkgs"
        const val K_PLAYERS = "known_players"
        const val K_WORLD = "world_anchor"
        const val K_WIDTH = "width"
        const val K_ROOM = "room"
        const val K_FACE_MODE = "face_mode"
        const val K_STRENGTH = "strength"
        const val K_SMOOTH = "smoothness"
        const val K_INVERT = "invert"
        const val K_RECENTER = "auto_recenter"
        const val K_GLOBAL = "global"
        const val K_SCREEN = "pause_screen_off"
    }
}
