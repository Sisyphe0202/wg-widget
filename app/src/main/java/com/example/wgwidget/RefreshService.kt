package com.example.wgwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RefreshService : Service() {

    companion object {
        private const val CHANNEL_ID = "wg_refresh"
        private const val NOTIF_ID = 7
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WG 刷新", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WG 流量监控")
            .setContentText("刷新中…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(NOTIF_ID, notif)

        executor.execute {
            doRefresh()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun doRefresh() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, WgWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.wg_widget)
        try {
            val conn = (URL(WgWidgetProvider.SNAPSHOT_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val q = root.getJSONObject("quota")
            val remainGb = q.getLong("remaining") / 1_000_000_000.0
            val budgetGb = q.getLong("budget") / 1_000_000_000.0
            val usedGb = q.getLong("used") / 1_000_000_000.0
            val pct = if (budgetGb > 0)
                ((usedGb / budgetGb) * 100).toInt().coerceIn(0, 100) else 0
            val days = q.getLong("seconds_to_reset") / 86400

            views.setTextViewText(R.id.wg_remaining, "%.2f".format(remainGb))
            views.setTextViewText(R.id.wg_budget, "/ %.2f GB".format(budgetGb))
            views.setProgressBar(R.id.wg_progress, 100, pct, false)
            views.setTextViewText(R.id.wg_reset, "距重置 ${days} 天 · 已用 ${pct}%")

            val peers = root.getJSONArray("peers")
            val sb = StringBuilder()
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                val mark = if (p.getBoolean("online")) "●" else "○"
                sb.append(mark).append(" ").append(p.getString("label"))
                if (i < peers.length() - 1) sb.append("    ")
            }
            views.setTextViewText(R.id.wg_peers, sb.toString())
        } catch (e: Exception) {
            views.setTextViewText(R.id.wg_remaining, "—")
            views.setTextViewText(R.id.wg_budget, "")
            views.setProgressBar(R.id.wg_progress, 100, 0, false)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(80)
            views.setTextViewText(R.id.wg_reset, msg)
            views.setTextViewText(R.id.wg_peers, "点击重试")
        }

        val pi = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, RefreshService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.wg_root, pi)

        for (id in ids) mgr.updateAppWidget(id, views)
    }
}
