package com.siliconfet.thermalmonitor

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ThermalHistory {

    private const val CAPACITY_SECONDS = 24 * 60 * 60
    private const val MAGIC = 0x544D4831
    private const val VERSION: Byte = 1
    private const val FILE_NAME = "thermal_history.bin"

    private val ZONE_IDS = ThermalReader.ALL_ZONE_IDS
    private val N_ZONES = ZONE_IDS.size

    private val timestamps: LongArray by lazy { LongArray(CAPACITY_SECONDS) }
    private val temps: Array<ByteArray> by lazy { Array(N_ZONES) { ByteArray(CAPACITY_SECONDS) } }
    private var head = 0
    private var size = 0

    private var lastSampleSec = 0L
    private var dirtyCount = 0
    private const val PERSIST_EVERY_N = 60

    private var historyFile: File? = null
    private var loaded = false

    /** Muda a cada amostra/limpeza: quem desenha so recalcula quando isto muda. */
    @Volatile var version = 0L
        private set

    private val persistExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tm-history-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private var serBuf = ByteArray(0)

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        historyFile = File(ctx.filesDir, FILE_NAME)
        loadFromDisk()
        loaded = true
    }

    @Synchronized
    fun addSample(tempLookup: (String) -> Int) {
        if (!loaded) return
        val nowSec = System.currentTimeMillis() / 1000L
        if (nowSec == lastSampleSec) return
        lastSampleSec = nowSec

        timestamps[head] = nowSec
        for (i in ZONE_IDS.indices) {
            val t = tempLookup(ZONE_IDS[i])
            temps[i][head] = t.coerceIn(0, 127).toByte()
        }
        head = (head + 1) % CAPACITY_SECONDS
        if (size < CAPACITY_SECONDS) size++
        version++

        dirtyCount++
        if (dirtyCount >= PERSIST_EVERY_N) {
            dirtyCount = 0
            persistAsync()
        }
    }

    /** Indice interno da zona, ou -1. Use com [readSeries]. */
    fun zoneIndex(zoneId: String): Int = ZONE_IDS.indexOf(zoneId)

    /**
     * Copia a serie de uma zona ja decimada para no maximo [maxPoints] pontos,
     * preservando o pico de cada balde (num grafico termico o pico e o que importa).
     *
     * Substitui o antigo getSamples24h(), que materializava ate 86.400 objetos
     * Sample, cada um com um HashMap de 10 entradas - dezenas de MB por redesenho,
     * refeitos a cada segundo porque o cache invalidava a cada amostra nova.
     * Aqui nada e alocado: os arrays de saida sao do chamador.
     *
     * @return quantidade de pontos escritos em [outTs]/[outT].
     */
    @Synchronized
    fun readSeries(zi: Int, maxPoints: Int, outTs: LongArray, outT: IntArray): Int {
        if (zi < 0 || zi >= N_ZONES || size == 0 || maxPoints <= 0) return 0
        val cap = minOf(maxPoints, outTs.size, outT.size)
        if (cap <= 0) return 0
        val cutoff = (System.currentTimeMillis() / 1000L) - CAPACITY_SECONDS
        val start = if (size < CAPACITY_SECONDS) 0 else head

        var valid = 0
        for (i in 0 until size) {
            if (timestamps[(start + i) % CAPACITY_SECONDS] >= cutoff) valid++
        }
        if (valid == 0) return 0

        val row = temps[zi]
        var n = 0
        var seen = 0
        var bucket = 0
        var bestT = -1
        var bestTs = 0L
        for (i in 0 until size) {
            val idx = (start + i) % CAPACITY_SECONDS
            val ts = timestamps[idx]
            if (ts < cutoff) continue
            val bk = ((seen.toLong() * cap) / valid).toInt().coerceAtMost(cap - 1)
            if (bk != bucket) {
                if (bestT >= 0 && n < cap) { outTs[n] = bestTs; outT[n] = bestT; n++ }
                bucket = bk; bestT = -1
            }
            val t = row[idx].toInt() and 0xFF
            if (t > bestT) { bestT = t; bestTs = ts }
            seen++
        }
        if (bestT >= 0 && n < cap) { outTs[n] = bestTs; outT[n] = bestT; n++ }
        return n
    }

    /** Primeiro e ultimo timestamp validos da janela, ou null se nao houver. */
    @Synchronized
    fun windowSec(): LongArray? {
        if (size == 0) return null
        val cutoff = (System.currentTimeMillis() / 1000L) - CAPACITY_SECONDS
        val start = if (size < CAPACITY_SECONDS) 0 else head
        var first = -1L
        var last = -1L
        for (i in 0 until size) {
            val ts = timestamps[(start + i) % CAPACITY_SECONDS]
            if (ts < cutoff) continue
            if (first < 0L) first = ts
            last = ts
        }
        return if (first < 0L) null else longArrayOf(first, last)
    }

    @Synchronized
    fun getHotspot24h(visibleZones: Set<String>): Hotspot? {
        if (size == 0) return null
        val cutoff = (System.currentTimeMillis() / 1000L) - CAPACITY_SECONDS
        var bestTemp = 0
        var bestZone = ""
        var bestTs = 0L
        val zoneIndices = ZONE_IDS.mapIndexedNotNull { i, id ->
            if (id in visibleZones) i to id else null
        }
        if (zoneIndices.isEmpty()) return null

        val start = if (size < CAPACITY_SECONDS) 0 else head
        for (i in 0 until size) {
            val idx = (start + i) % CAPACITY_SECONDS
            val ts = timestamps[idx]
            if (ts < cutoff) continue
            for ((zi, zid) in zoneIndices) {
                val t = (temps[zi][idx].toInt() and 0xFF)
                if (t > bestTemp) {
                    bestTemp = t; bestZone = zid; bestTs = ts
                }
            }
        }
        return if (bestTemp > 0) Hotspot(bestZone, bestTemp, bestTs) else null
    }

    @Synchronized
    fun sampleCount(): Int = size

    @Synchronized
    fun oldestAgeSec(): Long {
        if (size == 0) return 0
        val start = if (size < CAPACITY_SECONDS) 0 else head
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec - timestamps[start]
    }

    /**
     * Grava o que estiver pendente. A serializacao segura o lock (e so memoria),
     * mas a escrita em disco acontece na thread de I/O - antes eram ate 1,5 MB
     * escritos de forma sincrona no pool de agendamento e, no flush, na main thread.
     */
    fun flush() {
        val pending = synchronized(this) {
            if (!loaded || dirtyCount <= 0) false else { dirtyCount = 0; true }
        }
        if (!pending) return
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            persistExec.execute { try { persistToDisk() } finally { done.countDown() } }
            done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun persistAsync() {
        try { persistExec.execute { persistToDisk() } } catch (_: Exception) {}
    }

    @Synchronized
    fun clearAll() {
        head = 0; size = 0; dirtyCount = 0; lastSampleSec = 0L
        version++
        for (z in 0 until N_ZONES) for (i in 0 until CAPACITY_SECONDS) temps[z][i] = 0
        for (i in 0 until CAPACITY_SECONDS) timestamps[i] = 0L
        try { historyFile?.delete() } catch (_: Exception) {}
    }

    private fun persistToDisk() {
        val f = historyFile ?: return
        val bytes: ByteArray
        val len: Int
        synchronized(this) {
            len = serialize()
            bytes = serBuf
        }
        if (len <= 0) return
        try {
            FileOutputStream(f).use { fos -> fos.write(bytes, 0, len) }
        } catch (_: Exception) {}
    }

    /** Escreve o snapshot em [serBuf] (reaproveitado) e devolve o tamanho usado. */
    private fun serialize(): Int {
        var need = 4 + 1 + 1 + 4
        for (id in ZONE_IDS) need += 1 + id.length
        need += size * (8 + N_ZONES)
        if (serBuf.size < need) serBuf = ByteArray(need)
        val b = serBuf
        var o = 0

        fun put(v: Int) { b[o] = v.toByte(); o++ }
        fun i32(v: Int) {
            put(v ushr 24); put(v ushr 16); put(v ushr 8); put(v)
        }
        fun i64(v: Long) {
            for (k in 7 downTo 0) put((v ushr (k * 8)).toInt())
        }

        i32(MAGIC)
        put(VERSION.toInt())
        put(N_ZONES)
        for (id in ZONE_IDS) {
            val ba = id.toByteArray(Charsets.US_ASCII)
            put(ba.size)
            System.arraycopy(ba, 0, b, o, ba.size); o += ba.size
        }
        val start = if (size < CAPACITY_SECONDS) 0 else head
        i32(size)
        for (i in 0 until size) {
            val idx = (start + i) % CAPACITY_SECONDS
            i64(timestamps[idx])
            for (z in 0 until N_ZONES) { b[o] = temps[z][idx]; o++ }
        }
        return o
    }

    private fun loadFromDisk() {
        val f = historyFile ?: return
        if (!f.exists() || f.length() < 8) return
        try {
            FileInputStream(f).use { fis ->
                DataInputStream(fis.buffered()).use { dis ->
                    val magic = dis.readInt()
                    if (magic != MAGIC) return
                    val ver = dis.readByte()
                    if (ver != VERSION) return
                    val nZones = dis.readUnsignedByte()
                    val storedIds = ArrayList<String>(nZones)
                    for (i in 0 until nZones) {
                        val len = dis.readUnsignedByte()
                        val bytes = ByteArray(len); dis.readFully(bytes)
                        storedIds.add(String(bytes, Charsets.US_ASCII))
                    }

                    val mapping = IntArray(nZones) { i -> ZONE_IDS.indexOf(storedIds[i]) }

                    val n = dis.readInt()
                    val cutoff = (System.currentTimeMillis() / 1000L) - CAPACITY_SECONDS
                    for (i in 0 until n) {
                        val ts = dis.readLong()
                        val rowTemps = ByteArray(nZones)
                        for (z in 0 until nZones) rowTemps[z] = dis.readByte()
                        if (ts < cutoff) continue
                        timestamps[head] = ts
                        for (z in 0 until N_ZONES) temps[z][head] = 0
                        for (z in 0 until nZones) {
                            val target = mapping[z]
                            if (target >= 0) temps[target][head] = rowTemps[z]
                        }
                        head = (head + 1) % CAPACITY_SECONDS
                        if (size < CAPACITY_SECONDS) size++
                    }
                    if (size > 0) {
                        val lastIdx = (head - 1 + CAPACITY_SECONDS) % CAPACITY_SECONDS
                        lastSampleSec = timestamps[lastIdx]
                    }
                }
            }
        } catch (_: Exception) {}
    }

    data class Hotspot(val zoneId: String, val temp: Int, val tsSec: Long)
}
