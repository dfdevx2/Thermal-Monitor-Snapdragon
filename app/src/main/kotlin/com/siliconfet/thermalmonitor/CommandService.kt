package com.siliconfet.thermalmonitor

import com.siliconfet.thermalmonitor.TmConstants.logTrace
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Servidor de coleta. Roda num processo separado com privilegio:
 *  - modo ROOT: processo root criado pelo libsu (Magisk / KernelSU / APatch)
 *  - modo ADB : UserService do Shizuku (uid shell)
 *
 * O que mudou em relacao a versao Exynos:
 *  - Nenhum caminho fixo. O [HwProbe] varre o sysfs na partida e monta o mapa
 *    (clusters, zonas termicas, kgsl, bus_dcvs...). Cada no e testado: o que o
 *    processo consegue ler entra, o resto fica de fora sem custo.
 *  - Nada de `su` por tick. No modo root o processo JA e root, entao le tudo
 *    direto. O shell so e usado como plano B, no raro caso de um no que o
 *    kernel recusa ao processo mas aceita ao `sh` (o DEFEX da Samsung faz isso).
 *  - Uma thread so, com arquivos mantidos abertos ([SysNode]).
 */
class CommandService : ICommandService.Stub() {

    private val latestTemps = ConcurrentHashMap<String, Int>()
    private val latestFreqs = ConcurrentHashMap<String, Int>()
    private val latestCpuLoads = ConcurrentHashMap<String, Int>()
    private val latestDevfreqLoads = ConcurrentHashMap<String, Int>()
    private val latestVolts = ConcurrentHashMap<String, Int>()
    @Volatile private var latestGpuMemMb = -1

    private var pollThread: Thread? = null
    @Volatile private var streaming = false

    @Volatile var cpuFreqIntervalMs: Long = 900
    @Volatile var gpuFreqIntervalMs: Long = 900
    @Volatile var cpuLoadsIntervalMs: Long = 900
    @Volatile var gpuLoadIntervalMs: Long = 900
    @Volatile var gpuTempIntervalMs: Long = 1000
    @Volatile var cpuTempIntervalMs: Long = 1000
    @Volatile var npuIntervalMs: Long = 1000
    @Volatile var devfreqIntervalMs: Long = 1000
    @Volatile var gpuMemIntervalMs: Long = 1000

    private val uid = try { android.os.Process.myUid() } catch (_: Throwable) { -1 }
    private val isRoot get() = uid == 0

    @Volatile private var hw: HwProbe.HwMap? = null
    @Volatile private var hwInfo = ""
    private val probeLock = Any()

    @Volatile private var lastTempMs = 0L

    @Volatile var hotThresholdC = 95
    @Volatile private var hotCount = 0
    private var hotWasAbove = false
    private var hotLastMs = 0L

    /** O que o overlay exibe. Vazio = ainda nao informado, le tudo. */
    @Volatile private var visZones: Set<String> = emptySet()
    @Volatile private var visFreqs: Set<String> = emptySet()
    @Volatile private var visVolts: Set<String> = emptySet()

    // buffers reaproveitados: nada e alocado no laco de coleta
    private val readBuf = ByteArray(64)
    private val statBuf = ByteArray(4096)
    private var cpuCur = Array(MAX_CPUS) { LongArray(8) }
    private var cpuPrv = Array(MAX_CPUS) { LongArray(8) }
    private val clBusy = LongArray(4)
    private val clTotal = LongArray(4)
    private var haveCpuPrev = false

    /** Plano B por shell: so faz sentido no modo root (sh ja roda como root). */
    private val shellFn: ((String) -> String)? =
        if (isRoot) { cmd -> RootShell.exec(cmd) } else null

    // ------------------------------------------------------------------ AIDL

