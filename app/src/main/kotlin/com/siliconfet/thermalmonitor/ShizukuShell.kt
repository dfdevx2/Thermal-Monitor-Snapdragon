package com.siliconfet.thermalmonitor

import android.content.Context
import android.util.Base64
import rikka.shizuku.Shizuku
import java.io.InputStream

object ShizukuShell {

    private const val KILL_SCRIPT_ASSET = "kill_zombies.sh"
    private const val KILL_SCRIPT_PATH = "/data/local/tmp/tmon_kill.sh"

    fun exec(cmd: String, timeoutMs: Long = 5000): String? {
        if (!isShizukuReady()) return null
        val argv = arrayOf("sh", "-c", cmd)
        val proc = newProcessReflected(argv) ?: return null
        return try {
            val stdout = readStdout(proc)
            val waitFor = proc.javaClass.methods.firstOrNull {
                it.name == "waitFor" && it.parameterTypes.isEmpty()
            }
            val t = Thread { try { waitFor?.invoke(proc) } catch (_: Exception) {} }
            t.isDaemon = true
            t.start()
            t.join(timeoutMs)
            if (t.isAlive) {
                try { proc.javaClass.getMethod("destroy").invoke(proc) } catch (_: Exception) {}
            }
            stdout
        } catch (_: Exception) { null }
    }

    fun killZombiesInline(): Boolean {
        if (!isShizukuReady()) return false
        val cmd = "pkill -9 -f '${BuildConfig.APPLICATION_ID}:cmd' 2>/dev/null; " +
                  "pkill -9 -f 'thermalmonitor.*cmd' 2>/dev/null; echo done"
        val out = exec(cmd, 5000) ?: return false
        return out.contains("done")
    }

    fun runKillScript(ctx: Context): Boolean {
        if (!isShizukuReady()) return false
        return try {
            copyAssetToTmp(ctx)
            val out = exec("chmod 755 $KILL_SCRIPT_PATH && $KILL_SCRIPT_PATH", 8000) ?: return false
            out.contains("killed")
        } catch (_: Exception) { false }
    }

    private fun isShizukuReady(): Boolean {
        return try { Shizuku.pingBinder() } catch (_: Exception) { false }
    }

    private fun newProcessReflected(argv: Array<String>): Any? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, argv, null, null)
        } catch (_: Exception) { null }
    }

    private fun readStdout(proc: Any): String {
        return try {
            val m = proc.javaClass.methods.firstOrNull {
                it.name == "getInputStream" && it.parameterTypes.isEmpty()
            } ?: return ""
            val stream = m.invoke(proc) as? InputStream ?: return ""
            stream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
    }

    private fun copyAssetToTmp(ctx: Context) {
        try {
            ctx.assets.open(KILL_SCRIPT_ASSET).use { input ->
                val content = input.bufferedReader().readText()
                val b64 = Base64.encodeToString(
                    content.toByteArray(Charsets.US_ASCII),
                    Base64.NO_WRAP
                )

                exec("echo '$b64' | base64 -d > $KILL_SCRIPT_PATH && chmod 755 $KILL_SCRIPT_PATH", 5000)
            }
        } catch (_: Exception) {}
    }
}
