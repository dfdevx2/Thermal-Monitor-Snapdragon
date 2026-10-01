package com.siliconfet.thermalmonitor

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

val LocalAppTheme = staticCompositionLocalOf { AppTheme.DEFAULT }

private val DarkBg: Color       @Composable get() = Color(LocalAppTheme.current.cBg)
private val DarkSurface: Color  @Composable get() = Color(LocalAppTheme.current.cCard)
private val Foreground: Color   @Composable get() = Color(LocalAppTheme.current.cText)
private val Comment: Color      @Composable get() = Color(LocalAppTheme.current.cDim)
private val Cyan: Color         @Composable get() = Color(LocalAppTheme.current.cCyan)
private val Green: Color        @Composable get() = Color(LocalAppTheme.current.cGreen)
private val Purple: Color       @Composable get() = Color(LocalAppTheme.current.cPurple)
private val Orange: Color       @Composable get() = Color(LocalAppTheme.current.cOrange)
/**
 * Styrene B. A familia e proporcional (o "1" mede 445/1000 e o "0" mede 692/1000),
 * entao numero em tela liga os digitos tabulares via [NumFeatures].
 */
private val StyreneB = FontFamily(
    Font(R.font.styreneb_regular, FontWeight.Normal),
    Font(R.font.styreneb_medium, FontWeight.Medium),
    Font(R.font.styreneb_bold, FontWeight.Bold)
)

private val LocalAppFont = staticCompositionLocalOf { StyreneB }

/** Familia em uso; segue a escolha do usuario, como as cores seguem o tema. */
private val Mono: FontFamily
    @Composable get() = LocalAppFont.current

/** Digitos de largura fixa: use em qualquer Text que mostre numero. */
private val NumFeatures = "tnum"

private fun familyFor(id: String): FontFamily = when (id) {
    OverlayPrefs.FONT_SYSTEM -> FontFamily.Default
    OverlayPrefs.FONT_MONO -> FontFamily.Monospace
    else -> StyreneB
}

private val ELEM_IDS = listOf(
    "freq", "load", "ram", "swap", "volt", "dim", "pwrbat", "section", "gpumem"
)

private val FONT_CHOICES = listOf(
    OverlayPrefs.FONT_STYRENE to "Styrene B",
    OverlayPrefs.FONT_SYSTEM to "Do sistema",
    OverlayPrefs.FONT_MONO to "Monoespaçada"
)

private val PALETTE: IntArray = run {
    val out = ArrayList<Int>(70)
    val neutrals = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF8F8F2.toInt(), 0xFFE0E0E0.toInt(), 0xFFBDBDBD.toInt(), 0xFF9E9E9E.toInt(),
        0xFF757575.toInt(), 0xFF5E5E5E.toInt(), 0xFF3C3C3C.toInt(), 0xFF1A1A1A.toInt(), 0xFF000000.toInt()
    )
    for (c in neutrals) out.add(c)
    val warmDark = intArrayOf(
        0xFF75756C.toInt(), 0xFF565650.toInt(), 0xFF3D3D38.toInt(),
        0xFF282825.toInt(), 0xFF1A1A18.toInt(), 0xFF0F0F0E.toInt()
    )
    for (c in warmDark) out.add(c)
    val hues = floatArrayOf(0f, 25f, 45f, 80f, 140f, 175f, 200f, 225f, 270f, 320f)
    val tones = arrayOf(
        floatArrayOf(0.30f, 1.00f), floatArrayOf(0.50f, 1.00f), floatArrayOf(0.70f, 1.00f),
        floatArrayOf(0.85f, 0.92f), floatArrayOf(1.00f, 0.78f), floatArrayOf(1.00f, 0.58f)
    )
    for (t in tones) for (h in hues) out.add(android.graphics.Color.HSVToColor(floatArrayOf(h, t[0], t[1])))
    out.toIntArray()
}

private val BG_COLORS = intArrayOf(
    0xFF1E1E2E.toInt(), 0xFF000000.toInt(), 0xFF0D1117.toInt(),
    0xFF1A1B26.toInt(), 0xFF282A36.toInt(), 0xFF2E3440.toInt(),
    0xFF1E293B.toInt(), 0xFF0F172A.toInt(), 0xFF18181B.toInt(),
    0xFF1A1A18.toInt(), 0xFF0F0F0E.toInt(),
    0xFF05060A.toInt(), 0xFF101014.toInt(), 0xFF141821.toInt(),
    0xFF11151C.toInt(), 0xFF161B22.toInt(), 0xFF1C2128.toInt(),
    0xFF21262D.toInt(), 0xFF24283B.toInt(), 0xFF292D3E.toInt(),
    0xFF11111B.toInt(), 0xFF181825.toInt(), 0xFF313244.toInt(),
    0xFF1D2021.toInt(), 0xFF282828.toInt(), 0xFF32302F.toInt(),
    0xFF002B36.toInt(), 0xFF073642.toInt(), 0xFF0B2027.toInt(),
    0xFF1B1B2F.toInt(), 0xFF162447.toInt(), 0xFF1F4068.toInt(),
    0xFF2D1B2E.toInt(), 0xFF231A2E.toInt(), 0xFF1A1428.toInt(),
    0xFF14110E.toInt(), 0xFF1F1B16.toInt(), 0xFF2A241D.toInt(),
    0xFF0E1A14.toInt(), 0xFF13251C.toInt(), 0xFF1B3026.toInt(),
    0xFF1A0E0E.toInt(), 0xFF2A1515.toInt(), 0xFF3A1D1D.toInt(),
    0xFF2B2B2B.toInt(), 0xFF3C3C3C.toInt(), 0xFF4D4D4D.toInt(),
    0xFFF8F8F2.toInt(), 0xFFEDEDED.toInt(), 0xFFFFFFFF.toInt()
)

