package com.example.wgwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class WgWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.wgwidget.ACTION_REFRESH"
        const val SNAPSHOT_URL = "http://10.0.0.1:8088/api/snapshot"
        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        for (id in ids) refreshAsync(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, WgWidgetProvider::class.java)
            )
            for (id in ids) refreshAsync(context, mgr, id)
        }
    }

    private fun refreshAsync(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        executor.execute {
            val views = RemoteViews(context.packageName, R.layout.wg_widget)
            try {
                val conn = (URL(SNAPSHOT_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
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
                views.setTextViewText(R.id.wg_reset, "无法连接 (${e.javaClass.simpleName})")
                views.setTextViewText(R.id.wg_peers, "")
            }

            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, WgWidgetProvider::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wg_root, pi)

            mainHandler.post { mgr.updateAppWidget(widgetId, views) }
        }
    }
}
