package com.kuek.budscontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Battery for L, R and case plus ANC / Ambient / Off buttons. */
class BudsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.updateAll(ctx, BudsStore.get(ctx))
}

/** 1x1 tile: shows the noise mode, tap to cycle ANC -> Ambient -> Off. */
class ModeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.updateAll(ctx, BudsStore.get(ctx))
}

object Widgets {

    fun batt(v: Int, place: Int): String =
        if (v in 1..100 && place != 0) "$v%" else "–"

    fun updateAll(ctx: Context, s: BudsStatus) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val full = mgr.getAppWidgetIds(ComponentName(ctx, BudsWidget::class.java))
        if (full.isNotEmpty()) mgr.updateAppWidget(full, fullViews(ctx, s))
        val mode = mgr.getAppWidgetIds(ComponentName(ctx, ModeWidget::class.java))
        if (mode.isNotEmpty()) mgr.updateAppWidget(mode, modeViews(ctx, s))
    }

    private fun fullViews(ctx: Context, s: BudsStatus): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_buds)
        val live = s.connected
        v.setTextViewText(R.id.w_title, s.name.removePrefix("Galaxy "))
        v.setTextViewText(R.id.w_battery, when {
            live -> "L ${batt(s.battL, s.placeL)}   R ${batt(s.battR, s.placeR)}   Case " +
                (if (s.battCase in 1..100) "${s.battCase}%" else "–")
            s.connecting -> ctx.getString(R.string.connecting)
            else -> ctx.getString(R.string.tap_to_connect)
        })
        v.setTextColor(R.id.w_battery, ctx.getColor(if (live) R.color.text else R.color.text_dim))

        bindMode(ctx, v, R.id.w_anc, Protocol.MODE_ANC, s)
        bindMode(ctx, v, R.id.w_amb, Protocol.MODE_AMBIENT, s)
        bindMode(ctx, v, R.id.w_off, Protocol.MODE_OFF, s)

        v.setTextViewText(R.id.w_reseat, ctx.getString(
            if (s.reseating) R.string.reseating else R.string.reseat_short))
        v.setOnClickPendingIntent(R.id.w_reseat, BudsService.pending(ctx, BudsService.ACTION_RESEAT))
        v.setOnClickPendingIntent(R.id.w_battery, BudsService.pending(ctx, BudsService.ACTION_CONNECT))
        v.setOnClickPendingIntent(R.id.w_title, openApp(ctx))
        return v
    }

    private fun bindMode(ctx: Context, v: RemoteViews, id: Int, mode: Int, s: BudsStatus) {
        val on = s.mode == mode
        val single = s.singleBud()
        v.setInt(id, "setBackgroundResource", when {
            on && single -> R.drawable.pill_single
            on -> R.drawable.pill_on
            else -> R.drawable.pill_off
        })
        v.setTextColor(id, ctx.getColor(when {
            on && single -> R.color.on_single
            on -> R.color.on_accent
            else -> R.color.text
        }))
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

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
