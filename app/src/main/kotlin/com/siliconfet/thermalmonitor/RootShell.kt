package com.siliconfet.thermalmonitor

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Shell persistente (root), alimentado por stdin com um marcador de fim.
 *
 * Um único processo `su` atende todos os pollers: o fork/exec por chamada
 * custava ~20 ms, e há vários pollers por segundo.
 *
 * O `su` é lançado SEM `-c` de propósito: o DEFEX filtra `open()` pelo `comm`
 * do processo, e `sh` passa onde `cat` é bloqueado.
 */
object RootShell {

    private const val PROBE_TIMEOUT_S = 5L
    private const val MARK = "__TM_DONE__"

    /**
     * Teto por comando. Nenhuma leitura de sysfs demora isso; se estourar,
     * o shell travou e é derrubado.
     */
    private const val READ_TIMEOUT_MS = 4000L

    /** Se o probe falhar, espera isso antes de tentar de novo. */
    private const val REPROBE_AFTER_MS = 30_000L

    /**
     * No porte Snapdragon este shell e so plano B dentro do processo root do
     * libsu: o processo ja e uid 0, entao basta `sh` (herda o root). Fora dele
     * (uid != 0) cai no `su` como no original.
     */
    private val CANDIDATES: Array<String> =
        if (try { android.os.Process.myUid() } catch (_: Throwable) { -1 } == 0)
            arrayOf("sh", "/system/bin/sh")
        else arrayOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su"
        )

    @Volatile private var rootOk = false
    @Volatile private var bin: String? = null
    @Volatile private var lastProbeMs = 0L
    @Volatile private var probed = false
    @Volatile private var shuttingDown = false

    private val lock = Any()

    private var proc: Process? = null
    private var sin: OutputStreamWriter? = null
    private var sout: BufferedReader? = null
    private var lines: LinkedBlockingQueue<String>? = null
    private var pump: Thread? = null

    fun binary(): String? = bin

    /**
     * Antes o resultado do probe era definitivo: se o `su` ainda não estivesse
     * autorizado na primeira tentativa, o app ficava sem root até reiniciar o
     * processo. Agora um probe negativo é reavaliado a cada [REPROBE_AFTER_MS].
     */
    fun isAvailable(): Boolean {
        if (rootOk) return true
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (rootOk) return true
            if (probed && now - lastProbeMs < REPROBE_AFTER_MS) return false
            lastProbeMs = now
            probed = true
            rootOk = probe()
            return rootOk
        }
    }

    private fun probe(): Boolean {
        for (c in CANDIDATES) {
            if (probeOne(c)) { bin = c; return true }
        }
        return false
    }

    private fun probeOne(candidate: String): Boolean {
        var p: Process? = null
        return try {
            p = ProcessBuilder(candidate, "-c", "id -u").start()
            try { p.outputStream.close() } catch (_: Exception) {}
            if (!p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                try { p.destroyForcibly() } catch (_: Exception) {}
                return false
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            out == "0"
        } catch (_: Exception) {
            false
        } finally {
            try { p?.errorStream?.close() } catch (_: Exception) {}
            try { p?.inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun shellAlive(): Boolean {
        val p = proc ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    private fun openShell(): Boolean {
        if (shuttingDown) return false
        if (shellAlive()) return true
        closeShell()
        val b = bin ?: return false
        return try {
            val pb = ProcessBuilder(b)
            pb.redirectErrorStream(false)
            val p = pb.start()
            proc = p
            sin = OutputStreamWriter(p.outputStream)
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            sout = reader
            val q = LinkedBlockingQueue<String>()
            lines = q

            // stdout vai para uma fila: assim exec() espera com prazo em vez de
            // ficar preso num readLine() que nenhum interrupt consegue cortar.
            pump = Thread {
                try {
                    while (true) {
                        val l = reader.readLine() ?: break
                        q.put(l)
                    }
                } catch (_: Exception) {
                } finally {
                    try { q.put(EOF) } catch (_: Exception) {}
                }
            }.apply { name = "tm-su-out"; isDaemon = true; start() }

            val err = p.errorStream
            Thread {
                try {
                    val buf = ByteArray(4096)
                    while (err.read(buf) > 0) { }
                } catch (_: Exception) {}
            }.apply { name = "tm-su-err"; isDaemon = true; start() }

            sin!!.write("exec 2>/dev/null\n")
            sin!!.flush()
            true
        } catch (_: Exception) {
            closeShell()
            false
        }
    }

    private fun closeShell() {
        try { sin?.close() } catch (_: Exception) {}
        try { sout?.close() } catch (_: Exception) {}
        try { proc?.destroyForcibly() } catch (_: Exception) {}
        try { pump?.interrupt() } catch (_: Exception) {}
        sin = null; sout = null; proc = null; pump = null; lines = null
    }

    /**
     * Derruba o shell sem esperar por quem estiver no meio de um [exec].
     *
     * Antes isto era `synchronized(this) { closeShell() }`: se um poller
     * estivesse bloqueado dentro de exec(), shutdown() ficava preso no mesmo
     * monitor — e como stopThermalStream() chama shutdown(), o serviço inteiro
     * travava numa chamada binder.
     */
    fun shutdown() {
        shuttingDown = true
        val p = proc
        val w = sin
        try { w?.close() } catch (_: Exception) {}
        try { p?.destroyForcibly() } catch (_: Exception) {}
        // Só aqui espera o lock: a esta altura quem estava em exec() já saiu,
        // porque o stdout dele chegou ao fim quando o processo foi derrubado.
        synchronized(lock) {
            closeShell()
            shuttingDown = false
        }
    }

    fun exec(cmd: String): String {
        if (!isAvailable()) return ""
        synchronized(lock) {
            val first = runOnShell(cmd)
            if (first != null) return first
            closeShell()
            return runOnShell(cmd) ?: ""
        }
    }

    private fun runOnShell(cmd: String): String? {
        if (!openShell()) return null
        val w = sin ?: return null
        val q = lines ?: return null
        return try {
            q.clear()
            w.write(cmd)
            w.write("\necho ")
            w.write(MARK)
            w.write("\n")
            w.flush()
            val sb = StringBuilder(256)
            val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
            while (true) {
                val waitMs = (deadline - System.nanoTime()) / 1_000_000L
                if (waitMs <= 0L) return null
                val line = q.poll(waitMs, TimeUnit.MILLISECONDS) ?: return null
                if (line === EOF) return null
                if (line == MARK) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Sentinela de fim de stream; comparada por identidade. */
    private val EOF = String(charArrayOf('\u0000', 'E', 'O', 'F'))

    /** Diagnóstico: o shell persistente está de pé? */
    fun isShellUp(): Boolean = synchronized(lock) { shellAlive() }

    /**
     * Lê um arquivo direto e, se não der, pelo shell root.
     * Usa `read` e não `cat`: o DEFEX bloqueia `open()` quando o `comm` é `cat`.
     */
    fun readTextOrRoot(path: String): String? {
        try {
            val f = File(path)
            if (f.canRead()) return f.readText()
        } catch (_: Exception) {}
        if (!isAvailable()) return null
        val out = exec("v=; read -r v < $path 2>/dev/null; echo \"\$v\"")
        return if (out.isBlank()) null else out
    }
}
