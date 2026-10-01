package com.siliconfet.thermalmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

class ThermalOverlayView(context: Context) : View(context) {
    private val HUD_TITLE = 1.15f
    private val HUD_FOCUS = 1.70f
    private val HUD_SEC   = 1.05f
    private val HUD_ROW   = 1.08f
    private val VOLT_STEP_UV = 6250
    private val VOLT_SLOTS = 320

    private var wScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private var hScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private val minScale = 0.4f; private val maxScale = 3.5f

    private val cR = Color.parseColor("#FF5555")
    private val cO = Color.parseColor("#FFB86C")
    private val cW = Color.parseColor("#F8F8F2")
    private val cG = Color.parseColor("#50FA7B")
    private val cC = Color.parseColor("#8BE9FD")
    private val cD = Color.parseColor("#6272A4")
    private val cP = Color.parseColor("#BD93F9")
    private val FREQ_HI = Color.parseColor("#D7B8FF")
    private val HEAT_Y = Color.parseColor("#FFFF77")

    private var eFreq = 0; private var eLoad = 0; private var eRam = 0
    private var eSwap = 0; private var eVolt = 0; private var eDim = 0; private var ePwrBat = 0; private var eSection = 0
    private var eGpuMem = 0
    private fun loadElemColors() {
        eFreq = OverlayPrefs.getElemColor(context, "freq", FREQ_HI)
        eLoad = OverlayPrefs.getElemColor(context, "load", cW)
        eRam = OverlayPrefs.getElemColor(context, "ram", cC)
        eSwap = OverlayPrefs.getElemColor(context, "swap", cP)
        eVolt = OverlayPrefs.getElemColor(context, "volt", cP)
        eDim = OverlayPrefs.getElemColor(context, "dim", cD)
        ePwrBat = OverlayPrefs.getElemColor(context, "pwrbat", cW)
        eSection = OverlayPrefs.getElemColor(context, "section", cD)
        eGpuMem = OverlayPrefs.getElemColor(context, "gpumem", cC)
    }

    private fun tf(p: Paint, weight: Int): Paint { Fonts.apply(p, context, weight); return p }

    /** Reaplica a familia escolhida. Chamado no reload porque ela e configuravel. */
    private fun applyFonts() {
        Fonts.reloadMode(context)
        tf(titP, Fonts.BOLD); tf(secP, Fonts.BOLD); tf(hudEyebrowP, Fonts.BOLD)
        tf(hudBigP, Fonts.BOLD); tf(hudChipTxtP, Fonts.BOLD)
        tf(nmP, Fonts.MEDIUM); tf(tmpP, Fonts.MEDIUM); tf(loadP, Fonts.MEDIUM)
        tf(fqP, Fonts.MEDIUM); tf(hotP, Fonts.MEDIUM); tf(hotCntP, Fonts.MEDIUM)
        tf(dimP, Fonts.REGULAR); tf(gmP, Fonts.REGULAR)
        hudChipTxtP.textAlign = Paint.Align.CENTER
        hudEyebrowP.letterSpacing = 0.18f
    }

