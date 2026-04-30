package com.example.wgwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
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
        private const val PREFS = "wg_state"
        private const val KEY_LAST_TOTAL = "last_total_bytes"
        private const val KEY_LAST_TS = "last_total_ts"
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

    private fun fmtGb(bytes: Long): String = "%.2f GB".format(bytes / 1_000_000_000.0)

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1_000_000_000.0)
        b >= 1_000_000 -> "%.0f MB".format(b / 1_000_000.0)
        b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
        else -> "$b B"
    }

    private fun trim(s: String, n: Int): String =
        if (s.length <= n) s else s.take(n - 1) + "…"

    private fun doRefresh() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, WgWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.wg_widget)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        try {
            val conn = (URL(WgWidgetProvider.SNAPSHOT_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val nowTs = root.optDouble("ts", System.currentTimeMillis() / 1000.0)

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
            val byPeer = root.optJSONObject("apps")?.optJSONObject("by_peer")

            var totalNow = 0L
            for (i in 0 until peers.length().coerceAtMost(2)) {
                val p = peers.getJSONObject(i)
                val ip = p.getString("ip")
                val label = p.getString("label")
                val online = p.getBoolean("online")
                val total = p.getLong("up") + p.getLong("down")
                totalNow += total
                val mark = if (online) "●" else "○"
                val peerLine = "$mark $label  ${fmtGb(total)}"

                var topApp = "→ —"
                byPeer?.optJSONArray(ip)?.takeIf { it.length() > 0 }?.let { arr ->
                    val a = arr.getJSONObject(0)
                    topApp = "→ ${trim(a.getString("app"), 10)} ${fmtBytes(a.getLong("bytes"))}"
                }

                if (i == 0) {
                    views.setTextViewText(R.id.wg_peer1, peerLine)
                    views.setTextViewText(R.id.wg_peer1_app, topApp)
                } else {
                    views.setTextViewText(R.id.wg_peer2, peerLine)
                    views.setTextViewText(R.id.wg_peer2_app, topApp)
                }
            }

            val lastTotal = prefs.getLong(KEY_LAST_TOTAL, 0L)
            val lastTs = prefs.getLong(KEY_LAST_TS, 0L) / 1000.0
            val dt = (nowTs - lastTs).coerceAtLeast(1.0)
            val deltaBytes = if (lastTotal > 0) (totalNow - lastTotal).coerceAtLeast(0L) else 0L
            val bps = if (lastTotal > 0) (deltaBytes / dt).toLong() else 0L

            prefs.edit()
                .putLong(KEY_LAST_TOTAL, totalNow)
                .putLong(KEY_LAST_TS, (nowTs * 1000).toLong())
                .apply()

            val dinoText = when {
                lastTotal == 0L -> "🦖    ___    🌵"
                bps < 1_000 -> "💤 🦖    🌵"
                bps < 100_000 -> "🦖💨    ___"
                bps < 1_000_000 -> "🦖💨💨    ___"
                bps < 10_000_000 -> "🦖💨💨💨"
                else -> "🦖💨💨💨💨💨"
            }
            views.setTextViewText(R.id.wg_dino, dinoText)

        } catch (e: Exception) {
            views.setTextViewText(R.id.wg_remaining, "—")
            views.setTextViewText(R.id.wg_budget, "")
            views.setProgressBar(R.id.wg_progress, 100, 0, false)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(80)
            views.setTextViewText(R.id.wg_reset, msg)
            views.setTextViewText(R.id.wg_peer1, "")
            views.setTextViewText(R.id.wg_peer1_app, "")
            views.setTextViewText(R.id.wg_peer2, "")
            views.setTextViewText(R.id.wg_peer2_app, "")
            views.setTextViewText(R.id.wg_dino, "🦖❌  连不上")
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
