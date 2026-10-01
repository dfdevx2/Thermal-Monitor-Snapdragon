package com.siliconfet.thermalmonitor

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {

            if (!UserIntent.isRunning(this)) {

                cancel(this)
                return false
            }
            if (!OverlayService.isRunning) {
                val intent = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (_: Exception) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {

        return false
    }

    companion object {
        private const val JOB_ID = 1742
        private const val INTERVAL_MS = 120 * 60_000L

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return

            if (js.allPendingJobs.any { it.id == JOB_ID }) return

            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setPersisted(false)
                .build()
            try { js.schedule(info) } catch (_: Exception) {}
        }

        fun cancel(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            try { js.cancel(JOB_ID) } catch (_: Exception) {}
        }
    }
}
