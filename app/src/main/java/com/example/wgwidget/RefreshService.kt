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
        private val executor = Executors.newSingleThreadExecutor()

        // brain-with-legs sprite colors
        private val B = Color.parseColor("#3FB950")  // brain body
        private val S = Color.parseColor("#1F6F2C")  // fold shadow
        private const val O = 0                      // transparent

        //   12 cols x 12 rows — brain bumps on top, two legs at bottom
        private val BRAIN = arrayOf(
            intArrayOf(O, O, B, B, O, B, B, O, B, B, O, O),  //  3 bumps
            intArrayOf(O, B, B, B, B, B, B, B, B, B, B, O),
            intArrayOf(B, B, B, B, B, B, B, B, B, B, B, B),
            intArrayOf(B, S, B, B, S, B, B, S, B, B, S, B),  //  folds
            intArrayOf(B, B, B, B, B, B, B, B, B, B, B, B),
            intArrayOf(B, S, B, B, S, B, B, S, B, B, S, B),  //  folds
            intArrayOf(B, B, B, B, B, B, B, B, B, B, B, B),
            intArrayOf(O, B, B, B, B, B, B, B, B, B, B, O),
            intArrayOf(O, O, B, B, B, B, B, B, B, B, O, O),
            intArrayOf(O, O, O, B, B, O, O, B, B, O, O, O),  //  legs
            intArrayOf(O, O, O, B, B, O, O, B, B, O, O, O),
            intArrayOf(O, O, O, B, B, O, O, B, B, O, O, O),
        )

        private fun renderPig(): Bitmap {
            val px = 8
            val bmp = Bitmap.createBitmap(BRAIN[0].size * px, BRAIN.size * px, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val paint = Paint().apply { isAntiAlias = false }
            for (y in BRAIN.indices) for (x in BRAIN[y].indices) {
                val c = BRAIN[y][x]
                if (c != O) {
                    paint.color = c
                    cv.drawRect((x * px).toFloat(), (y * px).toFloat(),
                        ((x + 1) * px).toFloat(), ((y + 1) * px).toFloat(), paint)
                }
            }
            return bmp
        }

        fun doRefresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WgWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.wg_widget)

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
            }

            views.setImageViewBitmap(R.id.wg_dino_image, renderPig())

            val pi = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, RefreshService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wg_root, pi)

            for (id in ids) mgr.updateAppWidget(id, views)
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
