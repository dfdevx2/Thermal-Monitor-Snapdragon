package com.siliconfet.thermalmonitor

import android.content.Context
import android.graphics.Color

object OverlayPrefs {
    private const val PREFS = "overlay_prefs"

    val DEFAULT_NAMES = linkedMapOf(
        "CPUCL3" to "CPUCL3", "CPUCL2" to "CPUCL2", "CPUCL1" to "CPUCL1",
        "CPUCL0" to "CPUCL0", "DSU" to "DSU", "MIF" to "MIF",
        "G3D" to "G3D", "NPU0" to "NPU0", "NPU1" to "NPU1", "NPUCON" to "NPUCON", "ISP" to "ISP"
    )
    val DEFAULT_LABELS = linkedMapOf(
        "CPUCL3" to "Prime", "CPUCL2" to "Big", "CPUCL1" to "Mid",
        "CPUCL0" to "Little", "DSU" to "L3", "MIF" to "Memory",
        "G3D" to "GPU", "NPU0" to "Hexagon", "NPU1" to "NPU", "NPUCON" to "Vídeo", "ISP" to "Camera"
    )
    val SECTION_MAP = linkedMapOf(
        "CPU CLUSTERS" to listOf("CPUCL3", "CPUCL2", "CPUCL1", "CPUCL0"),
        "ACCELERATORS" to listOf("G3D", "NPU0", "NPU1", "NPUCON"),
        "SUBSISTEMAS"  to listOf("ISP")
    )

    /**
     * Dominios de barramento: nao tem linha propria: ocupam a metade direita
     * do bloco de memoria, ao lado das barras de RAM/SWAP.
     * Saiu da secao SHARED UNITS, que deixou de existir.
     */
    val BUS_ZONES = listOf("DSU", "MIF")

    /** Todas as zonas desenhaveis, na ordem: secoes + barramento. */
    fun allDrawableZones(): List<String> = SECTION_MAP.values.flatten() + BUS_ZONES
    val DEFAULT_BATT_LABELS = linkedMapOf(
        "power" to "Potência", "batt_temp" to "Temp. Bateria",
        "cycles" to "Ciclos", "max_temp" to "Temp. Máx. Registrada",
        "first_use" to "Primeiro Uso", "charge_limit" to "Limite de Carga"
    )
    val KNOWN_FREQ_IDS = linkedMapOf(
        "cpucl3" to "CPUCL3", "cpucl2" to "CPUCL2", "cpucl1" to "CPUCL1", "cpucl0" to "CPUCL0",
        "gpu" to "G3D", "dsu" to "DSU", "mif" to "MIF",
        "npu0" to "NPU0", "npu1" to "NPU1", "npucon" to "NPUCON", "isp" to "ISP",
        "disp" to "DISP", "int" to "INT", "icpu" to "ICPU"
    )

    val MEMORY_FREQ_IDS = setOf("dsu", "mif", "npu0", "npu1", "npucon", "isp", "disp", "int", "icpu")

    val LIGHT_FREQ_IDS = setOf("cpucl0", "cpucl1", "cpucl2", "cpucl3", "gpu")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMainOverlayEnabled(ctx: Context) = p(ctx).getBoolean("panel_main", true)
    fun setMainOverlayEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("panel_main", v).apply()
    fun isDevfreqOverlayEnabled(ctx: Context) = p(ctx).getBoolean("panel_devfreq", false)
    fun setDevfreqOverlayEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("panel_devfreq", v).apply()
    fun isBatteryOverlayEnabled(ctx: Context) = p(ctx).getBoolean("panel_battery", false)
    fun setBatteryOverlayEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("panel_battery", v).apply()
    fun isHistoryOverlayEnabled(ctx: Context) = p(ctx).getBoolean("panel_history", false)
    fun setHistoryOverlayEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("panel_history", v).apply()

