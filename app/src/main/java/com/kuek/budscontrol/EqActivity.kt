package com.kuek.budscontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ln

/** 10-band phone-side EQ with profile import (AutoEQ parametric / fixed band / GraphicEQ). */
class EqActivity : Activity() {

    private companion object { const val PICK = 7 }

    private lateinit var onSw: Switch
    private lateinit var budsSw: Switch
    private lateinit var globalSw: Switch
    private lateinit var state: TextView
    private lateinit var profileName: TextView
    private lateinit var curve: CurveView
    private lateinit var bars: List<SeekBar>
    private lateinit var values: List<TextView>
    private lateinit var savedBox: LinearLayout
    private var gains = FloatArray(Eq.FREQS.size)
    private var name = "Flat"
    /** null = automatic headroom. */
    private var preamp: Float? = null
    private lateinit var preBar: SeekBar
    private lateinit var preValue: TextView
    private lateinit var preAuto: TextView
    private var dp = 1f
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(32))
        }
        col.addView(TextView(this).apply {
            text = "Equaliser"; textSize = 26f; setTextColor(getColor(R.color.text))
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(note("Runs on the phone's audio output, on top of the buds' own sound."))

        fun sw(title: String) = Switch(this).apply {
            text = title; textSize = 16f; setTextColor(getColor(R.color.text)); minHeight = px(52)
        }
        onSw = sw("Equaliser on").also { col.addView(it) }
        onSw.setOnClickListener { Eq.setEnabled(this, onSw.isChecked); EqService.apply(this); renderStateSoon() }
        budsSw = sw("Only when the buds are playing").also { col.addView(it) }
        budsSw.setOnClickListener { Eq.setBudsOnly(this, budsSw.isChecked); EqService.live?.refresh(); renderStateSoon() }
        globalSw = sw("All audio (every app)").also { col.addView(it) }
        globalSw.setOnClickListener { Eq.setGlobal(this, globalSw.isChecked); EqService.live?.refresh(); renderStateSoon() }
        col.addView(note("Off = per app, like Wavelet: cleaner, but only for apps that announce their audio " +
            "(Poweramp, Spotify, most music players). Start playback after turning the EQ on. " +
            "On = every app through Android's global mix."))
        state = note("").also { col.addView(it) }

        profileName = TextView(this).apply {
            textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, px(16), 0, px(6))
        }
        col.addView(profileName)
        curve = CurveView(this)
        col.addView(curve, LinearLayout.LayoutParams(-1, px(130)))

        val bs = mutableListOf<SeekBar>(); val vs = mutableListOf<TextView>()
        for (i in Eq.FREQS.indices) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "${Eq.label(Eq.FREQS[i])} Hz"; textSize = 13f; setTextColor(getColor(R.color.text_dim))
            }, LinearLayout.LayoutParams(px(56), -2))
            val sb = SeekBar(this).apply { max = Eq.STEPS }
            val v = TextView(this).apply {
                textSize = 13f; setTextColor(getColor(R.color.text)); gravity = Gravity.END
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (!user) return
                    gains[i] = p / 2f - Eq.MAX_DB
                    if (!name.endsWith(" (edited)")) name = "$name (edited)"
                    commit(save = false)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = commit(save = true)
            })
            row.addView(sb, LinearLayout.LayoutParams(0, px(40), 1f))
            row.addView(v, LinearLayout.LayoutParams(px(64), -2))
            col.addView(row); bs += sb; vs += v
        }
        bars = bs; values = vs

        // Preamp: automatic headroom by default; dragging switches to a manual value.
        val preRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        preRow.addView(TextView(this).apply {
            text = "Preamp"; textSize = 13f; setTextColor(getColor(R.color.text))
        }, LinearLayout.LayoutParams(px(56), -2))
        preBar = SeekBar(this).apply { max = ((Eq.PRE_MAX - Eq.PRE_MIN) * 2).toInt() }
        preValue = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.text)); gravity = Gravity.END }
        preBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (!user) return
                preamp = Eq.PRE_MIN + p / 2f
                commit(save = false)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = commit(save = true)
        })
        preRow.addView(preBar, LinearLayout.LayoutParams(0, px(40), 1f))
        preRow.addView(preValue, LinearLayout.LayoutParams(px(64), -2))
        col.addView(preRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        preAuto = chip("Auto preamp") { preamp = null; commit(save = true); renderBands() }
        col.addView(preAuto, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(4) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0) }
        actions.addView(chip("Import file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), PICK)
        }, weighted(true))
        actions.addView(chip("Paste") { pasteDialog() }, weighted(true))
        actions.addView(chip("Flat") { load(Eq.Profile("Flat", FloatArray(Eq.FREQS.size))) }, weighted(false))
        col.addView(actions)
        col.addView(chip("Save as profile") { nameDialog(name.removeSuffix(" (edited)")) { n -> saveAs(n) } }, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(8) })

        col.addView(TextView(this).apply {
            text = "Saved profiles (tap to use, hold to delete)"; textSize = 13f; setTextColor(getColor(R.color.text_dim))
            setPadding(0, px(24), 0, px(6))
        })
        savedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(savedBox)
        col.addView(note("Import accepts Poweramp preset .json exports, AutoEQ ParametricEQ, FixedBandEQ and " +
            "GraphicEQ text, Equalizer APO filter lines, or plain \"frequency gain\" pairs. Parametric filters " +
            "are folded into the 10 bands, so very narrow peaks are softened."))

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
        Eq.current(this).let { gains = it.gains; name = it.name; preamp = it.preamp }
    }

    override fun onResume() {
        super.onResume()
        onSw.isChecked = Eq.enabled(this)
        budsSw.isChecked = Eq.budsOnly(this)
        globalSw.isChecked = Eq.global(this)
        if (Eq.enabled(this) && EqService.live == null) EqService.apply(this)
        renderBands(); renderSaved(); renderStateSoon()
        state.postDelayed(poll, 2000)
    }

    private fun weighted(gap: Boolean) = LinearLayout.LayoutParams(0, px(52), 1f).apply { if (gap) marginEnd = px(8) }

    private fun note(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(getColor(R.color.text_dim)); setPadding(0, px(4), 0, px(4))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.text))
        setBackgroundResource(R.drawable.card_bg); isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Saves the current gains and pushes them to the running effect. */
    @Suppress("UNUSED_PARAMETER")
    private fun commit(save: Boolean) {
        Eq.setCurrent(this, profile())
        EqService.live?.refresh()
        renderBands(fromSlider = true)
    }

    private fun profile(n: String = name) = Eq.Profile(n, gains.copyOf(), preamp)

    private fun load(p: Eq.Profile) {
        gains = p.gains.copyOf(); name = p.name; preamp = p.preamp
        Eq.setCurrent(this, p)
        EqService.live?.refresh()
        renderBands()
    }

    private fun saveAs(n: String) {
        name = n
        Eq.saveProfile(this, profile(n))
        Eq.setCurrent(this, profile(n))
        renderBands(); renderSaved()
    }

    private fun renderBands(fromSlider: Boolean = false) {
        for (i in Eq.FREQS.indices) {
            if (!fromSlider) bars[i].progress = Math.round((gains[i] + Eq.MAX_DB) * 2)
            values[i].text = Eq.db(gains[i])
        }
        val p = profile()
        val pre = Eq.preampOf(p)
        if (!fromSlider || preamp == null) preBar.progress = ((pre - Eq.PRE_MIN) * 2).toInt()
        preValue.text = Eq.db(pre)
        preBar.alpha = if (preamp == null) 0.55f else 1f
        preAuto.text = if (preamp == null) "Preamp: automatic (follows the loudest band)" else "Use automatic preamp"
        profileName.text = name + if (Eq.mayClip(p)) "   ·   may clip, lower the preamp" else ""
        profileName.setTextColor(getColor(if (Eq.mayClip(p)) R.color.danger else R.color.text))
        curve.gains = gains
    }

    private fun renderSaved() {
        savedBox.removeAllViews()
        val list = Eq.profiles(this)
        if (list.isEmpty()) savedBox.addView(note("None yet. Import or save one."))
        for (p in list) savedBox.addView(chip(p.name) { load(p) }.apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(16), 0)
            setOnLongClickListener {
                AlertDialog.Builder(this@EqActivity).setTitle("Delete \"${p.name}\"?")
                    .setPositiveButton("Delete") { _, _ -> Eq.deleteProfile(this@EqActivity, p.name); renderSaved() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }, LinearLayout.LayoutParams(-1, px(52)).apply { bottomMargin = px(8) })
    }

    private val poll = object : Runnable {
        override fun run() { renderState(); state.postDelayed(this, 2000) }
    }

    override fun onPause() {
        state.removeCallbacks(poll)
        super.onPause()
    }

        /** The service applies asynchronously; read its result a moment later. */
    private fun renderStateSoon() { renderState(); state.postDelayed({ renderState() }, 600) }

    private fun renderState() {
        val err = Eq.lastError(this)
        state.text = when {
            !Eq.enabled(this) -> "Off."
            err != null -> err
            Eq.global(this) && EqService.active -> "Active on all audio."
            !Eq.global(this) && EqService.apps.isEmpty() -> "Per app: waiting for a music app to start playing."
            EqService.active -> "Active on: " + EqService.apps.joinToString { appName(it) }
            Eq.budsOnly(this) -> "Waiting for the buds to play."
            else -> "Starting…"
        }
        state.setTextColor(getColor(if (err != null && Eq.enabled(this)) R.color.danger else R.color.text_dim))
    }

    private fun appName(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // ---------------------------------------------------------------- import

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().take(1 shl 20).toByteArray().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
        if (text == null) { toast("Couldn't read that file."); return }
        importText(text, suggestedName(uri))
    }

    private fun suggestedName(uri: Uri): String {
        val raw = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Imported"
        return raw.substringBeforeLast('.')
            .replace(Regex("\\s*\\[[^\\]]*\\]"), "")   // Poweramp's "[7 Aug 2025 3_42_45 pm]"
            .replace(Regex("\\s*(ParametricEQ|FixedBandEQ|GraphicEQ|Parametric|Graphic)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim().ifEmpty { "Imported" }
    }

    private fun pasteDialog() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val box = EditText(this).apply {
            setText(clip); minLines = 4; maxLines = 10; textSize = 12f; typeface = Typeface.MONOSPACE
            hint = "Filter 1: ON PK Fc 105 Hz Gain 2.4 dB Q 0.70, or a Poweramp preset JSON"
        }
        AlertDialog.Builder(this).setTitle("Paste EQ profile").setView(box)
            .setPositiveButton("Import") { _, _ -> importText(box.text.toString(), "Pasted") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun importText(text: String, suggested: String) {
        val list = Eq.parseAll(text)
        if (list.isEmpty()) { toast("No EQ found. Expected a Poweramp .json, AutoEQ / Equalizer APO filters or GraphicEQ."); return }
        if (list.size == 1) {
            val one = list[0]
            // The file name usually says more ("v1 -3dB") than the preset name inside it.
            nameDialog(if (suggested == "Imported" || suggested == "Pasted") one.name ?: suggested else suggested) { n ->
                gains = one.gains.copyOf(); preamp = one.preamp; saveAs(n); EqService.live?.refresh()
                toast("Imported \"$n\"")
            }
            return
        }
        list.forEachIndexed { k, it -> Eq.saveProfile(this, Eq.Profile(it.name ?: "$suggested ${k + 1}", it.gains, it.preamp)) }
        val first = list[0]
        load(Eq.Profile(first.name ?: "$suggested 1", first.gains, first.preamp))
        renderSaved()
        toast("Imported ${list.size} profiles")
    }

    private fun nameDialog(initial: String, done: (String) -> Unit) {
        val box = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
        AlertDialog.Builder(this).setTitle("Profile name").setView(box)
            .setPositiveButton("Save") { _, _ -> done(box.text.toString().trim().replace('\n', ' ').ifEmpty { initial }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Draws the 10 band gains on a log-frequency axis. */
    private class CurveView(ctx: Context) : View(ctx) {
        var gains = FloatArray(Eq.FREQS.size); set(v) { field = v.copyOf(); invalidate() }
        private val d = ctx.resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * d; color = ctx.getColor(R.color.accent)
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d; color = ctx.getColor(R.color.pill_off) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val pad = 8 * d
            fun x(i: Int) = pad + (w - 2 * pad) * (ln(Eq.FREQS[i].toFloat() / Eq.FREQS[0]) / ln(Eq.FREQS.last().toFloat() / Eq.FREQS[0]))
            fun y(g: Float) = h / 2 - (g / Eq.MAX_DB) * (h / 2 - pad)
            for (gl in listOf(-12f, -6f, 0f, 6f, 12f)) c.drawLine(pad, y(gl), w - pad, y(gl), grid)
            val p = Path()
            for (i in gains.indices) if (i == 0) p.moveTo(x(i), y(gains[i])) else {
                // Smooth with a midpoint quad so the curve isn't a zig-zag.
                val mx = (x(i - 1) + x(i)) / 2
                p.cubicTo(mx, y(gains[i - 1]), mx, y(gains[i]), x(i), y(gains[i]))
            }
            c.drawPath(p, line)
            for (i in gains.indices) c.drawCircle(x(i), y(gains[i]), 3.5f * d, dot)
        }
    }
}
