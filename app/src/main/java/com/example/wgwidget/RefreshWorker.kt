package com.example.wgwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            RefreshService.doRefresh(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