    fun defaultTitle(): String = HwProfile.overlayTitle()
    fun getTitle(ctx: Context): String = p(ctx).getString("title", null) ?: defaultTitle()
    fun setTitle(ctx: Context, v: String) = p(ctx).edit().putString("title", v.ifBlank { defaultTitle() }).apply()
    fun getDevfreqTitle(ctx: Context): String = p(ctx).getString("title_devfreq", "FREQUÊNCIAS") ?: "FREQUÊNCIAS"
    fun setDevfreqTitle(ctx: Context, v: String) = p(ctx).edit().putString("title_devfreq", v.ifBlank { "FREQUÊNCIAS" }).apply()
    fun getBatteryTitle(ctx: Context): String = p(ctx).getString("title_battery", "BATERIA") ?: "BATERIA"
    fun setBatteryTitle(ctx: Context, v: String) = p(ctx).edit().putString("title_battery", v.ifBlank { "BATERIA" }).apply()
    fun getHistoryTitle(ctx: Context): String = p(ctx).getString("title_history", "HISTÓRICO 24H") ?: "HISTÓRICO 24H"
    fun setHistoryTitle(ctx: Context, v: String) = p(ctx).edit().putString("title_history", v.ifBlank { "HISTÓRICO 24H" }).apply()

    fun getControlSize(ctx: Context): Float = p(ctx).getFloat("control_size", 1.0f)
    fun setControlSize(ctx: Context, v: Float) = p(ctx).edit().putFloat("control_size", v.coerceIn(0.5f, 3.0f)).apply()

    fun getTitleFontScale(ctx: Context): Float = p(ctx).getFloat("fs_title", 1.0f)
    fun setTitleFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fs_title", v.coerceIn(0.5f, 2.5f)).apply()
    fun getValueFontScale(ctx: Context): Float = p(ctx).getFloat("fs_value", 1.0f)
    fun setValueFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fs_value", v.coerceIn(0.5f, 2.5f)).apply()
    fun getLabelFontScale(ctx: Context): Float = p(ctx).getFloat("fs_label", 1.0f)
    fun setLabelFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fs_label", v.coerceIn(0.5f, 2.5f)).apply()
    fun getChartFontScale(ctx: Context): Float = p(ctx).getFloat("fs_chart", 1.0f)
    fun setChartFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fs_chart", v.coerceIn(0.5f, 2.5f)).apply()

    const val DEFAULT_OVERLAY_SCALE = 2.0f

    fun getDisplayName(ctx: Context, id: String): String = p(ctx).getString("name_$id", id) ?: id
    fun setDisplayName(ctx: Context, id: String, v: String) = p(ctx).edit().putString("name_$id", v.ifBlank { id }).apply()
    fun getLabel(ctx: Context, id: String): String { val d = DEFAULT_LABELS[id] ?: id; return p(ctx).getString("label_$id", d) ?: d }
    fun setLabel(ctx: Context, id: String, v: String) { val d = DEFAULT_LABELS[id] ?: id; p(ctx).edit().putString("label_$id", v.ifBlank { d }).apply() }
    fun getColor(ctx: Context, id: String): Int = p(ctx).getInt("color_$id", Color.parseColor("#F8F8F2"))
    fun setColor(ctx: Context, id: String, c: Int) = p(ctx).edit().putInt("color_$id", c).apply()
    val OPT_IN_FREQ_IDS = setOf("disp", "int", "icpu")

    val VOLT_ZONES = linkedSetOf(
        "CPUCL3", "CPUCL2", "CPUCL1", "DSU", "MIF", "G3D", "NPU0", "NPU1"
    )

    fun isVoltVisible(ctx: Context, id: String): Boolean =
        p(ctx).getBoolean("volt_visible_$id", false)
    fun setVoltVisible(ctx: Context, id: String, v: Boolean) =
        p(ctx).edit().putBoolean("volt_visible_$id", v).apply()

    val NO_LOAD_ZONES = setOf("DSU", "MIF", "NPU0", "NPU1", "NPUCON", "ISP")

