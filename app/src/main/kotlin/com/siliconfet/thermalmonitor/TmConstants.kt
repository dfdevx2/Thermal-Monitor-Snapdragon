package com.siliconfet.thermalmonitor

import android.util.Log

internal object TmConstants {

    const val DEBUG = false

    const val USER_SERVICE_VERSION = 63

    /** Frequencia de um dominio desligado (power gating). Distinto de "nao lido". */
    const val FREQ_OFF = -2

    const val TAG_BIND = "TM_BIND"
    const val TAG_HEALTH = "TM_HEALTH"
    const val TAG_TRACE = "TM_TRACE"

    const val BIND_RETRY_BACKOFF_MS = 2_000L
    const val BIND_RETRY_AFTER_REJECT_MS = 1_500L
    const val BIND_TIMEOUT_MS = 5_000L
    const val BIND_DEATH_REBIND_DELAY_MS = 1_000L
    const val BIND_KILL_ZOMBIE_GRACE_MS = 200L
    const val BIND_MAX_ATTEMPTS = 5

    const val WATCHDOG_STALE_MS = 15_000L
    const val WATCHDOG_COOLDOWN_MS = 20_000L
    const val UPDATE_FAIL_THRESHOLD = 5

    const val EMPTY_SNAPSHOT_LIMIT = 8

    const val NUKE_TIMEOUT_MS = 30_000L

    const val FAST_INTERVAL_DEFAULT_MS = 1000
    const val DRAG_INVALIDATE_MS = 250L

    const val LOADS_INTERVAL_MS = 1000L

    inline fun logBind(msg: () -> String) {
        if (DEBUG) try { Log.i(TAG_BIND, msg()) } catch (_: Exception) {}
    }
    inline fun logHealth(msg: () -> String) {
        if (DEBUG) try { Log.i(TAG_HEALTH, msg()) } catch (_: Exception) {}
    }
    inline fun logTrace(msg: () -> String) {
        if (DEBUG) try { Log.i(TAG_TRACE, msg()) } catch (_: Exception) {}
    }
}
