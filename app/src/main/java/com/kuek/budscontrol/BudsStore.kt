package com.kuek.budscontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Last known state. Persisted so widgets can draw it while the app is not running. */
data class BudsStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    /** Last connect found the buds not connected to this phone (case, off, or another device). */
    val elsewhere: Boolean = false,
    val name: String = "Galaxy Buds2 Pro",
    val battL: Int = -1,
    val battR: Int = -1,
    val battCase: Int = -1,
    /** Placement: 0 disconnected, 1 wearing, 2 out of ear, 3 in case, -1 unknown. */
    val placeL: Int = -1,
    val placeR: Int = -1,
    val mode: Int = -1,
    val finding: Boolean = false,
    val reseating: Boolean = false,
    /** Ambient sound level (ext [23]) and ANC level (ext [24]). */
    val ambientLevel: Int = -1,
    val ancLevel: Int = -1,
    /** Noise control works with only one bud in (ext [28]). */
    val oneEarbud: Boolean = true,
    /** Customised ambient sound (ext [29]-[31]): per-side level 0-2, tone 0-4. */
    val customOn: Boolean = false,
    val customL: Int = 1,
    val customR: Int = 1,
    val tone: Int = 2,
    /** Raw extended-status payload, used to read touch settings. */
    val ext: ByteArray? = null,
    val updated: Long = 0,
) {
    /** Exactly one bud reports being in an ear (placement 1 = wearing). */
    fun singleBud(): Boolean = connected && ((placeL == 1) xor (placeR == 1))

    fun modeLabel(): String = when (mode) {
        Protocol.MODE_ANC -> "ANC"
        Protocol.MODE_AMBIENT -> "Ambient"
        Protocol.MODE_OFF -> "Off"
        else -> "?"
    }
}

object BudsStore {
    private const val PREFS = "buds"
    private const val TAG = "Buds"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: BudsStatus? = null
    private val listeners = mutableSetOf<(BudsStatus) -> Unit>()
    private val logLines = ArrayDeque<String>()

    fun get(ctx: Context): BudsStatus {
        cached?.let { return it }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BudsStatus(
            name = p.getString("name", null) ?: "Galaxy Buds2 Pro",
            battL = p.getInt("battL", -1),
            battR = p.getInt("battR", -1),
            battCase = p.getInt("battCase", -1),
            mode = p.getInt("mode", -1),
            updated = p.getLong("updated", 0),
        ).also { cached = it }
    }

    @Synchronized
    fun update(ctx: Context, change: (BudsStatus) -> BudsStatus) {
        val app = ctx.applicationContext
        val s = change(get(app))
        cached = s
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", s.name)
            .putInt("battL", s.battL).putInt("battR", s.battR).putInt("battCase", s.battCase)
            .putInt("mode", s.mode).putLong("updated", s.updated)
            .apply()
        main.post {
            Widgets.updateAll(app, s)
            listeners.toList().forEach { it(s) }
        }
    }

    var deviceAddress: String? = null

    fun addListener(l: (BudsStatus) -> Unit) { listeners += l }
    fun removeListener(l: (BudsStatus) -> Unit) { listeners -= l }

    /** Set by the service; the log is mirrored to Android/data/<pkg>/files/link.log for adb. */
    @Volatile var logFile: java.io.File? = null

    @Synchronized
    fun log(line: String) {
        Log.d(TAG, line)
        logFile?.let { f ->
            try {
                if (f.length() > 512 * 1024) f.delete()
                f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
            } catch (_: Exception) {}
        }
        logLines.addLast(line)
        while (logLines.size > 60) logLines.removeFirst()
    }

    @Synchronized
    fun logText(): String = logLines.reversed().joinToString("\n")
}
