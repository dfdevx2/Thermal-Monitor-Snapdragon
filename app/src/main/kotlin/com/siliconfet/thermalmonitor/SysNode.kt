package com.siliconfet.thermalmonitor

import java.io.File
import java.io.RandomAccessFile

/**
 * Um arquivo de sysfs/procfs mantido ABERTO entre leituras.
 *
 * No port original cada leitura fazia open()+read()+close() (ou, pior, ia ao
 * shell `su`). Em sysfs, `lseek(0)` seguido de `read()` chama o show() do
 * driver de novo, entao da para reaproveitar o descritor: uma syscall a menos
 * por leitura e nenhum objeto alocado. Com ~30 sensores a cada tick isso e a
 * maior parte do custo de coleta.
 */
internal class SysNode(val path: String) {

    /** Como o no e lido. Decidido uma vez, no probe. */
    enum class Access { DIRECT, SHELL, NONE }

    @JvmField var access = Access.NONE
    @JvmField var id = -1

    /** Ultimo valor lido (ou -1). Para "busy total" guarda busy em [v0] e total em [v1]. */
    @JvmField var v0 = -1L
    @JvmField var v1 = -1L

    private var raf: RandomAccessFile? = null
    private var errors = 0

    fun openDirect(): Boolean {
        return try {
            val r = RandomAccessFile(path, "r")
            raf = r
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Le ate [buf].size bytes do inicio. Devolve a quantidade lida ou -1. */
    fun readRaw(buf: ByteArray): Int {
        var r = raf
        if (r == null) {
            if (errors > MAX_REOPEN) return -1
            if (!openDirect()) { errors++; return -1 }
            r = raf!!
        }
        return try {
            r.seek(0)
            val n = r.read(buf, 0, buf.size)
            if (n > 0) errors = 0
            n
        } catch (_: Exception) {
            // Sensor momentaneamente indisponivel (thermal zone desligada
            // devolve EINVAL/EAGAIN). Fecha e tenta reabrir na proxima; depois
            // de varias falhas seguidas o no deixa de ser lido.
            errors++
            close()
            -1
        }
    }

    /** Primeiro inteiro do arquivo (aceita sinal). -1 se nada. */
    fun readLong(buf: ByteArray): Long {
        val n = readRaw(buf)
        if (n <= 0) return -1L
        return parseFirst(buf, n)
    }

    /** Dois primeiros inteiros (ex.: kgsl `gpubusy` = "busy total"). */
    fun readPair(buf: ByteArray): Boolean {
        val n = readRaw(buf)
        if (n <= 0) return false
        return parsePair(buf, n)
    }

    fun parsePair(buf: ByteArray, n: Int): Boolean {
        var i = 0
        var count = 0
        var a = 0L; var b = 0L
        while (i < n && count < 2) {
            while (i < n && (buf[i] < ZERO || buf[i] > NINE)) i++
            if (i >= n) break
            var v = 0L
            while (i < n && buf[i] >= ZERO && buf[i] <= NINE) { v = v * 10 + (buf[i] - ZERO); i++ }
            if (count == 0) a = v else b = v
            count++
        }
        if (count < 2) return false
        v0 = a; v1 = b
        return true
    }

    fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
    }

    val isOpen: Boolean get() = raf != null

    companion object {
        private const val MAX_REOPEN = 50
        private const val ZERO = '0'.code.toByte()
        private const val NINE = '9'.code.toByte()
        private const val MINUS = '-'.code.toByte()

        fun parseFirst(buf: ByteArray, n: Int): Long {
            var i = 0
            while (i < n && buf[i] != MINUS && (buf[i] < ZERO || buf[i] > NINE)) i++
            if (i >= n) return -1L
            var neg = false
            if (buf[i] == MINUS) { neg = true; i++ }
            var v = 0L; var any = false
            while (i < n && buf[i] >= ZERO && buf[i] <= NINE) { v = v * 10 + (buf[i] - ZERO); i++; any = true }
            if (!any) return -1L
            return if (neg) -v else v
        }

        fun parseFirst(s: String): Long {
            val b = s.toByteArray()
            return parseFirst(b, b.size)
        }

        /** Texto curto de um arquivo (tipo de zona, nome de regulador...). */
        fun readText(path: String): String? = try {
            File(path).readText().trim()
        } catch (_: Exception) { null }

        /** Existe mas nao pode ser lido (EACCES/SELinux)? Usado para dizer "precisa de root". */
        fun existsButDenied(path: String): Boolean {
            val f = File(path)
            if (!f.exists()) return false
            return try { RandomAccessFile(f, "r").use { it.read() }; false } catch (_: Exception) { true }
        }
    }
}
