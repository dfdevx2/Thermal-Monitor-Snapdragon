package com.siliconfet.thermalmonitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Perfil do aparelho.
 *
 * Duas etapas:
 *  1. [detectSoc]: nome do chip, sem privilegio nenhum (Build/getprop). Serve
 *     para o selo da tela inicial e o titulo do overlay.
 *  2. [onHwInfo]: o servidor (root/ADB) manda o mapa real (clusters, sensores,
 *     GPU, barramento). Na primeira vez em cada aparelho isso vira as
 *     configuracoes do overlay: nomes dos clusters, o que fica visivel, rotulos
 *     de frequencia. Depois disso o usuario personaliza a vontade; o perfil so
 *     e reaplicado se o hardware mudar ou pelo botao REMAPEAR.
 */
object HwProfile {

    private const val PREFS = "hw_prefs"
    private const val KEY_INFO = "hw_info"
    private const val KEY_SIG = "hw_sig_applied"

    data class Soc(val name: String, val model: String?, val platform: String?, val qualcomm: Boolean)

    @Volatile private var socCache: Soc? = null

    // ------------------------------------------------------------- SoC

    fun detectSoc(): Soc {
        socCache?.let { return it }
        val model = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            prop("ro.soc.model"),
            prop("ro.chipname"),
            cpuinfoHardware()
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals("unknown", true) }
        val platform = prop("ro.board.platform")?.ifBlank { null } ?: Build.BOARD
        val manuf = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "") ?: ""
        val name = SocTable.marketingName(model, platform)
            ?: model?.let { "Snapdragon ($it)" }
            ?: "Snapdragon"
        val qc = manuf.equals("QTI", true) || manuf.contains("qualcomm", true) ||
            model?.uppercase()?.let { it.startsWith("SM") || it.startsWith("SDM") || it.startsWith("QC") } == true ||
            Build.HARDWARE.equals("qcom", true)
        return Soc(name, model, platform, qc).also { socCache = it }
    }

    /** Ex.: "SNAPDRAGON 8 GEN 2" para o selo. */
    fun badge(): String = detectSoc().name.uppercase()

    /** Ex.: "QUALCOMM Snapdragon 8 Gen 2" para o titulo do overlay. */
    fun overlayTitle(): String = "QUALCOMM " + detectSoc().name

    private fun prop(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String)?.ifBlank { null }
    } catch (_: Throwable) { null }

    private fun cpuinfoHardware(): String? = try {
        java.io.File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(':')?.trim()
            ?.let { h -> Regex("(SM|SDM|QCS|QCM)\\d{3,4}").find(h.uppercase())?.value ?: h }
    } catch (_: Throwable) { null }

    // ------------------------------------------------------- mapa (servidor)

    fun cachedInfo(ctx: Context): JSONObject? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INFO, null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    /** Recebe o mapa do servidor; aplica o perfil se for aparelho/hardware novo. */
    fun onHwInfo(ctx: Context, json: String, force: Boolean = false) {
        val info = try { JSONObject(json) } catch (_: Exception) { return }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sig = signature(info)
        val old = sp.getString(KEY_SIG, null)
        sp.edit().putString(KEY_INFO, json).apply()
        if (force || old != sig) {
            apply(ctx, info)
            sp.edit().putString(KEY_SIG, sig).apply()
        }
    }

    /** Faz o proximo mapa recebido ser aplicado mesmo que o hardware seja o mesmo. */
    fun forgetApplied(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SIG).apply()
    }

    /** Reaplica sobre as preferencias atuais (ex.: depois de RESTAURAR PADRAO). */
    fun reapply(ctx: Context) {
        cachedInfo(ctx)?.let { apply(ctx, it) }
    }

    private fun signature(o: JSONObject): String = buildString {
        append(o.optString("socModel")).append('|').append(o.optString("platform")).append('|')
        append(o.optJSONArray("clusters")?.length() ?: 0).append('|')
        o.optJSONObject("temps")?.keys()?.asSequence()?.sorted()?.joinTo(this, ",")
        append('|')
        o.optJSONObject("freqs")?.keys()?.asSequence()?.sorted()?.joinTo(this, ",")
        append('|').append(o.optInt("uid", -1) == 0)
    }

    private fun clusterLabels(n: Int, parts: List<String>): List<String> = when (n) {
        1 -> listOf("CPU")
        2 -> if (parts.all { it.contains("ORYON") }) listOf("Perf", "Prime") else listOf("Little", "Big")
        3 -> listOf("Little", "Big", "Prime")
        else -> listOf("Little", "Mid", "Big", "Prime")
    }

    private fun apply(ctx: Context, info: JSONObject) {
        val sp = ctx.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val e = sp.edit()

        val temps = info.optJSONObject("temps") ?: JSONObject()
        // Com o Thermal HAL (sysfs fechado pelo fabricante) nao ha lista de
        // sensores, mas CPU e GPU chegam por ele.
        val hal = info.optBoolean("hal", false)
        fun hasTemp(z: String) = temps.has(z) || (hal && (z.startsWith("CPUCL") || z == "G3D"))
        val freqs = info.optJSONObject("freqs") ?: JSONObject()
        val fNames = info.optJSONObject("freqNames") ?: JSONObject()
        val tNames = info.optJSONObject("tempNames") ?: JSONObject()
        val volts = info.optJSONArray("volts")
        val voltSet = HashSet<String>().apply { if (volts != null) for (i in 0 until volts.length()) add(volts.getString(i)) }

        // -------- clusters
        val cl = info.optJSONArray("clusters")
        val n = cl?.length() ?: 0
        val parts = (0 until n).map { cl!!.getJSONObject(it).optString("part").uppercase() }
        val labels = clusterLabels(n, parts)
        for (i in 0 until 4) {
            val id = "CPUCL$i"
            val f = "cpucl$i"
            if (i < n) {
                val c = cl!!.getJSONObject(i)
                val part = c.optString("part").uppercase()
                val cores = c.optInt("n", 0)
                val nm = when {
                    part.isEmpty() -> "CPU ${c.optString("cpus")}"
                    part.contains("ORYON") -> "ORYON"
                    else -> "ARM $part"
                }
                e.putString("name_$id", nm)
                e.putString("label_$id", labels.getOrElse(i) { "CPU" } + if (cores > 0) " ×$cores" else "")
                e.putString("freq_label_$f", labels.getOrElse(i) { "CPU" })
                e.putBoolean("visible_$id", true)
                e.putBoolean("freq_visible_$f", freqs.has(f))
            } else {
                e.putBoolean("visible_$id", false)
                e.putBoolean("freq_visible_$f", false)
            }
            e.putBoolean("volt_visible_$id", id in voltSet)
        }

        // -------- GPU
        val gm = info.optString("gpuModel")
        val adreno = Regex("(\\d{3})").find(gm)?.value
        e.putString("name_G3D", if (adreno != null) "ADRENO $adreno" else "GPU")
        e.putString("label_G3D", "GPU")
        e.putBoolean("visible_G3D", hasTemp("G3D") || freqs.has("gpu") || info.optBoolean("gpuLoad"))
        e.putBoolean("freq_visible_gpu", freqs.has("gpu"))
        e.putBoolean("volt_visible_G3D", "G3D" in voltSet)
        e.putBoolean("gpumem_visible", info.optBoolean("gpuMem", false))

        // -------- barramento (DSU -> L3/LLCC, MIF -> DDR)
        val dsuName = fNames.optString("dsu").ifEmpty { "CPUSS" }
        e.putString("name_DSU", dsuName)
        e.putString("freq_label_dsu", dsuName)
        e.putBoolean("visible_DSU", hasTemp("DSU") || freqs.has("dsu"))
        e.putBoolean("freq_visible_dsu", freqs.has("dsu"))
        e.putString("name_MIF", "DDR")
        e.putString("freq_label_mif", "DDR")
        e.putBoolean("visible_MIF", hasTemp("MIF") || freqs.has("mif"))
        e.putBoolean("freq_visible_mif", freqs.has("mif"))
        e.putBoolean("volt_visible_DSU", "DSU" in voltSet)
        e.putBoolean("volt_visible_MIF", "MIF" in voltSet)
        if (freqs.has("int")) e.putString("freq_label_int", fNames.optString("int").ifEmpty { "LLCC" })
        e.putBoolean("freq_visible_int", false)   // opt-in, como no original

        // -------- aceleradores
        val npu0 = tNames.optString("NPU0")
        e.putString("name_NPU0", when {
            npu0.contains("hvx") && npu0.contains("nsp") -> "NSP HVX"
            npu0.contains("nsp") -> "HEXAGON NSP"
            else -> "HEXAGON"
        })
        e.putString("name_NPU1", if (tNames.optString("NPU1").contains("hmx")) "NSP HMX" else "NPU")
        e.putString("name_NPUCON", "VIDEO")
        e.putString("name_ISP", "ISP")
        for (z in listOf("NPU0", "NPU1", "NPUCON", "ISP")) {
            e.putBoolean("visible_$z", hasTemp(z))
            e.putString("label_$z", "-")
        }
        // Hexagon/video/ISP nao expoem frequencia no Snapdragon
        for (f in listOf("npu0", "npu1", "npucon", "isp", "disp", "icpu")) e.putBoolean("freq_visible_$f", false)
        for (z in listOf("NPU0", "NPU1")) e.putBoolean("volt_visible_$z", false)

        // -------- titulo (so se ainda for um padrao, nunca sobre um titulo do usuario)
        val t = sp.getString("title", null)
        if (t == null || t.isBlank() || t.contains("Exynos", true) || t.contains("EXYNOS") ||
            t.startsWith("QUALCOMM", true) || t.startsWith("SNAPDRAGON", true)) {
            e.putString("title", overlayTitle())
        }
        e.apply()
    }

    // ------------------------------------------------------------ resumo

    /** Texto para a tela de configuracoes. */
    fun summary(ctx: Context): String {
        val soc = detectSoc()
        val sb = StringBuilder()
        sb.append(soc.name)
        soc.model?.let { sb.append(" · ").append(it) }
        soc.platform?.let { sb.append(" · ").append(it) }
        val info = cachedInfo(ctx) ?: return sb.append("\n\nMapa ainda não feito: inicie o monitor uma vez (com root ou Shizuku).").toString()
        sb.append('\n').append(if (info.optInt("uid", -1) == 0) "Acesso: ROOT" else "Acesso: ADB (Shizuku)")
        val cl = info.optJSONArray("clusters")
        if (cl != null) {
            sb.append("\n\nCPU: ")
            for (i in 0 until cl.length()) {
                val c = cl.getJSONObject(i)
                if (i > 0) sb.append(" + ")
                sb.append(c.optInt("n")).append("× ").append(c.optString("part").ifEmpty { "?" })
                val mhz = c.optInt("maxMhz", -1)
                if (mhz > 0) sb.append(" @").append(mhz).append("MHz")
            }
        }
        info.optString("gpuModel").takeIf { it.isNotEmpty() }?.let { sb.append("\nGPU: ").append(it) }
        val temps = info.optJSONObject("temps")
        if (temps != null) {
            sb.append("\n\nSensores térmicos:")
            for (k in temps.keys()) sb.append("\n  ").append(k).append(": ").append(temps.optInt(k)).append(" sensor(es)")
        }
        if (info.optBoolean("hal")) sb.append("\n  (temperaturas pelo Thermal HAL)")
        val freqs = info.optJSONObject("freqs")
        if (freqs != null) {
            sb.append("\n\nFrequências: ")
            sb.append(freqs.keys().asSequence().joinToString(", "))
        }
        sb.append("\nCarga GPU: ").append(if (info.optBoolean("gpuLoad")) "sim" else "não")
        sb.append(" · Memória GPU: ").append(if (info.optBoolean("gpuMem")) "sim" else "não")
        val nr = info.optJSONArray("needRoot")
        if (nr != null && nr.length() > 0) {
            sb.append("\n\nSó com root:")
            for (i in 0 until nr.length()) sb.append("\n  • ").append(nr.getString(i))
        }
        return sb.toString()
    }

    // ----------------------------------------------- mapeamento na 1a abertura

    /**
     * Conecta rapidamente ao servidor so para buscar o mapa, sem iniciar o
     * overlay. Usado na primeira abertura do app para ja deixar tudo mapeado.
     * Usa uma conexao propria, que nao derruba a do overlay ao desconectar.
     */
    fun mapOnce(ctx: Context, backend: AccessManager.Backend, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, CommandService::class.java.name))
            .daemon(false).processNameSuffix("cmd").debuggable(BuildConfig.DEBUG)
            .version(TmConstants.USER_SERVICE_VERSION)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = binder?.let { ICommandService.Stub.asInterface(it) }
                val self = this
                Thread {
                    val info = try { svc?.exec("get:hwinfo") } catch (_: Exception) { null }
                    val ok = !info.isNullOrBlank()
                    if (ok) try { onHwInfo(app, info!!) } catch (_: Exception) {}
                    main.post {
                        if (!OverlayService.isRunning) {
                            try {
                                if (backend == AccessManager.Backend.ROOT) RootService.unbind(self)
                                else Shizuku.unbindUserService(args, self, true)
                            } catch (_: Exception) {}
                        }
                        if (!finished) { finished = true; done(ok) }
                    }
                }.apply { isDaemon = true; start() }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            when (backend) {
                AccessManager.Backend.ROOT -> RootService.bind(AccessManager.rootIntent(app), conn)
                AccessManager.Backend.SHIZUKU -> Shizuku.bindUserService(args, conn)
                AccessManager.Backend.NONE -> { done(false); return }
            }
        } catch (_: Exception) { done(false); return }
        main.postDelayed({ if (!finished) { finished = true; done(false) } }, 10_000L)
    }
}
