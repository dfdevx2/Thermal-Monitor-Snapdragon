package com.siliconfet.thermalmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryOverlayView(context: Context) : View(context) {

    private var wScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private var hScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private val minScale = 0.5f
    private val maxScale = 3.5f

    private val cR = Color.parseColor("#FF5555")
    private val cO = Color.parseColor("#FFB86C")
    private val cY = Color.parseColor("#FFFF55")
    private val cW = Color.parseColor("#F8F8F2")
    private val cG = Color.parseColor("#50FA7B")
    private val cD = Color.parseColor("#6272A4")
    private val cBB = Color.parseColor("#44F8F8F2")
    private val cGrid = Color.parseColor("#226272A4")

    private fun tf(p: Paint, weight: Int): Paint { Fonts.apply(p, context, weight); return p }

    private val bgP = Paint().apply { style = Paint.Style.FILL }
    private val borP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cBB; style = Paint.Style.STROKE }
    private val titP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cW }, Fonts.BOLD)
    private val subP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cD }, Fonts.REGULAR)
    private val hotP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.MEDIUM)
    private val axP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cD }, Fonts.REGULAR)
    private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cGrid; style = Paint.Style.STROKE }
    private val linP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val legP = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.REGULAR)
    private val clsP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cR }, Fonts.BOLD)

    private val rstP = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cO }, Fonts.BOLD)
    private val hdlP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cD; style = Paint.Style.STROKE }
    private val bgRect = RectF()
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePath = Path()

    /** Pontos do grafico: mais que isso nao cabe em tela. Buffers reaproveitados. */
    private val MAX_POINTS = 512
    private val serTs = LongArray(MAX_POINTS)
    private val serT = IntArray(MAX_POINTS)

    private val sd = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            wScale = (wScale * f).coerceIn(minScale, maxScale)
            hScale = (hScale * f).coerceIn(minScale, maxScale)
            updSizes(); requestLayout(); return true
        }
    })

    var onDragListener: ((Float, Float) -> Unit)? = null
    var onCloseListener: (() -> Unit)? = null

    private enum class TM { NONE, DRAG, RWH }
    private var tm = TM.NONE; private var ltx = 0f; private var lty = 0f

    private val baseFontSize = 12f
    private val baseWidth = 360f
    private val baseHeight = 240f
    private val baseChartH = 130f
    private val baseHorizPad = 10f
    private val baseHandleArea = 28f

    private var clR = RectF()

    private var rstR = RectF()
    private val tFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE) }

    private var cachedVisibleZoneIds: List<String> = emptyList()
    private var cachedVisibleZoneSet: Set<String> = emptySet()

    private val pl = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedVisibleZoneIds = computeVisibleZones(); cachedVisibleZoneSet = cachedVisibleZoneIds.toSet()
        reloadPrefs()
        cacheVersion = -1L
        post { updSizes(); requestLayout(); invalidate() }
    }

    // ---- cache do grafico -------------------------------------------------
    // Antes, cada redesenho (1x por segundo) varria o historico inteiro: ate
    // 86.400 amostras x 10 zonas para o hotspot, e de novo por zona para as
    // linhas. Agora isso so e refeito quando ha amostra nova E passou
    // [CHART_REFRESH_MS] (a janela e de 24 h; 1 px equivale a minutos), ou
    // quando muda o tamanho/as zonas.
    private val CHART_REFRESH_MS = 5000L
    private var cacheVersion = -1L
    private var cacheAtMs = 0L
    private var cacheGeom = 0L
    private var cHot: ThermalHistory.Hotspot? = null
    private var cWin: LongArray? = null
    private var cSamples = 0
    private var cAgeSec = 0L
    private val cPaths = ArrayList<Path>()
    private val cPathColor = ArrayList<Int>()

    private fun refreshChart(chartLeft: Float, chartW: Float, chartBottom: Float, chartH: Float) {
        val ver = ThermalHistory.version
        var g = java.lang.Float.floatToRawIntBits(chartLeft).toLong()
        g = g * 31 + java.lang.Float.floatToRawIntBits(chartW)
        g = g * 31 + java.lang.Float.floatToRawIntBits(chartBottom)
        g = g * 31 + java.lang.Float.floatToRawIntBits(chartH)
        val now = android.os.SystemClock.uptimeMillis()
        val geomChanged = g != cacheGeom
        if (!geomChanged && cacheVersion != -1L &&
            (ver == cacheVersion || now - cacheAtMs < CHART_REFRESH_MS)) return
        cacheGeom = g; cacheVersion = ver; cacheAtMs = now

        cHot = ThermalHistory.getHotspot24h(cachedVisibleZoneSet)
        cSamples = ThermalHistory.sampleCount()
        cAgeSec = if (cSamples > 0) ThermalHistory.oldestAgeSec() else 0L
        cWin = ThermalHistory.windowSec()
        cPaths.clear(); cPathColor.clear()
        val win = cWin ?: return
        if (win[1] <= win[0]) return
        val firstTs = win[0]
        val span = (win[1] - firstTs).coerceAtLeast(1L).toFloat()
        val maxPts = MAX_POINTS.coerceAtMost(chartW.toInt().coerceAtLeast(2))
        for (i in cachedVisibleZoneIds.indices) {
            val zi = ThermalHistory.zoneIndex(cachedVisibleZoneIds[i])
            val n = ThermalHistory.readSeries(zi, maxPts, serTs, serT)
            if (n < 2) continue
            val path = Path()
            var started = false
            for (k in 0 until n) {
                val t = serT[k]
                if (t <= 0) continue
                val nx = chartLeft + ((serTs[k] - firstTs) / span) * chartW
                val ny = chartBottom - (t.coerceIn(0, 100) / 100f) * chartH
                if (!started) { path.moveTo(nx, ny); started = true } else path.lineTo(nx, ny)
            }
            if (started) {
                cPaths.add(path)
                cPathColor.add(if (i < cZoneColors.size) cZoneColors[i] else cW)
            }
        }
    }

    private fun computeVisibleZones(): List<String> =
        OverlayPrefs.allDrawableZones().filter { OverlayPrefs.isZoneVisible(context, it) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(pl)
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(pl)
        super.onDetachedFromWindow()
    }

    private var cUf = 1f; private var cCs = 1f; private var cMT = 1f; private var cMB = 1f
    private var cFsTitle = 1f; private var cFsValue = 1f; private var cFsLabel = 1f; private var cFsChart = 1f
    private var cBgColor = Color.BLACK
    private var cTitle = ""
    private var cZoneColors = IntArray(0)
    private var cZoneNames = emptyArray<String>()

    /** Uma leitura só; antes cada frame relia dezenas de chaves de SharedPreferences. */
    private fun reloadPrefs() {
        Fonts.reloadMode(context)
        tf(titP, Fonts.BOLD); tf(clsP, Fonts.BOLD); tf(rstP, Fonts.BOLD)
        tf(hotP, Fonts.MEDIUM)
        tf(subP, Fonts.REGULAR); tf(axP, Fonts.REGULAR); tf(legP, Fonts.REGULAR)
        cUf = OverlayPrefs.getFontSize(context)
        cCs = OverlayPrefs.getControlSize(context)
        cMT = OverlayPrefs.getMarginTop(context)
        cFsTitle = OverlayPrefs.getTitleFontScale(context)
        cFsValue = OverlayPrefs.getValueFontScale(context)
        cFsLabel = OverlayPrefs.getLabelFontScale(context)
        cFsChart = OverlayPrefs.getChartFontScale(context)
        val c = OverlayPrefs.getBgColor(context)
        val a = OverlayPrefs.getBgAlpha(context)
        cBgColor = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
        cTitle = OverlayPrefs.getHistoryTitle(context)
        cZoneColors = IntArray(cachedVisibleZoneIds.size) { OverlayPrefs.getColor(context, cachedVisibleZoneIds[it]) }
        cZoneNames = Array(cachedVisibleZoneIds.size) { OverlayPrefs.getDisplayName(context, cachedVisibleZoneIds[it]) }
    }

    // Idem InfoPanelView: init so depois das declaracoes de cache.
    init {
        cachedVisibleZoneIds = computeVisibleZones(); cachedVisibleZoneSet = cachedVisibleZoneIds.toSet()
        reloadPrefs()
        updSizes()
    }

    private fun uf() = cUf
    private fun cs() = cCs
    private fun mT() = cMT * hScale * uf()
    private fun fsTitle() = cFsTitle
    private fun fsValue() = cFsValue
    private fun fsLabel() = cFsLabel
    private fun fsChart() = cFsChart
    private fun fs() = baseFontSize * hScale * uf()
    private fun handleTouchArea() = baseHandleArea * hScale * cs()
    private fun hPad() = baseHorizPad * hScale * uf()

    private fun updSizes() {
        val f = fs()
        titP.textSize = f * 1.1f * fsTitle()
        subP.textSize = f * 0.85f * fsLabel()
        hotP.textSize = f * fsValue()
        axP.textSize = f * 0.8f * fsChart()
        legP.textSize = f * 0.85f * fsChart()
        clsP.textSize = f * 1.1f * cs()
        rstP.textSize = f * 1.1f * cs()
        borP.strokeWidth = 2f * hScale
        gridP.strokeWidth = 1f * hScale
        hdlP.strokeWidth = 1.5f * hScale * cs()
        linP.strokeWidth = 2f * hScale
    }

    private fun bgColor(): Int = cBgColor

    private fun tempColor(t: Int) = when {
        t >= 90 -> cR; t >= 70 -> cO; t >= 55 -> cY; t >= 40 -> cW; else -> cG
    }

    override fun onMeasure(wMS: Int, hMS: Int) {
        val w = (baseWidth * wScale).toInt()
        val h = (baseHeight * hScale).toInt()
        setMeasuredDimension(w, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        if (sd.isInProgress) { tm = TM.NONE; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {

                if (rstR.contains(e.x, e.y)) {
                    ThermalHistory.clearAll()
                    try {
                        Toast.makeText(context, "Histórico zerado", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    invalidate()
                    return true
                }
                if (clR.contains(e.x, e.y)) { onCloseListener?.invoke(); return true }
                val w = width.toFloat(); val h = height.toFloat()
                val ha = handleTouchArea()
                tm = if (e.x >= w - ha && e.y >= h - ha) TM.RWH else TM.DRAG
                ltx = e.rawX; lty = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - ltx; val dy = e.rawY - lty
                when (tm) {
                    TM.DRAG -> onDragListener?.invoke(dx, dy)
                    TM.RWH -> {
                        wScale = (wScale + dx / 200f).coerceIn(minScale, maxScale)
                        hScale = (hScale + dy / 200f).coerceIn(minScale, maxScale)
                        updSizes(); requestLayout(); invalidate()
                    }
                    TM.NONE -> {}
                }
                ltx = e.rawX; lty = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tm = TM.NONE
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val hp = hPad()
        val cr = 12f * hScale

        bgP.color = bgColor()
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cr, cr, bgP)
        canvas.drawRoundRect(bgRect, cr, cr, borP)

        var y = mT() + titP.textSize
        canvas.drawText(cTitle, hp, y, titP)

        val clsSize = clsP.textSize
        val cx = w - hp - clsSize
        val hitPad = 12f * cs()
        clR.set(cx - hitPad, y - clsSize - hitPad, cx + clsSize + hitPad, y + hitPad)
        canvas.drawText("✕", cx, y, clsP)

        val rstSize = rstP.textSize
        val rstGap = clsSize * 0.8f
        val rx = cx - rstSize - rstGap
        rstR.set(rx - hitPad, y - rstSize - hitPad, rx + rstSize + hitPad, y + hitPad)
        canvas.drawText("⟲", rx, y, rstP)


        y += subP.textSize * 1.8f

        // geometria do grafico (precisa vir antes para o cache)
        val chartTopG = y + subP.textSize * 1.5f + subP.textSize * 0.8f
        val chartHG = baseChartH * hScale
        val chartLeftG = hp + axP.measureText("100°C") + 4f * hScale
        refreshChart(chartLeftG, (w - hp) - chartLeftG, chartTopG + chartHG, chartHG)

        val hot = cHot
        if (hot != null) {
            val hourStr = tFmt.format(Date(hot.tsSec * 1000))
            val zIdx = cachedVisibleZoneIds.indexOf(hot.zoneId)
            val displayName = if (zIdx >= 0 && zIdx < cZoneNames.size) cZoneNames[zIdx]
                              else OverlayPrefs.getDisplayName(context, hot.zoneId)
            val prefix = "Hotspot: "
            canvas.drawText(prefix, hp, y, subP)
            val px = hp + subP.measureText(prefix)
            hotP.color = tempColor(hot.temp)
            canvas.drawText("$displayName  ${hot.temp}°C  às $hourStr", px, y, hotP)
        } else {
            canvas.drawText("Hotspot: coletando dados...", hp, y, subP)
        }

        y += subP.textSize * 1.5f
        val nSamples = cSamples
        if (nSamples > 0) {
            val ageMin = cAgeSec / 60
            val info = if (ageMin >= 60) {
                val h2 = ageMin / 60; val m2 = ageMin % 60
                "${nSamples} amostras · ${h2}h${if (m2 > 0) "${m2}min" else ""}"
            } else {
                "${nSamples} amostras · ${ageMin}min"
            }
            canvas.drawText(info, hp, y, subP)
        } else {
            canvas.drawText("0 amostras", hp, y, subP)
        }

        val chartTop = y + subP.textSize * 0.8f
        val chartH = baseChartH * hScale
        val chartLeft = hp + axP.measureText("100°C") + 4f * hScale
        val chartRight = w - hp
        val chartBottom = chartTop + chartH
        val chartW = chartRight - chartLeft

        for (level in intArrayOf(0, 25, 50, 75, 100)) {
            val ny = chartBottom - (level / 100f) * chartH
            canvas.drawLine(chartLeft, ny, chartRight, ny, gridP)
            val lbl = "${level}°C"
            canvas.drawText(lbl, hp, ny + axP.textSize / 3, axP)
        }

        val win = cWin
        if (win != null && win[1] > win[0]) {
            val firstTs = win[0]
            val lastTs = win[1]
            for (i in cPaths.indices) {
                linP.color = cPathColor[i]
                canvas.drawPath(cPaths[i], linP)
            }

            val firstStr = tFmt.format(Date(firstTs * 1000))
            val lastStr = tFmt.format(Date(lastTs * 1000))
            val midTs = firstTs + (lastTs - firstTs) / 2
            val midStr = tFmt.format(Date(midTs * 1000))
            val axY = chartBottom + axP.textSize * 1.2f
            canvas.drawText(firstStr, chartLeft, axY, axP)
            val midW = axP.measureText(midStr)
            canvas.drawText(midStr, chartLeft + chartW / 2 - midW / 2, axY, axP)
            val lastW = axP.measureText(lastStr)
            canvas.drawText(lastStr, chartRight - lastW, axY, axP)
        } else {
            val msg = "Coletando dados (1Hz)..."
            val mw = subP.measureText(msg)
            canvas.drawText(msg, chartLeft + chartW / 2 - mw / 2, chartBottom - chartH / 2, subP)
        }

        val legY = chartBottom + axP.textSize * 2.4f
        var legX = hp
        val legPad = 10f * hScale
        val dotR = 4f * hScale
        for (i in cachedVisibleZoneIds.indices) {
            val name = if (i < cZoneNames.size) cZoneNames[i] else cachedVisibleZoneIds[i]
            val color = if (i < cZoneColors.size) cZoneColors[i] else cW
            val itemW = dotR * 2 + 6f + legP.measureText(name) + legPad
            if (legX + itemW > w - hp) break
            dotP.color = color
            canvas.drawCircle(legX + dotR, legY - legP.textSize / 3, dotR, dotP)
            legP.color = cW
            canvas.drawText(name, legX + dotR * 2 + 6f, legY, legP)
            legX += itemW
        }

        val hs = handleTouchArea() * 0.5f
        val m = 6f * hScale * cs()
        val gripN = 3
        for (i in 0 until gripN) {
            val o = (i + 1) * (hs / (gripN + 1))
            canvas.drawLine(w - m - o, h - m, w - m, h - m - o, hdlP)
        }
    }
}
