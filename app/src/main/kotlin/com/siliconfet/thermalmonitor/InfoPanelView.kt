package com.siliconfet.thermalmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

class InfoPanelView(context: Context) : View(context) {

    private var wScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private var hScale = OverlayPrefs.DEFAULT_OVERLAY_SCALE
    private val minScale = 0.4f
    private val maxScale = 3.5f

    private val colorWhite = Color.parseColor("#F8F8F2")
    private val colorDim   = Color.parseColor("#6272A4")
    private val colorClose = Color.parseColor("#FF5555")

    private fun tf(p: Paint, weight: Int): Paint { Fonts.apply(p, context, weight); return p }

    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44F8F8F2"); style = Paint.Style.STROKE
    }
    private val titlePaint = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorWhite }, Fonts.BOLD)
    private val labelPaint = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorDim }, Fonts.REGULAR)
    private val valuePaint = tf(Paint(Paint.ANTI_ALIAS_FLAG), Fonts.MEDIUM)
    private val closePaint = tf(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorClose }, Fonts.BOLD)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorDim; style = Paint.Style.STROKE
    }
    private val bgRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                wScale = (wScale * f).coerceIn(minScale, maxScale)
                hScale = (hScale * f).coerceIn(minScale, maxScale)
                updateSizes(); requestLayout(); return true
            }
        }
    )

    var onDragListener: ((Float, Float) -> Unit)? = null
    var onCloseListener: (() -> Unit)? = null

    private enum class TM { NONE, DRAG, RWH }
    private var tm = TM.NONE
    private var ltx = 0f
    private var lty = 0f

    var title = ""
    var items = listOf<Triple<String, String, Int>>()

    /**
     * Atualiza os itens sem forcar relayout a cada tick.
     *
     * Antes o servico chamava requestLayout() uma vez por segundo em cada
     * painel; como a view e a raiz de uma janela de overlay, isso passa pelo
     * WindowManager. So o tamanho do texto (ou a quantidade de linhas) muda o
     * layout; o resto e so redesenho.
     */
    fun setItemsIfChanged(newItems: List<Triple<String, String, Int>>) {
        val old = items
        if (newItems == old) return
        var relayout = newItems.size != old.size
        if (!relayout) {
            for (i in newItems.indices) {
                if (newItems[i].first != old[i].first || newItems[i].second.length != old[i].second.length) {
                    relayout = true; break
                }
            }
        }
        items = newItems
        if (relayout) requestLayout()
        invalidate()
    }

    private val prefs: android.content.SharedPreferences by lazy {
        context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
    }
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        post { reloadPrefs(); updateSizes(); requestLayout(); invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadPrefs()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    private val baseFontSize = 13f
    private val baseHorizPad = 10f
    private val baseCornerRadius = 10f
    private val baseWidth = 220f
    private val baseHandleArea = 28f

    private var closeRect = RectF()
    private var minContentW = 0f

    private var cUf = 1f; private var cVSp = 0f; private var cCs = 1f
    private var cMT = 1f; private var cMB = 1f
    private var cFsTitle = 1f; private var cFsValue = 1f; private var cFsLabel = 1f
    private var cBgColor = Color.BLACK

    /** Lê as preferências uma vez; antes cada frame fazia ~10 leituras de SharedPreferences. */
    private fun reloadPrefs() {
        Fonts.reloadMode(context)
        tf(titlePaint, Fonts.BOLD); tf(closePaint, Fonts.BOLD)
        tf(valuePaint, Fonts.MEDIUM); tf(labelPaint, Fonts.REGULAR)
        cUf = OverlayPrefs.getFontSize(context)
        cVSp = OverlayPrefs.getVerticalSpacing(context)
        cCs = OverlayPrefs.getControlSize(context)
        cMT = OverlayPrefs.getMarginTop(context)
        cMB = OverlayPrefs.getMarginBottom(context)
        cFsTitle = OverlayPrefs.getTitleFontScale(context)
        cFsValue = OverlayPrefs.getValueFontScale(context)
        cFsLabel = OverlayPrefs.getLabelFontScale(context)
        val c = OverlayPrefs.getBgColor(context)
        val a = OverlayPrefs.getBgAlpha(context)
        cBgColor = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }

    // O init tem de vir DEPOIS das declaracoes acima: em Kotlin os
    // inicializadores de propriedade e os blocos init rodam na ordem em que
    // aparecem, entao um init no topo seria desfeito por `private var cUf = 1f`.
    init { reloadPrefs() }

    private fun uf() = cUf
    private fun vSp() = cVSp
    private fun cs() = cCs

    private fun mT() = cMT * hScale * uf()
    private fun mB() = cMB * hScale * uf()

    private fun fsTitle() = cFsTitle
    private fun fsValue() = cFsValue
    private fun fsLabel() = cFsLabel

    private fun fs(): Float = baseFontSize * hScale * uf()

    private fun lh(): Float {
        val maxScale = maxOf(fsValue(), fsTitle(), fsLabel())
        val baseFont = fs() * maxScale
        return baseFont * 1.05f * (1f + vSp())
    }
    private fun hPad(): Float = baseHorizPad * hScale * uf()

    private fun handleTouchArea(): Float = baseHandleArea * hScale * cs()

    private fun updateSizes() {
        val f = fs()
        titlePaint.textSize = f * 1.1f * fsTitle()
        labelPaint.textSize = f * 0.9f * fsLabel()
        valuePaint.textSize = f * fsValue()
        closePaint.textSize = f * 1.1f * cs()
        borderPaint.strokeWidth = 2f * hScale
        handlePaint.strokeWidth = 1.5f * hScale * cs()
    }

    private fun bgColor(): Int = cBgColor

    private fun measureContent() {
        updateSizes()
        val hp = hPad()

        var maxW = titlePaint.measureText(title) + closePaint.textSize + hp * 2

        for ((lbl, value, _) in items) {
            val w = labelPaint.measureText("$lbl: ") + valuePaint.measureText(value) + hp * 2
            if (w > maxW) maxW = w
        }
        minContentW = maxW
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureContent()
        val lh = lh()

        val h = (mT() + lh * (1f + items.size + 0.2f) + mB()).toInt()
        val contentW = minContentW
        val scaledW = baseWidth * wScale
        val w = max(contentW, scaledW).toInt()
        setMeasuredDimension(w, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) { tm = TM.NONE; return true }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (closeRect.contains(e.x, e.y)) { onCloseListener?.invoke(); return true }
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
                        hScale = (hScale + dy / 300f).coerceIn(minScale, maxScale)
                        updateSizes(); requestLayout(); invalidate()
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
        val lh = lh(); val hp = hPad()
        val cr = baseCornerRadius * hScale

        bgPaint.color = bgColor()
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cr, cr, bgPaint)
        canvas.drawRoundRect(bgRect, cr, cr, borderPaint)

        var y = mT() + lh

        canvas.drawText(title, hp, y, titlePaint)

        val clsSize = closePaint.textSize
        val cx = w - hp - clsSize
        val hitPadding = 12f * cs()

        val closeYTop = y - clsSize
        closeRect.set(cx - hitPadding, closeYTop - hitPadding, cx + clsSize + hitPadding, y + hitPadding)
        canvas.drawText("✕", cx, y, closePaint)

        for ((lbl, value, color) in items) {
            y += lh
            canvas.drawText("$lbl:", hp, y, labelPaint)
            valuePaint.color = color
            canvas.drawText(value, hp + labelPaint.measureText("$lbl: "), y, valuePaint)
        }

        val hs = handleTouchArea() * 0.5f
        val m = 6f * hScale * cs()
        val gripN = 3
        for (i in 0 until gripN) {
            val o = (i + 1) * (hs / (gripN + 1))
            canvas.drawLine(w - m - o, h - m, w - m, h - m - o, handlePaint)
        }
    }
}
