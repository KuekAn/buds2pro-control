package com.kuek.budscontrol

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Find my buds, kept on its own page so it can't be hit by accident.
 * Starting the beep needs a 2-second press and hold, and is blocked while
 * either bud reports being in an ear (the service also stops the beep if a
 * bud goes into an ear while it is beeping).
 */
class FindActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var hold: TextView
    private lateinit var stop: TextView
    private lateinit var note: TextView
    private lateinit var override: CheckBox
    private var holdStart = 0L
    private val holdMs = 2000L

    private val listener: (BudsStatus) -> Unit = { render(it) }

    private val tick = object : Runnable {
        override fun run() {
            val left = holdMs - (System.currentTimeMillis() - holdStart)
            if (left <= 0) {
                holdStart = 0
                BudsService.start(this@FindActivity, BudsService.ACTION_FIND_START)
                render(BudsStore.get(this@FindActivity))
            } else {
                hold.text = "Keep holding… %.1f s".format(left / 1000f)
                main.postDelayed(this, 100)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            fitsSystemWindows = true
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(getColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(text("Find my buds", 26f, R.color.text, true))
        root.addView(text(
            "The buds play a loud beep. Never start this while wearing them.", 15f, R.color.text_dim,
        ).apply { setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt()) })

        note = text("", 15f, R.color.danger)
        root.addView(note)
        override = CheckBox(this).apply {
            text = "Both buds are out of my ears (sensor is wrong)"
            setTextColor(getColor(R.color.text))
            setOnCheckedChangeListener { _, _ -> render(BudsStore.get(this@FindActivity)) }
        }
        root.addView(override)

        hold = text("", 18f, R.color.on_accent, true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_danger)
        }
        root.addView(hold, LinearLayout.LayoutParams(-1, (88 * dp).toInt()).apply { topMargin = (16 * dp).toInt() })
        hold.setOnTouchListener { v, e ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { holdStart = System.currentTimeMillis(); main.post(tick) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdStart != 0L) { holdStart = 0; main.removeCallbacks(tick) }
                    render(BudsStore.get(this))
                }
            }
            true
        }

        stop = text("Stop beeping", 18f, R.color.text, true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.card_bg)
            setOnClickListener { BudsService.start(this@FindActivity, BudsService.ACTION_FIND_STOP) }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, (72 * dp).toInt()).apply { topMargin = (16 * dp).toInt() })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        BudsStore.addListener(listener)
        render(BudsStore.get(this))
    }

    override fun onPause() {
        BudsStore.removeListener(listener)
        holdStart = 0
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun render(s: BudsStatus) {
        val worn = s.placeL == 1 || s.placeR == 1
        note.text = when {
            !s.connected -> "Buds not connected."
            worn -> "A bud reports being in your ear. Take both out first."
            else -> ""
        }
        override.visibility = if (s.connected && worn) android.view.View.VISIBLE else android.view.View.GONE
        val allowed = s.connected && !s.finding && (!worn || override.isChecked)
        hold.isEnabled = allowed
        hold.setTextColor(getColor(if (allowed) R.color.on_accent else R.color.text_dim))
        if (holdStart == 0L) hold.text = when {
            s.finding -> "Beeping…"
            allowed -> "Press and hold 2 s to beep"
            else -> "Beep unavailable"
        }
        stop.alpha = if (s.finding) 1f else 0.5f
    }
}
