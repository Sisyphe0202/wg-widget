package com.example.wgwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class WgWidgetProvider : AppWidgetProvider() {

    companion object {
        const val SNAPSHOT_URL = "http://10.0.0.1:8088/api/snapshot"
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        AlarmReceiver.schedule(context)

        val pi = PendingIntent.getForegroundService(
            context, 0,
            Intent(context, RefreshService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.wg_widget)
            views.setOnClickPendingIntent(R.id.wg_root, pi)
            mgr.updateAppWidget(id, views)
        }
        runCatching {
            context.startForegroundService(Intent(context, RefreshService::class.java))
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AlarmReceiver.cancel(context)
    }
}