    private val bgP = Paint().apply { style = Paint.Style.FILL }
    private val titP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cW }, Fonts.BOLD)
    private val nmP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.MEDIUM)
    private val dimP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cD }, Fonts.REGULAR)
    private val tmpP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.MEDIUM)
    private val loadP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cP }, Fonts.MEDIUM)
    private val secP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cW }, Fonts.BOLD)

    private val hudFillP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudTrackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudChipP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudChipTxtP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }, Fonts.BOLD)
    private val hudEdgeP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hudBrkP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val hudRuleP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hudEyebrowP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { letterSpacing = 0.18f }, Fonts.BOLD)
    private val hudBigP   = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.BOLD)
    private val hudTrackRect = RectF()
    private val hudChipRect  = RectF()
    private val bgRect = RectF()
    private val fqP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cP }, Fonts.MEDIUM)
    private val gmP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.REGULAR)
    private val hotP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.MEDIUM)

    private val hotCntP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cW }, Fonts.MEDIUM)

    private val sd = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor; wScale = (wScale * f).coerceIn(minScale, maxScale)
            hScale = (hScale * f).coerceIn(minScale, maxScale); reload(); requestLayout(); return true
        }
    })

    var onDragListener: ((Float, Float) -> Unit)? = null
    var onDragEndListener: (() -> Unit)? = null
    var onCloseListener: (() -> Unit)? = null
    var onStopListener: (() -> Unit)? = null

    private val gd = android.view.GestureDetector(context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onStopListener?.invoke(); return true
            }
        })

    private enum class TM { NONE, DRAG, RWH }
    private var tm = TM.NONE; private var ltx = 0f; private var lty = 0f

    private val bfs = 13f; private val blh = 20f
    private val bcr = 12f; private val bw0 = 260f
    private val baseHandleArea = 28f; private val cg = 1f

    var isConnected = false

    @Volatile var cachedBatStr: String = "--"
    @Volatile var cachedBatColor: Int = 0
    @Volatile var cachedPdStr: String = "0.00W"

    private var uf = 1.0f; private var vSp = 1.0f; private var hSp = 1.0f
    private var mL = 4f; private var mR = 4f; private var mT = 4f; private var mB = 4f
    private var ovT = ""
    private var bgC = Color.parseColor("#1E1E2E"); private var bgA = 221
    private var ctlSize = 1.0f

    private var fsTitle = 1.0f; private var fsValue = 1.0f
    private var fsLabel = 1.0f

    private var minimal = false

    private var visSec = listOf<Pair<String, List<String>>>()

    private var cachedGapName = 0.0f; private var cachedGapTemp = 0.0f
    private var cachedGapFreq = 0.0f; private var cachedGapLoad = 0.0f
    private var cachedGapPowerSec = 0.0f
    private var cachedHotspotVisible = false; private var cachedPowerVisible = false
    private var cBarAlpha = 128
    private var cBatLabel = ""; private var cPdLabel = ""
    private var cGpuMemOn = false; private var cGpuMemOx = 0f; private var cGpuMemOy = 0f
    private var cHotThr = 95; private var cHotCnt = 0
    private var cCoreIndent = 0f
    private var cChipW = 0f
    private var cVisFreq: Set<String> = emptySet()
    private var cMainCntOx = 0f; private var cMainCntOy = 0f
    private var cMemOx = 0f; private var cMemOy = 0f
    private var cMemOn = true
    private var cMemHeader = "MEMÓRIA"
    private var cBusV: List<String> = emptyList()
    private var cMemSplit = 0.5f
    private var cBusOx = 0f; private var cBusOy = 0f
    private var cDsuOx = 0f; private var cDsuOy = 0f
    private var cMifOx = 0f; private var cMifOy = 0f
    private var cDsuNOx = 0f; private var cDsuNOy = 0f
    private var cMifNOx = 0f; private var cMifNOy = 0f
    private var cDsuFOx = 0f; private var cDsuFOy = 0f
    private var cMifFOx = 0f; private var cMifFOy = 0f
    private var cDsuTOx = 0f; private var cDsuTOy = 0f
    private var cMifTOx = 0f; private var cMifTOy = 0f
    private var cDsuVOx = 0f; private var cDsuVOy = 0f
    private var cMifVOx = 0f; private var cMifVOy = 0f
    private var cDivOx = 0f; private var cDivOy = 0f
    private var cMemPctOx = 0f; private var cMemPctOy = 0f
    private var cMemGbOx = 0f; private var cMemGbOy = 0f
    private var cRamKey = -1L; private var cRamTxt: String? = null
    private var cSwpKey = -1L; private var cSwpTxt: String? = null
    private var divShader: android.graphics.LinearGradient? = null
    private var divKey = 0L
    private var cMemLblSize = 1f
    private var cRamlblOx = 0f; private var cRamlblOy = 0f
    private var cSwaplblOx = 0f; private var cSwaplblOy = 0f
    private var cAcOx = 0f; private var cAcOy = 0f; private var cAcTxtOx = 0f; private var cAcTxtOy = 0f
    private var cMainHotOx = 0f; private var cMainHotOy = 0f; private var cAcOn = true
    private var cLh = 0f; private var cTrackH = 0f; private var cPadX = 0f
    private var cBgLum = 0f
    private var cAllV: List<String> = emptyList()
    private val freqStrCache = android.util.SparseArray<String>(64)
    private var cMemMb = -1; private var cMemStr = "--"
    private val LOAD_STR = Array(101) { "$it%" }
    private val TEMP_STR = Array(151) { "$it\u00B0" }
    private val VOLT_STR = arrayOfNulls<String>(VOLT_SLOTS)

    private fun sLoad(l: Int): String = if (l in 0..100) LOAD_STR[l] else "--"
    private fun sTemp(t: Int): String = if (t in 0..150) TEMP_STR[t] else "$t\u00B0"
    private fun sFreq(m: Int): String {
        var s = freqStrCache.get(m)
        if (s == null) { s = "$m MHz"; freqStrCache.put(m, s) }
        return s
    }
    private fun sMem(mb: Int): String {
        if (mb == cMemMb) return cMemStr
        cMemMb = mb
        cMemStr = when {
            mb <= 0 -> "--"
            mb >= 1024 -> String.format("%.1f GB", mb / 1024f).replace('.', ',')
            else -> "$mb MB"
        }
        return cMemStr
    }
    private fun fmtVolt(uv: Int): String =
        String.format(java.util.Locale.US, "%.2fmV", uv / 1000f).replace('.', ',')

    private fun sVolt(uv: Int): String {
        if (uv <= 0) return "--"
        if (uv % VOLT_STEP_UV == 0) {
            val i = uv / VOLT_STEP_UV
            if (i in 0 until VOLT_SLOTS) {
                var s = VOLT_STR[i]
                if (s == null) { s = fmtVolt(uv); VOLT_STR[i] = s }
                return s
            }
        }
        return fmtVolt(uv)
    }

    private var colNW = 0f; private var colFW = 0f; private var colTG = 0f
    private var mwHotspot = 0f; private var mwTriSpace = 0f
    private val reClipPath = android.graphics.Path()
    private val reTriPath = android.graphics.Path()
    private var edgeShader: android.graphics.LinearGradient? = null
    private var edgeKey = 0L
    private val barShaderCache = android.util.LruCache<Long, android.graphics.LinearGradient>(24)
    private val tickPts = FloatArray(36)
    private val brkPts = FloatArray(16)
    private var colLW = 0f; private var minCW = 0f
    private var colLoadMaxW = 0f
    private var colVW = 0f
    private var cVoltVis: Set<String> = emptySet()
    private var cChipThr = 70
    private var cachedGapVolt = 0.0f

    private val nmC = mutableMapOf<String, String>()
    private val lcC = mutableMapOf<String, String>()
    private val ccC = mutableMapOf<String, Int>()

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE) }
    private var reloadPending = false

    /**
     * Coalesce: com as configuracoes aplicando ao vivo, um arrastar de slider
     * dispara varias gravacoes por segundo. Sem isto cada chave notificada
     * custaria um reload() inteiro (~80 leituras de preferencia + remedicao
     * das colunas). Agora a rajada vira um reload no proximo passo da main.
     */
    private val pl = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (reloadPending) return@OnSharedPreferenceChangeListener
        reloadPending = true
        post {
            reloadPending = false
            nmC.clear(); lcC.clear(); ccC.clear()
            reload(); requestLayout(); invalidate()
        }
    }

    init { reload() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(pl) }
    override fun onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(pl); super.onDetachedFromWindow() }

    internal fun reload() {
        applyFonts()
        loadElemColors()
        uf = OverlayPrefs.getFontSize(context); vSp = OverlayPrefs.getVerticalSpacing(context)
        hSp = OverlayPrefs.getHorizontalSpacing(context)
        mL = OverlayPrefs.getMarginLeft(context); mR = OverlayPrefs.getMarginRight(context)
        mT = OverlayPrefs.getMarginTop(context); mB = OverlayPrefs.getMarginBottom(context)
        ovT = OverlayPrefs.getTitle(context); bgC = OverlayPrefs.getBgColor(context)
        bgA = OverlayPrefs.getBgAlpha(context)
        ctlSize = OverlayPrefs.getControlSize(context)
        fsTitle = OverlayPrefs.getTitleFontScale(context)
        fsValue = OverlayPrefs.getValueFontScale(context)
        fsLabel = OverlayPrefs.getLabelFontScale(context)
        minimal = OverlayPrefs.isMinimalMode(context)


        cachedGapName = OverlayPrefs.getColGapName(context)
        cachedGapTemp = OverlayPrefs.getColGapTemp(context)
        cachedGapFreq = OverlayPrefs.getColGapFreq(context)
        cachedGapLoad = OverlayPrefs.getColGapLoad(context)
        cachedGapPowerSec = OverlayPrefs.getGapPowerSection(context)
        cachedHotspotVisible = OverlayPrefs.isHotspot95CountVisible(context)
        cachedPowerVisible   = OverlayPrefs.isPowerInMainVisible(context)

        cBarAlpha = (OverlayPrefs.getBarIntensity(context) * 255f).toInt().coerceIn(20, 255)
        cBatLabel = "${OverlayPrefs.getMainBatLabel(context)}: "
        cPdLabel = "${OverlayPrefs.getMainPdLabel(context)}: "
        cGpuMemOn = OverlayPrefs.isGpuMemVisible(context)
        cGpuMemOx = OverlayPrefs.getOff_gpumem_x(context)
        cGpuMemOy = OverlayPrefs.getOff_gpumem_y(context)
        cHotThr = OverlayPrefs.getHotspotThreshold(context)
        cHotCnt = OverlayPrefs.getHotspot95Count(context)
        cCoreIndent = OverlayPrefs.getTelemetryCoreIndent(context)
        cVisFreq = OverlayPrefs.KNOWN_FREQ_IDS.keys.filter { OverlayPrefs.isFreqVisible(context, it) }.toSet()
        cMainCntOx = OverlayPrefs.getOff_maincnt_x(context); cMainCntOy = OverlayPrefs.getOff_maincnt_y(context)
        cMemOx = OverlayPrefs.getOff_mem_x(context); cMemOy = OverlayPrefs.getOff_mem_y(context)
        cMemOn = OverlayPrefs.isMemVisible(context)
        cMemLblSize = OverlayPrefs.getMemLabelSize(context)
        cRamlblOx = OverlayPrefs.getOff_ramlbl_x(context); cRamlblOy = OverlayPrefs.getOff_ramlbl_y(context)
        cSwaplblOx = OverlayPrefs.getOff_swaplbl_x(context); cSwaplblOy = OverlayPrefs.getOff_swaplbl_y(context)
        cAcOx = OverlayPrefs.getOff_ac_x(context); cAcOy = OverlayPrefs.getOff_ac_y(context)
        cAcTxtOx = OverlayPrefs.getOff_actxt_x(context); cAcTxtOy = OverlayPrefs.getOff_actxt_y(context)
        cMainHotOx = OverlayPrefs.getOff_mainhot_x(context); cMainHotOy = OverlayPrefs.getOff_mainhot_y(context)
        cAcOn = OverlayPrefs.isAcVisible(context)

        visSec = OverlayPrefs.SECTION_MAP.mapNotNull { (t, ids) ->
            val v = OverlayPrefs.getVisibleZones(context, ids); if (v.isNotEmpty()) t to v else null
        }
        cBusV = OverlayPrefs.BUS_ZONES.filter { OverlayPrefs.isZoneVisible(context, it) }
        cMemSplit = OverlayPrefs.getMemSplit(context)
        cBusOx = OverlayPrefs.getOff_bus_x(context); cBusOy = OverlayPrefs.getOff_bus_y(context)
        cDsuOx = OverlayPrefs.getOff_dsu_x(context); cDsuOy = OverlayPrefs.getOff_dsu_y(context)
        cMifOx = OverlayPrefs.getOff_mif_x(context); cMifOy = OverlayPrefs.getOff_mif_y(context)
        cDsuNOx = OverlayPrefs.getOff_dsun_x(context); cDsuNOy = OverlayPrefs.getOff_dsun_y(context)
        cMifNOx = OverlayPrefs.getOff_mifn_x(context); cMifNOy = OverlayPrefs.getOff_mifn_y(context)
        cDsuFOx = OverlayPrefs.getOff_dsuf_x(context); cDsuFOy = OverlayPrefs.getOff_dsuf_y(context)
        cMifFOx = OverlayPrefs.getOff_miff_x(context); cMifFOy = OverlayPrefs.getOff_miff_y(context)
        cDsuTOx = OverlayPrefs.getOff_dsut_x(context); cDsuTOy = OverlayPrefs.getOff_dsut_y(context)
        cMifTOx = OverlayPrefs.getOff_mift_x(context); cMifTOy = OverlayPrefs.getOff_mift_y(context)
        cDsuVOx = OverlayPrefs.getOff_dsuv_x(context); cDsuVOy = OverlayPrefs.getOff_dsuv_y(context)
        cMifVOx = OverlayPrefs.getOff_mifv_x(context); cMifVOy = OverlayPrefs.getOff_mifv_y(context)
        cDivOx = OverlayPrefs.getOff_div_x(context); cDivOy = OverlayPrefs.getOff_div_y(context)
        cMemPctOx = OverlayPrefs.getOff_mempct_x(context); cMemPctOy = OverlayPrefs.getOff_mempct_y(context)
        cMemGbOx = OverlayPrefs.getOff_memgb_x(context); cMemGbOy = OverlayPrefs.getOff_memgb_y(context)
        cAllV = visSec.flatMap { it.second } + cBusV
        cMemHeader = if (cBusV.isEmpty()) "MEMÓRIA" else "MEMÓRIA · BARRAMENTO"
        cRamKey = -1L; cSwpKey = -1L
        cChipThr = OverlayPrefs.getChipThreshold(context)
        cachedGapVolt = OverlayPrefs.getColGapVolt(context)
        cVoltVis = cAllV.filter {
            OverlayPrefs.VOLT_ZONES.contains(it) && OverlayPrefs.isVoltVisible(context, it)
        }.toSet()
        cBgLum = lum(bgC)
        freqStrCache.clear()
        updSizes(); measCols()
    }

    private fun gn(id: String) = nmC.getOrPut(id) { OverlayPrefs.getDisplayName(context, id) }
    private fun gl(id: String) = lcC.getOrPut(id) { OverlayPrefs.getLabel(context, id) }
    private fun gc(id: String) = ccC.getOrPut(id) { OverlayPrefs.getColor(context, id) }

    private fun handleTouchArea(): Float = baseHandleArea * hScale * ctlSize

    private fun updSizes() {
        val fs = bfs * hScale * uf

        titP.textSize = fs * 1.1f * fsTitle
        nmP.textSize = fs * fsValue
        dimP.textSize = fs * 0.9f * fsLabel
        tmpP.textSize = fs * fsValue
        secP.textSize = fs * fsLabel
        loadP.textSize = fs * fsValue
        fqP.textSize = fs * fsValue
        hotP.textSize = fs * fsValue
        hotCntP.textSize = fs * fsValue
        gmP.textSize = fs * OverlayPrefs.getGpuMemFontScale(context)

        hudChipTxtP.textSize = fs * fsValue
        cChipW = hudChipTxtP.measureText("100°")
        cLh = maxOf(tmpP.textSize, titP.textSize, secP.textSize, hotP.textSize) * 1.05f * (1f + vSp)
        cTrackH = cLh * HUD_ROW * 0.84f
        cPadX = cTrackH * 0.34f
        hudEyebrowP.textSize = fs * 0.62f * fsLabel
        hudBigP.textSize = fs * 1.7f * fsValue
        hudEdgeP.strokeWidth = 1.4f * hScale
        hudBrkP.strokeWidth = 2f * hScale
        hudRuleP.strokeWidth = 1f * hScale
    }

    private fun measCols() {

        val globalGap = cg * hScale * hSp * 8f
        val gN  = globalGap + (cg * hScale * cachedGapName * 4f)
        val gT  = globalGap + (cg * hScale * cachedGapTemp * 4f)
        val gF  = globalGap + (cg * hScale * cachedGapFreq * 4f)
        val gL  = globalGap + (cg * hScale * cachedGapLoad * 4f)
        val gV  = globalGap + (cg * hScale * cachedGapVolt * 4f)

        var maxN = 0f
        val allVis = if (minimal) cAllV else visSec.flatMap { it.second }
        for (id in allVis) {
            val w = nmP.measureText(gn(id))
            if (w > maxN) maxN = w
        }
        colNW = maxN + gN
        colTG = gT
        val freqMaxW = fqP.measureText("9999 MHz")
        var labelMaxW = 0f
        for (id in allVis) {
            val lw = dimP.measureText("(${gl(id)})")
            if (lw > labelMaxW) labelMaxW = lw
        }
        colFW = maxOf(freqMaxW, labelMaxW) + gF
        colVW = if (cVoltVis.isEmpty()) 0f else fqP.measureText("9999,99mV") + gV
        colLW = gL

        colLoadMaxW = loadP.measureText("100%")

        val rowW = hudRowContentW()

        mwHotspot = hudEyebrowP.measureText("HOTSPOT")
        mwTriSpace = dimP.measureText("   ")

        minCW = maxOf(rowW, measPowerRowW()) + pR()
    }

    /**
     * Largura da linha BAT/PD/AC/contador.
     *
     * Essa linha é desenhada da esquerda para a direita e não era medida: com o
     * chip AC e o contador ligados ela passava da largura do painel e era cortada
     * pelo próprio limite da View. Mede-se com os textos no pior caso (os dígitos
     * são tabulares, então a largura só depende da quantidade de dígitos).
     */
    private fun measPowerRowW(): Float {
        if (minimal || !cachedPowerVisible) return 0f
        var x = hudPadX()
        x += dimP.measureText(cBatLabel) + hotCntP.measureText("100,0°C")
        x += mwTriSpace
        x += dimP.measureText(cPdLabel) + hotCntP.measureText("99,99W")
        if (cAcOn) {
            val padIn = tmpP.textSize * 0.4f
            x += lh() * 0.5f + cAcOx * hScale + tmpP.measureText("AC") + padIn * 2f
        }
        if (cachedHotspotVisible) {
            val padX = hudEyebrowP.textSize * 0.9f
            x += lh() * 0.5f + cMainCntOx * hScale +
                dimP.measureText("$cHotThr° ") + hotCntP.measureText("8888") + padX * 2f
        }
        return x + hudPadX()
    }


    private fun lh(): Float = cLh
    private fun pL() = mL * hScale * uf; private fun pR() = mR * hScale * uf
    private fun pT() = mT * hScale * uf; private fun pB() = mB * hScale * uf

    private fun lnCnt(): Float {
        if (minimal) {

            var n = HUD_TITLE
            n += cAllV.size * HUD_ROW
            return n + 0.35f
        }

        var n = HUD_TITLE + HUD_FOCUS
        if (cachedPowerVisible) n += 1f + cachedGapPowerSec * 0.25f
        for ((_, z) in visSec) n += HUD_SEC + z.size * HUD_ROW

        if (visSec.any { it.second.contains("G3D") } && "G3D" !in cVoltVis) n += HUD_ROW
        if (cMemOn) n += HUD_SEC + 2f * (0.72f + HUD_ROW)
        return n + 0.35f
    }

    override fun onMeasure(wMS: Int, hMS: Int) {
        measCols()
        val cw = pL() + minCW + pR()
        setMeasuredDimension(cw.toInt(), (pT() + lh() * lnCnt() + pB()).toInt())
    }

    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    private var downRawX = 0f; private var downRawY = 0f
    private var dragStarted = false
    fun isDragging(): Boolean = dragStarted

    private val glowOn: Boolean get() = !dragStarted

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val singleFingerDrag = dragStarted && e.pointerCount == 1
        if (!singleFingerDrag) sd.onTouchEvent(e)
        gd.onTouchEvent(e)
        if (sd.isInProgress) { tm = TM.NONE; dragStarted = false; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {

                val w = width.toFloat(); val h = height.toFloat()
                val ha = handleTouchArea()
                tm = if (e.x >= w - ha && e.y >= h - ha) TM.RWH else TM.DRAG
                ltx = e.rawX; lty = e.rawY
                downRawX = e.rawX; downRawY = e.rawY
                dragStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - ltx; val dy = e.rawY - lty
                val totalDx = e.rawX - downRawX; val totalDy = e.rawY - downRawY
                when (tm) {
                    TM.DRAG -> {

                        if (!dragStarted) {
                            if (Math.hypot(totalDx.toDouble(), totalDy.toDouble()) > touchSlop) {
                                dragStarted = true
                            }
                        }
                        if (dragStarted) onDragListener?.invoke(dx, dy)
                    }
                    TM.RWH -> {
                        wScale = (wScale + dx / 200f).coerceIn(minScale, maxScale)
                        hScale = (hScale + dy / 300f).coerceIn(minScale, maxScale)
                        updSizes(); measCols(); requestLayout(); invalidate()
                    }
                    TM.NONE -> {}
                }; ltx = e.rawX; lty = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tm = TM.NONE; dragStarted = false
                invalidate()
                onDragEndListener?.invoke()
            }
        }; return true
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)

        val cr = bcr * hScale; val w = width.toFloat(); val h = height.toFloat()
        bgP.color = Color.argb(bgA, Color.red(bgC), Color.green(bgC), Color.blue(bgC))
        bgRect.set(0f, 0f, w, h)
        cv.drawRoundRect(bgRect, cr, cr, bgP)

        hudRuleP.color = Color.argb(15, 255, 255, 255)
        cv.drawLine(cr, 1f * hScale, w - cr, 1f * hScale, hudRuleP)
        val lh = lh(); val pl = pL(); val rightX = w - pR()

        val allV = cAllV
        var hotZid: String? = null; var hotTemp = 0
        for (i in allV.indices) {
            val t = ThermalReader.getTemp(allV[i])
            if (t > hotTemp) { hotTemp = t; hotZid = allV[i] }
        }
        val accent = if (hotTemp > 0) hudHeat(hotTemp) else cC

        drawGlassAccents(cv, w, h, accent)

        var top = pT()

        val titleBaseline = top + titP.textSize
        val dotR = lh * 0.14f
        hudChipP.color = accent
        if (glowOn) hudChipP.setShadowLayer(8f * hScale, 0f, 0f, withAlpha(accent, 180)) else hudChipP.clearShadowLayer()
        cv.drawCircle(pl + dotR, titleBaseline - titP.textSize * 0.32f, dotR, hudChipP)
        hudChipP.clearShadowLayer()
        titP.color = cW
        cv.drawText(ovT, pl + dotR * 2f + lh * 0.22f, titleBaseline, titP)
        top += lh * HUD_TITLE

        if (minimal) {

            for (zid in allV) {
                val ztemp = ThermalReader.getTemp(zid)
                val fId = OverlayPrefs.getFreqIdForZone(zid)
                val freqToggleOn = fId != null && fId in cVisFreq
                val mhz = if (fId != null) ThermalReader.getFreq(fId) else 0
                val showFreq = freqToggleOn && (mhz > 0 || mhz == ThermalReader.FREQ_OFF)
                drawHudZoneRow(cv, zid, ztemp, pl, top, rightX, mhz, showFreq)
                top += lh * HUD_ROW
            }
        } else {

            val focusBaseline = top + hudBigP.textSize * 0.78f
            drawHudHotspot(cv, pl, focusBaseline, rightX, hotZid?.let { gn(it) }, hotTemp, accent)
            top += lh * HUD_FOCUS

            if (cachedPowerVisible) {
                val bl = top + dimP.textSize
                var x = pl
                val gapH = mwTriSpace
                val batLabel = cBatLabel
                dimP.color = eDim; cv.drawText(batLabel, x, bl, dimP)
                x += dimP.measureText(batLabel)
                hotCntP.color = cachedBatColor; cv.drawText(cachedBatStr, x, bl, hotCntP)
                x += hotCntP.measureText(cachedBatStr) + gapH
                val pdLabel = cPdLabel
                cv.drawText(pdLabel, x, bl, dimP)
                x += dimP.measureText(pdLabel)
                hotCntP.color = ePwrBat; cv.drawText(cachedPdStr, x, bl, hotCntP)
                x += hotCntP.measureText(cachedPdStr)
                if (cAcOn) {
                    val charging = BatteryMonitor.isCharging
                    val acCol = if (charging) cG else cD
                    val acTxt = "AC"
                    val padIn = tmpP.textSize * 0.4f
                    val cw2 = tmpP.measureText(acTxt) + padIn * 2
                    val ch2 = lh() * 0.7f
                    val acX = x + lh() * 0.5f + cAcOx * hScale
                    val ct2 = bl - ch2 * 0.72f + cAcOy * hScale
                    hudChipP.color = if (charging) withAlpha(cG, 60) else withAlpha(cD, 40)
                    hudChipP.clearShadowLayer()
                    hudChipRect.set(acX, ct2, acX + cw2, ct2 + ch2)
                    cv.drawRoundRect(hudChipRect, ch2 * 0.4f, ch2 * 0.4f, hudChipP)
                    hudEdgeP.color = withAlpha(acCol, 180)
                    cv.drawRoundRect(hudChipRect, ch2 * 0.4f, ch2 * 0.4f, hudEdgeP)
                    nmP.color = acCol
                    cv.drawText(acTxt, acX + padIn + cAcTxtOx * hScale, ct2 + ch2 * 0.5f + nmP.textSize * 0.35f + cAcTxtOy * hScale, nmP)
                    x = acX + cw2
                }
                if (cachedHotspotVisible) {
                    drawHudCounterChip(cv,
                        x + lh() * 0.5f + cMainCntOx * hScale,
                        bl + cMainCntOy * hScale)
                }
                top += lh
                top += lh * cachedGapPowerSec * 0.25f
            }

            for ((title, zIds) in visSec) {
                val secBaseline = top + hudEyebrowP.textSize
                drawHudSectionLabel(cv, pl, secBaseline, rightX, title)
                top += lh * HUD_SEC
                for (zid in zIds) {
                    val ztemp = ThermalReader.getTemp(zid)
                    val fId = OverlayPrefs.getFreqIdForZone(zid)
                    val freqToggleOn = fId != null && fId in cVisFreq
                    val mhz = if (fId != null) ThermalReader.getFreq(fId) else 0
                    val showFreq = freqToggleOn && (mhz > 0 || mhz == ThermalReader.FREQ_OFF)
                    val gpuRowTop = top
                    drawHudZoneRow(cv, zid, ztemp, pl, top, rightX, mhz, showFreq)
                    top += lh * HUD_ROW

                    if (zid == "G3D") {
                        if (cGpuMemOn) {
                            val trackH0 = hudTrackH()
                            val padX0 = hudPadX()
                            val ybG = gpuRowTop + trackH0 * 0.5f + gmP.textSize * 0.34f
                            val nameW = nmP.measureText(gn(zid))
                            val gx = pl + padX0 + nameW + gmP.textSize * 0.6f +
                                cGpuMemOx * hScale
                            val gy = ybG + cGpuMemOy * hScale
                            val mb = ThermalReader.gpuMemUsedMb
                            gmP.color = eGpuMem
                            cv.drawText(sMem(mb), gx, gy, gmP)
                        }
                        if ("G3D" !in cVoltVis) {
                            val mv = gpuVoltUv()
                            val trackH = hudTrackH(); val rr2 = trackH * 0.30f
                            val padX2 = hudPadX()
                            val indent = cCoreIndent * hScale
                            val yb = top + trackH * 0.5f + tmpP.textSize * 0.34f
                            hudTrackP.color = withAlpha(cD, 26)
                            hudTrackRect.set(pl, top, rightX, top + trackH)
                            cv.drawRoundRect(hudTrackRect, rr2, rr2, hudTrackP)
                            nmP.color = cW; cv.drawText("volt", pl + padX2 + indent, yb, nmP)
                            fqP.color = eVolt
                            cv.drawText(sVolt(mv),
                                pl + padX2 + colNW, yb, fqP)
                            top += lh * HUD_ROW
                        }
                    }
                }
            }

            if (cMemOn) {
                val mx = cMemOx * hScale
                val my = cMemOy * hScale
                val mpl = pl + mx
                val mrx = rightX + mx
                var mtop = top + my
                val secBaseline = mtop + hudEyebrowP.textSize
                drawHudSectionLabel(cv, mpl, secBaseline, mrx, cMemHeader)
                mtop += lh * HUD_SEC

                // Metade esquerda: barras. Metade direita: DSU/MIF, separadas
                // por um filete. Sem barramento visivel as barras voltam inteiras.
                val bus = cBusV
                val gap = hudPadX() * 1.6f
                val divX = if (bus.isEmpty()) mrx else mpl + (mrx - mpl) * cMemSplit
                val barR = if (bus.isEmpty()) mrx else divX - gap
                val busX = divX + gap + cBusOx * hScale
                val busR = mrx + cBusOx * hScale

                val blockTop = mtop
                val rt = ThermalReader.ramTotalMb
                val ru = ThermalReader.ramUsedMb
                mtop = drawMemStrip(cv, mpl, mtop, barR, "RAM", eRam,
                    if (rt > 0) ru * 100 / rt else -1,
                    memText(ru, rt, true),
                    cRamlblOx * hScale, cRamlblOy * hScale)
                val st = ThermalReader.swapTotalMb
                val su = st - ThermalReader.swapFreeMb
                mtop = drawMemStrip(cv, mpl, mtop, barR, "SWAP", eSwap,
                    if (st > 0) su * 100 / st else -1,
                    memText(su, st, false),
                    cSwaplblOx * hScale, cSwaplblOy * hScale)

                if (bus.isNotEmpty()) {
                    val dvy = cDivOy * hScale
                    drawMemDivider(cv, divX + cDivOx * hScale, blockTop + dvy, mtop + dvy)
                    val strip = lh * (0.72f + HUD_ROW)
                    for (i in bus.indices) {
                        if (i >= 2) break
                        val zid = bus[i]
                        val rx = (if (zid == "DSU") cDsuOx else cMifOx) * hScale
                        val ry = (if (zid == "DSU") cDsuOy else cMifOy) * hScale
                        val t0 = blockTop + strip * i + cBusOy * hScale + ry
                        drawBusRow(cv, busX + rx, busR + rx, t0, zid)
                    }
                }
                top = mtop - my
            }
        }
    }

    /** GB com duas casas fixas: com digito tabular a largura nao muda. */
    private fun fmtGb(mb: Int): String =
        String.format(java.util.Locale.US, "%.2f", mb / 1024f).replace('.', ',')

    /** "7,90 / 11,40", memorizado por faixa para nao formatar a cada frame. */
    private fun memText(used: Int, total: Int, isRam: Boolean): String? {
        if (total <= 0) return null
        val key = used.toLong() * 100000L + total
        if (isRam) {
            if (key != cRamKey) { cRamKey = key; cRamTxt = fmtGb(used) + " / " + fmtGb(total) }
            return cRamTxt
        }
        if (key != cSwpKey) { cSwpKey = key; cSwpTxt = fmtGb(used) + " / " + fmtGb(total) }
        return cSwpTxt
    }

    /**
     * Uma faixa de memoria: cabecalho (rotulo, porcentagem e "x,xx / y,yy GB")
     * e, abaixo, a barra com escala.
     *
     * A porcentagem saiu de dentro da barra: sobre o preenchimento com marcas
     * ela ficava ilegivel.
     */
    private fun drawMemStrip(cv: Canvas, pl: Float, topIn: Float, rightX: Float, label: String,
                             col: Int, pct: Int, rightText: String?, offX: Float, offY: Float): Float {
        var top = topIn
        val lh = lh()
        val trackH = hudTrackH()
        val rr = trackH * 0.30f
        val padX = hudPadX()

        val headBl = top + tmpP.textSize * 0.9f + offY
        val prevSz = nmP.textSize
        nmP.textSize = prevSz * cMemLblSize

        nmP.color = col
        val lx = pl + padX + offX
        cv.drawText(label, lx, headBl, nmP)

        nmP.color = cW
        val pctStr = if (pct >= 0) sLoad(pct) else "--"
        // A porcentagem acompanha o rótulo na horizontal e tem ajuste próprio.
        val pctX = lx + nmP.measureText(label) + nmP.textSize * 0.62f + cMemPctOx * hScale
        cv.drawText(pctStr, pctX, headBl + cMemPctOy * hScale, nmP)

        if (rightText != null) {
            val unit = "GB"
            val uw = dimP.measureText(unit) + dimP.textSize * 0.42f
            val gx = rightX + cMemGbOx * hScale
            val gy = headBl + cMemGbOy * hScale
            // Com a coluna pela metade o cabeçalho pode não caber. Some com o
            // "x,xx / y,yy GB" em vez de deixar sobrepor o rótulo.
            val leftEnd = pctX + nmP.measureText(pctStr) + nmP.textSize * 0.5f
            if (gx - nmP.measureText(rightText) - uw > leftEnd) {
                dimP.color = eDim
                dimP.textAlign = Paint.Align.RIGHT
                cv.drawText(unit, gx, gy, dimP)
                dimP.textAlign = Paint.Align.LEFT
                nmP.color = cW
                nmP.textAlign = Paint.Align.RIGHT
                cv.drawText(rightText, gx - uw, gy, nmP)
                nmP.textAlign = Paint.Align.LEFT
            }
        }
        nmP.textSize = prevSz

        top += lh * 0.72f
        hudTrackP.color = withAlpha(cD, 26)
        hudTrackRect.set(pl, top, rightX, top + trackH)
        cv.drawRoundRect(hudTrackRect, rr, rr, hudTrackP)

        val span = rightX - pl
        if (pct > 0) {
            val fw = span * (pct.coerceAtMost(100) / 100f)
            fillTrack(cv, pl, top, fw, trackH, rr, withAlpha(col, 210), withAlpha(col, 92))
            drawBarEdge(cv, pl, top, fw, span, trackH, rr, withAlpha(col, 255))
        }
        // escala: marca a cada 10%. As marcas ficam no trecho reto da faixa,
        // longe dos cantos arredondados, entao nao precisam de recorte.
        hudRuleP.color = withAlpha(Color.BLACK, 112)
        var o = 0
        for (i in 1 until 10) {
            val tx = pl + span * i / 10f
            tickPts[o] = tx; tickPts[o + 1] = top
            tickPts[o + 2] = tx; tickPts[o + 3] = top + trackH
            o += 4
        }
        cv.drawLines(tickPts, hudRuleP)

        return top + lh * HUD_ROW
    }

    /** Filete que separa as barras do barramento. */
    private fun drawMemDivider(cv: Canvas, x: Float, y0: Float, y1: Float) {
        var k = 17L
        k = k * 31 + java.lang.Float.floatToRawIntBits(y0)
        k = k * 31 + java.lang.Float.floatToRawIntBits(y1)
        if (k != divKey || divShader == null) {
            divShader = LinearGradient(0f, y0, 0f, y1,
                intArrayOf(withAlpha(cD, 0), withAlpha(cD, 175), withAlpha(cD, 175), withAlpha(cD, 0)),
                floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP)
            divKey = k
        }
        hudRuleP.color = Color.WHITE
        hudRuleP.shader = divShader
        cv.drawLine(x, y0, x, y1, hudRuleP)
        hudRuleP.shader = null
    }

    /**
     * DSU ou MIF na coluna direita, em duas linhas.
     *
     * Linha 1 alinha com o cabecalho da faixa de memoria, linha 2 com o centro
     * da barra. Duas linhas cabem em 100% do tamanho; numa linha so o texto
     * precisaria cair para 82% e ficaria a 18 px da borda no pior caso.
     */
    private fun drawBusRow(cv: Canvas, x: Float, rightX: Float, stripTop: Float, zid: String) {
        val lh = lh()
        val yb1 = stripTop + tmpP.textSize * 0.9f
        val yb2 = stripTop + lh * 0.72f + hudTrackH() * 0.5f + tmpP.textSize * 0.34f
        val temp = ThermalReader.getTemp(zid)
        val isDsu = zid == "DSU"

        nmP.color = gc(zid)
        cv.drawText(gn(zid),
            x + (if (isDsu) cDsuNOx else cMifNOx) * hScale,
            yb1 + (if (isDsu) cDsuNOy else cMifNOy) * hScale, nmP)

        val fId = OverlayPrefs.getFreqIdForZone(zid)
        val mhz = if (fId != null && fId in cVisFreq) ThermalReader.getFreq(fId) else 0
        val fTxt = when {
            mhz == ThermalReader.FREQ_OFF -> "OFFLINE"
            mhz > 0 -> sFreq(mhz)
            else -> "--"
        }
        fqP.color = if (mhz > 0) eFreq else eDim
        fqP.textAlign = Paint.Align.RIGHT
        cv.drawText(fTxt,
            rightX + (if (isDsu) cDsuFOx else cMifFOx) * hScale,
            yb1 + (if (isDsu) cDsuFOy else cMifFOy) * hScale, fqP)
        fqP.textAlign = Paint.Align.LEFT

        if (zid in cVoltVis) {
            val uv = ThermalReader.getVoltUv(zid)
            val vx = (if (isDsu) cDsuVOx else cMifVOx) * hScale
            val vy = (if (isDsu) cDsuVOy else cMifVOy) * hScale
            fqP.color = eVolt
            cv.drawText(if (uv > 0) sVolt(uv) else "--", x + vx, yb2 + vy, fqP)
        }

        tmpP.color = when {
            temp <= 0 -> cD
            temp >= cChipThr -> cR
            else -> hudHeat(temp)
        }
        tmpP.textAlign = Paint.Align.RIGHT
        cv.drawText(if (temp > 0) sTemp(temp) else "--",
            rightX + (if (isDsu) cDsuTOx else cMifTOx) * hScale,
            yb2 + (if (isDsu) cDsuTOy else cMifTOy) * hScale, tmpP)
        tmpP.textAlign = Paint.Align.LEFT
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /**
     * Preenchimento da barra de carga, igual ao original sem clipPath.
     *
     * Antes: clipPath(faixa arredondada) + drawRect(0..fw). Recorte por Path
     * com antialias obriga o HWUI a gerar uma mascara (stencil) a cada barra, a
     * cada quadro, e quebra o agrupamento das chamadas de desenho: era o item
     * mais caro do overlay para a GPU. Agora: clipRect(0..fw), que e um scissor
     * de graca, + a propria faixa arredondada. A intersecao e a mesma forma.
     */
    private fun fillTrack(cv: Canvas, pl: Float, top: Float, fw: Float, trackH: Float,
                          rr: Float, c0: Int, c1: Int) {
        if (fw <= 0f) return
        val sc = cv.save()
        cv.clipRect(pl, top, pl + fw, top + trackH)
        hudFillP.shader = barGradient(pl, fw, c0, c1)
        cv.drawRoundRect(hudTrackRect, rr, rr, hudFillP)
        hudFillP.shader = null
        cv.restoreToCount(sc)
    }

    /** Filete na ponta da barra; so no trecho reto (nos cantos o original o cortava). */
    private fun drawBarEdge(cv: Canvas, pl: Float, top: Float, fw: Float, span: Float,
                            trackH: Float, rr: Float, color: Int) {
        if (fw < rr || fw > span - rr) return
        hudEdgeP.color = color
        cv.drawLine(pl + fw, top, pl + fw, top + trackH, hudEdgeP)
    }
    private fun hudTrackH() = cTrackH
    private fun hudPadX() = cPadX
    private fun hudChipW() = cChipW

    private fun chipOffset(): Float =
        colNW + colFW + colVW + colLW + colLoadMaxW + colTG

    private fun hudRowContentW(): Float =
        hudPadX() + chipOffset() + hudChipW() + hudPadX()

    private fun drawGlassAccents(cv: Canvas, w: Float, h: Float, accent: Int) {
        val cr = bcr * hScale
        val inset = 0.9f * hScale

        hudEdgeP.color = Color.WHITE

        var ek = 17L
        ek = ek * 31 + java.lang.Float.floatToRawIntBits(inset)
        ek = ek * 31 + java.lang.Float.floatToRawIntBits(w)
        ek = ek * 31 + java.lang.Float.floatToRawIntBits(h)
        ek = ek * 31 + accent
        if (ek != edgeKey || edgeShader == null) {
            edgeShader = LinearGradient(
                inset, inset, w - inset, h - inset,
                intArrayOf(withAlpha(accent, 170), withAlpha(cP, 140), withAlpha(cP, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            edgeKey = ek
        }
        hudEdgeP.shader = edgeShader
        cv.drawRoundRect(inset, inset, w - inset, h - inset, cr, cr, hudEdgeP)
        hudEdgeP.shader = null
        val b = maxOf(9f * hScale, lh() * 0.5f)
        val m = 6f * hScale

        hudBrkP.color = withAlpha(accent, 204)
        brkPts[0] = m;         brkPts[1] = m + b;     brkPts[2] = m;         brkPts[3] = m
        brkPts[4] = m;         brkPts[5] = m;         brkPts[6] = m + b;     brkPts[7] = m
        brkPts[8] = w - m - b; brkPts[9] = m;         brkPts[10] = w - m;    brkPts[11] = m
        brkPts[12] = w - m;    brkPts[13] = m;        brkPts[14] = w - m;    brkPts[15] = m + b
        cv.drawLines(brkPts, hudBrkP)

        hudBrkP.color = withAlpha(cP, 204)
        brkPts[0] = m;         brkPts[1] = h - m - b; brkPts[2] = m;         brkPts[3] = h - m
        brkPts[4] = m;         brkPts[5] = h - m;     brkPts[6] = m + b;     brkPts[7] = h - m
        brkPts[8] = w - m - b; brkPts[9] = h - m;     brkPts[10] = w - m;    brkPts[11] = h - m
        brkPts[12] = w - m;    brkPts[13] = h - m;    brkPts[14] = w - m;    brkPts[15] = h - m - b
        cv.drawLines(brkPts, hudBrkP)
    }

    private fun drawHudHotspot(cv: Canvas, pl: Float, yb: Float, rightX: Float,
                               hotId: String?, hotTemp: Int, accent: Int) {
        hudEyebrowP.color = cD
        cv.drawText("HOTSPOT", pl, yb, hudEyebrowP)
        val ex = pl + mwHotspot + hudEyebrowP.textSize * 0.9f
        nmP.color = cW
        cv.drawText(hotId ?: "--", ex, yb, nmP)
        val big = if (hotTemp > 0) sTemp(hotTemp) else "--"
        hudBigP.color = accent

        val chipColCenter = pl + hudPadX() + chipOffset() + hudChipW() / 2f + cMainHotOx * hScale
        hudBigP.textAlign = Paint.Align.CENTER
        if (glowOn && hotTemp >= 58) hudBigP.setShadowLayer(16f * hScale, 0f, 0f, withAlpha(accent, 170))
        else hudBigP.clearShadowLayer()
        cv.drawText(big, chipColCenter, yb + hudBigP.textSize * 0.06f + cMainHotOy * hScale, hudBigP)
        hudBigP.clearShadowLayer()
        hudBigP.textAlign = Paint.Align.LEFT
    }

    private fun drawHudSectionLabel(cv: Canvas, pl: Float, yb: Float, rightX: Float, text: String) {
        val d = hudEyebrowP.textSize * 0.5f
        val cyMid = yb - hudEyebrowP.textSize * 0.32f
        reTriPath.rewind()
        reTriPath.moveTo(pl, cyMid - d); reTriPath.lineTo(pl + d, cyMid); reTriPath.lineTo(pl, cyMid + d); reTriPath.lineTo(pl - d, cyMid); reTriPath.close()
        hudChipP.color = eSection; hudChipP.clearShadowLayer(); cv.drawPath(reTriPath, hudChipP)
        val tx = pl + d + hudEyebrowP.textSize * 0.7f
        drawTextOutlined(cv, text, tx, yb, hudEyebrowP, eSection)
        val rx0 = tx + hudEyebrowP.measureText(text) + hudEyebrowP.textSize * 0.7f
        hudRuleP.color = withAlpha(eSection, 70)
        cv.drawLine(rx0, cyMid, rightX, cyMid, hudRuleP)
    }

    private fun drawTextOutlined(cv: Canvas, t: String, x: Float, y: Float, paint: Paint, col: Int) {
        paint.color = col
        if (colorNearBg(col)) {
            val prev = paint.style; val pc = paint.color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * hScale
            paint.color = if (lum(bgC) < 0.5f) Color.WHITE else Color.BLACK
            cv.drawText(t, x, y, paint)
            paint.style = prev; paint.color = pc; paint.strokeWidth = 0f
        }
        cv.drawText(t, x, y, paint)
    }
    private fun lum(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    private fun colorNearBg(c: Int): Boolean = kotlin.math.abs(lum(c) - cBgLum) < 0.22f

    private fun drawHudZoneRow(cv: Canvas, zid: String, ztemp: Int, pl: Float, top: Float, rightX: Float,
                               mhz: Int, isFreq: Boolean) {
        val trackH = hudTrackH()
        val rr = trackH * 0.30f
        val padX = hudPadX()
        val yb = top + trackH * 0.5f + tmpP.textSize * 0.34f
        val heat = hudHeat(ztemp)

        hudTrackP.color = withAlpha(cD, 26)
        hudTrackRect.set(pl, top, rightX, top + trackH)
        cv.drawRoundRect(hudTrackRect, rr, rr, hudTrackP)

        val hasLoad = zid !in OverlayPrefs.NO_LOAD_ZONES
        val load = if (hasLoad) ThermalReader.getLoad(zid) else -1
        if (load > 0) {
            val span = rightX - pl
            val fw = (span * load / 100f).coerceIn(0f, span)
            fillTrack(cv, pl, top, fw, trackH, rr,
                withAlpha(heat, barA()), withAlpha(heat, (barA() * 0.45f).toInt()))
            drawBarEdge(cv, pl, top, fw, span, trackH, rr, withAlpha(heat, 255))
        }

        nmP.color = gc(zid)
        cv.drawText(gn(zid), pl + padX, yb, nmP)

        val fx = pl + padX + colNW
        if (isFreq && mhz == ThermalReader.FREQ_OFF) { fqP.color = dimP.color; cv.drawText("OFFLINE", fx, yb, fqP) }
        else if (isFreq && mhz > 0) { fqP.color = eFreq; cv.drawText(sFreq(mhz), fx, yb, fqP) }
        else cv.drawText("(${gl(zid)})", fx, yb, dimP)

        if (colVW > 0f && zid in cVoltVis) {
            val uv = ThermalReader.getVoltUv(zid)
            if (uv > 0) { fqP.color = eVolt; cv.drawText(sVolt(uv), fx + colFW, yb, fqP) }
        }

        if (hasLoad) {
            val lx = fx + colFW + colVW + colLW
            loadP.textAlign = Paint.Align.LEFT
            loadP.color = if (load >= 0) eLoad else eDim
            cv.drawText(sLoad(load), lx, yb, loadP)
        }

        val cxc = pl + padX + chipOffset() + hudChipW() * 0.5f
        val tyb = top + trackH * 0.5f + hudChipTxtP.textSize * 0.35f
        if (ztemp <= 0) {
            hudChipTxtP.color = cD
            cv.drawText("--", cxc, tyb, hudChipTxtP)
        } else {
            val alert = ztemp >= cChipThr
            hudChipTxtP.color = if (alert) cR else heat
            if (alert && glowOn) hudChipTxtP.setShadowLayer(9f * hScale, 0f, 0f, withAlpha(cR, 150))
            cv.drawText(sTemp(ztemp), cxc, tyb, hudChipTxtP)
            if (alert && glowOn) hudChipTxtP.clearShadowLayer()
        }
    }


    private fun drawHudCounterChip(cv: Canvas, leftX: Float, yb: Float) {
        val threshold = cHotThr
        val count = cHotCnt
        val label = "${threshold}° "
        val countStr = "$count"
        val padX = hudEyebrowP.textSize * 0.9f
        val w = dimP.measureText(label) + hotCntP.measureText(countStr) + padX * 2
        val h = lh() * 0.86f
        val top = yb - h * 0.74f
        val left = leftX
        hudChipRect.set(left, top, left + w, top + h)
        hudEdgeP.color = withAlpha(cD, 85)
        cv.drawRoundRect(hudChipRect, h * 0.5f, h * 0.5f, hudEdgeP)
        val ty = top + h * 0.5f + dimP.textSize * 0.35f
        dimP.color = cD
        cv.drawText(label, left + padX, ty, dimP)
        hotCntP.color = cO
        cv.drawText(countStr, left + padX + dimP.measureText(label), ty, hotCntP)
        dimP.color = cD
    }


    private fun gpuVoltUv(): Int = ThermalReader.getVoltUv("G3D")

    private fun barA(): Int = cBarAlpha

    private fun barGradient(pl: Float, fw: Float, c0: Int, c1: Int): android.graphics.LinearGradient {
        val plQ = Math.round(pl / 2f) * 2
        val fwQ = Math.round(fw / 2f) * 2
        // Chave numerica: a versao em String alocava um objeto por barra, por frame.
        var key = plQ.toLong()
        key = key * 8191L + fwQ
        key = key * 8191L + c0
        key = key * 8191L + c1
        var sh = barShaderCache.get(key)
        if (sh == null) {
            sh = LinearGradient(plQ.toFloat(), 0f, (plQ + fwQ).toFloat(), 0f, c0, c1, Shader.TileMode.CLAMP)
            barShaderCache.put(key, sh)
        }
        return sh
    }

    private fun hudHeat(t: Int): Int = when {
        t >= 88 -> cR
        t >= 72 -> cO
        t >= 58 -> HEAT_Y
        t >= 45 -> cG
        else    -> cC
    }



}
