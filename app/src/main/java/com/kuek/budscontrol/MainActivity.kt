package com.kuek.budscontrol

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private companion object { const val AMBIENT_SLIDER = false }

    private lateinit var status: TextView
    private lateinit var battL: TextView
    private lateinit var battR: TextView
    private lateinit var battCase: TextView
    private lateinit var placeL: TextView
    private lateinit var placeR: TextView
    private lateinit var placeCase: TextView
    private lateinit var reseatTitle: TextView
    private lateinit var useHere: TextView
    private lateinit var singleNote: TextView
    private lateinit var strengthBox: View
    private lateinit var strengthLabel: TextView
    private lateinit var strengthValue: TextView
    private lateinit var strength: SeekBar
    private lateinit var volume: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var volumeValue: TextView
    private lateinit var modeButtons: Map<Int, View>
    private lateinit var audio: AudioManager
    private var draggingStrength = false

    private val listener: (BudsStatus) -> Unit = { render(it) }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = renderVolume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AudioManager::class.java)
        status = findViewById(R.id.status)
        battL = findViewById(R.id.batt_l); battR = findViewById(R.id.batt_r); battCase = findViewById(R.id.batt_case)
        placeL = findViewById(R.id.place_l); placeR = findViewById(R.id.place_r); placeCase = findViewById(R.id.place_case)
        reseatTitle = findViewById(R.id.reseat_title)
        useHere = findViewById(R.id.use_here)
        useHere.setOnClickListener { takeOver() }
        singleNote = findViewById(R.id.single_note)
        strengthBox = findViewById(R.id.strength_box)
        strengthLabel = findViewById(R.id.strength_label)
        strengthValue = findViewById(R.id.strength_value)
        strength = findViewById(R.id.strength)
        volume = findViewById(R.id.volume)
        volumeLabel = findViewById(R.id.volume_label)
        volumeValue = findViewById(R.id.volume_value)
        modeButtons = mapOf(
            Protocol.MODE_ANC to findViewById(R.id.mode_anc),
            Protocol.MODE_AMBIENT to findViewById(R.id.mode_ambient),
            Protocol.MODE_OFF to findViewById(R.id.mode_off),
        )
        modeButtons.forEach { (mode, b) ->
            b.setOnClickListener { BudsService.start(this, BudsService.ACTION_SET_MODE, mode) }
        }
        findViewById<View>(R.id.reseat).setOnClickListener {
            BudsService.start(this, BudsService.ACTION_RESEAT)
        }
        findViewById<View>(R.id.open_touch).setOnClickListener { startActivity(Intent(this, TouchActivity::class.java)) }
        findViewById<View>(R.id.open_eq).setOnClickListener { startActivity(Intent(this, EqActivity::class.java)) }
        findViewById<View>(R.id.open_find).setOnClickListener { startActivity(Intent(this, FindActivity::class.java)) }
        findViewById<View>(R.id.open_more).setOnClickListener { startActivity(Intent(this, MoreActivity::class.java)) }

        strength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) strengthValue.text = strengthText(BudsStore.get(this@MainActivity).mode, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { draggingStrength = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                draggingStrength = false
                // Sent once on release, so dragging doesn't flood the buds.
                if (BudsStore.get(this@MainActivity).mode == Protocol.MODE_AMBIENT)
                    BudsService.setting(this@MainActivity, BudsService.SET_AMBIENT_LEVEL, sb.progress)
            }
        })

        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                volumeValue.text = "${p * 100 / sb.max.coerceAtLeast(1)}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onResume() {
        super.onResume()
        BudsStore.addListener(listener)
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        render(BudsStore.get(this))
        renderVolume()
        if (!BudsStore.get(this).connected) BudsService.start(this, BudsService.ACTION_CONNECT)
    }

    override fun onPause() {
        BudsStore.removeListener(listener)
        unregisterReceiver(volumeReceiver)
        super.onPause()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun strengthText(mode: Int, p: Int) = if (p >= 4) "Extra loud" else "${p + 1} of 5"

    private fun renderVolume() {
        val onBuds = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        volumeLabel.text = if (onBuds) "Buds volume" else "Media volume (buds not playing)"
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** Explicitly moves the buds to this phone, like tapping them in the Bluetooth panel. */
    private fun takeOver() {
        val dev = BudsService.findDevice(this) ?: return
        useHere.text = "Connecting…"
        Link.takeOver(this, dev) { ok ->
            runOnUiThread {
                useHere.text = "Use the buds on this phone"
                if (!ok) {
                    Toast.makeText(this, "Tap the buds in Bluetooth settings to connect them here", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                // On success the ACL_CONNECTED broadcast starts the control link.
            }
        }
    }

    /** Small grey line under each bud's battery. Placement codes come from the status messages. */
    private fun placeText(s: BudsStatus, p: Int) = if (!s.connected) "–" else when (p) {
        1 -> "In ear"
        2 -> "Out of ear"
        3 -> "In case"
        0 -> "Not connected"
        else -> "–"
    }

    private fun render(s: BudsStatus) {
        status.text = when {
            s.connected -> "${s.name}  ·  connected"
            s.connecting -> "${s.name}  ·  connecting…"
            s.elsewhere -> "${s.name}  ·  not on this phone (case, off or other device)"
            else -> "${s.name}  ·  not connected"
        }
        battL.text = "L ${Widgets.batt(s.battL, s.placeL)}"
        battR.text = "R ${Widgets.batt(s.battR, s.placeR)}"
        battCase.text = "Case ${if (s.battCase in 1..100) "${s.battCase}%" else "–"}"
        placeL.text = placeText(s, s.placeL)
        placeR.text = placeText(s, s.placeR)
        placeCase.text = if (!s.connected) "" else when {
            s.placeL == 3 || s.placeR == 3 -> "Buds inside"
            else -> ""
        }

        useHere.visibility = if (!s.connected && !s.connecting && s.elsewhere) View.VISIBLE else View.GONE

        val single = s.singleBud()
        modeButtons.forEach { (mode, b) ->
            b.isSelected = s.mode == mode
            b.isActivated = single
            b.alpha = if (s.connected) 1f else 0.5f
        }
        singleNote.visibility = if (single) View.VISIBLE else View.GONE
        if (single) {
            val side = if (s.placeL == 1) "Left" else "Right"
            singleNote.text = if (s.oneEarbud) "Only the $side bud is in. Noise control applies to it alone."
            else "Only the $side bud is in. Noise control is off with one bud (change in More)."
        }

        when (s.mode) {
            // Hidden: an ear test on 2026-11-19 found no audible change from custom ambient
            // levels 0/4 or Extra loud (all acked), and Buds2 Pro has no ANC level setting.
            Protocol.MODE_AMBIENT -> if (!AMBIENT_SLIDER) strengthBox.visibility = View.GONE else {
                strengthBox.visibility = View.VISIBLE
                strengthLabel.text = "Ambient loudness"
                strength.max = 4
                if (!draggingStrength) {
                    val v = when {
                        !s.customOn -> 1            // buds' default ambient, before any custom level
                        s.customL == s.customR -> s.customL.coerceIn(0, 4)
                        else -> maxOf(s.customL, s.customR).coerceIn(0, 4)
                    }
                    strength.progress = v
                    strengthValue.text = if (s.customOn && s.customL != s.customR) "L ${s.customL + 1} · R ${s.customR + 1}"
                        else if (!s.customOn) "Default" else strengthText(s.mode, v)
                }
                strength.isEnabled = s.connected
            }
            else -> strengthBox.visibility = View.GONE
        }
        reseatTitle.text = getString(if (s.reseating) R.string.reseating else R.string.reseat_long)
    }
}
