package com.kuek.budscontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/** Every widget redraws from the same status; resizing redraws too. */
open class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.updateAll(ctx, BudsStore.get(ctx))
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) =
        Widgets.updateAll(ctx, BudsStore.get(ctx))
}

/** Battery for L, R and case plus ANC / Ambient / Off buttons (solid). */
class BudsWidget : BaseWidget()

/** Same controls in a translucent "glass" style that sits quietly on any wallpaper. */
class GlassWidget : BaseWidget()

/** Buttons stacked vertically (e.g. 1x3), solid. */
class BudsVerticalWidget : BaseWidget()

/** Buttons stacked vertically, glass. */
class GlassVerticalWidget : BaseWidget()

/** 1x1 tile: shows the noise mode, tap to cycle ANC -> Ambient -> Off. */
class ModeWidget : BaseWidget()

/** Minimal see-through tile (down to 1x1): mode name only, tap to cycle. */
class MinimalTileWidget : BaseWidget()

object Widgets {

    fun batt(v: Int, place: Int): String =
        if (v in 1..100 && place != 0) "$v%" else "–"

    /** Colours for the two widget looks. */
    /**
     * Colours for the two widget looks. Mode buttons use *_tall (inset) or *_wide (38dp pill)
     * drawables: the pill looks smaller than the button, but the whole cell stays tappable.
     */
    private class Style(
        val bg: Int, val bar: Int,
        val offT: Int, val onT: Int, val singleT: Int,
        val offW: Int, val onW: Int, val singleW: Int,
        val text: Int, val dim: Int, val onText: Int, val onSingle: Int, val icon: Int,
    )

    private val SOLID = Style(R.drawable.widget_bg, R.drawable.pill_off,
        R.drawable.pill_off_tall, R.drawable.pill_on_tall, R.drawable.pill_single_tall,
        R.drawable.pill_off_wide, R.drawable.pill_on_wide, R.drawable.pill_single_wide,
        R.color.text, R.color.text_dim, R.color.on_accent, R.color.on_single, R.drawable.ic_buds_dim)
    private val GLASS = Style(R.drawable.glass_bg, R.drawable.glass_pill_off,
        R.drawable.glass_pill_off_tall, R.drawable.glass_pill_on_tall, R.drawable.glass_pill_single_tall,
        R.drawable.glass_pill_off_wide, R.drawable.glass_pill_on_wide, R.drawable.glass_pill_single_wide,
        R.color.glass_text, R.color.glass_text_dim, R.color.glass_on, R.color.glass_on_single, R.drawable.ic_buds_glass)

    /** Short status for the widget header: where the buds are, or why they're not linked. */
    private fun statusLine(s: BudsStatus): String = when {
        s.connected -> when {
            s.placeL == 1 && s.placeR == 1 -> "In ears"
            s.placeL == 1 -> "Left in ear"
            s.placeR == 1 -> "Right in ear"
            s.placeL == 3 && s.placeR == 3 -> "In case"
            else -> "Connected"
        }
        s.connecting -> "Connecting…"
        s.elsewhere -> "Not on this device"
        else -> "Tap to connect"
    }

    /** Below this height (dp) the one-row layout is used; below WIDE_DP labels are shortened. */
    private const val TALL_DP = 150f  // one row is ~109 dp on a 400 dpi tablet, two ~229 dp
    private const val WIDE_DP = 230f

    /** Layout shapes: one row, the tall grid, or stacked buttons. */
    private enum class Shape { ROW, TALL, VERTICAL }