    override fun exec(command: String): String {
        if (command.startsWith(BULK_PREFIX)) {
            return bulk(command.substring(BULK_PREFIX.length).toIntOrNull() ?: 0)
        }
        if (command == "get:hwinfo") {
            ensureProbed()
            return hwInfo
        }
        if (command == "get:reprobe") {
            val wasStreaming = streaming
            if (wasStreaming) stopThermalStream()
            synchronized(probeLock) { hw?.nodes?.forEach { it.close() }; hw = null }
            ensureProbed()
            if (wasStreaming) startThermalStream()
            return hwInfo
        }
        if (command.startsWith("config:")) {
            val parts = command.removePrefix("config:").split(":", limit = 2)
            val v = parts.getOrNull(1)
            when (parts.getOrNull(0)) {
                "cpu_freq_interval"  -> cpuFreqIntervalMs  = (v?.toLongOrNull() ?: cpuFreqIntervalMs).coerceIn(100, 2000)
                "gpu_freq_interval"  -> gpuFreqIntervalMs  = (v?.toLongOrNull() ?: gpuFreqIntervalMs).coerceIn(100, 2000)
                "cpu_loads_interval" -> cpuLoadsIntervalMs = (v?.toLongOrNull() ?: cpuLoadsIntervalMs).coerceIn(100, 2000)
                "gpu_load_interval"  -> gpuLoadIntervalMs  = (v?.toLongOrNull() ?: gpuLoadIntervalMs).coerceIn(100, 2000)
                "gpu_temp_interval"  -> gpuTempIntervalMs  = (v?.toLongOrNull() ?: gpuTempIntervalMs).coerceIn(100, 5000)
                "cpu_temp_interval"  -> cpuTempIntervalMs  = (v?.toLongOrNull() ?: cpuTempIntervalMs).coerceIn(100, 1000)
                "hot_threshold"      -> hotThresholdC      = (v?.toIntOrNull() ?: hotThresholdC).coerceIn(50, 110)
                "npu_interval"       -> npuIntervalMs      = (v?.toLongOrNull() ?: npuIntervalMs).coerceIn(100, 5000)
                "devfreq_interval"   -> devfreqIntervalMs  = (v?.toLongOrNull() ?: devfreqIntervalMs).coerceIn(100, 5000)
                "gpu_mem_interval"   -> gpuMemIntervalMs   = (v?.toLongOrNull() ?: gpuMemIntervalMs).coerceIn(100, 5000)
                "zones" -> { visZones = csv(v); pruneHidden() }
                "freqs" -> { visFreqs = csv(v); pruneHidden() }
                "volts" -> { visVolts = csv(v); pruneHidden() }
            }
            return "OK"
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun csv(s: String?): Set<String> {
        if (s.isNullOrEmpty()) return emptySet()
        val out = HashSet<String>(16)
        for (t in s.split(',')) if (t.isNotEmpty()) out.add(t)
        return out
    }

    /** O que saiu de vista para de ser lido e some do cache (senao fica congelado). */
    private fun pruneHidden() {
        if (visZones.isNotEmpty()) latestTemps.keys.retainAll(visZones)
        if (visVolts.isNotEmpty()) latestVolts.keys.retainAll(visVolts)
        if (visFreqs.isNotEmpty()) latestFreqs.keys.retainAll(visFreqs)
    }

    private fun ensureProbed(): HwProbe.HwMap {
        hw?.let { return it }
        synchronized(probeLock) {
            hw?.let { return it }
            val p = HwProbe(shellFn)
            val m = p.run()
            hwInfo = p.describe()
            hw = m
            return m
        }
    }

    override fun startThermalStream() {
        if (streaming) return
        logTrace { "startThermalStream pid=${android.os.Process.myPid()} uid=$uid" }
        ensureProbed()
        streaming = true
        startPoller()
    }

    // --------------------------------------------------------------- coleta

    private fun visibleZone(id: String) = visZones.isEmpty() || id in visZones
    private fun visibleFreq(k: String) = visFreqs.isEmpty() || k in visFreqs
    private fun visibleVolt(k: String) = visVolts.isEmpty() || k in visVolts

    private fun startPoller() {
        pollThread = Thread {
            val m = hw ?: return@Thread
            val shellIds = ArrayList<Int>(16)
            val sb = StringBuilder(512)
            var dCpuF = 0L; var dGpuF = 0L; var dGpuL = 0L; var dGpuT = 0L
            var dTemp = 0L; var dDev = 0L; var dCpuL = 0L; var dMem = 0L; var dHal = 0L
            while (streaming) {
                val now = android.os.SystemClock.elapsedRealtime()
                val wCpuF = now >= dCpuF; if (wCpuF) dCpuF = now + cpuFreqIntervalMs
                val wGpuF = now >= dGpuF; if (wGpuF) dGpuF = now + gpuFreqIntervalMs
                val wGpuL = now >= dGpuL; if (wGpuL) dGpuL = now + gpuLoadIntervalMs
                val wGpuT = now >= dGpuT; if (wGpuT) dGpuT = now + gpuTempIntervalMs
                val wTemp = now >= dTemp; if (wTemp) dTemp = now + cpuTempIntervalMs
                val wDev  = now >= dDev;  if (wDev)  dDev  = now + devfreqIntervalMs
                val wCpuL = now >= dCpuL; if (wCpuL) dCpuL = now + cpuLoadsIntervalMs
                val wMem  = now >= dMem;  if (wMem)  dMem  = now + gpuMemIntervalMs
                val wHal  = m.useHal && now >= dHal
                if (wHal) dHal = now + maxOf(cpuTempIntervalMs, HAL_MIN_INTERVAL_MS)

                try {
                    // 1) leitura: diretos agora, os de shell numa ida so
                    shellIds.clear()
                    if (wCpuF) for (k in CPU_FREQ_KEYS) if (visibleFreq(k)) m.freqs[k]?.let { readAll(m, it.nodeIds, shellIds) }
                    if (wGpuF && visibleFreq("gpu")) m.freqs["gpu"]?.let { readAll(m, it.nodeIds, shellIds) }
                    if (wGpuL && m.gpuLoadNode >= 0) readOne(m, m.gpuLoadNode, shellIds)
                    if (wGpuT && visibleZone("G3D")) m.temps["G3D"]?.let { readAll(m, it, shellIds) }
                    if (wTemp) for ((z, ids) in m.temps) if (z != "G3D" && visibleZone(z)) readAll(m, ids, shellIds)
                    if (wDev) {
                        for (k in BUS_FREQ_KEYS) if (visibleFreq(k)) m.freqs[k]?.let { readAll(m, it.nodeIds, shellIds) }
                        for ((z, id) in m.volts) if (visibleVolt(z)) readOne(m, id, shellIds)
                    }
                    if (wMem && m.gpuMemNode >= 0) readOne(m, m.gpuMemNode, shellIds)
                    if (shellIds.isNotEmpty()) readViaShell(m, shellIds, sb)

                    // 2) valores
                    if (wCpuF) for (k in CPU_FREQ_KEYS) m.freqs[k]?.let { putFreq(m, k, it) }
                    if (wGpuF) m.freqs["gpu"]?.let { putFreq(m, "gpu", it) }
                    if (wGpuL && m.gpuLoadNode >= 0) putGpuLoad(m)
                    var peak = -1
                    if (wGpuT) m.temps["G3D"]?.let { peak = maxOf(peak, putTemp(m, "G3D", it)) }
                    if (wTemp) {
                        for ((z, ids) in m.temps) if (z != "G3D") peak = maxOf(peak, putTemp(m, z, ids))
                        if (!m.useHal) lastTempMs = System.currentTimeMillis()
                    }
                    if (wDev) {
                        for (k in BUS_FREQ_KEYS) m.freqs[k]?.let { putFreq(m, k, it) }
                        for ((z, id) in m.volts) {
                            val uv = m.nodes[id].v0
                            if (visibleVolt(z) && uv > 0) latestVolts[z] = uv.toInt() else latestVolts.remove(z)
                        }
                    }
                    if (wMem && m.gpuMemNode >= 0) {
                        val b = m.nodes[m.gpuMemNode].v0
                        latestGpuMemMb = if (b >= 0) (b / (1024 * 1024)).toInt() else -1
                    }
                    if (wHal) peak = maxOf(peak, sampleHal())
                    if (peak >= 0) applyPeak(peak, System.currentTimeMillis())
                    if (wCpuL) sampleCpuLoads(m)
                } catch (_: Throwable) {
                    // uma excecao aqui nao pode matar a coleta
                }

                var next = dCpuF
                if (dGpuF < next) next = dGpuF
                if (dGpuL < next) next = dGpuL
                if (dGpuT < next) next = dGpuT
                if (dTemp < next) next = dTemp
                if (dDev < next) next = dDev
                if (dCpuL < next) next = dCpuL
                if (dMem < next) next = dMem
                if (m.useHal && dHal < next) next = dHal
                val sleep = (next - android.os.SystemClock.elapsedRealtime()).coerceIn(5L, 1000L)
                try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "tm-poll"; isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun readAll(m: HwProbe.HwMap, ids: IntArray, shellIds: ArrayList<Int>) {
        for (id in ids) readOne(m, id, shellIds)
    }

    private fun readOne(m: HwProbe.HwMap, id: Int, shellIds: ArrayList<Int>) {
        val n = m.nodes[id]
        when (n.access) {
            SysNode.Access.DIRECT -> {
                if (id == m.gpuLoadNode && m.gpuLoadIsPair) {
                    if (!n.readPair(readBuf)) { n.v0 = -1; n.v1 = -1 }
                } else {
                    n.v0 = n.readLong(readBuf)
                }
            }
            SysNode.Access.SHELL -> shellIds.add(id)
            SysNode.Access.NONE -> n.v0 = -1
        }
    }

    /** Uma ida ao shell para todos os nos que so ele consegue ler. */
    private fun readViaShell(m: HwProbe.HwMap, ids: ArrayList<Int>, sb: StringBuilder) {
        sb.setLength(0)
        for (id in ids) {
            sb.append("v=; read -r v < ").append(m.nodes[id].path)
                .append(" 2>/dev/null; echo \"").append(id).append(" \$v\"\n")
        }
        for (id in ids) m.nodes[id].v0 = -1
        val out = try { RootShell.exec(sb.toString()) } catch (_: Exception) { "" }
        for (line in out.lineSequence()) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val id = line.substring(0, sp).toIntOrNull() ?: continue
            val n = m.nodes.getOrNull(id) ?: continue
            val rest = line.substring(sp + 1)
            if (id == m.gpuLoadNode && m.gpuLoadIsPair) {
                val b = rest.toByteArray()
                if (!n.parsePair(b, b.size)) { n.v0 = -1; n.v1 = -1 }
            } else {
                n.v0 = SysNode.parseFirst(rest)
            }
        }
    }

    private fun toMhz(raw: Long, unit: HwProbe.Unit): Int = when (unit) {
        HwProbe.Unit.KHZ -> (raw / 1000).toInt()
        HwProbe.Unit.HZ -> (raw / 1_000_000).toInt()
        HwProbe.Unit.MHZ -> raw.toInt()
        HwProbe.Unit.AUTO -> when {
            raw >= 100_000_000L -> (raw / 1_000_000).toInt()
            raw >= 100_000L -> (raw / 1000).toInt()
            else -> raw.toInt()
        }
    }

    private fun putFreq(m: HwProbe.HwMap, key: String, src: HwProbe.FreqSrc) {
        if (!visibleFreq(key)) { latestFreqs.remove(key); return }
        var best = -1L
        for (id in src.nodeIds) { val v = m.nodes[id].v0; if (v > best) best = v }
        when {
            best > 0L -> latestFreqs[key] = toMhz(best, src.unit)
            best == 0L -> latestFreqs[key] = FREQ_OFF
            else -> latestFreqs.remove(key)
        }
    }

    private fun putGpuLoad(m: HwProbe.HwMap) {
        val n = m.nodes[m.gpuLoadNode]
        val pct = if (m.gpuLoadIsPair) {
            if (n.v1 > 0) ((n.v0 * 100) / n.v1).toInt() else if (n.v1 == 0L) 0 else -1
        } else n.v0.toInt()
        if (pct >= 0) latestDevfreqLoads["g3d"] = pct.coerceIn(0, 100) else latestDevfreqLoads.remove("g3d")
    }

    /** Maior leitura valida entre os sensores da zona, em °C. Devolve o valor ou -1. */
    private fun putTemp(m: HwProbe.HwMap, zone: String, ids: IntArray): Int {
        if (!visibleZone(zone)) { latestTemps.remove(zone); return -1 }
        var best = -1
        for (id in ids) {
            val c = normTemp(m.nodes[id].v0)
            if (c > best) best = c
        }
        if (best > 0) latestTemps[zone] = best else latestTemps.remove(zone)
        return best
    }

    private fun normTemp(raw: Long): Int = when {
        raw >= 1000L -> (raw / 1000L).toInt().let { if (it in 1..150) it else -1 }
        raw in 1L..150L -> raw.toInt()
        else -> -1
    }

    /**
     * Plano C para temperatura: Thermal HAL. O shell sempre consegue ler o
     * `dumpsys thermalservice`, mesmo quando o fabricante fecha o sysfs.
     */
    private fun sampleHal(): Int {
        val out = exec("dumpsys thermalservice")
        val i = out.indexOf("Current temperatures from HAL")
        if (i < 0) return -1
        val acc = HashMap<String, Int>(16)
        val m = hw
        for (line in out.substring(i).lineSequence().drop(1)) {
            val t = line.trim()
            if (!t.startsWith("Temperature{")) { if (t.isEmpty() || t.endsWith(":")) break else continue }
            val value = HAL_VALUE.find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val type = HAL_TYPE.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val name = HAL_NAME.find(t)?.groupValues?.get(1) ?: ""
            val c = value.toInt()
            if (c !in 1..150) continue
            val targets: List<String> = when (val cls = HwProbe.classify(name)) {
                HwProbe.CPU -> halCpuTargets(name, m)
                null -> when (type) {
                    0 -> m?.clusters?.map { "CPUCL${it.index}" }?.distinct() ?: listOf("CPUCL0")
                    1 -> listOf("G3D")
                    9 -> listOf("NPU0")
                    13 -> listOf("DSU")
                    15 -> listOf("ISP")
                    else -> emptyList()
                }
                else -> listOf(cls)
            }
            for (z in targets) if ((acc[z] ?: -1) < c) acc[z] = c
        }
        var peak = -1
        for ((z, c) in acc) {
            if (m?.temps?.containsKey(z) == true) continue   // sysfs tem prioridade
            if (!visibleZone(z)) continue
            latestTemps[z] = c
            if (c > peak) peak = c
        }
        if (acc.isNotEmpty()) lastTempMs = System.currentTimeMillis()
        return peak
    }

    private fun halCpuTargets(name: String, m: HwProbe.HwMap?): List<String> {
        if (m == null || m.clusters.isEmpty()) return listOf("CPUCL0")
        val n = HwProbe.normZone(name)
        val core = SocTable.zoneCoreMap(m.platform)[n]
        if (core != null) {
            val cl = m.cpuToCluster.getOrNull(core) ?: -1
            if (cl >= 0) return listOf("CPUCL$cl")
        }
        val g = Regex("^cpu-(\\d+)-").find(n)?.groupValues?.get(1)?.toIntOrNull()
        return if (g == 0) listOf("CPUCL0")
        else m.clusters.drop(1).map { "CPUCL${it.index}" }.ifEmpty { listOf("CPUCL0") }.distinct()
    }

    /** Conta um evento por subida acima do limiar, com carencia. */
    private fun applyPeak(peak: Int, now: Long) {
        if (peak > hotThresholdC) {
            if (!hotWasAbove && now - hotLastMs >= HOT_COOLDOWN_MS) {
                hotCount++
                hotLastMs = now
                hotWasAbove = true
            }
        } else if (hotWasAbove) {
            hotWasAbove = false
        }
    }

    /**
     * Load por cluster a partir de /proc/stat, sem alocar nada. O shell (ADB)
     * e o root leem /proc/stat; um app comum nao (Android 8+).
     */
    private fun sampleCpuLoads(m: HwProbe.HwMap) {
        val node = m.procStat ?: return
        val n = node.readRaw(statBuf)
        if (n <= 0) return

        var seen = 0
        var i = 0
        while (i < n) {
            if (i + 4 > n) break
            if (statBuf[i] != C_c || statBuf[i + 1] != C_p || statBuf[i + 2] != C_u) break
            var j = i + 3
            if (j >= n || statBuf[j] < ZERO || statBuf[j] > NINE) {
                while (j < n && statBuf[j] != NL) j++
                i = j + 1
                continue
            }
            var cpuId = 0
            while (j < n && statBuf[j] >= ZERO && statBuf[j] <= NINE) {
                cpuId = cpuId * 10 + (statBuf[j] - ZERO); j++
            }
            if (cpuId >= MAX_CPUS) {
                while (j < n && statBuf[j] != NL) j++
                i = j + 1
                continue
            }
            val row = cpuCur[cpuId]
            var field = 0
            while (field < 8 && j < n) {
                while (j < n && statBuf[j] == SP) j++
                if (j >= n || statBuf[j] == NL) break
                var v = 0L
                var any = false
                while (j < n && statBuf[j] >= ZERO && statBuf[j] <= NINE) {
                    v = v * 10 + (statBuf[j] - ZERO); j++; any = true
                }
                if (!any) break
                row[field++] = v
            }
            val complete = field >= 5
            if (complete) while (field < 8) row[field++] = 0L
            while (j < n && statBuf[j] != NL) j++
            if (complete && cpuId + 1 > seen) seen = cpuId + 1
            i = j + 1
        }
        if (seen == 0) return

        if (haveCpuPrev) {
            java.util.Arrays.fill(clBusy, 0L)
            java.util.Arrays.fill(clTotal, 0L)
            for (id in 0 until seen) {
                val cl = m.cpuToCluster.getOrNull(id) ?: -1
                if (cl !in 0..3) continue
                val cur = cpuCur[id]; val prv = cpuPrv[id]
                var totalCur = 0L; var totalPrev = 0L
                for (k in 0 until 8) { totalCur += cur[k]; totalPrev += prv[k] }
                val total = totalCur - totalPrev
                if (total <= 0L) continue
                val idle = (cur[3] + cur[4]) - (prv[3] + prv[4])
                val busy = (total - idle).coerceAtLeast(0L)
                clBusy[cl] += busy
                clTotal[cl] += total
            }
            for (c in 0 until 4) {
                val t = clTotal[c]
                if (t > 0L) {
                    latestCpuLoads[CPU_FREQ_KEYS[c]] = ((clBusy[c] * 100L) / t).toInt().coerceIn(0, 100)
                }
            }
        }
        val swap = cpuPrv; cpuPrv = cpuCur; cpuCur = swap
        haveCpuPrev = true
    }

    // ----------------------------------------------------------------- bulk

    /**
     * Devolve num unico bloco tudo que o cliente pediu na mascara: uma chamada
     * binder por tick, com secoes marcadas por `#N` (mesmo protocolo de antes).
     */
    private fun bulk(mask: Int): String {
        val sb = StringBuilder(1024)
        if (mask and B_CPUFREQ != 0) { sb.append("#1\n"); appendFreqs(sb, CPU_FREQ_KEYS) }
        if (mask and B_GPUFREQ != 0) { sb.append("#2\n"); appendFreqs(sb, GPU_FREQ_KEYS) }
        if (mask and B_CPULOADS != 0) { sb.append("#3\n"); appendMap(sb, latestCpuLoads) }
        if (mask and B_GPULOAD != 0) { sb.append("#5\n"); appendMap(sb, latestDevfreqLoads) }
        if (mask and B_GPUTEMP != 0) {
            sb.append("#6\n")
            latestTemps["G3D"]?.let { sb.append("G3D ").append(it).append('\n') }
        }
        if (mask and B_TEMPS != 0) {
            sb.append("#7\n"); appendMap(sb, latestTemps)
            sb.append("#8\nhot ").append(hotCount).append('\n')
        }
        if (mask and B_DEVFREQS != 0) { sb.append("#9\n"); appendFreqs(sb, DEVFREQ_FREQ_KEYS) }
        if (mask and B_VOLTS != 0) { sb.append("#10\n"); appendMap(sb, latestVolts) }
        if (mask and B_NPU != 0) { sb.append("#11\n"); appendFreqs(sb, NPU_FREQ_KEYS) }
        if (mask and B_GPUMEM != 0) { sb.append("#12\ngpumem ").append(latestGpuMemMb).append('\n') }
        return sb.toString()
    }

    private fun appendFreqs(sb: StringBuilder, keys: Array<String>) {
        for (k in keys) sb.append(k).append(' ').append(latestFreqs[k] ?: -1).append('\n')
    }

    private fun appendMap(sb: StringBuilder, map: Map<String, Int>) {
        for ((k, v) in map) sb.append(k).append(' ').append(v).append('\n')
    }

    override fun getHealthStatus(): String {
        val m = hw
        val age = System.currentTimeMillis() - lastTempMs
        val tempSources = (m?.temps?.size ?: 0) + (if (m?.useHal == true) 1 else 0)
        return buildString {
            append("root="); append(isRoot); append('\n')
            append("su="); append(if (isRoot) "root" else "shell"); append('\n')
            append("rails="); append(m?.volts?.size ?: 0); append('\n')
            append("zones="); append(tempSources); append('\n')
            append("temp_age_ms="); append(if (lastTempMs == 0L) -1 else age); append('\n')
            append("volts="); append(latestVolts.size); append('\n')
            append("npu_nodes="); append(0); append('\n')
        }
    }

    override fun stopThermalStream() {
        if (!streaming) return
        streaming = false

        pollThread?.let { t ->
            t.interrupt()
            try { t.join(300) } catch (_: Exception) {}
        }
        pollThread = null

        latestTemps.clear(); latestFreqs.clear()
        latestCpuLoads.clear(); latestDevfreqLoads.clear()
        latestVolts.clear()
        latestGpuMemMb = -1
        haveCpuPrev = false
        hw?.nodes?.forEach { it.close() }   // reabrem sozinhos na proxima leitura

        // Sem isso, parar o monitor acima do limiar e religar ainda quente
        // deixava hotWasAbove=true e o primeiro evento nao era contado.
        hotWasAbove = false
        hotLastMs = 0L

        RootShell.shutdown()
    }

    override fun destroy() {
        stopThermalStream()
        exitProcess(0)
    }

    companion object {
        private val CPU_FREQ_KEYS = arrayOf("cpucl0", "cpucl1", "cpucl2", "cpucl3")
        private val BUS_FREQ_KEYS = arrayOf("dsu", "mif", "int", "isp", "disp", "icpu")

        const val BULK_PREFIX = "get:bulk:"
        const val B_CPUFREQ = 1
        const val B_GPUFREQ = 2
        const val B_CPULOADS = 4
        const val B_GPULOAD = 8
        const val B_GPUTEMP = 16
        const val B_TEMPS = 32
        const val B_DEVFREQS = 64
        const val B_VOLTS = 128
        const val B_NPU = 256
        const val B_GPUMEM = 512

        private const val MAX_CPUS = HwProbe.MAX_CPUS
        private const val HAL_MIN_INTERVAL_MS = 2000L

        private const val ZERO = '0'.code.toByte()
        private const val NINE = '9'.code.toByte()
        private const val SP = ' '.code.toByte()
        private const val NL = '\n'.code.toByte()
        private const val C_c = 'c'.code.toByte()
        private const val C_p = 'p'.code.toByte()
        private const val C_u = 'u'.code.toByte()

        private val GPU_FREQ_KEYS = arrayOf("gpu")
        private const val HOT_COOLDOWN_MS = 2000L

        /** Dominio desligado (power gating), distinto de "nao lido". */
        private const val FREQ_OFF = TmConstants.FREQ_OFF

        private val DEVFREQ_FREQ_KEYS = arrayOf("mif", "dsu", "int", "isp", "disp", "icpu")
        private val NPU_FREQ_KEYS = arrayOf("npu0", "npu1", "npucon")

        private val HAL_VALUE = Regex("mValue=([-0-9.]+)")
        private val HAL_TYPE = Regex("mType=(-?\\d+)")
        private val HAL_NAME = Regex("mName=([^,}]+)")
    }
}
