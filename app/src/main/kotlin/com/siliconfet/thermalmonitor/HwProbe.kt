package com.siliconfet.thermalmonitor

import com.siliconfet.thermalmonitor.TmConstants.logTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Descobre, no proprio aparelho, de onde sai cada dado.
 *
 * Roda dentro do processo privilegiado (root via libsu ou shell via Shizuku),
 * entao o que for legivel aqui e o que o overlay vai conseguir mostrar. Nada de
 * caminho fixo com endereco fisico: tudo vem de varredura, com tabelas do
 * [SocTable] so para desempatar o que o kernel nao diz.
 *
 * Mapa de IDs (os mesmos do app original, para nao mexer na interface):
 *
 *  CPUCL0..3  clusters de cpufreq, do menor (eficiencia) ao maior (prime)
 *  G3D        GPU Adreno (kgsl)
 *  DSU        subsistema da CPU: temp `cpuss-*`, freq do cache L3 (ou LLCC)
 *  MIF        memoria: temp `ddr`, freq da DDR (bus_dcvs)
 *  NPU0/NPU1  Hexagon NSP (HVX / HMX)
 *  NPUCON     bloco de video (`video`)
 *  ISP        camera (`camera-*`)
 */
internal class HwProbe(private val shell: ((String) -> String)?) {

    enum class Unit { KHZ, HZ, MHZ, AUTO }

    class Cluster(
        val index: Int,
        val policy: String,
        val cpus: IntArray,
        val maxKhz: Long,
        var part: String? = null
    )

    class FreqSrc(val nodeIds: IntArray, val unit: Unit)

    class HwMap {
        val nodes = ArrayList<SysNode>(96)
        var clusters: List<Cluster> = emptyList()
        val cpuToCluster = IntArray(MAX_CPUS) { -1 }
        /** id logico -> nos de temperatura (valor exibido = maximo). */
        val temps = LinkedHashMap<String, IntArray>()
        /** chave de frequencia do app -> fonte. */
        val freqs = LinkedHashMap<String, FreqSrc>()
        val freqNames = HashMap<String, String>()
        var gpuLoadNode = -1
        var gpuLoadIsPair = false
        var gpuMemNode = -1
        val volts = LinkedHashMap<String, Int>()
        var procStat: SysNode? = null
        var platform: String? = null
        var socModel: String? = null
        var gpuModel: String? = null
        var useHal = false
        val needRoot = ArrayList<String>()
        val zoneNames = LinkedHashMap<String, ArrayList<String>>()
    }

    private val m = HwMap()
    private val uid = try { android.os.Process.myUid() } catch (_: Throwable) { -1 }

    fun run(): HwMap {
        val t0 = System.nanoTime()
        readProps()
        probeClusters()
        probeCpuParts()
        probeThermal()
        probeGpu()
        probeBus()
        probeVolts()
        probeProcStat()
        logTrace {
            "probe ${(System.nanoTime() - t0) / 1_000_000}ms nodes=${m.nodes.size} " +
                "clusters=${m.clusters.size} temps=${m.temps.keys} freqs=${m.freqs.keys}"
        }
        return m
    }

    // ------------------------------------------------------------------ nodes

    /**
     * Registra um no se ele puder ser lido. Tenta direto; se o kernel/SELinux
     * negar e houver shell (so no modo root), testa pelo shell.
     */
    private fun node(path: String, needRootLabel: String? = null): Int {
        m.nodes.firstOrNull { it.path == path }?.let { return it.id }
        val n = SysNode(path)
        val buf = ByteArray(64)
        if (n.openDirect() && n.readRaw(buf) > 0) {
            n.access = SysNode.Access.DIRECT
        } else {
            n.close()
            val sh = shell
            if (sh != null && File(path).exists()) {
                val out = try { sh("v=; read -r v < $path 2>/dev/null; echo \"\$v\"") } catch (_: Exception) { "" }
                if (out.isNotBlank()) n.access = SysNode.Access.SHELL
            }
        }
        if (n.access == SysNode.Access.NONE) {
            if (needRootLabel != null && uid != 0 && SysNode.existsButDenied(path)) {
                if (needRootLabel !in m.needRoot) m.needRoot.add(needRootLabel)
            }
            return -1
        }
        n.id = m.nodes.size
        m.nodes.add(n)
        return n.id
    }

