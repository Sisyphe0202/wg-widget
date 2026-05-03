package com.example.wgwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // Primary: ForegroundService (works when ColorOS allows self-start)
            context.startForegroundService(Intent(context, RefreshService::class.java))
        } catch (e: Exception) {
            // Fallback: run directly in BroadcastReceiver window (no FGS needed)
            val pr = goAsync()
            Thread {
                runCatching { RefreshService.doRefresh(context) }
                pr.finish()
            }.start()
        }
    }

    companion object {
        private const val INTERVAL = 15 * 60 * 1000L  // 15 min

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = pi(context)
            am.cancel(pi)  // clear any previous alarm
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL,
                INTERVAL,
                pi
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pi(context))
        }

        private fun pi(context: Context) = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
