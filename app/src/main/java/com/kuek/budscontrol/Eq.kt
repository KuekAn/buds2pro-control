package com.kuek.budscontrol

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone-side 10-band EQ. The Buds2 Pro only accept preset EQ, so this runs on the
 * phone's audio output (DynamicsProcessing on the global session, see EqService).
 */
object Eq {
    val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val MAX_DB = 12f
    /** Slider steps: 0.5 dB from -12 to +12. */
    const val STEPS = 48

    /** [preamp] null = automatic (just enough headroom for the largest boost). */
    class Profile(val name: String, val gains: FloatArray, val preamp: Float? = null)

    /** One profile read from an imported file. */
    class Imported(val name: String?, val gains: FloatArray, val preamp: Float?)

    const val PRE_MIN = -15f
    const val PRE_MAX = 5f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun budsOnly(ctx: Context) = prefs(ctx).getBoolean("buds_only", true)
    fun current(ctx: Context): Profile =
        Profile(prefs(ctx).getString("name", "Flat")!!, decode(prefs(ctx).getString("gains", null)),
            decodePre(prefs(ctx).getString("preamp", null)))

    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun setBudsOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("buds_only", on).apply()
    /** false = per app (announced sessions, like Wavelet); true = all audio via session 0. */
    fun global(ctx: Context) = prefs(ctx).getBoolean("global", false)
    fun setGlobal(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("global", on).apply()
    fun setCurrent(ctx: Context, p: Profile) =
        prefs(ctx).edit().putString("name", p.name).putString("gains", encode(p.gains))
            .putString("preamp", p.preamp?.toString() ?: "auto").apply()

    /** Saved profiles, oldest first. */
    fun profiles(ctx: Context): List<Profile> {
        val p = prefs(ctx)
        return (p.getString("profile_names", "") ?: "").split('\n').filter { it.isNotEmpty() }
            .map {
                val v = p.getString("profile:$it", null) ?: ""
                Profile(it, decode(v.substringBefore('|')), decodePre(v.substringAfter('|', "auto")))
            }
    }

    fun saveProfile(ctx: Context, prof: Profile) {
        val names = profiles(ctx).map { it.name }.filter { it != prof.name } + prof.name
        prefs(ctx).edit().putString("profile:${prof.name}", encode(prof.gains) + "|" + (prof.preamp?.toString() ?: "auto"))
            .putString("profile_names", names.joinToString("\n")).apply()
    }

    fun deleteProfile(ctx: Context, name: String) {
        val names = profiles(ctx).map { it.name }.filter { it != name }
        prefs(ctx).edit().remove("profile:$name").putString("profile_names", names.joinToString("\n")).apply()
    }

    fun lastError(ctx: Context): String? = prefs(ctx).getString("error", null)
    fun setError(ctx: Context, e: String?) = prefs(ctx).edit().putString("error", e).apply()

    private fun encode(g: FloatArray) = g.joinToString(",")
    private fun decodePre(s: String?) = s?.toFloatOrNull()?.coerceIn(PRE_MIN, PRE_MAX)
    private fun decode(s: String?): FloatArray {
        val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
        return FloatArray(FREQS.size) { v.getOrElse(it) { 0f }.coerceIn(-MAX_DB, MAX_DB) }
    }

    // ------------------------------------------------------------ engine
    private const val LOW_HZ = 20.0
    private const val HIGH_HZ = 20000.0

    fun frameMs(ctx: Context) = prefs(ctx).getInt("frame_ms", 20).toFloat()
    fun engineBands(ctx: Context) = prefs(ctx).getInt("bands", 48)
    fun limiter(ctx: Context) = prefs(ctx).getBoolean("limiter", false)
    fun tune(ctx: Context, frameMs: Int, bands: Int, limiter: Int) = prefs(ctx).edit().apply {
        if (frameMs > 0) putInt("frame_ms", frameMs)
        if (bands > 0) putInt("bands", bands.coerceIn(10, 128))
        if (limiter >= 0) putBoolean("limiter", limiter == 1)
    }.apply()

    /** Edge k of n log-spaced engine bands between 20 Hz and 20 kHz. */
    fun bandEdge(k: Int, n: Int) = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(k.toDouble() / n)

    /** Smooth curve through the 10 band gains (cosine blend in log frequency, no overshoot). */
    fun curveAt(g: FloatArray, f: Double): Float {
        if (f <= FREQS.first()) return g.first()
        if (f >= FREQS.last()) return g.last()
        val j = FREQS.indexOfFirst { it >= f }
        val t = (ln(f / FREQS[j - 1]) / ln(FREQS[j].toDouble() / FREQS[j - 1])).coerceIn(0.0, 1.0)
        val s = (1 - cos(PI * t)) / 2
        return (g[j - 1] + (g[j] - g[j - 1]) * s).toFloat()
    }

    /** Headroom so boosted bands don't clip. */
    fun autoPreamp(g: FloatArray) = -max(0f, g.maxOrNull() ?: 0f)
    fun preampOf(p: Profile) = p.preamp ?: autoPreamp(p.gains)
    /** True when a manual preamp leaves the loudest band above 0 dB. */
    fun mayClip(p: Profile) = ((p.gains.maxOrNull() ?: 0f) + preampOf(p)) > 0.05f

    // ---------------------------------------------------------------- import

    private val num = "([-+]?\\d+(?:\\.\\d+)?)"
    private val filterRe = Regex(
        "Filter\\s*\\d*\\s*:\\s*ON\\s+([A-Z]+)\\s+Fc\\s+$num\\s*Hz" +
            "(?:\\s+Gain\\s+$num\\s*dB)?(?:\\s+Q\\s+$num)?", RegexOption.IGNORE_CASE)

    /**
     * Reads an EQ profile and returns the 10 band gains, or null if nothing usable
     * was found. Accepts:
     *  - AutoEQ / Equalizer APO parametric ("Filter 1: ON PK Fc 105 Hz Gain 2.4 dB Q 0.70",
     *    with PK, LSC, HSC and LS/HS filters; FixedBandEQ files are the same format)
     *  - AutoEQ / Wavelet GraphicEQ ("GraphicEQ: 20 -1.2; 21 -1.1; ...")
     *  - plain "frequency gain" pairs, one per line (comma, tab, space or ; separated)
     *  - Poweramp preset JSON exports (one or many presets), see [poweramp]
     */
    fun parse(text: String): FloatArray? = parseAll(text).firstOrNull()?.gains

    fun parseAll(text: String): List<Imported> {
        poweramp(text)?.let { return it }
        val pre = Regex("Preamp\\s*:\\s*$num\\s*dB", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it != 0f }?.coerceIn(PRE_MIN, PRE_MAX)
        val g = parseText(text) ?: return emptyList()
        // A file's own preamp was set for its own filters; keep it only for 1:1 fixed-band files.
        return listOf(Imported(null, g, if (fixedBand(text) != null) pre else null))
    }

    private fun parseText(text: String): FloatArray? {
        fixedBand(text)?.let { return it }
        val filters = filterRe.findAll(text).mapNotNull { m ->
            val type = m.groupValues[1].uppercase()
            val fc = m.groupValues[2].toDouble()
            val gain = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val q = m.groupValues[4].toDoubleOrNull() ?: if (type == "PK" || type == "PEQ") 1.41 else 0.71
            when (type) {
                "PK", "PEQ" -> Biquad.peak(fc, gain, q)
                "LS", "LSC", "LSQ" -> Biquad.shelf(fc, gain, q, low = true)
                "HS", "HSC", "HSQ" -> Biquad.shelf(fc, gain, q, low = false)
                else -> null            // LP/HP/notch etc.: not meaningful for a graphic EQ
            }
        }.toList()
        val curve: ((Double) -> Double)? = when {
            filters.isNotEmpty() -> { f -> filters.sumOf { it.db(f) } }
            else -> points(text)?.let { pts -> { f: Double -> interp(pts, f) } }
        }
        curve ?: return null
        return fromCurve(curve)
    }

    /** Each band's gain is the curve's average across its own octave, so a narrow
     *  peak just off a band centre still counts. */
    private fun fromCurve(curve: (Double) -> Double) = FloatArray(FREQS.size) { i ->
        val fc = FREQS[i].toDouble()
        r1((0..8).map { k -> curve(fc * 2.0.pow((k - 4) / 8.0)) }.average())
    }

    private fun r1(v: Double) = (Math.round(v * 10) / 10f).coerceIn(-MAX_DB, MAX_DB)

    /** Index of our band whose centre is within ~10% of [fc], or null. */
    private fun bandAt(fc: Double) = FREQS.indices.firstOrNull { abs(ln(fc / FREQS[it])) < 0.1 }

    private fun field(obj: String, key: String) =
        Regex("\"$key\"\\s*:\\s*$num").find(obj)?.groupValues?.get(1)?.toDouble()

    /**
     * Poweramp preset export: a JSON array of presets, each with "name", "preamp",
     * "parametric" and "bands". Band "type" 0 = bass tone (low shelf), 1 = treble tone
     * (high shelf), 2 = an EQ band (graphic, or peaking with "q" when parametric).
     * Parsed by hand so the parser runs in plain JVM unit tests too.
     */
    private fun poweramp(text: String): List<Imported>? {
        val starts = Regex("\"bands\"\\s*:\\s*\\[").findAll(text).toList()
        if (starts.isEmpty()) return null
        val out = mutableListOf<Imported>()
        var prevEnd = 0
        for (st in starts) {
            val end = text.indexOf(']', st.range.last)
            if (end < 0) break
            val head = text.substring(prevEnd, st.range.first)
            prevEnd = end + 1
            val name = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(head).lastOrNull()?.groupValues?.get(1)
            val pre = field(head, "preamp")?.toFloat()
            val parametric = Regex("\"parametric\"\\s*:\\s*true").containsMatchIn(head)
            val bands = Regex("\\{[^{}]*\\}").findAll(text.substring(st.range.last + 1, end)).map { it.value }.toList()
            val tones = mutableListOf<Biquad>()
            val eq = mutableListOf<Triple<Double, Double, Double>>()   // freq, gain, q
            for (b in bands) {
                val f = field(b, "frequency") ?: continue
                val gain = field(b, "gain") ?: 0.0
                val q = field(b, "q") ?: 0.0
                when (field(b, "type")?.toInt()) {
                    0 -> if (gain != 0.0) tones += Biquad.shelf(f, gain, q.coerceIn(0.3, 2.0), low = true)
                    1 -> if (gain != 0.0) tones += Biquad.shelf(f, gain, q.coerceIn(0.3, 2.0), low = false)
                    else -> eq += Triple(f, gain, q)
                }
            }
            if (eq.isEmpty() && tones.isEmpty()) continue
            fun toneAt(f: Double) = tones.sumOf { it.db(f) }
            val idx = eq.map { bandAt(it.first) }
            val gains = when {
                // Poweramp's 10-band graphic EQ sits on our centres (124, 249, 498 Hz...): copy 1:1.
                !parametric && eq.size == FREQS.size && idx.all { it != null } && idx.toSet().size == FREQS.size ->
                    FloatArray(FREQS.size).also { g ->
                        eq.forEachIndexed { k, t -> val i = idx[k]!!; g[i] = r1(t.second + toneAt(FREQS[i].toDouble())) }
                    }
                parametric -> {
                    val pk = eq.map { (f, gain, q) -> Biquad.peak(f, gain, if (q > 0) q else 1.41) }
                    fromCurve { f -> pk.sumOf { it.db(f) } + toneAt(f) }
                }
                else -> {
                    val pts = eq.map { it.first to it.second }.sortedBy { it.first }
                    if (pts.size >= 2) fromCurve { f -> interp(pts, f) + toneAt(f) } else fromCurve { f -> toneAt(f) }
                }
            }
            out += Imported(name?.takeIf { it.isNotBlank() }, gains, pre?.takeIf { it != 0f }?.coerceIn(PRE_MIN, PRE_MAX))
        }
        return out.ifEmpty { null }
    }

    /** A FixedBandEQ-style file whose peaks sit on our band centres maps 1:1, no smearing. */
    private fun fixedBand(text: String): FloatArray? {
        val ms = filterRe.findAll(text).toList()
        if (ms.isEmpty()) return null
        val g = FloatArray(FREQS.size)
        val seen = BooleanArray(FREQS.size)
        for (m in ms) {
            if (m.groupValues[1].uppercase() !in setOf("PK", "PEQ")) return null
            val fc = m.groupValues[2].toDouble()
            val i = FREQS.indices.firstOrNull { abs(ln(fc / FREQS[it])) < 0.1 } ?: return null
            if (seen[i]) return null
            seen[i] = true
            g[i] = r1(m.groupValues[3].toDoubleOrNull() ?: 0.0)
        }
        return g
    }

    private fun points(text: String): List<Pair<Double, Double>>? {
        val body = text.substringAfter("GraphicEQ:", text)
        val pairRe = Regex("$num\\s*[,;\\t ]\\s*$num")
        val chunks = if (text.contains("GraphicEQ:")) body.split(';') else body.lines()
        val pts = chunks.mapNotNull { c ->
            pairRe.find(c.trim())?.let { m ->
                val f = m.groupValues[1].toDouble(); val g = m.groupValues[2].toDouble()
                if (f in 10.0..30000.0 && abs(g) < 60) f to g else null
            }
        }.sortedBy { it.first }
        return if (pts.size >= 2) pts else null
    }

    private fun interp(pts: List<Pair<Double, Double>>, f: Double): Double {
        if (f <= pts.first().first) return pts.first().second
        if (f >= pts.last().first) return pts.last().second
        val j = pts.indexOfFirst { it.first >= f }
        val (f0, g0) = pts[j - 1]; val (f1, g1) = pts[j]
        val t = (ln(f) - ln(f0)) / (ln(f1) - ln(f0))
        return g0 + (g1 - g0) * t
    }

    /** RBJ cookbook biquads at 48 kHz, used only to read off a filter's response. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun db(f: Double): Double {
            val w = 2 * PI * f / FS
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }

        companion object {
            const val FS = 48000.0
            fun peak(fc: Double, g: Double, q: Double): Biquad {
                val a = 10.0.pow(g / 40); val w = 2 * PI * fc / FS; val al = sin(w) / (2 * q)
                val a0 = 1 + al / a
                return Biquad((1 + al * a) / a0, -2 * cos(w) / a0, (1 - al * a) / a0, -2 * cos(w) / a0, (1 - al / a) / a0)
            }
            fun shelf(fc: Double, g: Double, q: Double, low: Boolean): Biquad {
                val a = 10.0.pow(g / 40); val w = 2 * PI * fc / FS; val c = cos(w)
                val al = sin(w) / (2 * q); val sa = 2 * sqrt(a) * al
                return if (low) {
                    val a0 = (a + 1) + (a - 1) * c + sa
                    Biquad(a * ((a + 1) - (a - 1) * c + sa) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0,
                        a * ((a + 1) - (a - 1) * c - sa) / a0, -2 * ((a - 1) + (a + 1) * c) / a0,
                        ((a + 1) + (a - 1) * c - sa) / a0)
                } else {
                    val a0 = (a + 1) - (a - 1) * c + sa
                    Biquad(a * ((a + 1) + (a - 1) * c + sa) / a0, -2 * a * ((a - 1) + (a + 1) * c) / a0,
                        a * ((a + 1) + (a - 1) * c - sa) / a0, 2 * ((a - 1) - (a + 1) * c) / a0,
                        ((a + 1) - (a - 1) * c - sa) / a0)
                }
            }
        }
    }

    fun label(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
    fun db(g: Float): String {
        val r = Math.round(g * 10) / 10f
        return (if (r > 0) "+" else "") + (if (r % 1f == 0f) "${r.toInt()}" else "$r") + " dB"
    }
}
