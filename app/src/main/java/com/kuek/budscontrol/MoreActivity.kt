package com.kuek.budscontrol

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Less-used settings: one-earbud noise control, custom ambient, widgets, connection, log. */
class MoreActivity : Activity() {

    private lateinit var oneBud: Switch
    private lateinit var customOn: Switch
    private lateinit var left: SeekBar
    private lateinit var right: SeekBar
    private lateinit var tone: SeekBar
    private lateinit var customBox: LinearLayout
    private lateinit var log: TextView
    private lateinit var updStatus: TextView
    private lateinit var updInstall: Button
    private var available: Updater.Info? = null
    private var dragging = false

    private val listener: (BudsStatus) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(32))
        }
        fun header(s: String, into: LinearLayout = col) = into.addView(TextView(this).apply {
            text = s; textSize = 13f; setTextColor(getColor(R.color.text_dim))
            setPadding(0, px(24), 0, px(6))
        })
        fun sw(title: String) = Switch(this).apply {
            text = title; textSize = 16f; setTextColor(getColor(R.color.text)); minHeight = px(56)
        }

        col.addView(TextView(this).apply {
            text = "More"; textSize = 26f; setTextColor(getColor(R.color.text))
            setTypeface(typeface, Typeface.BOLD)
        })

        header("Noise control")
        oneBud = sw("Noise control with one bud in\nANC / ambient keep working when only one bud is worn")
        oneBud.setOnClickListener {
            BudsService.setting(this, BudsService.SET_ONE_EARBUD, if (oneBud.isChecked) 1 else 0)
        }
        col.addView(oneBud)

        col.addView(sw("Re-seat with two cycles\nOff → mode twice instead of once, for stubborn mismatches").apply {
            isChecked = BudsService.reseatCycles(this@MoreActivity) == 2
            setOnClickListener { BudsService.setReseatCycles(this@MoreActivity, if (isChecked) 2 else 1) }
        })

        header("Customise ambient sound")
        customOn = sw("Use custom ambient sound")
        customOn.setOnClickListener { sendCustom() }
        col.addView(customOn)
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun slider(label: String, max: Int): SeekBar {
            customBox.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, px(8), 0, 0)
            })
            val sb = SeekBar(this).apply { this.max = max }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; sendCustom() }
            })
            customBox.addView(sb, LinearLayout.LayoutParams(-1, px(40)))
            return sb
        }
        left = slider("Left ambient volume", 4)
        right = slider("Right ambient volume", 4)
        tone = slider("Tone (soft to clear)", 4)
        col.addView(customBox)

        header("Notification")
        col.addView(sw("Controls notification while connected\nBattery and ANC / Ambient / Off buttons").apply {
            isChecked = BudsService.notifFull(this@MoreActivity)
            setOnClickListener { BudsService.setNotifFull(this@MoreActivity, isChecked) }
        })
        col.addView(TextView(this).apply {
            text = "Android needs some notification while the app is linked to the buds. Turned off, it becomes " +
                "a silent, collapsed one with no buttons. To hide that too, switch off its category here:"
            textSize = 12f; setTextColor(getColor(R.color.text_dim))
        })
        col.addView(Button(this).apply {
            text = "Hide the quiet notification…"
            setOnClickListener {
                BudsService.ensureChannels(this@MoreActivity)
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, BudsService.CHANNEL_QUIET))
            }
        })

        header("Home screen widgets")
        col.addView(Button(this).apply { text = "Add Solid · Controls"; setOnClickListener { pin(BudsWidget::class.java) } })
        col.addView(Button(this).apply { text = "Add Glass · Controls (4x1)"; setOnClickListener { pin(GlassWidget::class.java) } })
        col.addView(Button(this).apply { text = "Add Solid · Vertical"; setOnClickListener { pin(BudsVerticalWidget::class.java) } })
        col.addView(Button(this).apply { text = "Add Glass · Vertical"; setOnClickListener { pin(GlassVerticalWidget::class.java) } })
        col.addView(Button(this).apply { text = "Add Solid · Mode tile"; setOnClickListener { pin(ModeWidget::class.java) } })
        col.addView(Button(this).apply { text = "Add Minimal · Mode tile"; setOnClickListener { pin(MinimalTileWidget::class.java) } })

        header("Connection")
        col.addView(Button(this).apply {
            text = "Reconnect"; setOnClickListener { BudsService.start(this@MoreActivity, BudsService.ACTION_CONNECT) }
        })
        col.addView(Button(this).apply {
            text = "Release link"; setOnClickListener { BudsService.start(this@MoreActivity, BudsService.ACTION_DISCONNECT) }
        })

        header("Updates")
        col.addView(TextView(this).apply {
            text = "Installed: ${Updater.installedName(this@MoreActivity)} (${Updater.installedCode(this@MoreActivity)})"
            textSize = 15f; setTextColor(getColor(R.color.text))
        })
        updStatus = TextView(this).apply {
            textSize = 13f; setTextColor(getColor(R.color.text_dim)); setPadding(0, px(4), 0, px(4))
        }
        col.addView(updStatus)
        col.addView(Button(this).apply { text = "Check for updates"; setOnClickListener { checkUpdate() } })
        updInstall = Button(this).apply { visibility = View.GONE; setOnClickListener { installUpdate() } }
        col.addView(updInstall)
        col.addView(sw("Check automatically once a day").apply {
            isChecked = Updater.auto(this@MoreActivity)
            setOnClickListener { Updater.setAuto(this@MoreActivity, isChecked) }
        })
        col.addView(TextView(this).apply {
            text = "The app only goes online to check GitHub (KuekAn/buds2pro-control) for a newer version: " +
                "when you tap the button, or once a day if the switch is on."
            textSize = 12f; setTextColor(getColor(R.color.text_dim))
        })

        header("Link log (newest first)")
        log = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.text_dim))
            setTextIsSelectable(true)
        }
        col.addView(log)

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }

    private fun checkUpdate() {
        updStatus.text = "Checking…"
        updInstall.visibility = View.GONE
        Thread {
            val r = try { Result.success(Updater.fetch(this)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { info ->
                    if (info.code > Updater.installedCode(this)) {
                        available = info
                        updStatus.text = "Version ${info.name} is available." + if (info.notes.isNotBlank()) "\n${info.notes}" else ""
                        updInstall.text = "Download and install ${info.name}"
                        updInstall.visibility = View.VISIBLE
                    } else updStatus.text = "Up to date (latest is ${info.name})."
                }.onFailure { updStatus.text = "Couldn't check: ${it.message ?: it.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun installUpdate() {
        val info = available ?: return
        updInstall.isEnabled = false
        Thread {
            try {
                val apk = Updater.download(this, info) { p -> runOnUiThread { updStatus.text = "Downloading… $p%" } }
                runOnUiThread { updStatus.text = "Opening Android's installer…" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread { updStatus.text = "Update failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            runOnUiThread { updInstall.isEnabled = true }
        }.start()
    }

    private fun sendCustom() {
        val v = ((if (customOn.isChecked) 1 else 0) shl 12) or (left.progress shl 8) or
            (right.progress shl 4) or tone.progress
        BudsService.setting(this, BudsService.SET_CUSTOM_AMBIENT, v)
    }

    private fun pin(cls: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        val ok = mgr.isRequestPinAppWidgetSupported && mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
        if (!ok) Toast.makeText(this, "Long-press the home screen > Widgets > Buds Control", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        BudsStore.addListener(listener)
        render(BudsStore.get(this))
    }

    override fun onPause() {
        BudsStore.removeListener(listener)
        super.onPause()
    }

    private fun render(s: BudsStatus) {
        oneBud.isChecked = s.oneEarbud
        customOn.isChecked = s.customOn
        if (!dragging) {
            left.progress = s.customL.coerceIn(0, 4)
            right.progress = s.customR.coerceIn(0, 4)
            tone.progress = s.tone.coerceIn(0, 4)
        }
        customBox.alpha = if (s.customOn) 1f else 0.4f
        for (v in listOf<View>(left, right, tone)) v.isEnabled = s.customOn && s.connected
        oneBud.isEnabled = s.connected
        customOn.isEnabled = s.connected
        log.text = BudsStore.logText()
        Updater.lastError(this)?.let { updStatus.text = it }
    }
}
