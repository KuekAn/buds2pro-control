package com.kuek.budscontrol

import android.content.Context

/**
 * Touch control settings for the Buds2 Pro. Stored locally and pushed to the
 * buds as a whole whenever anything changes.
 */
data class TouchConfig(
    /** Master switch: false blocks every touch gesture. */
    val enabled: Boolean = true,
    val singleTap: Boolean = true,
    val doubleTap: Boolean = true,
    val tripleTap: Boolean = true,
    val hold: Boolean = true,
    val doubleTapCall: Boolean = true,
    val holdCall: Boolean = true,
    /** Touch-and-hold action per side, one of the HOLD_* constants. */
    val holdLeft: Int = Touch.HOLD_NOISE,
    val holdRight: Int = Touch.HOLD_NOISE,
    /** Which modes touch-and-hold cycles through when set to noise controls. */
    val cycleAnc: Boolean = true,
    val cycleAmbient: Boolean = true,
    val cycleOff: Boolean = false,
    /** Double tap the edge of a bud to change volume. */
    val edgeDoubleTap: Boolean = false,
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences("touch", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).putBoolean("singleTap", singleTap)
            .putBoolean("doubleTap", doubleTap).putBoolean("tripleTap", tripleTap)
            .putBoolean("hold", hold).putBoolean("doubleTapCall", doubleTapCall)
            .putBoolean("holdCall", holdCall).putInt("holdLeft", holdLeft).putInt("holdRight", holdRight)
            .putBoolean("cycleAnc", cycleAnc).putBoolean("cycleAmbient", cycleAmbient)
            .putBoolean("cycleOff", cycleOff).putBoolean("edgeDoubleTap", edgeDoubleTap).apply()
    }

    companion object {
        /**
         * Current settings as reported by the buds in the extended status
         * (0x61) payload. Layout per GalaxyBudsClient's decoder; byte 11 and
         * 21 matched the Buds2 Pro's real settings on 2026-11-19.
         */
        fun fromExt(b: ByteArray): TouchConfig? {
            if (b.size < 33) return null
            fun u(i: Int) = b[i].toInt() and 0xFF
            val t = u(10); val n = u(21)
            fun bit(v: Int, i: Int) = v and (1 shl i) != 0
            return TouchConfig(
                enabled = bit(t, 7),
                singleTap = bit(t, 3), doubleTap = bit(t, 2), tripleTap = bit(t, 1), hold = bit(t, 0),
                doubleTapCall = bit(t, 4), holdCall = bit(t, 5),
                holdLeft = u(11) shr 4, holdRight = u(11) and 0x0F,
                cycleAnc = bit(n, 2), cycleAmbient = bit(n, 1), cycleOff = bit(n, 0),
                edgeDoubleTap = u(32) == 1,
            )
        }

        fun load(ctx: Context): TouchConfig {
            val p = ctx.getSharedPreferences("touch", Context.MODE_PRIVATE)
            val d = TouchConfig()
            return TouchConfig(
                p.getBoolean("enabled", d.enabled), p.getBoolean("singleTap", d.singleTap),
                p.getBoolean("doubleTap", d.doubleTap), p.getBoolean("tripleTap", d.tripleTap),
                p.getBoolean("hold", d.hold), p.getBoolean("doubleTapCall", d.doubleTapCall),
                p.getBoolean("holdCall", d.holdCall), p.getInt("holdLeft", d.holdLeft),
                p.getInt("holdRight", d.holdRight), p.getBoolean("cycleAnc", d.cycleAnc),
                p.getBoolean("cycleAmbient", d.cycleAmbient), p.getBoolean("cycleOff", d.cycleOff),
                p.getBoolean("edgeDoubleTap", d.edgeDoubleTap),
            )
        }
    }
}

object Touch {
    const val HOLD_VOICE = 1
    const val HOLD_NOISE = 2
    const val HOLD_VOLUME = 3
    const val HOLD_SPOTIFY = 4

    val holdLabels = linkedMapOf(
        HOLD_NOISE to "Switch noise controls",
        HOLD_VOLUME to "Volume (left down, right up)",
        HOLD_VOICE to "Voice assistant",
        HOLD_SPOTIFY to "Spotify",
    )

    // Message IDs and payloads per GalaxyBudsClient (timschneeb/GalaxyBudsClient).
    const val LOCK_TOUCHPAD = 0x90
    const val SET_TOUCHPAD_OPTION = 0x92
    const val SET_HOLD_NOISE_CYCLE = 0x79
    const val OUTSIDE_DOUBLE_TAP = 0x95

    fun packets(c: TouchConfig): List<ByteArray> {
        fun b(x: Boolean) = (if (x) 1 else 0).toByte()
        val cycle = byteArrayOf(b(c.cycleAnc), b(c.cycleAmbient), b(c.cycleOff))
        return listOf(
            // [0] is "not locked": 1 = touch controls on.
            Protocol.encode(LOCK_TOUCHPAD, byteArrayOf(
                b(c.enabled), b(c.singleTap), b(c.doubleTap), b(c.tripleTap),
                b(c.hold), b(c.doubleTapCall), b(c.holdCall),
            )),
            Protocol.encode(SET_TOUCHPAD_OPTION, byteArrayOf(c.holdLeft.toByte(), c.holdRight.toByte())),
            // Buds2 Pro takes the cycle per side: left 3 bytes, then right 3 bytes.
            Protocol.encode(SET_HOLD_NOISE_CYCLE, cycle + cycle),
            Protocol.encode(OUTSIDE_DOUBLE_TAP, byteArrayOf(b(c.edgeDoubleTap))),
        )
    }
}