    fun getTelemetryCoreIndent(ctx: Context): Float = p(ctx).getFloat("telemetry_core_indent", 14f)
    fun setTelemetryCoreIndent(ctx: Context, v: Float) = p(ctx).edit().putFloat("telemetry_core_indent", v.coerceIn(0f, 48f)).apply()
    fun getMemLabelSize(ctx: Context): Float = p(ctx).getFloat("tel_mem_label_size", 1f)
    fun setMemLabelSize(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_mem_label_size", v.coerceIn(0.5f, 2.0f)).apply()
    fun getOff_ramlbl_x(ctx: Context): Float = p(ctx).getFloat("tel_off_ramlbl_x", 0f)
    fun setOff_ramlbl_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_off_ramlbl_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_ramlbl_y(ctx: Context): Float = p(ctx).getFloat("tel_off_ramlbl_y", 0f)
    fun setOff_ramlbl_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_off_ramlbl_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_swaplbl_x(ctx: Context): Float = p(ctx).getFloat("tel_off_swaplbl_x", 0f)
    fun setOff_swaplbl_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_off_swaplbl_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_swaplbl_y(ctx: Context): Float = p(ctx).getFloat("tel_off_swaplbl_y", 0f)
    fun setOff_swaplbl_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_off_swaplbl_y", v.coerceIn(-200f, 200f)).apply()

    fun getOff_mem_x(ctx: Context): Float = p(ctx).getFloat("main_off_mem_x", 0f)
    fun setOff_mem_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mem_x", v.coerceIn(-300f, 300f)).apply()
    fun getOff_mem_y(ctx: Context): Float = p(ctx).getFloat("main_off_mem_y", 0f)
    fun setOff_mem_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mem_y", v.coerceIn(-300f, 300f)).apply()

    fun isMemVisible(ctx: Context): Boolean = p(ctx).getBoolean("main_mem_visible", true)
    fun setMemVisible(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("main_mem_visible", v).apply()

    // ---- bloco MEMORIA / BARRAMENTO: posicao de cada item ----

    /** Onde o bloco se divide: 0,25 = barras estreitas, 0,75 = barramento estreito. */
    fun getMemSplit(ctx: Context): Float = p(ctx).getFloat("mem_split", 0.5f)
    fun setMemSplit(ctx: Context, v: Float) =
        p(ctx).edit().putFloat("mem_split", v.coerceIn(0.25f, 0.75f)).apply()

    /** Barramento inteiro (DSU + MIF). */
    fun getOff_bus_x(ctx: Context): Float = p(ctx).getFloat("main_off_bus_x", 0f)
    fun setOff_bus_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_bus_x", v.coerceIn(-300f, 300f)).apply()
    fun getOff_bus_y(ctx: Context): Float = p(ctx).getFloat("main_off_bus_y", 0f)
    fun setOff_bus_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_bus_y", v.coerceIn(-300f, 300f)).apply()

    /** Cada domínio do barramento, por cima do deslocamento do bloco. */
    fun getOff_dsu_x(ctx: Context): Float = p(ctx).getFloat("main_off_dsu_x", 0f)
    fun setOff_dsu_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsu_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_dsu_y(ctx: Context): Float = p(ctx).getFloat("main_off_dsu_y", 0f)
    fun setOff_dsu_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsu_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mif_x(ctx: Context): Float = p(ctx).getFloat("main_off_mif_x", 0f)
    fun setOff_mif_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mif_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mif_y(ctx: Context): Float = p(ctx).getFloat("main_off_mif_y", 0f)
    fun setOff_mif_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mif_y", v.coerceIn(-200f, 200f)).apply()

    /**
     * A tensao de cada dominio, independente do titulo.
     *
     * O deslocamento do dominio (getOff_dsu_*) move a linha inteira; este move
     * so o valor de tensao dentro dela.
     */
    fun getOff_dsuv_x(ctx: Context): Float = p(ctx).getFloat("main_off_dsuv_x", 0f)
    fun setOff_dsuv_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsuv_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_dsuv_y(ctx: Context): Float = p(ctx).getFloat("main_off_dsuv_y", 0f)
    fun setOff_dsuv_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsuv_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mifv_x(ctx: Context): Float = p(ctx).getFloat("main_off_mifv_x", 0f)
    fun setOff_mifv_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mifv_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mifv_y(ctx: Context): Float = p(ctx).getFloat("main_off_mifv_y", 0f)
    fun setOff_mifv_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mifv_y", v.coerceIn(-200f, 200f)).apply()

    /** O titulo (DSU/MIF), independente do resto da linha. */
    fun getOff_dsun_x(ctx: Context): Float = p(ctx).getFloat("main_off_dsun_x", 0f)
    fun setOff_dsun_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsun_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_dsun_y(ctx: Context): Float = p(ctx).getFloat("main_off_dsun_y", 0f)
    fun setOff_dsun_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsun_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mifn_x(ctx: Context): Float = p(ctx).getFloat("main_off_mifn_x", 0f)
    fun setOff_mifn_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mifn_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mifn_y(ctx: Context): Float = p(ctx).getFloat("main_off_mifn_y", 0f)
    fun setOff_mifn_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mifn_y", v.coerceIn(-200f, 200f)).apply()

    /** A frequencia de cada dominio do barramento, independente do titulo. */
    fun getOff_dsuf_x(ctx: Context): Float = p(ctx).getFloat("main_off_dsuf_x", 0f)
    fun setOff_dsuf_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsuf_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_dsuf_y(ctx: Context): Float = p(ctx).getFloat("main_off_dsuf_y", 0f)
    fun setOff_dsuf_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsuf_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_miff_x(ctx: Context): Float = p(ctx).getFloat("main_off_miff_x", 0f)
    fun setOff_miff_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_miff_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_miff_y(ctx: Context): Float = p(ctx).getFloat("main_off_miff_y", 0f)
    fun setOff_miff_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_miff_y", v.coerceIn(-200f, 200f)).apply()

    /** A temperatura de cada dominio do barramento. */
    fun getOff_dsut_x(ctx: Context): Float = p(ctx).getFloat("main_off_dsut_x", 0f)
    fun setOff_dsut_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsut_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_dsut_y(ctx: Context): Float = p(ctx).getFloat("main_off_dsut_y", 0f)
    fun setOff_dsut_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_dsut_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mift_x(ctx: Context): Float = p(ctx).getFloat("main_off_mift_x", 0f)
    fun setOff_mift_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mift_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mift_y(ctx: Context): Float = p(ctx).getFloat("main_off_mift_y", 0f)
    fun setOff_mift_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mift_y", v.coerceIn(-200f, 200f)).apply()

    /** O filete, sem mexer em onde o bloco se divide. */
    fun getOff_div_x(ctx: Context): Float = p(ctx).getFloat("main_off_div_x", 0f)
    fun setOff_div_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_div_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_div_y(ctx: Context): Float = p(ctx).getFloat("main_off_div_y", 0f)
    fun setOff_div_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_div_y", v.coerceIn(-100f, 100f)).apply()

    /** A porcentagem (69% / 47%), nas duas faixas. */
    fun getOff_mempct_x(ctx: Context): Float = p(ctx).getFloat("main_off_mempct_x", 0f)
    fun setOff_mempct_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mempct_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_mempct_y(ctx: Context): Float = p(ctx).getFloat("main_off_mempct_y", 0f)
    fun setOff_mempct_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_mempct_y", v.coerceIn(-200f, 200f)).apply()

    /** O total em GB, nas duas faixas. */
    fun getOff_memgb_x(ctx: Context): Float = p(ctx).getFloat("main_off_memgb_x", 0f)
    fun setOff_memgb_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_memgb_x", v.coerceIn(-300f, 300f)).apply()
    fun getOff_memgb_y(ctx: Context): Float = p(ctx).getFloat("main_off_memgb_y", 0f)
    fun setOff_memgb_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_memgb_y", v.coerceIn(-200f, 200f)).apply()

    fun getOff_maincnt_x(ctx: Context): Float = p(ctx).getFloat("main_off_cnt_x", 0f)
    fun setOff_maincnt_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_cnt_x", v.coerceIn(-300f, 300f)).apply()
    fun getOff_maincnt_y(ctx: Context): Float = p(ctx).getFloat("main_off_cnt_y", 0f)
    fun setOff_maincnt_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_cnt_y", v.coerceIn(-300f, 300f)).apply()

    fun getChipThreshold(ctx: Context): Int = p(ctx).getInt("chip_threshold", 70)
    fun setChipThreshold(ctx: Context, v: Int) =
        p(ctx).edit().putInt("chip_threshold", v.coerceIn(30, 110)).apply()

    fun getIntervalCpuTemp(ctx: Context): Int = p(ctx).getInt("interval_cpu_temp", 1000)
    fun setIntervalCpuTemp(ctx: Context, v: Int) =
        p(ctx).edit().putInt("interval_cpu_temp", v.coerceIn(100, 1000)).apply()

    fun isZoneVisible(ctx: Context, id: String): Boolean = p(ctx).getBoolean("visible_$id", true)
    fun setZoneVisible(ctx: Context, id: String, v: Boolean) = p(ctx).edit().putBoolean("visible_$id", v).apply()
    fun getVisibleZones(ctx: Context, ids: List<String>) = ids.filter { isZoneVisible(ctx, it) }

    const val FONT_STYRENE = "styrene"
    const val FONT_SYSTEM = "system"
    const val FONT_MONO = "mono"

    /**
     * Familia usada em todo o app. [FONT_STYRENE] e a Styrene B embutida,
     * [FONT_SYSTEM] e a fonte do proprio Android e [FONT_MONO] e a
     * monoespacada que o app usava antes.
     */
    fun getFontFamily(ctx: Context): String =
        p(ctx).getString("font_family", FONT_STYRENE) ?: FONT_STYRENE
    fun setFontFamily(ctx: Context, v: String) =
        p(ctx).edit().putString("font_family", v).apply()

    fun getFontSize(ctx: Context): Float = p(ctx).getFloat("font_size", 1.0f)
    fun setFontSize(ctx: Context, v: Float) = p(ctx).edit().putFloat("font_size", v.coerceIn(0.5f, 2.5f)).apply()
    fun getVerticalSpacing(ctx: Context): Float = p(ctx).getFloat("v_spacing", 0.0f)
    fun setVerticalSpacing(ctx: Context, v: Float) = p(ctx).edit().putFloat("v_spacing", v.coerceIn(0.0f, 2.5f)).apply()
    fun getHorizontalSpacing(ctx: Context): Float = p(ctx).getFloat("h_spacing", 0.0f)
    fun setHorizontalSpacing(ctx: Context, v: Float) = p(ctx).edit().putFloat("h_spacing", v.coerceIn(0.0f, 2.5f)).apply()
    fun getMarginLeft(ctx: Context): Float = p(ctx).getFloat("margin_left", 1f)
    fun setMarginLeft(ctx: Context, v: Float) = p(ctx).edit().putFloat("margin_left", v.coerceIn(0f, 80f)).apply()
    fun getMarginRight(ctx: Context): Float = p(ctx).getFloat("margin_right", 1f)
    fun setMarginRight(ctx: Context, v: Float) = p(ctx).edit().putFloat("margin_right", v.coerceIn(0f, 80f)).apply()
    fun getMarginTop(ctx: Context): Float = p(ctx).getFloat("margin_top", 1f)
    fun setMarginTop(ctx: Context, v: Float) = p(ctx).edit().putFloat("margin_top", v.coerceIn(0f, 80f)).apply()
    fun getMarginBottom(ctx: Context): Float = p(ctx).getFloat("margin_bottom", 1f)
    fun setMarginBottom(ctx: Context, v: Float) = p(ctx).edit().putFloat("margin_bottom", v.coerceIn(0f, 80f)).apply()

    fun isMinimalMode(ctx: Context) = p(ctx).getBoolean("minimal_mode", false)
    fun setMinimalMode(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("minimal_mode", v).apply()

    fun getBgColor(ctx: Context): Int = p(ctx).getInt("bg_color", Color.parseColor("#1E1E2E"))
    fun setBgColor(ctx: Context, c: Int) = p(ctx).edit().putInt("bg_color", c).apply()
    fun getBgAlpha(ctx: Context): Int = p(ctx).getInt("bg_alpha", 221)
    fun setBgAlpha(ctx: Context, a: Int) = p(ctx).edit().putInt("bg_alpha", a.coerceIn(0, 255)).apply()

    fun getBattLabel(ctx: Context, k: String): String { val d = DEFAULT_BATT_LABELS[k] ?: k; return p(ctx).getString("batt_label_$k", d) ?: d }
    fun setBattLabel(ctx: Context, k: String, v: String) { val d = DEFAULT_BATT_LABELS[k] ?: k; p(ctx).edit().putString("batt_label_$k", v.ifBlank { d }).apply() }
    fun isBatteryVisible(ctx: Context, k: String): Boolean = p(ctx).getBoolean("batt_visible_$k", true)
    fun setBatteryVisible(ctx: Context, k: String, v: Boolean) = p(ctx).edit().putBoolean("batt_visible_$k", v).apply()

    fun getPowerInterval(ctx: Context): Int = p(ctx).getInt("power_interval", 500)
    fun setPowerInterval(ctx: Context, ms: Int) = p(ctx).edit().putInt("power_interval", ms.coerceIn(100, 1000)).apply()


    fun getHotspot95Count(ctx: Context): Int = p(ctx).getInt("hotspot_count_95", 0)
    fun setHotspot95Count(ctx: Context, n: Int) = p(ctx).edit().putInt("hotspot_count_95", n.coerceAtLeast(0)).apply()

    fun isHotspot95CountVisible(ctx: Context): Boolean = p(ctx).getBoolean("show_hotspot_95", true)
    fun setHotspot95CountVisible(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("show_hotspot_95", v).apply()

    fun getHotspotThreshold(ctx: Context): Int = p(ctx).getInt("hotspot_threshold", 95)
    fun setHotspotThreshold(ctx: Context, c: Int) = p(ctx).edit().putInt("hotspot_threshold", c.coerceIn(50, 110)).apply()

    fun getColGapName(ctx: Context): Float   = p(ctx).getFloat("col_gap_name", 0.0f)
    fun setColGapName(ctx: Context, v: Float) = p(ctx).edit().putFloat("col_gap_name", v.coerceIn(0f, 4f)).apply()
    fun getColGapFreq(ctx: Context): Float   = p(ctx).getFloat("col_gap_freq", 0.0f)
    fun setColGapFreq(ctx: Context, v: Float) = p(ctx).edit().putFloat("col_gap_freq", v.coerceIn(0f, 4f)).apply()
    fun getColGapTemp(ctx: Context): Float   = p(ctx).getFloat("col_gap_temp", 0.0f)
    fun setColGapTemp(ctx: Context, v: Float) = p(ctx).edit().putFloat("col_gap_temp", v.coerceIn(0f, 4f)).apply()
    fun getColGapVolt(ctx: Context): Float   = p(ctx).getFloat("col_gap_volt", 0.0f)
    fun setColGapVolt(ctx: Context, v: Float) = p(ctx).edit().putFloat("col_gap_volt", v.coerceIn(0f, 4f)).apply()
    fun getColGapLoad(ctx: Context): Float   = p(ctx).getFloat("col_gap_load", 0.0f)
    fun setColGapLoad(ctx: Context, v: Float) = p(ctx).edit().putFloat("col_gap_load", v.coerceIn(0f, 4f)).apply()

    fun getGapPowerSection(ctx: Context): Float   = p(ctx).getFloat("gap_power_section", 0.0f)
    fun setGapPowerSection(ctx: Context, v: Float) = p(ctx).edit().putFloat("gap_power_section", v.coerceIn(0f, 4f)).apply()








    fun getOff_ac_x(ctx: Context): Float = p(ctx).getFloat("main_off_ac_x", 0f)
    fun setOff_ac_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_ac_x", v.coerceIn(-400f, 400f)).apply()
    fun getOff_ac_y(ctx: Context): Float = p(ctx).getFloat("main_off_ac_y", 0f)
    fun setOff_ac_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_ac_y", v.coerceIn(-200f, 200f)).apply()
    fun getOff_actxt_x(ctx: Context): Float = p(ctx).getFloat("main_off_actxt_x", 0f)
    fun setOff_actxt_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_actxt_x", v.coerceIn(-100f, 100f)).apply()
    fun getOff_actxt_y(ctx: Context): Float = p(ctx).getFloat("main_off_actxt_y", 0f)
    fun setOff_actxt_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_actxt_y", v.coerceIn(-100f, 100f)).apply()
    fun getOff_mainhot_x(ctx: Context): Float = p(ctx).getFloat("main_off_hot_x", 0f)
    fun setOff_mainhot_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_hot_x", v.coerceIn(-400f, 400f)).apply()
    fun getOff_mainhot_y(ctx: Context): Float = p(ctx).getFloat("main_off_hot_y", 0f)
    fun setOff_mainhot_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("main_off_hot_y", v.coerceIn(-200f, 200f)).apply()
    fun isAcVisible(ctx: Context): Boolean = p(ctx).getBoolean("main_ac_visible", true)
    fun setAcVisible(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("main_ac_visible", v).apply()

    fun getBarIntensity(ctx: Context): Float = p(ctx).getFloat("tel_bar_intensity", 0.78f)
    fun setBarIntensity(ctx: Context, v: Float) = p(ctx).edit().putFloat("tel_bar_intensity", v.coerceIn(0.1f, 1.0f)).apply()


    fun getElemColor(ctx: Context, id: String, def: Int): Int = p(ctx).getInt("elemcolor_$id", def)
    fun setElemColor(ctx: Context, id: String, v: Int) = p(ctx).edit().putInt("elemcolor_$id", v).apply()







    fun getIntervalCpuFreq(ctx: Context): Int = p(ctx).getInt("interval_cpu_freq", 900)
    fun setIntervalCpuFreq(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_cpu_freq", v.coerceIn(100, 2000)).apply()
    fun getIntervalGpuFreq(ctx: Context): Int = p(ctx).getInt("interval_gpu_freq", 900)
    fun setIntervalGpuFreq(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_gpu_freq", v.coerceIn(100, 2000)).apply()
    fun getIntervalCpuLoads(ctx: Context): Int = p(ctx).getInt("interval_cpu_loads", 900)
    fun setIntervalCpuLoads(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_cpu_loads", v.coerceIn(100, 2000)).apply()
    fun getIntervalGpuLoad(ctx: Context): Int = p(ctx).getInt("interval_gpu_load", 900)
    fun setIntervalGpuLoad(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_gpu_load", v.coerceIn(100, 2000)).apply()
    fun getIntervalGpuTemp(ctx: Context): Int = p(ctx).getInt("interval_gpu_temp", 1000)
    fun setIntervalGpuTemp(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_gpu_temp", v.coerceIn(100, 5000)).apply()

    /**
     * NPU num intervalo proprio. Antes npu0/npu1/npucon vinham no bloco devfreq
     * com tick fixo de 1000 ms, sem controle nenhum.
     */
    fun getIntervalNpu(ctx: Context): Int = p(ctx).getInt("interval_npu", 1000)
    fun setIntervalNpu(ctx: Context, v: Int) =
        p(ctx).edit().putInt("interval_npu", v.coerceIn(100, 5000)).apply()

    /** Demais dominios devfreq (MIF, DSU, INT, ISP, DISP, ICPU...). */
    fun getIntervalDevfreq(ctx: Context): Int = p(ctx).getInt("interval_devfreq", 1000)
    fun setIntervalDevfreq(ctx: Context, v: Int) =
        p(ctx).edit().putInt("interval_devfreq", v.coerceIn(100, 5000)).apply()

    /**
     * Com a tela apagada, parar tambem a amostragem no servidor.
     *
     * Default false = comportamento historico: os pollers root continuam rodando
     * na taxa cheia com a tela apagada (o contador de hotspot segue contando,
     * mas gasta bateria). Ligado, a coleta para junto com a tela.
     */
    fun isDeepPauseOnScreenOff(ctx: Context): Boolean =
        p(ctx).getBoolean("deep_pause_screen_off", false)
    fun setDeepPauseOnScreenOff(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("deep_pause_screen_off", v).apply()

    fun getIntervalRam(ctx: Context): Int = p(ctx).getInt("interval_ram", 1000)
    fun setIntervalRam(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_ram", v.coerceIn(100, 2000)).apply()

    fun getIntervalGpuMem(ctx: Context): Int = p(ctx).getInt("interval_gpu_mem", 1000)
    fun setIntervalGpuMem(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_gpu_mem", v.coerceIn(100, 5000)).apply()

    fun isGpuMemVisible(ctx: Context): Boolean = p(ctx).getBoolean("gpumem_visible", true)
    fun setGpuMemVisible(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("gpumem_visible", v).apply()

    fun getGpuMemFontScale(ctx: Context): Float = p(ctx).getFloat("fs_gpumem", 0.8f)
    fun setGpuMemFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fs_gpumem", v.coerceIn(0.4f, 2.0f)).apply()

    fun getOff_gpumem_x(ctx: Context): Float = p(ctx).getFloat("off_gpumem_x", 0f)
    fun setOff_gpumem_x(ctx: Context, v: Float) = p(ctx).edit().putFloat("off_gpumem_x", v.coerceIn(-200f, 200f)).apply()
    fun getOff_gpumem_y(ctx: Context): Float = p(ctx).getFloat("off_gpumem_y", 0f)
    fun setOff_gpumem_y(ctx: Context, v: Float) = p(ctx).edit().putFloat("off_gpumem_y", v.coerceIn(-200f, 200f)).apply()




    fun isHistoryEnabled(ctx: Context): Boolean = p(ctx).getBoolean("history_enabled", true)
    fun setHistoryEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("history_enabled", v).apply()
    fun isPowerInMainVisible(ctx: Context): Boolean = p(ctx).getBoolean("main_show_power", false)
    fun setPowerInMainVisible(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("main_show_power", v).apply()

    fun getMainBatLabel(ctx: Context): String =
        p(ctx).getString("main_bat_label", "BAT")?.ifBlank { "BAT" } ?: "BAT"
    fun setMainBatLabel(ctx: Context, v: String) =
        p(ctx).edit().putString("main_bat_label", v.ifBlank { "BAT" }).apply()
    fun getMainPdLabel(ctx: Context): String =
        p(ctx).getString("main_pd_label", "PD")?.ifBlank { "PD" } ?: "PD"
    fun setMainPdLabel(ctx: Context, v: String) =
        p(ctx).edit().putString("main_pd_label", v.ifBlank { "PD" }).apply()
    fun getPowerThreshold(ctx: Context): Float = p(ctx).getFloat("power_threshold", 10f)
    fun setPowerThreshold(ctx: Context, v: Float) = p(ctx).edit().putFloat("power_threshold", v.coerceIn(1f, 30f)).apply()
    fun getPowerAlertColor(ctx: Context): Int = p(ctx).getInt("power_alert_color", Color.parseColor("#FF5555"))
    fun setPowerAlertColor(ctx: Context, c: Int) = p(ctx).edit().putInt("power_alert_color", c).apply()
    fun getPowerNormalColor(ctx: Context): Int = p(ctx).getInt("power_normal_color", Color.parseColor("#50FA7B"))
    fun setPowerNormalColor(ctx: Context, c: Int) = p(ctx).edit().putInt("power_normal_color", c).apply()

    fun getFreqLabel(ctx: Context, fId: String): String {
        val d = defaultFreqLabel(fId); return p(ctx).getString("freq_label_$fId", d) ?: d
    }
    fun setFreqLabel(ctx: Context, fId: String, v: String) = p(ctx).edit().putString("freq_label_$fId", v.ifBlank { defaultFreqLabel(fId) }).apply()
    fun isFreqVisible(ctx: Context, fId: String): Boolean =
        p(ctx).getBoolean("freq_visible_$fId", fId !in OPT_IN_FREQ_IDS)
    fun setFreqVisible(ctx: Context, fId: String, v: Boolean) = p(ctx).edit().putBoolean("freq_visible_$fId", v).apply()

    private val ZONE_TO_FREQ_ID: Map<String, String> =
        KNOWN_FREQ_IDS.entries.associate { (k, v) -> v to k }

    fun getFreqIdForZone(zoneId: String): String? = ZONE_TO_FREQ_ID[zoneId]

    fun defaultFreqLabel(fId: String): String = when (fId) {
        "cpucl3" -> "Prime"; "cpucl2" -> "Big"; "cpucl1" -> "Mid"; "cpucl0" -> "Little"
        "gpu" -> "GPU"; "dsu" -> "L3"; "mif" -> "DDR"
        "npu0" -> "NSP"; "npu1" -> "NPU"; "npucon" -> "Vídeo"; "npu" -> "NPU"; "isp" -> "ISP"
        "int" -> "LLCC"; "disp" -> "Display"; "icpu" -> "CPU da Câmera"
        else -> fId.uppercase()
    }

    fun resetAll(ctx: Context) = p(ctx).edit().clear().apply()
}
