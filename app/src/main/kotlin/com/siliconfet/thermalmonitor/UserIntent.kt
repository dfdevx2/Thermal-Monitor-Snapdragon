package com.siliconfet.thermalmonitor

import android.content.Context

internal object UserIntent {
    private const val PREFS = "user_intent_prefs"
    private const val KEY_RUNNING = "user_wants_running"

    fun setRunning(ctx: Context, running: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .apply()
        } catch (_: Exception) {}
    }

    fun isRunning(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)
        } catch (_: Exception) {
            false
        }
    }
}