    private fun firstNode(label: String?, vararg paths: String): Int {
        var denied = false
        for (p in paths) {
            val id = node(p)
            if (id >= 0) return id
            if (!denied && uid != 0 && SysNode.existsButDenied(p)) denied = true
        }
        if (denied && label != null && label !in m.needRoot) m.needRoot.add(label)
        return -1
    }

    private fun text(path: String): String? {
        SysNode.readText(path)?.let { if (it.isNotEmpty()) return it }
        val sh = shell ?: return null
        return try {
            sh("v=; read -r v < $path 2>/dev/null; echo \"\$v\"").trim().ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun readProps() {
        val out = runCmd("getprop ro.board.platform; getprop ro.soc.model; getprop ro.hardware")
        val lines = out.lines().map { it.trim() }
        m.platform = lines.getOrNull(0)?.ifEmpty { null } ?: lines.getOrNull(2)?.ifEmpty { null }
        m.socModel = lines.getOrNull(1)?.ifEmpty { null }
            ?: SysNode.readText("/sys/devices/soc0/machine")
    }

    // --------------------------------------------------------------- clusters

    private fun probeClusters() {
        val dir = File("/sys/devices/system/cpu/cpufreq")
        val pols = (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("policy") && it.name.substring(6).toIntOrNull() != null }
        val list = ArrayList<Triple<String, IntArray, Long>>()
        for (p in pols) {
            val rel = text("${p.path}/related_cpus") ?: text("${p.path}/affected_cpus")
                ?: p.name.substring(6)
            val cpus = rel.split(' ', '\t').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 0 until MAX_CPUS }.sorted().toIntArray()
            if (cpus.isEmpty()) continue
            val max = text("${p.path}/cpuinfo_max_freq")?.let { SysNode.parseFirst(it) } ?: -1L
            list.add(Triple(p.path, cpus, max))
        }
        list.sortBy { it.second.first() }
        val out = ArrayList<Cluster>(list.size)
        for ((i, t) in list.withIndex()) {
            val idx = if (i < 4) i else 3      // o app tem 4 linhas; excedente cai no ultimo
            val c = Cluster(idx, t.first, t.second, t.third)
            out.add(c)
            for (cpu in c.cpus) m.cpuToCluster[cpu] = idx
        }
        m.clusters = out

        // Frequencia: cpuinfo_cur_freq e a leitura do hardware (so root no
        // qcom-cpufreq-hw, 0400); scaling_cur_freq e o pedido do governor e
        // qualquer processo le.
        val byIdx = HashMap<Int, ArrayList<Int>>()
        for (c in out) {
            val id = firstNode(null, "${c.policy}/cpuinfo_cur_freq", "${c.policy}/scaling_cur_freq")
            if (id >= 0) byIdx.getOrPut(c.index) { ArrayList() }.add(id)
        }
        for ((idx, ids) in byIdx) m.freqs["cpucl$idx"] = FreqSrc(ids.toIntArray(), Unit.KHZ)
    }

    private fun probeCpuParts() {
        val txt = try { File("/proc/cpuinfo").readText() } catch (_: Exception) { return }
        val impl = IntArray(MAX_CPUS) { -1 }
        val part = IntArray(MAX_CPUS) { -1 }
        var cur = -1
        for (line in txt.lineSequence()) {
            val k = line.substringBefore(':').trim()
            val v = line.substringAfter(':', "").trim()
            when (k) {
                "processor" -> cur = v.toIntOrNull() ?: -1
                "CPU implementer" -> if (cur in 0 until MAX_CPUS) impl[cur] = hex(v)
                "CPU part" -> if (cur in 0 until MAX_CPUS) part[cur] = hex(v)
            }
        }
        for (c in m.clusters) {
            val names = LinkedHashSet<String>()
            for (cpu in c.cpus) {
                val n = SocTable.corePartName(impl[cpu], part[cpu]) ?: continue
                names.add(n)
            }
            if (names.isNotEmpty()) {
                c.part = if (c.part == null) names.joinToString("/") else c.part + "/" + names.joinToString("/")
            }
        }
    }

    private fun hex(s: String): Int = try {
        if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toInt(16) else s.toInt()
    } catch (_: Exception) { -1 }

    // ---------------------------------------------------------------- thermal

    private fun probeThermal() {
        val base = File("/sys/class/thermal")
        val zones = (base.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("thermal_zone") }
            .sortedBy { it.name.substring(12).toIntOrNull() ?: 0 }

        // tipo de cada zona: direto, ou numa ida so ao shell
        val types = HashMap<String, String>()
        for (z in zones) SysNode.readText("${z.path}/type")?.let { types[z.path] = it }
        if (types.isEmpty() && shell != null && zones.isNotEmpty()) {
            val out = try {
                shell.invoke("for z in /sys/class/thermal/thermal_zone*; do t=; read t < \$z/type 2>/dev/null; echo \"\$z \$t\"; done")
            } catch (_: Exception) { "" }
            for (l in out.lineSequence()) {
                val sp = l.indexOf(' ')
                if (sp > 0) types[l.substring(0, sp)] = l.substring(sp + 1).trim()
            }
        }

        val table = SocTable.zoneCoreMap(m.platform)
        val lists = LinkedHashMap<String, ArrayList<Int>>()
        var cpuDenied = false
        for (z in zones) {
            val type = types[z.path] ?: continue
            val cls = classify(type) ?: continue
            val targets: List<String> = if (cls == CPU) {
                cpuTargets(z.path, type, table)
            } else listOf(cls)
            if (targets.isEmpty()) continue
            val id = node("${z.path}/temp")
            if (id < 0) {
                if (cls == CPU && SysNode.existsButDenied("${z.path}/temp")) cpuDenied = true
                continue
            }
            for (t in targets) {
                lists.getOrPut(t) { ArrayList() }.add(id)
                m.zoneNames.getOrPut(t) { ArrayList() }.add(type)
            }
        }

        // GPU: o kgsl ja entrega o maximo dos sensores da GPU num arquivo so.
        val kgslTemp = firstNode(null, "/sys/class/kgsl/kgsl-3d0/temp", "/sys/kernel/gpu/gpu_tmu")
        if (kgslTemp >= 0) {
            lists["G3D"] = arrayListOf(kgslTemp)
        }

        for ((k, v) in lists) m.temps[k] = v.distinct().toIntArray()

        val anyCpu = m.temps.keys.any { it.startsWith("CPUCL") }
        if (!anyCpu) {
            // Sem acesso ao sysfs termico (alguns fabricantes fecham no SELinux):
            // usa o Thermal HAL via `dumpsys thermalservice`, que o shell sempre le.
            m.useHal = true
            if (cpuDenied || zones.isNotEmpty()) {
                val lbl = "Temperaturas por sensor (usando Thermal HAL)"
                if (lbl !in m.needRoot && uid != 0) m.needRoot.add(lbl)
            }
        }
    }

    /**
     * Para qual(is) cluster(s) vai um sensor de CPU.
     *  1. cooling devices ligados a zona (cpu-hotplugN / thermal-pause-MASK)
     *  2. tabela da plataforma (extraida dos device trees)
     *  3. heuristica: grupo 0 = cluster de eficiencia, resto = clusters grandes
     */
    private fun cpuTargets(zonePath: String, type: String, table: Map<String, Int>): List<String> {
        val nClusters = m.clusters.size
        if (nClusters == 0) return listOf("CPUCL0")
        val cores = coresFromCdev(zonePath)
        val norm = normZone(type)
        val set = LinkedHashSet<Int>()
        if (cores.isNotEmpty()) {
            for (c in cores) m.cpuToCluster.getOrNull(c)?.let { if (it >= 0) set.add(it) }
        }
        if (set.isEmpty()) {
            table[norm]?.let { core -> m.cpuToCluster.getOrNull(core)?.let { if (it >= 0) set.add(it) } }
        }
        if (set.isEmpty()) {
            val g = CPU_GROUP.find(norm)
            val legacy = LEGACY_CPU.find(norm)
            when {
                legacy != null -> {
                    if (legacy.groupValues[2] == "silver") set.add(0)
                    else for (i in 1 until nClusters) set.add(m.clusters[i].index)
                }
                g != null -> {
                    val grp = g.groupValues[1].toInt()
                    if (grp == 0 || nClusters == 1) set.add(0)
                    else for (i in 1 until nClusters) set.add(m.clusters[i].index)
                }
                else -> for (c in m.clusters) set.add(c.index)   // sensor generico de CPU
            }
        }
        return set.map { "CPUCL$it" }
    }

    private fun coresFromCdev(zonePath: String): List<Int> {
        val entries = File(zonePath).list() ?: return emptyList()
        val out = ArrayList<Int>(2)
        for (e in entries) {
            if (!e.startsWith("cdev") || e.contains('_')) continue
            val type = SysNode.readText("$zonePath/$e/type") ?: continue
            val t = type.lowercase()
            PAUSE_MASK.find(t)?.let { mm ->
                val mask = mm.groupValues[1].toLongOrNull(16) ?: 0L
                for (b in 0 until MAX_CPUS) if (mask and (1L shl b) != 0L) out.add(b)
                return@let
            } ?: run {
                if (t.contains("cpu") && (t.contains("hotplug") || t.contains("pause") || t.contains("isolate"))) {
                    LAST_NUM.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { out.add(it) }
                }
            }
        }
        return out.distinct()
    }

    // -------------------------------------------------------------------- GPU

    private fun probeGpu() {
        val k = "/sys/class/kgsl/kgsl-3d0"
        m.gpuModel = SysNode.readText("$k/gpu_model") ?: SysNode.readText("/sys/kernel/gpu/gpu_model")

        // frequencia
        val cands = listOf(
            "$k/gpuclk" to Unit.HZ,
            "/sys/kernel/gpu/gpu_clock" to Unit.MHZ,
            "$k/clock_mhz" to Unit.MHZ,
            "$k/devfreq/cur_freq" to Unit.HZ
        )
        var gpuFreq: FreqSrc? = null
        var denied = false
        for ((p, u) in cands) {
            val id = node(p)
            if (id >= 0) { gpuFreq = FreqSrc(intArrayOf(id), u); break }
            if (uid != 0 && SysNode.existsButDenied(p)) denied = true
        }
        if (gpuFreq == null) {
            for (d in File("/sys/class/devfreq").listFiles() ?: emptyArray()) {
                val n = d.name.lowercase()
                if (!n.contains("kgsl") && !n.contains("gpu")) continue
                val id = node("${d.path}/cur_freq")
                if (id >= 0) { gpuFreq = FreqSrc(intArrayOf(id), Unit.AUTO); break }
            }
        }
        if (gpuFreq != null) m.freqs["gpu"] = gpuFreq
        else if (denied) m.needRoot.add("Frequência da GPU")

        // carga: porcentagem pronta ou o par "busy total" (este o shell le)
        var id = firstNode(null, "$k/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy")
        if (id >= 0) {
            m.gpuLoadNode = id; m.gpuLoadIsPair = false
        } else {
            id = node("$k/gpubusy", "Carga da GPU")
            if (id >= 0) { m.gpuLoadNode = id; m.gpuLoadIsPair = true }
        }

        // memoria alocada pelo driver
        m.gpuMemNode = node("/sys/class/kgsl/kgsl/page_alloc", "Memória da GPU")
    }

    // -------------------------------------------------------------------- bus

    private fun probeBus() {
        val bd = "/sys/devices/system/cpu/bus_dcvs"
        val l3 = node("$bd/L3/cur_freq")
        val llcc = node("$bd/LLCC/cur_freq")
        val ddr = node("$bd/DDR/cur_freq")
        if (l3 >= 0) {
            m.freqs["dsu"] = FreqSrc(intArrayOf(l3), Unit.KHZ); m.freqNames["dsu"] = "L3"
            if (llcc >= 0) { m.freqs["int"] = FreqSrc(intArrayOf(llcc), Unit.KHZ); m.freqNames["int"] = "LLCC" }
        } else if (llcc >= 0) {
            m.freqs["dsu"] = FreqSrc(intArrayOf(llcc), Unit.KHZ); m.freqNames["dsu"] = "LLCC"
        }
        if (ddr >= 0) { m.freqs["mif"] = FreqSrc(intArrayOf(ddr), Unit.KHZ); m.freqNames["mif"] = "DDR" }

        // Kernels antigos (4.19/5.4, ate o 888): sem bus_dcvs. O L3 aparece no
        // devfreq em Hz/kHz; a DDR so no debugfs (root).
        if (!m.freqs.containsKey("dsu")) {
            val ids = ArrayList<Int>()
            for (d in File("/sys/class/devfreq").listFiles() ?: emptyArray()) {
                val n = d.name.lowercase()
                if (n.contains("l3") && !n.contains("cdsp") && !n.contains("bw")) {
                    val id = node("${d.path}/cur_freq", "Frequência do L3")
                    if (id >= 0) ids.add(id)
                }
            }
            if (ids.isNotEmpty()) { m.freqs["dsu"] = FreqSrc(ids.toIntArray(), Unit.AUTO); m.freqNames["dsu"] = "L3" }
        }
        if (!m.freqs.containsKey("mif")) {
            val id = firstNode(if (uid != 0) "Frequência da DDR (debugfs)" else null,
                "/sys/kernel/debug/clk/measure_only_mccc_clk/clk_measure",
                "/sys/kernel/debug/clk/measure_only_mccc_clk/measure",
                "/sys/kernel/debug/clk/mc_cc_debug_mux/clk_measure")
            if (id >= 0) { m.freqs["mif"] = FreqSrc(intArrayOf(id), Unit.HZ); m.freqNames["mif"] = "DDR" }
        }
    }

    // ------------------------------------------------------------------ volts

    /**
     * No Snapdragon a tensao da CPU e controlada pelo CPR/firmware e nao aparece
     * como regulador. Pegamos so o que for identificavel pelo nome.
     */
    private fun probeVolts() {
        val dir = File("/sys/class/regulator")
        for (r in dir.listFiles() ?: emptyArray()) {
            val name = SysNode.readText("${r.path}/name")?.lowercase() ?: continue
            val zone = when {
                VOLT_GPU.containsMatchIn(name) -> "G3D"
                VOLT_MEM.containsMatchIn(name) -> "MIF"
                VOLT_CPUSS.containsMatchIn(name) -> "DSU"
                else -> null
            } ?: continue
            if (m.volts.containsKey(zone)) continue
            val id = node("${r.path}/microvolts")
            if (id >= 0) m.volts[zone] = id
        }
    }

    private fun probeProcStat() {
        val n = SysNode("/proc/stat")
        if (n.openDirect()) m.procStat = n
    }

    // ------------------------------------------------------------------- info

    /** Resumo para o app (aplicar perfil e mostrar na tela de configuracoes). */
    fun describe(): String {
        val o = JSONObject()
        o.put("uid", uid)
        o.put("platform", m.platform ?: "")
        o.put("socModel", m.socModel ?: "")
        o.put("gpuModel", m.gpuModel ?: "")
        val cl = JSONArray()
        for (c in m.clusters) {
            if (cl.length() > 0 && c.index == (cl.getJSONObject(cl.length() - 1).optInt("idx"))) {
                // clusters excedentes fundidos no ultimo
                val last = cl.getJSONObject(cl.length() - 1)
                last.put("n", last.getInt("n") + c.cpus.size)
                continue
            }
            cl.put(JSONObject().apply {
                put("idx", c.index)
                put("cpus", "${c.cpus.first()}-${c.cpus.last()}")
                put("n", c.cpus.size)
                put("maxMhz", if (c.maxKhz > 0) c.maxKhz / 1000 else -1)
                put("part", c.part ?: "")
            })
        }
        o.put("clusters", cl)
        o.put("temps", JSONObject().apply { for ((k, v) in m.temps) put(k, v.size) })
        o.put("tempNames", JSONObject().apply { for ((k, v) in m.zoneNames) put(k, v.joinToString(",")) })
        o.put("freqs", JSONObject().apply {
            for ((k, v) in m.freqs) put(k, v.nodeIds.map { m.nodes[it].access.name }.distinct().joinToString(","))
        })
        o.put("freqNames", JSONObject().apply { for ((k, v) in m.freqNames) put(k, v) })
        o.put("volts", JSONArray(m.volts.keys.toList()))
        o.put("gpuLoad", m.gpuLoadNode >= 0)
        o.put("gpuMem", m.gpuMemNode >= 0)
        o.put("cpuLoad", m.procStat != null)
        o.put("hal", m.useHal)
        o.put("needRoot", JSONArray(m.needRoot))
        return o.toString()
    }

    companion object {
        const val MAX_CPUS = 32
        const val CPU = "CPU"

        private val CPU_GROUP = Regex("^cpu-(\\d+)-(\\d+)(?:-(\\d+))?$")
        private val LEGACY_CPU = Regex("^cpu(\\d+)-(silver|gold|prime|gold-prime)$")
        private val GENERIC_CPU = Regex("^(cpu|cpu-?\\d+|cpu\\d+-thermal|cpu-thermal|cpu_therm|mtktscpu)$")
        private val PAUSE_MASK = Regex("pause-([0-9a-f]+)$")
        private val LAST_NUM = Regex("(\\d+)(?!.*\\d)")

        private val VOLT_GPU = Regex("(^|[_-])(gfx|gpu)")
        private val VOLT_MEM = Regex("(ddr|ebi|vdd_?mx$|^mx$)")
        private val VOLT_CPUSS = Regex("(apc|cpuss|vdd_?cpu)")

        fun normZone(type: String): String =
            type.lowercase().trim().removeSuffix("-usr")

        /**
         * Classifica um nome de zona termica (mesmo nome usado pelo Thermal HAL).
         * Devolve o id logico, [CPU] para sensor de nucleo, ou null para ignorar.
         */
        fun classify(type: String): String? {
            val n = normZone(type)
            if (n.isEmpty()) return null
            // duplicatas/virtuais: `-step` (lahaina), `max-step`, `lowf`, termistores de placa
            if (n.endsWith("-step") || n.contains("max-step") || n.contains("lowf") ||
                n.contains("therm") && !n.startsWith("cpu")) return null
            return when {
                CPU_GROUP.matches(n) || LEGACY_CPU.matches(n) || GENERIC_CPU.matches(n) -> CPU
                n.startsWith("cpuss") || n == "cpul3" || n.startsWith("cpullc") || n.startsWith("cpu-llc") -> "DSU"
                n.startsWith("gpu") -> if (n.contains("virt")) null else "G3D"
                n.startsWith("ddr") || n.startsWith("pop-mem") -> "MIF"
                n.startsWith("nsphmx") || n.startsWith("nspmx") || n == "npu" -> "NPU1"
                n.startsWith("nsphvx") || n.startsWith("nspvx") || n.startsWith("nspss") ||
                    n.startsWith("nsp-") || n.startsWith("q6-hvx") || n.startsWith("cdsp") ||
                    n.startsWith("compute-hvx") || n.startsWith("hvx") -> "NPU0"
                n.startsWith("video") || n.startsWith("vpu") -> "NPUCON"
                n.startsWith("camera") || n.startsWith("camss") || n == "cam" || n.startsWith("cam-") -> "ISP"
                else -> null
            }
        }

        fun runCmd(cmd: String): String = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (_: Exception) { "" }
    }
}
