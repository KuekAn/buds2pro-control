package com.kuek.budscontrol

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Touch controls: master on/off, per-gesture switches, touch-and-hold actions. */
class TouchActivity : Activity() {

    private var cfg = TouchConfig()
    private val dependents = mutableListOf<View>()
    private val cycleViews = mutableListOf<View>()
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The buds are the source of truth; fall back to the last saved copy.
        cfg = BudsStore.get(this).ext?.let { TouchConfig.fromExt(it) }?.also { it.save(this) }
            ?: TouchConfig.load(this)
        build()
    }

    private fun update(change: (TouchConfig) -> TouchConfig) {
        if (building) return
        cfg = change(cfg)
        cfg.save(this)
        BudsService.start(this, BudsService.ACTION_TOUCH)
        refreshEnabled()
    }

    private fun build() {
        building = true
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(32))
        }
        fun header(s: String) = col.addView(TextView(this).apply {
            text = s; textSize = 13f; setTextColor(getColor(R.color.text_dim))
            setPadding(0, px(24), 0, px(6))
        })
        fun sw(title: String, sub: String?, value: Boolean, dependent: Boolean = true,
               set: (TouchConfig, Boolean) -> TouchConfig): Switch {
            val s = Switch(this).apply {
                text = if (sub == null) title else "$title\n$sub"
                textSize = 16f
                setTextColor(getColor(R.color.text))
                isChecked = value
                minHeight = px(56)
                setOnCheckedChangeListener { _, on -> update { set(it, on) } }
            }
            col.addView(s)
            if (dependent) dependents += s
            return s
        }

        col.addView(TextView(this).apply {
            text = "Touch controls"; textSize = 26f; setTextColor(getColor(R.color.text))
            setTypeface(typeface, Typeface.BOLD)
        })

        val master = sw("Touch controls", "Off blocks every tap and hold", cfg.enabled, dependent = false) { c, on -> c.copy(enabled = on) }
        master.textSize = 18f

        header("Gestures")
        sw("Single tap", "Play / pause", cfg.singleTap) { c, on -> c.copy(singleTap = on) }
        sw("Double tap", "Next track", cfg.doubleTap) { c, on -> c.copy(doubleTap = on) }
        sw("Triple tap", "Previous track", cfg.tripleTap) { c, on -> c.copy(tripleTap = on) }
        sw("Touch and hold", "Action set below", cfg.hold) { c, on -> c.copy(hold = on) }

        sw("Double tap earbud edge", "Volume up (right) / down (left)", cfg.edgeDoubleTap) { c, on -> c.copy(edgeDoubleTap = on) }

        header("During calls")
        sw("Double tap", "Answer / end call", cfg.doubleTapCall) { c, on -> c.copy(doubleTapCall = on) }
        sw("Touch and hold", "Decline call", cfg.holdCall) { c, on -> c.copy(holdCall = on) }

        fun holdGroup(label: String, current: Int, set: (TouchConfig, Int) -> TouchConfig) {
            header(label)
            val g = RadioGroup(this)
            Touch.holdLabels.forEach { (id, name) ->
                g.addView(RadioButton(this).apply {
                    this.id = View.generateViewId(); tag = id
                    text = name; textSize = 16f; setTextColor(getColor(R.color.text))
                    minHeight = px(48); isChecked = id == current
                })
            }
            g.setOnCheckedChangeListener { grp, checked ->
                val id = grp.findViewById<View>(checked)?.tag as? Int ?: return@setOnCheckedChangeListener
                update { set(it, id) }
            }
            col.addView(g)
            dependents += g
        }
        holdGroup("Touch and hold: left bud", cfg.holdLeft) { c, v -> c.copy(holdLeft = v) }
        holdGroup("Touch and hold: right bud", cfg.holdRight) { c, v -> c.copy(holdRight = v) }

        header("Noise modes that touch and hold switches between (pick at least 2)")
        fun cyc(title: String, value: Boolean, set: (TouchConfig, Boolean) -> TouchConfig) {
            val cb = CheckBox(this).apply {
                text = title; textSize = 16f; setTextColor(getColor(R.color.text))
                minHeight = px(48); isChecked = value
            }
            cb.setOnCheckedChangeListener { box, on ->
                val next = set(cfg, on)
                if (listOf(next.cycleAnc, next.cycleAmbient, next.cycleOff).count { it } < 2) {
                    Toast.makeText(this, "Pick at least two modes", Toast.LENGTH_SHORT).show()
                    box.isChecked = true
                } else update { next }
            }
            col.addView(cb)
            dependents += cb
            cycleViews += cb
        }
        cyc("ANC", cfg.cycleAnc) { c, on -> c.copy(cycleAnc = on) }
        cyc("Ambient sound", cfg.cycleAmbient) { c, on -> c.copy(cycleAmbient = on) }
        cyc("Off", cfg.cycleOff) { c, on -> c.copy(cycleOff = on) }

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
        building = false
        refreshEnabled()
    }

    private fun refreshEnabled() {
        fun setTree(v: View, on: Boolean) {
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.4f
            if (v is RadioGroup) for (i in 0 until v.childCount) v.getChildAt(i).isEnabled = on
        }
        dependents.forEach { setTree(it, cfg.enabled) }
        val cycleOn = cfg.enabled && cfg.hold &&
            (cfg.holdLeft == Touch.HOLD_NOISE || cfg.holdRight == Touch.HOLD_NOISE)
        cycleViews.forEach { setTree(it, cycleOn) }
    }
}
