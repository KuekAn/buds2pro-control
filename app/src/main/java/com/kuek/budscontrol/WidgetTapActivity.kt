package com.kuek.budscontrol

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast

/**
 * Invisible trampoline for the widget's left column, since widgets only get single
 * clicks. A tap waits briefly for a second tap:
 *  - double tap (any state): open Buds Control.
 *  - single tap, buds not linked here: "Use the buds on this phone" (as on the main page).
 *  - single tap, buds linked: refresh the link.
 * An activity (not a broadcast) so it may open the app or Bluetooth settings.
 */
class WidgetTapActivity : Activity() {

    companion object {
        private const val DOUBLE_TAP_MS = 400L

        fun pending(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, WidgetTapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val single = Runnable { singleTap() }
    private var waiting = false

    /** Where the tapped widget view is on screen (from the launcher), to accept second taps there. */
    private var target: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent?.sourceBounds
        tap()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        tap()
    }

    /**
     * This invisible window covers the home screen while it waits for a second tap, so the
     * second tap usually lands here rather than on the widget. Count it if it's over (or
     * near) the tapped column; a tap anywhere else just ends the wait as a single tap.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && waiting) {
            val slop = (24 * resources.displayMetrics.density).toInt()
            val r = target
            if (r == null || Rect(r).apply { inset(-slop, -slop) }.contains(ev.rawX.toInt(), ev.rawY.toInt())) tap()
            else { main.removeCallbacks(single); singleTap() }
        }
        return true
    }

    private fun tap() {
        if (waiting) {
            main.removeCallbacks(single)
            waiting = false
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        waiting = true
        main.postDelayed(single, DOUBLE_TAP_MS)
    }

    private fun singleTap() {
        waiting = false
        val s = BudsStore.get(this)
        if (s.connected || s.connecting) {
            BudsService.start(this, BudsService.ACTION_CONNECT)
            finish(); return
        }
        val dev = BudsService.findDevice(this) ?: run { finish(); return }
        Toast.makeText(this, "Connecting the buds to this device…", Toast.LENGTH_SHORT).show()
        Link.takeOver(this, dev) { ok ->
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "Tap the buds in Bluetooth settings to connect them here", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(single)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