    fun updateAll(ctx: Context, s: BudsStatus) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val full = listOf(
            Triple(BudsWidget::class.java, SOLID, false), Triple(GlassWidget::class.java, GLASS, false),
            Triple(BudsVerticalWidget::class.java, SOLID, true), Triple(GlassVerticalWidget::class.java, GLASS, true),
        )
        for ((cls, style, vertical) in full) {
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, cls)))
                mgr.updateAppWidget(id, sized(ctx, mgr, id, s, style, vertical))
        }
        val mode = mgr.getAppWidgetIds(ComponentName(ctx, ModeWidget::class.java))
        if (mode.isNotEmpty()) mgr.updateAppWidget(mode, modeViews(ctx, s))
        val minimal = mgr.getAppWidgetIds(ComponentName(ctx, MinimalTileWidget::class.java))
        if (minimal.isNotEmpty()) mgr.updateAppWidget(minimal, minimalViews(ctx, s))
    }

    /**
     * Android 12+: hand the launcher every variant and let it pick by the widget's real size
     * (so resizing switches instantly). Older: pick from the size the launcher reports.
     */
    private fun sized(ctx: Context, mgr: AppWidgetManager, id: Int, s: BudsStatus, st: Style,
                      vertical: Boolean): RemoteViews {
        // Vertical widgets stack their buttons once tall enough; squashed flat they use the row.
        val tallShape = if (vertical) Shape.VERTICAL else Shape.TALL
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(
            SizeF(0f, 0f) to fullViews(ctx, s, st, Shape.ROW, wide = false),
            SizeF(WIDE_DP, 0f) to fullViews(ctx, s, st, Shape.ROW, wide = true),
            SizeF(0f, TALL_DP) to fullViews(ctx, s, st, tallShape, wide = false),
            SizeF(WIDE_DP, TALL_DP) to fullViews(ctx, s, st, tallShape, wide = true),
        ))
        val o = mgr.getAppWidgetOptions(id)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        return fullViews(ctx, s, st, if (h >= TALL_DP) tallShape else Shape.ROW, wide = w >= WIDE_DP)
    }

    private fun fullViews(ctx: Context, s: BudsStatus, st: Style, shape: Shape, wide: Boolean): RemoteViews {
        val tall = shape == Shape.TALL
        val v = RemoteViews(ctx.packageName, when (shape) {
            Shape.TALL -> R.layout.widget_tall
            Shape.ROW -> R.layout.widget_wide
            Shape.VERTICAL -> R.layout.widget_vertical
        })
        val live = s.connected
        v.setInt(R.id.w_root, "setBackgroundResource", st.bg)
        v.setTextViewText(R.id.w_title, s.name.removePrefix("Galaxy "))
        v.setTextColor(R.id.w_title, ctx.getColor(st.text))
        val l = batt(s.battL, s.placeL); val r = batt(s.battR, s.placeR)
        // Case battery only when the buds report it; otherwise leave it out entirely.
        val case = if (s.battCase in 1..100) "${s.battCase}%" else null
        v.setTextViewText(R.id.w_battery, when {
            !tall && live -> "L $l\nR $r" + (case?.let { "\nC $it" } ?: "")
            !tall && s.connecting -> "…"
            !tall -> ""
            live && wide -> "L $l   R $r" + (case?.let { "   Case $it" } ?: "")
            live -> "L $l  R $r"
            else -> statusLine(s)
        })
        v.setTextColor(R.id.w_battery, ctx.getColor(if (live) st.text else st.dim))
        // Row / vertical layout, buds not linked here: a quiet buds icon instead of battery text
        // (start drawable in the row's narrow column, top drawable in the vertical's full-width header).
        val icon = if (!tall && !live && !s.connecting) st.icon else 0
        if (shape == Shape.VERTICAL) v.setTextViewCompoundDrawables(R.id.w_battery, 0, icon, 0, 0)
        else v.setTextViewCompoundDrawables(R.id.w_battery, icon, 0, 0, 0)

        bindMode(ctx, v, R.id.w_anc, Protocol.MODE_ANC, s, st, tall, "ANC")
        bindMode(ctx, v, R.id.w_amb, Protocol.MODE_AMBIENT, s, st, tall, if (wide || shape == Shape.VERTICAL) "Ambient" else "Amb")
        bindMode(ctx, v, R.id.w_off, Protocol.MODE_OFF, s, st, tall, "Off")

        v.setTextViewText(R.id.w_reseat, when {
            !tall -> if (s.reseating) "…" else "↻"
            else -> ctx.getString(if (s.reseating) R.string.reseating else R.string.reseat_short)
        })
        v.setInt(R.id.w_reseat, "setBackgroundResource", if (tall) st.bar else st.offW)
        v.setTextColor(R.id.w_reseat, ctx.getColor(st.text))
        v.setFloat(R.id.w_reseat, "setAlpha", if (live) 1f else 0.4f)
        v.setOnClickPendingIntent(R.id.w_reseat, BudsService.pending(ctx, BudsService.ACTION_RESEAT))
        // One-row layout: the left column taps through WidgetTapActivity (tap = use the buds
        // here, double tap = open the app). Tall layout: tap refreshes / connects.
        v.setOnClickPendingIntent(R.id.w_battery,
            if (tall) BudsService.pending(ctx, BudsService.ACTION_CONNECT) else WidgetTapActivity.pending(ctx))
        v.setOnClickPendingIntent(R.id.w_title, openApp(ctx))
        return v
    }

    private fun bindMode(ctx: Context, v: RemoteViews, id: Int, mode: Int, s: BudsStatus, st: Style,
                         tall: Boolean, label: String) {
        val on = s.mode == mode
        val single = s.singleBud()
        v.setTextViewText(id, label)
        v.setInt(id, "setBackgroundResource", when {
            on && single -> if (tall) st.singleT else st.singleW
            on -> if (tall) st.onT else st.onW
            else -> if (tall) st.offT else st.offW
        })
        v.setTextColor(id, ctx.getColor(when { on && single -> st.onSingle; on -> st.onText; else -> st.text }))
        // Dim the buttons while the buds aren't linked to this device.
        v.setFloat(id, "setAlpha", if (s.connected) 1f else 0.55f)
        v.setOnClickPendingIntent(id, BudsService.pending(ctx, BudsService.ACTION_SET_MODE, mode))
    }

    private fun modeViews(ctx: Context, s: BudsStatus): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_mode)
        v.setTextViewText(R.id.m_mode, if (s.mode in 0..2) s.modeLabel() else "Buds")
        val low = listOf(s.battL to s.placeL, s.battR to s.placeR)
            .filter { it.first in 1..100 && it.second != 0 }.minOfOrNull { it.first }
        v.setTextViewText(R.id.m_batt, when {
            s.connected && low != null -> "$low%"
            s.connecting -> "…"
            s.connected -> ""
            else -> ctx.getString(R.string.offline_short)
        })
        val active = s.connected && s.mode != Protocol.MODE_OFF && s.mode >= 0
        val single = active && s.singleBud()
        v.setInt(R.id.m_root, "setBackgroundResource", when {
            single -> R.drawable.tile_single; active -> R.drawable.tile_on; else -> R.drawable.widget_bg
        })
        val fg = when { single -> R.color.on_single; active -> R.color.on_accent; else -> R.color.text }
        v.setTextColor(R.id.m_mode, ctx.getColor(fg))
        v.setTextColor(R.id.m_batt, ctx.getColor(if (active) fg else R.color.text_dim))
        v.setOnClickPendingIntent(R.id.m_root, BudsService.pending(ctx, BudsService.ACTION_CYCLE_MODE))
        return v
    }

    /** Minimal tile: no fill colour; the mode name is bright when active, faint when off. */
    private fun minimalViews(ctx: Context, s: BudsStatus): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_minimal)
        v.setTextViewText(R.id.m_mode, if (s.connected && s.mode in 0..2) s.modeLabel() else "Buds")
        val low = listOf(s.battL to s.placeL, s.battR to s.placeR)
            .filter { it.first in 1..100 && it.second != 0 }.minOfOrNull { it.first }
        v.setTextViewText(R.id.m_batt, when {
            s.connected && low != null -> "$low%"
            s.connecting -> "…"
            else -> ""
        })
        val active = s.connected && s.mode != Protocol.MODE_OFF && s.mode >= 0
        v.setTextColor(R.id.m_mode, ctx.getColor(when {
            active && s.singleBud() -> R.color.minimal_single
            active -> R.color.glass_text
            else -> R.color.glass_text_dim
        }))
        v.setOnClickPendingIntent(R.id.m_root, BudsService.pending(ctx, BudsService.ACTION_CYCLE_MODE))
        return v
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
