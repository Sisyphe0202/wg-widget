package com.example.wgwidget

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            applicationContext.startForegroundService(
                Intent(applicationContext, RefreshService::class.java)
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
