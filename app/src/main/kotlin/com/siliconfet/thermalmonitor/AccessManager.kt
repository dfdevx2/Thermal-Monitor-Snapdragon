package com.siliconfet.thermalmonitor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku

/** Configura o libsu antes de qualquer shell ser criado. */
class ThermalApp : Application() {
    companion object {
        init {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        }
    }
}

/**
 * Servico de coleta no modo ROOT: o mesmo [CommandService] do modo ADB, so que
 * rodando num processo root criado pelo libsu (funciona com Magisk, KernelSU,
 * KernelSU Next, APatch e qualquer `su` compativel).
 */
class RootCommandService : RootService() {
    private val impl by lazy { CommandService() }
    override fun onBind(intent: Intent): IBinder = impl
}

/**
 * Decide por onde a coleta roda.
 *
 *  ROOT    -> processo root (libsu). Le tudo: frequencia real da CPU, GPU, etc.
 *  SHIZUKU -> processo shell (ADB) via Shizuku. Le o que o SELinux libera para
 *             o shell; o resto aparece como "--".
 *
 * No modo automatico, root tem prioridade.
 */
object AccessManager {

    enum class Backend { ROOT, SHIZUKU, NONE }

    const val MODE_AUTO = "auto"
    const val MODE_ROOT = "root"
    const val MODE_SHIZUKU = "shizuku"

    private const val PREFS = "access_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_ROOT_SEEN = "root_seen"

    /** null = ainda nao perguntado nesta execucao. */
    @Volatile var rootGranted: Boolean? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(ctx: Context, m: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, m).apply()

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /** O app ja teve root concedido antes (para nao mostrar "sem root" a toa). */
    fun rootSeen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROOT_SEEN, false)

    /**
     * Pede o root (mostra o prompt do Magisk; no KernelSU/APatch o app precisa
     * estar liberado no gerenciador). Bloqueia: nao chamar na main thread.
     */
    fun probeRootBlocking(ctx: Context? = null): Boolean {
        val ok = try {
            var sh = Shell.getCachedShell()
            if (sh != null && !sh.isRoot) {
                // shell sem root em cache (negado antes): descarta e tenta de novo
                try { sh.close() } catch (_: Exception) {}
                sh = null
            }
            (sh ?: Shell.getShell()).isRoot
        } catch (_: Throwable) { false }
        rootGranted = ok
        if (ok && ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROOT_SEEN, true).apply()
        }
        return ok
    }

    fun probeRootAsync(ctx: Context, cb: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val ok = probeRootBlocking(app)
            main.post { cb(ok) }
        }.apply { name = "tm-su-probe"; isDaemon = true; start() }
    }

    /** Sem bloquear: usa o que ja se sabe. */
    fun resolve(ctx: Context): Backend {
        val root = rootGranted == true || (rootGranted == null && Shell.isAppGrantedRoot() == true)
        return pick(mode(ctx), root)
    }

    /** Pode pedir root (bloqueia). Usado pelo bind, fora da main thread. */
    fun resolveBlocking(ctx: Context): Backend {
        val m = mode(ctx)
        // Pergunta o su no maximo uma vez por processo: o bind e re-tentado a
        // cada segundo enquanto nao conecta, e cada tentativa de `su` negada
        // gera um aviso do Magisk/KernelSU.
        val root = if (m == MODE_SHIZUKU) false else when (rootGranted) {
            true -> true
            false -> false
            null -> probeRootBlocking(ctx)
        }
        return pick(m, root)
    }

    private fun pick(mode: String, root: Boolean): Backend = when (mode) {
        MODE_ROOT -> if (root) Backend.ROOT else Backend.NONE
        MODE_SHIZUKU -> if (shizukuGranted()) Backend.SHIZUKU else Backend.NONE
        else -> when {
            root -> Backend.ROOT
            shizukuGranted() -> Backend.SHIZUKU
            else -> Backend.NONE
        }
    }

    fun rootIntent(ctx: Context) = Intent(ctx, RootCommandService::class.java)
}
