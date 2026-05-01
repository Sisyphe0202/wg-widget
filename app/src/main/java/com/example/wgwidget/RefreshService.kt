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

    private fun drawSprite(c: Canvas, grid: Array<String>, ox: Int, oy: Int, px: Int, paint: Paint) {
        for (y in grid.indices) {
            val row = grid[y]
            for (x in row.indices) {
                if (row[x] == 'X') {
                    val left = ((ox + x) * px).toFloat()
                    val top = ((oy + y) * px).toFloat()
                    c.drawRect(left, top, left + px, top + px, paint)
                }
            }
        }
    }

    private fun renderDinoScene(dinoSprite: Array<String>): Bitmap {
        val px = 3
        val gridW = 70
        val gridH = 23
        val w = gridW * px
        val h = gridH * px
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val green = Paint().apply {
            color = Color.parseColor("#3FB950")
            isAntiAlias = false
        }

        val dinoOy = gridH - dinoSprite.size - 1
        drawSprite(canvas, dinoSprite, 0, dinoOy, px, green)

        val cactus = Sprites.CACTUS_BIG
        val cactusOx = gridW - cactus[0].length - 4
        val cactusOy = gridH - cactus.size - 1
        drawSprite(canvas, cactus, cactusOx, cactusOy, px, green)

        val groundY = gridH - 1
        canvas.drawRect(0f, (groundY * px).toFloat(),
            (gridW * px).toFloat(), ((groundY + 1) * px).toFloat(), green)

        return bmp
    }

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
            views.setTextViewText(R.id.wg_reset, "Reset in ${days}d · Used ${pct}%")

            val peers = root.getJSONArray("peers")
            val byPeer = root.optJSONObject("apps")?.optJSONObject("by_peer")

            var totalNow = 0L
            var anyOnline = false
            for (i in 0 until peers.length().coerceAtMost(2)) {
                val p = peers.getJSONObject(i)
                val ip = p.getString("ip")
                val label = p.getString("label")
                val online = p.getBoolean("online")
                if (online) anyOnline = true
                val total = p.getLong("up") + p.getLong("down")
                totalNow += total
                val mark = if (online) "●" else "○"
                val displayLabel = when (label) {
                    "手机" -> "Phone"
                    "电脑" -> "PC"
                    else -> label
                }
                val labelLine = "$displayLabel $mark"
                val totalLine = fmtGb(total)

                var appName = "→ —"
                var appSize = ""
                byPeer?.optJSONArray(ip)?.takeIf { it.length() > 0 }?.let { arr ->
                    val a = arr.getJSONObject(0)
                    appName = "→ ${trim(a.getString("app"), 8)}"
                    appSize = "  ${fmtBytes(a.getLong("bytes"))}"
                }

                if (i == 0) {
                    views.setTextViewText(R.id.wg_peer1_label, labelLine)
                    views.setTextViewText(R.id.wg_peer1_total, totalLine)
                    views.setTextViewText(R.id.wg_peer1_app, appName)
                    views.setTextViewText(R.id.wg_peer1_app_size, appSize)
                } else {
                    views.setTextViewText(R.id.wg_peer2_label, labelLine)
                    views.setTextViewText(R.id.wg_peer2_total, totalLine)
                    views.setTextViewText(R.id.wg_peer2_app, appName)
                    views.setTextViewText(R.id.wg_peer2_app_size, appSize)
                }
            }

            if (anyOnline) {
                views.setImageViewBitmap(R.id.wg_dino_a, renderDinoScene(Sprites.DINO_RUN1))
                views.setImageViewBitmap(R.id.wg_dino_b, renderDinoScene(Sprites.DINO_RUN2))
            } else {
                val stand = renderDinoScene(Sprites.DINO_STAND)
                views.setImageViewBitmap(R.id.wg_dino_a, stand)
                views.setImageViewBitmap(R.id.wg_dino_b, stand)
            }

        } catch (e: Exception) {
            views.setTextViewText(R.id.wg_remaining, "—")
            views.setTextViewText(R.id.wg_budget, "")
            views.setProgressBar(R.id.wg_progress, 100, 0, false)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(80)
            views.setTextViewText(R.id.wg_reset, msg)
            views.setTextViewText(R.id.wg_peer1_label, "")
            views.setTextViewText(R.id.wg_peer1_total, "")
            views.setTextViewText(R.id.wg_peer1_app, "")
            views.setTextViewText(R.id.wg_peer1_app_size, "")
            views.setTextViewText(R.id.wg_peer2_label, "")
            views.setTextViewText(R.id.wg_peer2_total, "")
            views.setTextViewText(R.id.wg_peer2_app, "")
            views.setTextViewText(R.id.wg_peer2_app_size, "")
            val dead = renderDinoScene(Sprites.DINO_DEAD)
            views.setImageViewBitmap(R.id.wg_dino_a, dead)
            views.setImageViewBitmap(R.id.wg_dino_b, dead)
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
