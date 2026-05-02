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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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

        fun doRefresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WgWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.wg_widget)
            var anyOnline = false

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
                views.setTextViewText(R.id.wg_reset, "Reset in ${days}d · Used ${pct}%")

                val peers = root.getJSONArray("peers")
                val byPeer = root.optJSONObject("apps")?.optJSONObject("by_peer")

                for (i in 0 until peers.length().coerceAtMost(2)) {
                    val p = peers.getJSONObject(i)
                    val ip = p.getString("ip")
                    val label = p.getString("label")
                    val online = p.getBoolean("online")
                    if (online) anyOnline = true
                    val total = p.getLong("up") + p.getLong("down")
                    val mark = if (online) "●" else "○"
                    val displayLabel = when (label) {
                        "手机" -> "Phone"
                        "电脑" -> "PC"
                        else -> label
                    }

                    var appName = "→ —"
                    var appSize = ""
                    byPeer?.optJSONArray(ip)?.takeIf { it.length() > 0 }?.let { arr ->
                        val a = arr.getJSONObject(0)
                        appName = "→ ${trim(a.getString("app"), 8)}"
                        appSize = "  ${fmtBytes(a.getLong("bytes"))}"
                    }

                    if (i == 0) {
                        views.setTextViewText(R.id.wg_peer1_label, "$displayLabel $mark")
                        views.setTextViewText(R.id.wg_peer1_total, fmtGb(total))
                        views.setTextViewText(R.id.wg_peer1_app, appName)
                        views.setTextViewText(R.id.wg_peer1_app_size, appSize)
                    } else {
                        views.setTextViewText(R.id.wg_peer2_label, "$displayLabel $mark")
                        views.setTextViewText(R.id.wg_peer2_total, fmtGb(total))
                        views.setTextViewText(R.id.wg_peer2_app, appName)
                        views.setTextViewText(R.id.wg_peer2_app_size, appSize)
                    }
                }

                val state = if (anyOnline) "running" else "stand"
                views.setImageViewBitmap(R.id.wg_dino_image, renderClaudeFrame(state, true))

            } catch (e: Exception) {
                views.setTextViewText(R.id.wg_remaining, "—")
                views.setTextViewText(R.id.wg_budget, "")
                views.setProgressBar(R.id.wg_progress, 100, 0, false)
                views.setTextViewText(R.id.wg_reset,
                    "${e.javaClass.simpleName}: ${e.message ?: ""}".take(80))
                views.setTextViewText(R.id.wg_peer1_label, "")
                views.setTextViewText(R.id.wg_peer1_total, "")
                views.setTextViewText(R.id.wg_peer1_app, "")
                views.setTextViewText(R.id.wg_peer1_app_size, "")
                views.setTextViewText(R.id.wg_peer2_label, "")
                views.setTextViewText(R.id.wg_peer2_total, "")
                views.setTextViewText(R.id.wg_peer2_app, "")
                views.setTextViewText(R.id.wg_peer2_app_size, "")
                views.setImageViewBitmap(R.id.wg_dino_image, renderClaudeFrame("dead", false))
            }

            val pi = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, RefreshService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wg_root, pi)

            for (id in ids) mgr.updateAppWidget(id, views)

            if (anyOnline) {
                val frameOn  = renderClaudeFrame("running", true)
                val frameOff = renderClaudeFrame("running", false)
                for (i in 1..33) {
                    Thread.sleep(300)
                    // cursor on for 2 frames (600 ms), off for 2 frames — natural blink
                    val frame = if ((i / 2) % 2 == 0) frameOn else frameOff
                    val v = RemoteViews(context.packageName, R.layout.wg_widget)
                    v.setImageViewBitmap(R.id.wg_dino_image, frame)
                    for (id in ids) mgr.partiallyUpdateAppWidget(id, v)
                }
            }
        }

        private fun renderClaudeFrame(state: String, cursorOn: Boolean): Bitmap {
            val W = 560; val H = 108
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val tf = Typeface.MONOSPACE

            val bright = Paint().apply { color = Color.parseColor("#3FB950"); isAntiAlias = true; typeface = tf }
            val dim    = Paint().apply { color = Color.parseColor("#1F6F2C"); isAntiAlias = true; typeface = tf }
            val mid    = Paint().apply { color = Color.parseColor("#7EE787"); isAntiAlias = true; typeface = tf }

            // rounded border
            val border = Paint(dim).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
            cv.drawRoundRect(1f, 1f, W - 1f, H - 1f, 10f, 10f, border)

            // header  "◆ Claude Code"
            bright.textSize = 28f
            cv.drawText("◆ Claude Code", 14f, 34f, bright)

            // separator
            cv.drawRect(14f, 42f, W - 14f, 44f, dim)

            // prompt line
            val prm = Paint(bright).apply { textSize = 22f }
            val msg = Paint(mid).apply   { textSize = 22f }
            val promptStr  = "> "
            val messageStr = when (state) {
                "running" -> "Monitoring WireGuard"
                "dead"    -> "Connection error"
                else      -> "Idle"
            }
            cv.drawText(promptStr, 14f, 80f, prm)
            cv.drawText(messageStr, 14f + prm.measureText(promptStr), 80f, msg)

            // blinking cursor block
            if (cursorOn && state == "running") {
                val x = 14f + prm.measureText(promptStr) + msg.measureText(messageStr) + 5f
                cv.drawRect(x, 60f, x + 14f, 84f, bright)
            }

            return bmp
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
            doRefresh(this)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