private val BG_PALETTE: IntArray = BG_COLORS + PALETTE

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = SettingsBackup.exportToJson(ctx)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            Toast.makeText(ctx, "Configurações exportadas", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(ctx, "Falha ao exportar", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("empty")
            when (val r = SettingsBackup.importFromJson(ctx, json)) {
                is SettingsBackup.ImportResult.Ok -> {
                    Toast.makeText(ctx, "Importado (${r.applied}) — reabra as configurações", Toast.LENGTH_LONG).show()
                    (ctx as? ComponentActivity)?.finish()
                }
                is SettingsBackup.ImportResult.Error ->
                    Toast.makeText(ctx, r.reason, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(ctx, "Falha ao ler o arquivo", Toast.LENGTH_SHORT).show()
        }
    }

    val sl = remember { mutableStateMapOf<String, Float>().apply {
        put("telemetry_core_indent", OverlayPrefs.getTelemetryCoreIndent(ctx))
        put("tel_off_ramlbl_x", OverlayPrefs.getOff_ramlbl_x(ctx))
        put("tel_off_ramlbl_y", OverlayPrefs.getOff_ramlbl_y(ctx))
        put("tel_off_swaplbl_x", OverlayPrefs.getOff_swaplbl_x(ctx))
        put("tel_off_swaplbl_y", OverlayPrefs.getOff_swaplbl_y(ctx))
        put("tel_mem_label_size", OverlayPrefs.getMemLabelSize(ctx))
        put("tel_bar_intensity", OverlayPrefs.getBarIntensity(ctx))
        put("hotspot_threshold", OverlayPrefs.getHotspotThreshold(ctx).toFloat())
        put("power_interval", OverlayPrefs.getPowerInterval(ctx).toFloat())
        put("power_threshold", OverlayPrefs.getPowerThreshold(ctx))
        put("col_gap_name", OverlayPrefs.getColGapName(ctx))
        put("col_gap_freq", OverlayPrefs.getColGapFreq(ctx))
        put("col_gap_temp", OverlayPrefs.getColGapTemp(ctx))
        put("col_gap_load", OverlayPrefs.getColGapLoad(ctx))
        put("col_gap_volt", OverlayPrefs.getColGapVolt(ctx))
        put("gap_power_section", OverlayPrefs.getGapPowerSection(ctx))
        put("interval_cpu_freq", OverlayPrefs.getIntervalCpuFreq(ctx).toFloat())
        put("interval_gpu_freq", OverlayPrefs.getIntervalGpuFreq(ctx).toFloat())
        put("interval_cpu_loads", OverlayPrefs.getIntervalCpuLoads(ctx).toFloat())
        put("interval_gpu_load", OverlayPrefs.getIntervalGpuLoad(ctx).toFloat())
        put("interval_gpu_temp", OverlayPrefs.getIntervalGpuTemp(ctx).toFloat())
        put("interval_cpu_temp", OverlayPrefs.getIntervalCpuTemp(ctx).toFloat())
        put("interval_npu", OverlayPrefs.getIntervalNpu(ctx).toFloat())
        put("interval_devfreq", OverlayPrefs.getIntervalDevfreq(ctx).toFloat())
        put("chip_threshold", OverlayPrefs.getChipThreshold(ctx).toFloat())
        put("interval_ram", OverlayPrefs.getIntervalRam(ctx).toFloat())
        put("interval_gpu_mem", OverlayPrefs.getIntervalGpuMem(ctx).toFloat())
        put("fs_gpumem", OverlayPrefs.getGpuMemFontScale(ctx))
        put("off_gpumem_x", OverlayPrefs.getOff_gpumem_x(ctx))
        put("off_gpumem_y", OverlayPrefs.getOff_gpumem_y(ctx))
        put("main_off_mem_x", OverlayPrefs.getOff_mem_x(ctx))
        put("main_off_mem_y", OverlayPrefs.getOff_mem_y(ctx))
        put("mem_split", OverlayPrefs.getMemSplit(ctx))
        put("main_off_bus_x", OverlayPrefs.getOff_bus_x(ctx))
        put("main_off_bus_y", OverlayPrefs.getOff_bus_y(ctx))
        put("main_off_dsu_x", OverlayPrefs.getOff_dsu_x(ctx))
        put("main_off_dsu_y", OverlayPrefs.getOff_dsu_y(ctx))
        put("main_off_mif_x", OverlayPrefs.getOff_mif_x(ctx))
        put("main_off_mif_y", OverlayPrefs.getOff_mif_y(ctx))
        put("main_off_dsun_x", OverlayPrefs.getOff_dsun_x(ctx))
        put("main_off_dsun_y", OverlayPrefs.getOff_dsun_y(ctx))
        put("main_off_mifn_x", OverlayPrefs.getOff_mifn_x(ctx))
        put("main_off_mifn_y", OverlayPrefs.getOff_mifn_y(ctx))
        put("main_off_dsuf_x", OverlayPrefs.getOff_dsuf_x(ctx))
        put("main_off_dsuf_y", OverlayPrefs.getOff_dsuf_y(ctx))
        put("main_off_miff_x", OverlayPrefs.getOff_miff_x(ctx))
        put("main_off_miff_y", OverlayPrefs.getOff_miff_y(ctx))
        put("main_off_dsut_x", OverlayPrefs.getOff_dsut_x(ctx))
        put("main_off_dsut_y", OverlayPrefs.getOff_dsut_y(ctx))
        put("main_off_mift_x", OverlayPrefs.getOff_mift_x(ctx))
        put("main_off_mift_y", OverlayPrefs.getOff_mift_y(ctx))
        put("main_off_dsuv_x", OverlayPrefs.getOff_dsuv_x(ctx))
        put("main_off_dsuv_y", OverlayPrefs.getOff_dsuv_y(ctx))
        put("main_off_mifv_x", OverlayPrefs.getOff_mifv_x(ctx))
        put("main_off_mifv_y", OverlayPrefs.getOff_mifv_y(ctx))
        put("main_off_div_x", OverlayPrefs.getOff_div_x(ctx))
        put("main_off_div_y", OverlayPrefs.getOff_div_y(ctx))
        put("main_off_mempct_x", OverlayPrefs.getOff_mempct_x(ctx))
        put("main_off_mempct_y", OverlayPrefs.getOff_mempct_y(ctx))
        put("main_off_memgb_x", OverlayPrefs.getOff_memgb_x(ctx))
        put("main_off_memgb_y", OverlayPrefs.getOff_memgb_y(ctx))
        put("main_off_cnt_x", OverlayPrefs.getOff_maincnt_x(ctx))
        put("main_off_cnt_y", OverlayPrefs.getOff_maincnt_y(ctx))
        put("main_off_ac_x", OverlayPrefs.getOff_ac_x(ctx))
        put("main_off_ac_y", OverlayPrefs.getOff_ac_y(ctx))
        put("main_off_actxt_x", OverlayPrefs.getOff_actxt_x(ctx))
        put("main_off_actxt_y", OverlayPrefs.getOff_actxt_y(ctx))
        put("main_off_hot_x", OverlayPrefs.getOff_mainhot_x(ctx))
        put("main_off_hot_y", OverlayPrefs.getOff_mainhot_y(ctx))
        put("bg_alpha", OverlayPrefs.getBgAlpha(ctx).toFloat())
        put("font_size", OverlayPrefs.getFontSize(ctx))
        put("fs_title", OverlayPrefs.getTitleFontScale(ctx))
        put("fs_value", OverlayPrefs.getValueFontScale(ctx))
        put("fs_label", OverlayPrefs.getLabelFontScale(ctx))
        put("fs_chart", OverlayPrefs.getChartFontScale(ctx))
        put("v_spacing", OverlayPrefs.getVerticalSpacing(ctx))
        put("h_spacing", OverlayPrefs.getHorizontalSpacing(ctx))
        put("margin_top", OverlayPrefs.getMarginTop(ctx))
        put("margin_bottom", OverlayPrefs.getMarginBottom(ctx))
        put("margin_left", OverlayPrefs.getMarginLeft(ctx))
        put("margin_right", OverlayPrefs.getMarginRight(ctx))
        put("control_size", OverlayPrefs.getControlSize(ctx))
    }}

    var mainOn by remember { mutableStateOf(OverlayPrefs.isMainOverlayEnabled(ctx)) }
    var minimalOn by remember { mutableStateOf(OverlayPrefs.isMinimalMode(ctx)) }
    var devfreqOn by remember { mutableStateOf(OverlayPrefs.isDevfreqOverlayEnabled(ctx)) }
    var batteryOn by remember { mutableStateOf(OverlayPrefs.isBatteryOverlayEnabled(ctx)) }
    var historyOn by remember { mutableStateOf(OverlayPrefs.isHistoryOverlayEnabled(ctx)) }
    var histRecOn by remember { mutableStateOf(OverlayPrefs.isHistoryEnabled(ctx)) }
    var hot95On by remember { mutableStateOf(OverlayPrefs.isHotspot95CountVisible(ctx)) }
    var powerMainOn by remember { mutableStateOf(OverlayPrefs.isPowerInMainVisible(ctx)) }
    var gpuMemOn by remember { mutableStateOf(OverlayPrefs.isGpuMemVisible(ctx)) }
    var acOn by remember { mutableStateOf(OverlayPrefs.isAcVisible(ctx)) }
    var memOn by remember { mutableStateOf(OverlayPrefs.isMemVisible(ctx)) }
    var deepPauseOn by remember { mutableStateOf(OverlayPrefs.isDeepPauseOnScreenOff(ctx)) }
    var titMain by remember { mutableStateOf(OverlayPrefs.getTitle(ctx)) }
    var titDevfreq by remember { mutableStateOf(OverlayPrefs.getDevfreqTitle(ctx)) }
    var titBattery by remember { mutableStateOf(OverlayPrefs.getBatteryTitle(ctx)) }
    var titHistory by remember { mutableStateOf(OverlayPrefs.getHistoryTitle(ctx)) }
    var batLabel by remember { mutableStateOf(OverlayPrefs.getMainBatLabel(ctx)) }
    var pdLabel by remember { mutableStateOf(OverlayPrefs.getMainPdLabel(ctx)) }

    val elemColors = remember {
        mutableStateMapOf<String, Int>().apply {
            for (e in ELEM_IDS)
                put(e, OverlayPrefs.getElemColor(ctx, e, 0xFFF8F8F2.toInt()))
        }
    }

    var bgColor by remember { mutableStateOf(OverlayPrefs.getBgColor(ctx)) }
    var pwNormalColor by remember { mutableStateOf(OverlayPrefs.getPowerNormalColor(ctx)) }
    var pwAlertColor by remember { mutableStateOf(OverlayPrefs.getPowerAlertColor(ctx)) }

    val zoneIds = OverlayPrefs.DEFAULT_NAMES.keys.toList()
    val zoneNames = remember { mutableStateMapOf<String, String>().apply {
        for (id in zoneIds) put(id, OverlayPrefs.getDisplayName(ctx, id))
    }}
    val zoneLabels = remember { mutableStateMapOf<String, String>().apply {
        for (id in zoneIds) put(id, OverlayPrefs.getLabel(ctx, id))
    }}
    val zoneColors = remember { mutableStateMapOf<String, Int>().apply {
        for (id in zoneIds) put(id, OverlayPrefs.getColor(ctx, id))
    }}
    val zoneVisible = remember { mutableStateMapOf<String, Boolean>().apply {
        for (id in zoneIds) put(id, OverlayPrefs.isZoneVisible(ctx, id))
    }}

    val voltIds = OverlayPrefs.VOLT_ZONES.toList()
    val voltVisible = remember { mutableStateMapOf<String, Boolean>().apply {
        for (id in voltIds) put(id, OverlayPrefs.isVoltVisible(ctx, id))
    }}

    val allFreqIds = (OverlayPrefs.LIGHT_FREQ_IDS + OverlayPrefs.MEMORY_FREQ_IDS).toList()
    val freqLabels = remember { mutableStateMapOf<String, String>().apply {
        for (id in allFreqIds) put(id, OverlayPrefs.getFreqLabel(ctx, id))
    }}
    val freqVisible = remember { mutableStateMapOf<String, Boolean>().apply {
        for (id in allFreqIds) put(id, OverlayPrefs.isFreqVisible(ctx, id))
    }}

    val battKeys = OverlayPrefs.DEFAULT_BATT_LABELS.keys.toList()
    val battLabels = remember { mutableStateMapOf<String, String>().apply {
        for (k in battKeys) put(k, OverlayPrefs.getBattLabel(ctx, k))
    }}
    val battVisible = remember { mutableStateMapOf<String, Boolean>().apply {
        for (k in battKeys) put(k, OverlayPrefs.isBatteryVisible(ctx, k))
    }}

    var selFont by remember { mutableStateOf(OverlayPrefs.getFontFamily(ctx)) }
    val appFont = remember(selFont) { familyFor(selFont) }
    var selTheme by remember { mutableStateOf(AppTheme.current(ctx).id) }
    val theme = remember(selTheme) { AppTheme.ALL.firstOrNull { it.id == selTheme } ?: AppTheme.DEFAULT }

    // Nada de botao SALVAR: cada mexida grava. Para nao virar enxurrada, so a
    // chave que realmente mudou e regravada — `chg` compara com o ultimo valor
    // escrito naquela posicao da sequencia, que e fixa.
    val written = remember { HashMap<Int, Any?>() }
    // A primeira passada apenas registra o estado inicial: ele veio das próprias
    // preferências, então regravar as ~170 chaves ao abrir a tela seria à toa.
    val seeding = remember { booleanArrayOf(true) }
    var slot = 0

    fun <T> chg(v: T, set: (T) -> Unit) {
        val i = slot++
        if (written.containsKey(i) && written[i] == v) return
        written[i] = v
        if (!seeding[0]) set(v)
    }

    fun persistAll() {
        slot = 0
        chg(mainOn) { OverlayPrefs.setMainOverlayEnabled(ctx, it) }
        chg(minimalOn) { OverlayPrefs.setMinimalMode(ctx, it) }
        chg(sl["telemetry_core_indent"]!!) { OverlayPrefs.setTelemetryCoreIndent(ctx, it) }
        chg(sl["tel_off_ramlbl_x"]!!) { OverlayPrefs.setOff_ramlbl_x(ctx, it) }
        chg(sl["tel_off_ramlbl_y"]!!) { OverlayPrefs.setOff_ramlbl_y(ctx, it) }
        chg(sl["tel_off_swaplbl_x"]!!) { OverlayPrefs.setOff_swaplbl_x(ctx, it) }
        chg(sl["tel_off_swaplbl_y"]!!) { OverlayPrefs.setOff_swaplbl_y(ctx, it) }
        chg(sl["tel_mem_label_size"]!!) { OverlayPrefs.setMemLabelSize(ctx, it) }
        chg(sl["tel_bar_intensity"]!!) { OverlayPrefs.setBarIntensity(ctx, it) }
        chg(devfreqOn) { OverlayPrefs.setDevfreqOverlayEnabled(ctx, it) }
        chg(batteryOn) { OverlayPrefs.setBatteryOverlayEnabled(ctx, it) }
        chg(historyOn) { OverlayPrefs.setHistoryOverlayEnabled(ctx, it) }
        chg(histRecOn) { OverlayPrefs.setHistoryEnabled(ctx, it) }
        chg(titMain) { OverlayPrefs.setTitle(ctx, it) }
        chg(titDevfreq) { OverlayPrefs.setDevfreqTitle(ctx, it) }
        chg(titBattery) { OverlayPrefs.setBatteryTitle(ctx, it) }
        chg(titHistory) { OverlayPrefs.setHistoryTitle(ctx, it) }
        chg(bgColor) { OverlayPrefs.setBgColor(ctx, it) }
        chg(sl["bg_alpha"]!!.toInt()) { OverlayPrefs.setBgAlpha(ctx, it) }
        chg(sl["font_size"]!!) { OverlayPrefs.setFontSize(ctx, it) }
        chg(sl["v_spacing"]!!) { OverlayPrefs.setVerticalSpacing(ctx, it) }
        chg(sl["h_spacing"]!!) { OverlayPrefs.setHorizontalSpacing(ctx, it) }
        chg(sl["control_size"]!!) { OverlayPrefs.setControlSize(ctx, it) }
        chg(sl["fs_title"]!!) { OverlayPrefs.setTitleFontScale(ctx, it) }
        chg(sl["fs_value"]!!) { OverlayPrefs.setValueFontScale(ctx, it) }
        chg(sl["fs_label"]!!) { OverlayPrefs.setLabelFontScale(ctx, it) }
        chg(sl["fs_chart"]!!) { OverlayPrefs.setChartFontScale(ctx, it) }
        chg(sl["margin_top"]!!) { OverlayPrefs.setMarginTop(ctx, it) }
        chg(sl["margin_bottom"]!!) { OverlayPrefs.setMarginBottom(ctx, it) }
        chg(sl["margin_left"]!!) { OverlayPrefs.setMarginLeft(ctx, it) }
        chg(sl["margin_right"]!!) { OverlayPrefs.setMarginRight(ctx, it) }
        chg(sl["power_interval"]!!.toInt()) { OverlayPrefs.setPowerInterval(ctx, it) }
        chg(sl["power_threshold"]!!) { OverlayPrefs.setPowerThreshold(ctx, it) }
        chg(pwAlertColor) { OverlayPrefs.setPowerAlertColor(ctx, it) }
        chg(pwNormalColor) { OverlayPrefs.setPowerNormalColor(ctx, it) }
        chg(sl["interval_cpu_freq"]!!.toInt()) { OverlayPrefs.setIntervalCpuFreq(ctx, it) }
        chg(sl["interval_gpu_freq"]!!.toInt()) { OverlayPrefs.setIntervalGpuFreq(ctx, it) }
        chg(sl["interval_cpu_loads"]!!.toInt()) { OverlayPrefs.setIntervalCpuLoads(ctx, it) }
        chg(sl["interval_gpu_load"]!!.toInt()) { OverlayPrefs.setIntervalGpuLoad(ctx, it) }
        chg(sl["interval_gpu_temp"]!!.toInt()) { OverlayPrefs.setIntervalGpuTemp(ctx, it) }
        chg(sl["interval_cpu_temp"]!!.toInt()) { OverlayPrefs.setIntervalCpuTemp(ctx, it) }
        chg(sl["interval_npu"]!!.toInt()) { OverlayPrefs.setIntervalNpu(ctx, it) }
        chg(sl["interval_devfreq"]!!.toInt()) { OverlayPrefs.setIntervalDevfreq(ctx, it) }
        chg(deepPauseOn) { OverlayPrefs.setDeepPauseOnScreenOff(ctx, it) }
        chg(selFont) { OverlayPrefs.setFontFamily(ctx, it) }
        chg(sl["chip_threshold"]!!.toInt()) { OverlayPrefs.setChipThreshold(ctx, it) }
        chg(sl["interval_ram"]!!.toInt()) { OverlayPrefs.setIntervalRam(ctx, it) }
        chg(sl["interval_gpu_mem"]!!.toInt()) { OverlayPrefs.setIntervalGpuMem(ctx, it) }
        chg(sl["fs_gpumem"]!!) { OverlayPrefs.setGpuMemFontScale(ctx, it) }
        chg(sl["off_gpumem_x"]!!) { OverlayPrefs.setOff_gpumem_x(ctx, it) }
        chg(sl["off_gpumem_y"]!!) { OverlayPrefs.setOff_gpumem_y(ctx, it) }
        chg(sl["main_off_mem_x"]!!) { OverlayPrefs.setOff_mem_x(ctx, it) }
        chg(sl["main_off_mem_y"]!!) { OverlayPrefs.setOff_mem_y(ctx, it) }
        chg(sl["mem_split"]!!) { OverlayPrefs.setMemSplit(ctx, it) }
        chg(sl["main_off_bus_x"]!!) { OverlayPrefs.setOff_bus_x(ctx, it) }
        chg(sl["main_off_bus_y"]!!) { OverlayPrefs.setOff_bus_y(ctx, it) }
        chg(sl["main_off_dsu_x"]!!) { OverlayPrefs.setOff_dsu_x(ctx, it) }
        chg(sl["main_off_dsu_y"]!!) { OverlayPrefs.setOff_dsu_y(ctx, it) }
        chg(sl["main_off_mif_x"]!!) { OverlayPrefs.setOff_mif_x(ctx, it) }
        chg(sl["main_off_mif_y"]!!) { OverlayPrefs.setOff_mif_y(ctx, it) }
        chg(sl["main_off_dsun_x"]!!) { OverlayPrefs.setOff_dsun_x(ctx, it) }
        chg(sl["main_off_dsun_y"]!!) { OverlayPrefs.setOff_dsun_y(ctx, it) }
        chg(sl["main_off_mifn_x"]!!) { OverlayPrefs.setOff_mifn_x(ctx, it) }
        chg(sl["main_off_mifn_y"]!!) { OverlayPrefs.setOff_mifn_y(ctx, it) }
        chg(sl["main_off_dsuf_x"]!!) { OverlayPrefs.setOff_dsuf_x(ctx, it) }
        chg(sl["main_off_dsuf_y"]!!) { OverlayPrefs.setOff_dsuf_y(ctx, it) }
        chg(sl["main_off_miff_x"]!!) { OverlayPrefs.setOff_miff_x(ctx, it) }
        chg(sl["main_off_miff_y"]!!) { OverlayPrefs.setOff_miff_y(ctx, it) }
        chg(sl["main_off_dsut_x"]!!) { OverlayPrefs.setOff_dsut_x(ctx, it) }
        chg(sl["main_off_dsut_y"]!!) { OverlayPrefs.setOff_dsut_y(ctx, it) }
        chg(sl["main_off_mift_x"]!!) { OverlayPrefs.setOff_mift_x(ctx, it) }
        chg(sl["main_off_mift_y"]!!) { OverlayPrefs.setOff_mift_y(ctx, it) }
        chg(sl["main_off_dsuv_x"]!!) { OverlayPrefs.setOff_dsuv_x(ctx, it) }
        chg(sl["main_off_dsuv_y"]!!) { OverlayPrefs.setOff_dsuv_y(ctx, it) }
        chg(sl["main_off_mifv_x"]!!) { OverlayPrefs.setOff_mifv_x(ctx, it) }
        chg(sl["main_off_mifv_y"]!!) { OverlayPrefs.setOff_mifv_y(ctx, it) }
        chg(sl["main_off_div_x"]!!) { OverlayPrefs.setOff_div_x(ctx, it) }
        chg(sl["main_off_div_y"]!!) { OverlayPrefs.setOff_div_y(ctx, it) }
        chg(sl["main_off_mempct_x"]!!) { OverlayPrefs.setOff_mempct_x(ctx, it) }
        chg(sl["main_off_mempct_y"]!!) { OverlayPrefs.setOff_mempct_y(ctx, it) }
        chg(sl["main_off_memgb_x"]!!) { OverlayPrefs.setOff_memgb_x(ctx, it) }
        chg(sl["main_off_memgb_y"]!!) { OverlayPrefs.setOff_memgb_y(ctx, it) }
        chg(sl["main_off_cnt_x"]!!) { OverlayPrefs.setOff_maincnt_x(ctx, it) }
        chg(sl["main_off_cnt_y"]!!) { OverlayPrefs.setOff_maincnt_y(ctx, it) }
        chg(sl["main_off_ac_x"]!!) { OverlayPrefs.setOff_ac_x(ctx, it) }
        chg(sl["main_off_ac_y"]!!) { OverlayPrefs.setOff_ac_y(ctx, it) }
        chg(sl["main_off_actxt_x"]!!) { OverlayPrefs.setOff_actxt_x(ctx, it) }
        chg(sl["main_off_actxt_y"]!!) { OverlayPrefs.setOff_actxt_y(ctx, it) }
        chg(sl["main_off_hot_x"]!!) { OverlayPrefs.setOff_mainhot_x(ctx, it) }
        chg(sl["main_off_hot_y"]!!) { OverlayPrefs.setOff_mainhot_y(ctx, it) }
        chg(acOn) { OverlayPrefs.setAcVisible(ctx, it) }
        chg(memOn) { OverlayPrefs.setMemVisible(ctx, it) }
        chg(gpuMemOn) { OverlayPrefs.setGpuMemVisible(ctx, it) }
        chg(hot95On) { OverlayPrefs.setHotspot95CountVisible(ctx, it) }
        chg(powerMainOn) { OverlayPrefs.setPowerInMainVisible(ctx, it) }
        chg(batLabel) { OverlayPrefs.setMainBatLabel(ctx, it) }
        chg(pdLabel) { OverlayPrefs.setMainPdLabel(ctx, it) }
        chg(sl["col_gap_volt"]!!) { OverlayPrefs.setColGapVolt(ctx, it) }
        chg(sl["col_gap_name"]!!) { OverlayPrefs.setColGapName(ctx, it) }
        chg(sl["col_gap_freq"]!!) { OverlayPrefs.setColGapFreq(ctx, it) }
        chg(sl["col_gap_temp"]!!) { OverlayPrefs.setColGapTemp(ctx, it) }
        chg(sl["col_gap_load"]!!) { OverlayPrefs.setColGapLoad(ctx, it) }
        chg(sl["gap_power_section"]!!) { OverlayPrefs.setGapPowerSection(ctx, it) }
        for (id in voltIds) {
            chg(voltVisible[id] ?: false) { OverlayPrefs.setVoltVisible(ctx, id, it) }
        }
        for (id in zoneIds) {
            chg(zoneNames[id] ?: id) { OverlayPrefs.setDisplayName(ctx, id, it) }
            chg(zoneLabels[id] ?: id) { OverlayPrefs.setLabel(ctx, id, it) }
            chg(zoneColors[id] ?: 0xFFF8F8F2.toInt()) { OverlayPrefs.setColor(ctx, id, it) }
            chg(zoneVisible[id] ?: true) { OverlayPrefs.setZoneVisible(ctx, id, it) }
        }
        for (eid in ELEM_IDS) {
            // Sempre consome um slot: pular um chg desalinharia todos os
            // indices seguintes e corromperia a deteccao de mudanca.
            chg(elemColors[eid] ?: 0) {
                if (it != 0) OverlayPrefs.setElemColor(ctx, eid, it)
            }
        }
        for (k in battKeys) {
            chg(battLabels[k] ?: k) { OverlayPrefs.setBattLabel(ctx, k, it) }
            chg(battVisible[k] ?: true) { OverlayPrefs.setBatteryVisible(ctx, k, it) }
        }
        for (fId in allFreqIds) {
            chg(freqLabels[fId] ?: fId) { OverlayPrefs.setFreqLabel(ctx, fId, it) }
            chg(freqVisible[fId] ?: true) { OverlayPrefs.setFreqVisible(ctx, fId, it) }
        }
        chg(sl["hotspot_threshold"]!!.toInt()) {
            OverlayPrefs.setHotspotThreshold(ctx, it)
            // trocar o limiar invalida a contagem acumulada
            OverlayPrefs.setHotspot95Count(ctx, 0)
            ThermalReader.resetHotspotEdgeState()
        }
        seeding[0] = false
    }

    // Toca em todos os estados: qualquer mudança recompõe e reinicia o efeito,
    // que então grava só o que de fato mudou.
    val fingerprint = listOf<Any?>(
        sl.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        mainOn, minimalOn, devfreqOn, batteryOn, historyOn, histRecOn, hot95On,
        powerMainOn, gpuMemOn, acOn, memOn, deepPauseOn, selFont,
        titMain, titDevfreq, titBattery, titHistory, batLabel, pdLabel,
        bgColor, pwNormalColor, pwAlertColor,
        elemColors.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value },
        zoneNames.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        zoneLabels.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        zoneColors.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value },
        zoneVisible.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        voltVisible.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        freqLabels.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        freqVisible.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        battLabels.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() },
        battVisible.entries.fold(0) { a, e -> a * 31 + e.key.hashCode() * 31 + e.value.hashCode() }
    ).hashCode()

    LaunchedEffect(fingerprint) { persistAll() }

    var accessMode by remember { mutableStateOf(AccessManager.mode(ctx)) }
    var hwSummary by remember { mutableStateOf(HwProfile.summary(ctx)) }
    var remapping by remember { mutableStateOf(false) }

    fun remapHardware() {
        if (remapping) return
        remapping = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (ThermalReader.isServiceBound()) {
            Thread {
                val ok = ThermalReader.syncHwInfo(ctx, reprobe = true) != null
                main.post {
                    remapping = false
                    hwSummary = HwProfile.summary(ctx)
                    Toast.makeText(ctx, if (ok) "Hardware remapeado — reabra as configurações" else "Falha ao remapear", Toast.LENGTH_SHORT).show()
                }
            }.apply { isDaemon = true; start() }
        } else {
            val backend = AccessManager.resolve(ctx)
            if (backend == AccessManager.Backend.NONE) {
                remapping = false
                Toast.makeText(ctx, "Precisa de root ou Shizuku para mapear", Toast.LENGTH_LONG).show()
                return
            }
            HwProfile.forgetApplied(ctx)
            HwProfile.mapOnce(ctx, backend) { ok ->
                remapping = false
                hwSummary = HwProfile.summary(ctx)
                Toast.makeText(ctx, if (ok) "Hardware remapeado — reabra as configurações" else "Falha ao remapear", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabNames = listOf("Overlays", "Sensores", "Aparência")

    CompositionLocalProvider(LocalAppTheme provides theme, LocalAppFont provides appFont) {
    Surface(color = Color(theme.cBg), modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

            Text("CONFIGURAÇÕES", color = Green, fontSize = 22.sp,
                fontFamily = Mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp))

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Green,
                edgePadding = 14.dp
            ) {
                tabNames.forEachIndexed { i, name ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(name, fontFamily = Mono, fontSize = 13.sp,
                            fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal) },
                        selectedContentColor = Green, unselectedContentColor = Comment)
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when (selectedTab) {

                    0 -> {
                        item { ExpandableSection("ACESSO (ROOT / ADB)") {
                            SubText("Automático: usa root (Magisk/KernelSU/APatch) se houver; senão ADB pelo Shizuku. " +
                                "Com root aparecem mais dados (frequência real da CPU, GPU, memória da GPU).")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                for ((id, nome) in listOf(
                                    AccessManager.MODE_AUTO to "Automático",
                                    AccessManager.MODE_ROOT to "Root",
                                    AccessManager.MODE_SHIZUKU to "ADB (Shizuku)")) {
                                    val sel = id == accessMode
                                    Text(nome, color = if (sel) DarkBg else Foreground,
                                        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Green else Comment.copy(0.3f))
                                            .clickable {
                                                if (accessMode != id) {
                                                    accessMode = id
                                                    AccessManager.setMode(ctx, id)
                                                    if (OverlayService.isRunning) Toast.makeText(ctx,
                                                        "Pare e inicie o monitor para trocar o acesso",
                                                        Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                            val live = ThermalReader.activeBackend
                            SubText("Em uso agora: " + when {
                                !OverlayService.isRunning -> "monitor parado"
                                live == AccessManager.Backend.ROOT -> "root"
                                live == AccessManager.Backend.SHIZUKU -> "ADB (Shizuku)"
                                else -> "conectando..."
                            })
                        }}
                        item { ExpandableSection("HARDWARE DETECTADO") {
                            Text(hwSummary, color = Foreground, fontSize = 12.sp, fontFamily = Mono,
                                modifier = Modifier.padding(vertical = 6.dp))
                            SubText("O mapa é feito automaticamente na primeira vez. Remapear reaplica nomes, " +
                                "visibilidade e rótulos para este chip (sobrescreve esses ajustes).")
                            ActionButton(if (remapping) "MAPEANDO..." else "REMAPEAR HARDWARE", Cyan, DarkBg) {
                                remapHardware()
                            }
                        }}
                        item { ExpandableSection("TEMA") {
                            SubText("Escolha o tema visual do app (não afeta os overlays)")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                for (t in AppTheme.ALL) {
                                    val sel = t.id == selTheme
                                    Text(t.displayName, color = if (sel) DarkBg else Foreground,
                                        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Green else Comment.copy(0.3f))
                                            .clickable { selTheme = t.id; AppTheme.setCurrent(ctx, t.id) }
                                            .padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                        }}
                        item { ExpandableSection("PAINÉIS") {
                            SubText("Escolha quais overlays exibir na tela")
                            SettingsSwitch("Painel Principal", "Temperaturas + CPU/GPU freq", mainOn) { mainOn = it }
                            SettingsSwitch("Painel Frequências", "L3/DDR/LLCC freq", devfreqOn) { devfreqOn = it }
                            SettingsSwitch("Painel Bateria", "Potência, temperatura, ciclos", batteryOn) { batteryOn = it }
                            SettingsSwitch("Painel Histórico 24h", "Gráfico de temperatura", historyOn) { historyOn = it }
                            SettingsSwitch("Gravar histórico de 24h", "Necessário para o gráfico", histRecOn) { histRecOn = it }
                        }}
                        item { ExpandableSection("PAINEL PRINCIPAL") {
                            SettingsSwitch("Modo minimalista", "Só zonas/loads, sem headers", minimalOn) { minimalOn = it }
                            SettingsSwitch("Memória (RAM/SWAP)", "Seção no fim do painel", memOn) { memOn = it }
                            SubText("Memória — mover a seção inteira:")
                            SliderRow("Memória: X", sl, "main_off_mem_x", -300f, 300f, "px")
                            SliderRow("Memória: Y", sl, "main_off_mem_y", -300f, 300f, "px")
                            SubText("Memória — mover só as legendas:")
                            SliderRow("Indentação interna", sl, "telemetry_core_indent", 0f, 48f, "px")
                            SliderRow("Legenda RAM: X", sl, "tel_off_ramlbl_x", -200f, 200f, "px")
                            SliderRow("Legenda RAM: Y", sl, "tel_off_ramlbl_y", -200f, 200f, "px")
                            SliderRow("Legenda SWAP: X", sl, "tel_off_swaplbl_x", -200f, 200f, "px")
                            SliderRow("Legenda SWAP: Y", sl, "tel_off_swaplbl_y", -200f, 200f, "px")
                            SliderRow("Fonte legenda RAM/SWAP", sl, "tel_mem_label_size", 0.5f, 2.0f, "x")
                            SubText("Memória — porcentagem e total:")
                            SliderRow("Porcentagem: X", sl, "main_off_mempct_x", -200f, 200f, "px")
                            SliderRow("Porcentagem: Y", sl, "main_off_mempct_y", -200f, 200f, "px")
                            SliderRow("Total em GB: X", sl, "main_off_memgb_x", -300f, 300f, "px")
                            SliderRow("Total em GB: Y", sl, "main_off_memgb_y", -200f, 200f, "px")
                            SubText("Barramento (L3/DDR) — onde o bloco se divide:")
                            SliderRow("Divisão do bloco", sl, "mem_split", 0.25f, 0.75f, "%")
                            SliderRow("Divisória: X", sl, "main_off_div_x", -200f, 200f, "px")
                            SliderRow("Divisória: Y", sl, "main_off_div_y", -100f, 100f, "px")
                            SubText("Barramento — mover a coluna inteira:")
                            SliderRow("Barramento: X", sl, "main_off_bus_x", -300f, 300f, "px")
                            SliderRow("Barramento: Y", sl, "main_off_bus_y", -300f, 300f, "px")
                            SubText("Barramento — mover cada domínio:")
                            SliderRow("L3: X", sl, "main_off_dsu_x", -200f, 200f, "px")
                            SliderRow("L3: Y", sl, "main_off_dsu_y", -200f, 200f, "px")
                            SliderRow("DDR: X", sl, "main_off_mif_x", -200f, 200f, "px")
                            SliderRow("DDR: Y", sl, "main_off_mif_y", -200f, 200f, "px")
                            SubText("Barramento — mover só o título:")
                            SliderRow("Título L3: X", sl, "main_off_dsun_x", -200f, 200f, "px")
                            SliderRow("Título L3: Y", sl, "main_off_dsun_y", -200f, 200f, "px")
                            SliderRow("Título DDR: X", sl, "main_off_mifn_x", -200f, 200f, "px")
                            SliderRow("Título DDR: Y", sl, "main_off_mifn_y", -200f, 200f, "px")
                            SubText("Barramento — mover só a frequência:")
                            SliderRow("Freq. L3: X", sl, "main_off_dsuf_x", -200f, 200f, "px")
                            SliderRow("Freq. L3: Y", sl, "main_off_dsuf_y", -200f, 200f, "px")
                            SliderRow("Freq. DDR: X", sl, "main_off_miff_x", -200f, 200f, "px")
                            SliderRow("Freq. DDR: Y", sl, "main_off_miff_y", -200f, 200f, "px")
                            SubText("Barramento — mover só a temperatura:")
                            SliderRow("Temp. L3: X", sl, "main_off_dsut_x", -200f, 200f, "px")
                            SliderRow("Temp. L3: Y", sl, "main_off_dsut_y", -200f, 200f, "px")
                            SliderRow("Temp. DDR: X", sl, "main_off_mift_x", -200f, 200f, "px")
                            SliderRow("Temp. DDR: Y", sl, "main_off_mift_y", -200f, 200f, "px")
                            SubText("Barramento — mover só a tensão, sem o título:")
                            SliderRow("Tensão L3: X", sl, "main_off_dsuv_x", -200f, 200f, "px")
                            SliderRow("Tensão L3: Y", sl, "main_off_dsuv_y", -200f, 200f, "px")
                            SliderRow("Tensão DDR: X", sl, "main_off_mifv_x", -200f, 200f, "px")
                            SliderRow("Tensão DDR: Y", sl, "main_off_mifv_y", -200f, 200f, "px")
                            SubText("Cores dos elementos (não-térmicos):")
                            for ((eid, elabel) in listOf("freq" to "Frequência", "load" to "Load dos cores",
                                "ram" to "RAM", "swap" to "SWAP", "volt" to "Tensão",
                                "dim" to "Rótulos (#PWR etc)", "pwrbat" to "Valores PWR/BAT", "section" to "Mini-títulos (ARM/GPU)",
                                "gpumem" to "Memória da GPU")) {
                                SubText(elabel)
                                SimpleColorRow(elemColors[eid] ?: 0, PALETTE) { elemColors[eid] = it }
                            }
                            SettingsSwitch("Mostrar Power no overlay", "Potência no painel principal", powerMainOn) { powerMainOn = it }
                            SettingsSwitch("Mostrar AC (carregador)", "Chip ao lado do #PWR", acOn) { acOn = it }
                            SubText("AC — mover o chip todo:")
                            SliderRow("AC chip: X", sl, "main_off_ac_x", -400f, 400f, "px")
                            SliderRow("AC chip: Y", sl, "main_off_ac_y", -200f, 200f, "px")
                            SubText("AC — mover o texto dentro do chip:")
                            SliderRow("AC texto: X", sl, "main_off_actxt_x", -100f, 100f, "px")
                            SliderRow("AC texto: Y", sl, "main_off_actxt_y", -100f, 100f, "px")
                            SubText(">95° — mover o contador (fica ao lado do AC):")
                            SliderRow(">95°: X", sl, "main_off_cnt_x", -300f, 300f, "px")
                            SliderRow(">95°: Y", sl, "main_off_cnt_y", -300f, 300f, "px")
                            SubText("Hotspot (número grande) — mover:")
                            SliderRow("Hotspot: X", sl, "main_off_hot_x", -400f, 400f, "px")
                            SliderRow("Hotspot: Y", sl, "main_off_hot_y", -200f, 200f, "px")
                            SubText("Legenda BAT:")
                            SettingsTextField(batLabel, "BAT") { batLabel = it }
                            SubText("Legenda PD:")
                            SettingsTextField(pdLabel, "PD") { pdLabel = it }
                            SettingsSwitch("Contador de hotspot", "Vezes acima do limiar", hot95On) { hot95On = it }
                            SliderRow("Limiar do contador", sl, "hotspot_threshold", 50f, 110f, "°C")
                            ActionButton("ZERAR CONTADOR", Orange, DarkBg) {
                                OverlayPrefs.setHotspot95Count(ctx, 0)
                                Toast.makeText(ctx, "Zerado", Toast.LENGTH_SHORT).show()
                            }
                            SubText("Zonas térmicas (Nome | Label | Cor)")
                            for (id in zoneIds) {
                                ZoneRow(id, zoneVisible[id] ?: true, zoneNames[id] ?: id,
                                    zoneLabels[id] ?: id, zoneColors[id] ?: 0xFFF8F8F2.toInt(),
                                    onVisChange = { zoneVisible[id] = it },
                                    onNameChange = { zoneNames[id] = it },
                                    onLabelChange = { zoneLabels[id] = it },
                                    onColorChange = { zoneColors[id] = it })
                            }
                        }}
                    }

                    1 -> {
                        item { ExpandableSection("FREQUÊNCIAS") {
                            SubText("CPU e GPU")
                            for (fId in OverlayPrefs.LIGHT_FREQ_IDS) {
                                FreqRow(fId, freqVisible[fId] ?: true, freqLabels[fId] ?: fId,
                                    { freqVisible[fId] = it }, { freqLabels[fId] = it })
                            }
                            SubText("Domínios devfreq — todos numa leitura só, mesmo custo")
                            for (fId in OverlayPrefs.MEMORY_FREQ_IDS) {
                                FreqRow(fId, freqVisible[fId] ?: true, freqLabels[fId] ?: fId,
                                    { freqVisible[fId] = it }, { freqLabels[fId] = it })
                            }
                        }}
                        item { ExpandableSection("BATERIA") {
                            SubText("Ative/desative e renomeie cada item")
                            for (k in battKeys) {
                                FreqRow(k, battVisible[k] ?: true, battLabels[k] ?: k,
                                    { battVisible[k] = it }, { battLabels[k] = it })
                            }
                        }}
                        item { ExpandableSection("POTÊNCIA") {
                            SliderRow("Taxa de atualização", sl, "power_interval", 100f, 1000f, "ms")
                            SliderRow("Limite de alerta", sl, "power_threshold", 1f, 30f, "W")
                            SubText("Cor normal")
                            SimpleColorRow(pwNormalColor, PALETTE) { pwNormalColor = it }
                            SubText("Cor de alerta")
                            SimpleColorRow(pwAlertColor, PALETTE) { pwAlertColor = it }
                        }}
                        item { ExpandableSection("MONITORAMENTO") {
                            SubText("Intervalos de coleta independentes por dado")
                            SliderRow("Frequência CPU (sysfs)", sl, "interval_cpu_freq", 100f, 2000f, "ms")
                            SliderRow("Frequência GPU (sysfs)", sl, "interval_gpu_freq", 100f, 2000f, "ms")
                            SliderRow("Load CPU (/proc/stat)", sl, "interval_cpu_loads", 100f, 2000f, "ms")
                            SliderRow("Load GPU (kgsl)", sl, "interval_gpu_load", 100f, 2000f, "ms")
                            SliderRow("Temp GPU (kgsl/thermal)", sl, "interval_gpu_temp", 100f, 5000f, "ms")
                            SliderRow("RAM/SWAP (/proc/meminfo)", sl, "interval_ram", 100f, 2000f, "ms")
                            SliderRow("Memória GPU (kgsl)", sl, "interval_gpu_mem", 100f, 5000f, "ms")
                            SliderRow("Temp CPU (thermal_zone)", sl, "interval_cpu_temp", 100f, 1000f, "ms")
                            SubText("NPU — o Hexagon não expõe frequência no Snapdragon (só temperatura)")
                            SliderRow("Frequência NPU", sl, "interval_npu", 100f, 5000f, "ms")
                            SubText("Barramento (bus_dcvs) — L3, DDR, LLCC — e tensões")
                            SliderRow("Frequência do barramento", sl, "interval_devfreq", 100f, 5000f, "ms")
                            SubText("Sensores têm resolução de 1 °C: abaixo de ~100 ms não há ganho, só custo de CPU")
                            SettingsSwitch(
                                "Parar coleta com a tela apagada",
                                "Economiza bateria, mas o contador de hotspot para junto",
                                deepPauseOn
                            ) { deepPauseOn = it }
                        }}
                        item { ExpandableSection("ALERTA DE TEMPERATURA") {
                            SubText("Abaixo do limiar o número segue a escala de calor; a partir dele fica vermelho")
                            SliderRow("Limiar do chip", sl, "chip_threshold", 30f, 110f, "°C")
                        }}
                        item { ExpandableSection("TENSÃO") {
                            SubText("No Snapdragon a tensão da CPU é controlada pelo firmware (CPR) e quase nunca aparece; " +
                                "só são exibidos os rails que o kernel expõe")
                            for (id in voltIds) {
                                SettingsSwitch(
                                    OverlayPrefs.DEFAULT_NAMES[id] ?: id,
                                    "setpoint do PMIC",
                                    voltVisible[id] ?: false
                                ) { voltVisible[id] = it }
                            }
                        }}
                        item { ExpandableSection("MEMÓRIA DA GPU") {
                            SubText("Exibida na linha da GPU, entre o rótulo e a frequência")
                            SettingsSwitch("Exibir memória da GPU", "kgsl page_alloc (precisa de root)", gpuMemOn) { gpuMemOn = it }
                            SliderRow("Tamanho da fonte", sl, "fs_gpumem", 0.4f, 2.0f, "x")
                            SliderRow("Posição ← →", sl, "off_gpumem_x", -200f, 200f, "px")
                            SliderRow("Posição ↑ ↓", sl, "off_gpumem_y", -200f, 200f, "px")
                        }}
                    }

                    2 -> {
                        item { ExpandableSection("COLUNAS DO OVERLAY") {
                            SubText("0.0 = sem gap  1.0 = padrão  4.0 = máximo")
                            SliderRow("Espaço col. Nome", sl, "col_gap_name", 0f, 4f, "x")
                            SliderRow("Espaço col. Frequência", sl, "col_gap_freq", 0f, 4f, "x")
                            SliderRow("Espaço col. Tensão", sl, "col_gap_volt", 0f, 4f, "x")
                            SliderRow("Espaço col. Load (%)", sl, "col_gap_load", 0f, 4f, "x")
                            SliderRow("Espaço col. Temperatura", sl, "col_gap_temp", 0f, 4f, "x")
                            SliderRow("Gap Power → 1ª seção", sl, "gap_power_section", 0f, 4f, "x")
                        }}
                        item { ExpandableSection("APARÊNCIA") {
                            SubText("Títulos dos painéis")
                            SettingsTextField(titMain, OverlayPrefs.defaultTitle()) { titMain = it }
                            SettingsTextField(titDevfreq, "FREQUÊNCIAS") { titDevfreq = it }
                            SettingsTextField(titBattery, "BATERIA") { titBattery = it }
                            SettingsTextField(titHistory, "HISTÓRICO 24H") { titHistory = it }
                            SubText("Fonte do app e dos overlays")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                for ((id, nome) in FONT_CHOICES) {
                                    val sel = id == selFont
                                    Text(nome, color = if (sel) DarkBg else Foreground,
                                        fontFamily = familyFor(id), fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Cyan else Comment.copy(0.3f))
                                            .clickable {
                                                selFont = id
                                                OverlayPrefs.setFontFamily(ctx, id)
                                                Fonts.reloadMode(ctx)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                            SubText("A Styrene B tem dígitos de largura fixa; a do sistema não, " +
                                    "então os números mudam de largura a cada leitura")
                            SubText("Cor de fundo")
                            SimpleColorRow(bgColor, BG_PALETTE) { bgColor = it }
                            SliderRow("Transparência", sl, "bg_alpha", 0f, 255f, "alpha")
                            SubText("Tamanho da fonte")
                            SliderRow("Fonte global", sl, "font_size", 0.5f, 2.5f, "%")
                            SubText("Ajuste fino por categoria")
                            SliderRow("Título", sl, "fs_title", 0.5f, 2.5f, "%")
                            SliderRow("Valores", sl, "fs_value", 0.5f, 2.5f, "%")
                            SliderRow("Legendas", sl, "fs_label", 0.5f, 2.5f, "%")
                            SliderRow("Gráfico", sl, "fs_chart", 0.5f, 2.5f, "%")
                            SubText("Espaçamento")
                            SliderRow("Vertical", sl, "v_spacing", 0.0f, 2.5f, "%")
                            SliderRow("Horizontal", sl, "h_spacing", 0.0f, 2.5f, "%")
                            SubText("Margens internas")
                            SliderRow("Superior", sl, "margin_top", 0f, 80f, "dp")
                            SliderRow("Inferior", sl, "margin_bottom", 0f, 80f, "dp")
                            SliderRow("Esquerda", sl, "margin_left", 0f, 80f, "dp")
                            SliderRow("Direita", sl, "margin_right", 0f, 80f, "dp")
                            SubText("Controles (botão ✕ e resize)")
                            SliderRow("Tamanho", sl, "control_size", 0.5f, 3.0f, "%")
                        }}
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
                item { SubText("As mudanças valem na hora, direto no overlay.") }
                item { ActionButton("RESTAURAR PADRÃO", Comment, Foreground) {
                    // Aplica o preset que vem no APK; se faltar, cai no reset seco.
                    val ok = SettingsBackup.applyBundledDefaults(ctx)
                    if (!ok) OverlayPrefs.resetAll(ctx)
                    // o preset e generico: reaplica o mapa deste aparelho por cima
                    HwProfile.reapply(ctx)
                    Toast.makeText(
                        ctx,
                        if (ok) "Configuração padrão aplicada — reabra as configurações"
                        else "Restaurado — reabra as configurações",
                        Toast.LENGTH_SHORT
                    ).show()
                    (ctx as? ComponentActivity)?.finish()
                }}
                item { SubText("Volta ao preset embutido no app (DarkAMOLED_FET) e reaplica o mapa do chip") }
                item { Spacer(Modifier.height(8.dp)) }
                item { SubText("Backup completo (tema, painéis, cores, intervalos, layout)") }
                item { ActionButton("EXPORTAR CONFIGURAÇÕES", Cyan, DarkBg) {
                    exportLauncher.launch(SettingsBackup.suggestedFileName())
                }}
                item { ActionButton("IMPORTAR CONFIGURAÇÕES", Purple, DarkBg) {
                    importLauncher.launch(arrayOf("application/json"))
                }}
            }
        }
    }
    }
}


@Composable
private fun SubText(text: String) {
    Text(text, color = Comment, fontSize = 11.sp, fontFamily = Mono,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun SettingsSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Foreground, fontSize = 14.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold)
            Text(desc, color = Comment, fontSize = 11.sp, fontFamily = Mono)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Green, checkedTrackColor = Green.copy(0.3f),
                uncheckedThumbColor = Comment, uncheckedTrackColor = Comment.copy(0.2f)))
    }
}

@Composable
private fun SettingsTextField(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, placeholder = { Text(hint, color = Comment) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Foreground, fontSize = 13.sp, fontFamily = Mono),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple, unfocusedBorderColor = Comment.copy(0.4f),
            cursorColor = Green, focusedContainerColor = DarkSurface, unfocusedContainerColor = DarkSurface),
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable
private fun SliderRow(label: String, state: MutableMap<String, Float>,
                      key: String, min: Float, max: Float, unit: String) {
    val value = state[key] ?: min
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember(key) { mutableStateOf("") }

    fun fmt(v: Float): String = when (unit) {
        "%" -> "${(v * 100).roundToInt()}%"
        "alpha" -> "${v.toInt()} (${(v / 255 * 100).roundToInt()}%)"
        "ms" -> "${v.toInt()}ms"
        "W" -> String.format("%.1fW", v)
        "°C" -> "${v.toInt()}°C"
        "x" -> String.format("%.1fx", v)
        "esp" -> "${v.toInt()} esp"
        "dp", "px" -> "${v.toInt()} $unit"
        else -> "${v.toInt()} $unit"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label: ", color = Cyan, fontSize = 13.sp, fontFamily = Mono,
                modifier = Modifier.weight(1f))

            Text(fmt(value), color = Green, fontSize = 13.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = NumFeatures),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurface)
                    .clickable { inputText = value.toInt().toString(); showInput = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Slider(value = value, onValueChange = { state[key] = it }, valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Cyan, activeTrackColor = Cyan.copy(0.7f),
                inactiveTrackColor = Comment.copy(0.3f)),
            modifier = Modifier.fillMaxWidth())
    }

    if (showInput) {
        Dialog(onDismissRequest = { showInput = false }) {
            Surface(color = DarkBg, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Cyan.copy(0.5f))) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(label, color = Foreground, fontSize = 14.sp,
                        fontFamily = Mono, fontWeight = FontWeight.Bold)
                    Text("Range: ${fmt(min)} – ${fmt(max)}", color = Comment,
                        fontSize = 11.sp, fontFamily = Mono,
                        modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it.filter { c -> c.isDigit() || c == '.' } },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Foreground, fontSize = 16.sp, fontFamily = Mono),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Comment.copy(0.4f),
                            cursorColor = Green, focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp)) {
                        Text("CANCELAR", color = Comment, fontSize = 13.sp, fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Comment.copy(0.15f))
                                .clickable { showInput = false }
                                .padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("OK", color = DarkBg, fontSize = 13.sp, fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Green)
                                .clickable {
                                    val v = inputText.toFloatOrNull()
                                    if (v != null) state[key] = v.coerceIn(min, max)
                                    showInput = false
                                }
                                .padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleColorRow(currentColor: Int, palette: IntArray, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp)) {
            Box(modifier = Modifier.size(28.dp)
                .clip(CircleShape)
                .background(Color(currentColor))
                .border(1.dp, Comment.copy(0.4f), CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(if (expanded) "Fechar cores" else "Escolher cor",
                color = Comment, fontSize = 13.sp, fontFamily = Mono)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in palette.toList().chunked(10)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) {
                            val sel = c == currentColor
                            Box(modifier = Modifier.size(26.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (sel) 3.dp else 1.dp,
                                    if (sel) Green else Comment.copy(0.3f), CircleShape)
                                .clickable { onChange(c) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(id: String, visible: Boolean, name: String, label: String,
                    color: Int, onVisChange: (Boolean) -> Unit,
                    onNameChange: (String) -> Unit, onLabelChange: (String) -> Unit,
                    onColorChange: (Int) -> Unit) {
    var showColorPicker by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = visible, onCheckedChange = onVisChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Green,
                    checkedTrackColor = Green.copy(0.3f), uncheckedThumbColor = Comment,
                    uncheckedTrackColor = Comment.copy(0.2f)),
                modifier = Modifier.padding(end = 8.dp))
            Text(id, color = Foreground, fontSize = 14.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold)
        }
        Row(modifier = Modifier.padding(start = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = onNameChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = Foreground, fontSize = 12.sp, fontFamily = Mono),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple,
                    unfocusedBorderColor = Comment.copy(0.3f), cursorColor = Green,
                    focusedContainerColor = DarkSurface, unfocusedContainerColor = DarkSurface),
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = label, onValueChange = onLabelChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = Cyan, fontSize = 12.sp, fontFamily = Mono),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan,
                    unfocusedBorderColor = Comment.copy(0.3f), cursorColor = Green,
                    focusedContainerColor = DarkSurface, unfocusedContainerColor = DarkSurface),
                singleLine = true, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(Color(color))
                .border(1.dp, Comment.copy(0.4f), CircleShape)
                .clickable { showColorPicker = true })
        }
    }
    if (showColorPicker) {
        Dialog(onDismissRequest = { showColorPicker = false }) {
            Surface(color = DarkBg, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Purple.copy(0.5f))) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Cor de $id", color = Foreground, fontSize = 14.sp, fontFamily = Mono)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())) {
                        for (prow in PALETTE.toList().chunked(10)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (c in prow) {
                                    Box(modifier = Modifier.size(26.dp).clip(CircleShape)
                                        .background(Color(c))
                                        .border(if (c == color) 3.dp else 1.dp,
                                            if (c == color) Green else Comment.copy(0.3f), CircleShape)
                                        .clickable { onColorChange(c); showColorPicker = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreqRow(id: String, visible: Boolean, label: String,
                    onVisChange: (Boolean) -> Unit, onLabelChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Switch(checked = visible, onCheckedChange = onVisChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Purple,
                checkedTrackColor = Purple.copy(0.3f), uncheckedThumbColor = Comment,
                uncheckedTrackColor = Comment.copy(0.2f)),
            modifier = Modifier.padding(end = 8.dp))
        OutlinedTextField(value = label, onValueChange = onLabelChange,
            textStyle = androidx.compose.ui.text.TextStyle(color = Purple, fontSize = 13.sp, fontFamily = Mono),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple,
                unfocusedBorderColor = Comment.copy(0.3f), cursorColor = Green,
                focusedContainerColor = DarkSurface, unfocusedContainerColor = DarkSurface),
            singleLine = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(text, color = fg, fontSize = 14.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (expanded) Purple.copy(0.5f) else Comment.copy(0.2f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(title, color = if (expanded) Green else Foreground,
                fontSize = 15.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "▲" else "▼", color = if (expanded) Green else Comment,
                fontSize = 14.sp, fontFamily = Mono)
        }

        AnimatedVisibility(visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}
