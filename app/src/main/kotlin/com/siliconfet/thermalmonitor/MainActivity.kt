package com.siliconfet.thermalmonitor

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var theme: AppTheme

    companion object {
        private const val SHIZUKU_PERM_CODE = 200
    }

    private var shizukuAvailable = false
    private var userIntentRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var btnToggle: TextView
    private lateinit var rowChip: Pair<TextView, TextView>
    private lateinit var rowAccess: Pair<TextView, TextView>
    private lateinit var rowShizuku: Pair<TextView, TextView>
    private lateinit var rowOverlay: Pair<TextView, TextView>
    private lateinit var rowMonitor: Pair<TextView, TextView>

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuAvailable = true; handler.post { updateUI(); maybeMapHardware() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuAvailable = false; handler.post { updateUI() }
    }
    private val permResultListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == SHIZUKU_PERM_CODE && grantResult == PackageManager.PERMISSION_GRANTED)
                handler.post { maybeMapHardware(); startOverlay() }
            handler.post { updateUI() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        Fonts.reloadMode(this)

        try {
            val sp = getSharedPreferences("overlay_prefs", MODE_PRIVATE)
            if (sp.all.isEmpty()) {
                SettingsBackup.applyBundledDefaults(this)
                HwProfile.reapply(this)
            }
        } catch (_: Exception) {}
        try {
            theme = AppTheme.current(this)
            buildUI()
            animateEntry()
        } catch (e: Exception) {

            android.util.Log.e("MainActivity", "buildUI failed", e)
            try {
                AppTheme.setCurrent(this, "default")
                theme = AppTheme.DEFAULT
                buildUI()
            } catch (e2: Exception) {

                android.util.Log.e("MainActivity", "buildUI fallback also failed", e2)
                val tv = TextView(this).apply {
                    text = "Erro ao carregar UI: ${e.javaClass.simpleName}\n\n${e.message}"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.BLACK)
                    setPadding(40, 80, 40, 40)
                    typeface = Typeface.MONOSPACE // fallback de erro: nao depende de recurso
                }
                setContentView(tv)
            }
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permResultListener)
        handleIntent(intent)

        // Primeira coisa: perguntar o root (Magisk mostra o prompt; no KernelSU/
        // APatch o app precisa estar liberado no gerenciador). Sem root, segue
        // pelo Shizuku (ADB).
        if (AccessManager.mode(this) != AccessManager.MODE_SHIZUKU) {
            AccessManager.probeRootAsync(this) { updateUI(); maybeMapHardware() }
        } else {
            maybeMapHardware()
        }
    }

    private var mappingInFlight = false

    /**
     * Na primeira abertura (ou se o hardware mudou), conecta ao servidor so para
     * mapear o chip e ja deixar o overlay configurado para ele.
     */
    private fun maybeMapHardware(force: Boolean = false) {
        if (mappingInFlight) return
        if (!force && HwProfile.cachedInfo(this) != null) return
        if (OverlayService.isRunning) return
        val backend = AccessManager.resolve(this)
        if (backend == AccessManager.Backend.NONE) return
        mappingInFlight = true
        rowChip.second.text = "Mapeando..."
        HwProfile.mapOnce(this, backend) { ok ->
            mappingInFlight = false
            if (ok) android.widget.Toast.makeText(this,
                "Hardware mapeado: ${HwProfile.detectSoc().name}", android.widget.Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        shizukuAvailable = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        try { getSystemService(Context.ACTIVITY_SERVICE) } catch (_: Exception) {}

        userIntentRunning = UserIntent.isRunning(this)
        updateUI()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permResultListener)
        super.onDestroy()
    }

    private fun buildUI() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(theme.cBg)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(32), d(20), d(40))
        }
        rootContainer = root
        scroll.addView(root)

        root.addView(buildHeader())
        root.addView(gap(28))
        root.addView(buildStatusCard())
        root.addView(gap(16))
        btnToggle = buildMainButton("INICIAR MONITOR")
        root.addView(btnToggle)
        root.addView(gap(10))
        root.addView(buildSecondaryButton())
        root.addView(gap(20))
        root.addView(buildPanelsSection())
        root.addView(gap(28))
        root.addView(buildFooter())

        setContentView(scroll)
    }

    private fun buildHeader(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = llp()
        }

        val badge = TextView(this).apply {
            text = "  ${HwProfile.badge()}  "
            textSize = 12f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(if (theme.useGradient) theme.cText else theme.cBg)
            background = if (theme.useGradient) {
                gradientPill(theme.accent1, theme.accent2, d(20))
            } else {
                roundRect(d(20), theme.accent1, Color.TRANSPARENT)
            }
            if (theme.hasGlow) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                paint.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.SOLID)
            }
            setPadding(d(14), d(6), d(14), d(6))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(16); gravity = Gravity.CENTER_HORIZONTAL }
        }
        col.addView(badge)

        val title = TextView(this).apply {
            text = "Thermal Monitor"
            textSize = 38f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(theme.cText)
            gravity = Gravity.CENTER
            layoutParams = llp()
        }
        col.addView(title)

        val line = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(d(80), d(3)).apply {
                topMargin = d(10); bottomMargin = d(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = if (theme.useGradient) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.accent1, theme.accent2)
                ).apply { cornerRadius = d(4).toFloat() }
            } else {
                roundRect(d(4), theme.accent1, Color.TRANSPARENT)
            }
        }
        col.addView(line)

        val sub = TextView(this).apply {
            text = "Temperatura  ·  CPU / GPU  ·  Bateria"
            textSize = 13f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            gravity = Gravity.CENTER
            layoutParams = llp()
        }
        col.addView(sub)

        return col
    }

    private fun buildStatusCard(): View {
        val card = themedCard()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                d((18 * theme.padScale).toInt()),
                d((18 * theme.padScale).toInt()),
                d((18 * theme.padScale).toInt()),
                d((10 * theme.padScale).toInt())
            )
        }

        col.addView(TextView(this).apply {
            text = "STATUS"
            textSize = 10f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(theme.cDim)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, d(14))
        })

        rowChip = addStatusRow(col, "Chip")
        col.addView(rowDivider())
        rowAccess = addStatusRow(col, "Acesso")
        col.addView(rowDivider())
        rowShizuku = addStatusRow(col, "Shizuku")
        col.addView(rowDivider())
        rowOverlay = addStatusRow(col, "Overlay")
        col.addView(rowDivider())
        rowMonitor = addStatusRow(col, "Monitor")

        card.addView(col)
        return card
    }

    private fun addStatusRow(parent: LinearLayout, label: String): Pair<TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(10), 0, d(10))
        }

        val dot = TextView(this).apply {
            text = "•"
            textSize = 18f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            setPadding(0, 0, d(12), 0)

            if (theme.hasGlow) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                paint.maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.SOLID)
            }
        }
        val lbl = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = "..."
            textSize = 13f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cText)
            gravity = Gravity.END
        }

        row.addView(dot); row.addView(lbl); row.addView(value)
        parent.addView(row)
        return dot to value
    }

    private fun buildMainButton(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(theme.cBg)
            gravity = Gravity.CENTER
            setPadding(d(24), d(20), d(24), d(20))
            layoutParams = llp()
            elevation = 6f * resources.displayMetrics.density
            setOnClickListener { toggleOverlay() }
        }
    }

    private fun buildSecondaryButton(): View {
        return TextView(this).apply {
            text = "•   CONFIGURAÇÕES"
            textSize = 14f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            gravity = Gravity.CENTER
            background = roundRect(d(14), Color.TRANSPARENT, theme.cBorder)
            setPadding(d(24), d(16), d(24), d(16))
            layoutParams = llp()
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
    }

    private fun buildPanelsSection(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(TextView(this).apply {
            text = "PAINÉIS"
            textSize = 10f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(theme.cDim)
            letterSpacing = 0.15f
            layoutParams = llp().apply { bottomMargin = d(12) }
        })

        data class Panel(val name: String, val desc: String, val accent: Int)
        listOf(
            Panel("Principal",   "Temps + CPU / GPU load e frequência", theme.cGreen),
            Panel("Frequências", "L3 · DDR · LLCC frequência",          theme.cOrange),
            Panel("Bateria",     "Potência · temperatura · ciclos",     theme.cCyan),
            Panel("Histórico",   "Gráfico 24h + hotspot registrado",    theme.cPurple)
        ).forEachIndexed { i, p ->
            if (i > 0) col.addView(gap(8))
            col.addView(buildPanelRow(p.name, p.desc, p.accent))
        }
        return col
    }

    private fun buildPanelRow(name: String, desc: String, accent: Int): View {
        val card = themedCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(14), d(14), d(14))
        }

        val stripeWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(d(4), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(accent)
        }

        val ic = TextView(this).apply {
            text = "•"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(accent)
            layoutParams = LinearLayout.LayoutParams(d(36), d(36)).apply {
                leftMargin = d(12); rightMargin = d(12)
            }
            if (theme.hasGlow) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                paint.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.SOLID)
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = name
            textSize = 14f
            typeface = Fonts.bold(this@MainActivity)
            setTextColor(theme.cText)
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 11f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            setPadding(0, d(2), 0, 0)
        })

        row.addView(stripeWrap)
        row.addView(ic)
        row.addView(texts)
        card.addView(row)
        return card
    }

    private fun buildFooter(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(View(this).apply {
            layoutParams = llp().apply { height = d(1); bottomMargin = d(16) }
            setBackgroundColor(theme.cBorder)
        })
        col.addView(TextView(this).apply {
            text = "by SiliconFET & DFDX047  ·  root / ADB  ·  Tema: ${theme.displayName}"
            textSize = 11f
            typeface = Fonts.regular(this@MainActivity)
            setTextColor(theme.cDim)
            gravity = Gravity.CENTER
        })
        return col
    }

    private fun updateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasShizukuPerm = shizukuAvailable && hasShizukuPermission()
        val realRunning = OverlayService.isRunning
        val backend = if (realRunning && ThermalReader.activeBackend != AccessManager.Backend.NONE)
            ThermalReader.activeBackend else AccessManager.resolve(this)

        val soc = HwProfile.detectSoc()
        val mapped = HwProfile.cachedInfo(this) != null
        setRow(rowChip, (soc.name + if (mapped) "  ✓" else "") to
            (if (!soc.qualcomm) theme.cOrange else if (mapped) theme.cGreen else theme.cText))

        val mode = AccessManager.mode(this)
        setRow(rowAccess, when (backend) {
            AccessManager.Backend.ROOT -> "Root" to theme.cGreen
            AccessManager.Backend.SHIZUKU -> "ADB (Shizuku)" to theme.cCyan
            AccessManager.Backend.NONE -> when {
                mode == AccessManager.MODE_ROOT -> "Root negado" to theme.cRed
                AccessManager.rootGranted == null && mode != AccessManager.MODE_SHIZUKU -> "Verificando..." to theme.cDim
                else -> "Sem root / sem Shizuku" to theme.cRed
            }
        })

        setRow(rowShizuku, when {
            !shizukuAvailable && backend == AccessManager.Backend.ROOT -> "Não necessário (root)" to theme.cDim
            !shizukuAvailable -> "Nao ativo" to theme.cRed
            !hasShizukuPerm   -> "Ativo (sem perm)" to theme.cOrange
            else              -> "Ativo e autorizado" to theme.cGreen
        })
        setRow(rowOverlay,
            if (hasOverlay) "Permitido" to theme.cGreen else "Sem permissao" to theme.cRed)
        setRow(rowMonitor,
            if (realRunning) "Ativo" to theme.cGreen else "Inativo" to theme.cDim)

        if (userIntentRunning) {
            btnToggle.text = "PARAR MONITOR"
            btnToggle.setTextColor(theme.cText)
            btnToggle.background = if (theme.useGradient)
                gradientPill(theme.cRed, blendColors(theme.cRed, theme.accent2, 0.3f), d(14))
            else
                roundRect(d(14), theme.cRed, Color.TRANSPARENT)
        } else {
            btnToggle.text = "INICIAR MONITOR"
            btnToggle.setTextColor(if (theme.useGradient) theme.cText else theme.cBg)
            btnToggle.background = if (theme.useGradient)
                gradientPill(theme.cGreen, blendColors(theme.cGreen, theme.accent1, 0.3f), d(14))
            else
                roundRect(d(14), theme.cGreen, Color.TRANSPARENT)
        }
        btnToggle.isEnabled = backend != AccessManager.Backend.NONE || shizukuAvailable || userIntentRunning
        btnToggle.alpha = if (btnToggle.isEnabled) 1f else 0.45f
    }

    private fun setRow(row: Pair<TextView, TextView>, state: Pair<String, Int>) {
        val (dot, value) = row
        val (text, color) = state
        dot.setTextColor(color)
        value.text = text
        value.setTextColor(color)
    }

    private var rootContainer: LinearLayout? = null

    private fun animateEntry() {
        val container = rootContainer ?: return
        val n = container.childCount
        for (i in 0 until n) {
            val view = container.getChildAt(i)
            view.alpha = 0f
            view.translationY = d(20).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setStartDelay(i * 50L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "stop") stopOverlay()
    }

    private fun toggleOverlay() {

        if (OverlayService.isRunning || userIntentRunning) stopOverlay() else startOverlay()
    }

    private fun startOverlay() {
        val backend = AccessManager.resolve(this)
        if (backend == AccessManager.Backend.NONE) {
            val mode = AccessManager.mode(this)
            if (mode != AccessManager.MODE_ROOT && shizukuAvailable && !hasShizukuPermission()) {
                Shizuku.requestPermission(SHIZUKU_PERM_CODE); return
            }
            flashButton(theme.cOrange)
            android.widget.Toast.makeText(this,
                if (mode == AccessManager.MODE_ROOT) "Root não concedido. Libere o app no Magisk/KernelSU e reabra."
                else "Precisa de root (Magisk/KernelSU) ou do Shizuku ativo (ADB).",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            return
        }
        userIntentRunning = true
        UserIntent.setRunning(this, true)
        startForegroundService(Intent(this, OverlayService::class.java))

        updateUI()
        pollUntilRunning(attempts = 15)
    }

    private fun pollUntilRunning(attempts: Int) {
        if (attempts <= 0) return
        handler.postDelayed({
            updateUI()
            if (!OverlayService.isRunning && userIntentRunning) {
                pollUntilRunning(attempts - 1)
            }
        }, 200L)
    }

    private fun stopOverlay() {
        userIntentRunning = false
        UserIntent.setRunning(this, false)
        try { KeepAliveJobService.cancel(this) } catch (_: Exception) {}

        Thread { try { ThermalReader.stopDataCollection() } catch (_: Exception) {} }.start()
        stopService(Intent(this, OverlayService::class.java))
        updateUI()

        pollUntilStopped(attempts = 10)
    }

    private fun pollUntilStopped(attempts: Int) {
        if (attempts <= 0) return
        handler.postDelayed({
            updateUI()
            if (OverlayService.isRunning) {
                pollUntilStopped(attempts - 1)
            }
        }, 200L)
    }

    private fun hasShizukuPermission() = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startOverlay()
    }

    private fun d(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun gap(dpVal: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(dpVal))
    }

    private fun llp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun rowDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(1))
            .apply { marginStart = d(22) }
        setBackgroundColor(theme.cBorder)
    }

    private fun themedCard(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = llp()
            background = if (!theme.showCardBg) {

                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(d(1), theme.cBorder)
                    cornerRadius = d(theme.cardCorner).toFloat()
                }
            } else {
                roundRect(
                    d(theme.cardCorner),
                    if (theme.cardAlpha < 255)
                        Color.argb(theme.cardAlpha, Color.red(theme.cCard),
                            Color.green(theme.cCard), Color.blue(theme.cCard))
                    else theme.cCard,
                    theme.cCardStroke
                )
            }
            elevation = theme.cardElevation * resources.displayMetrics.density
        }
    }

    private fun roundRect(cornerPx: Int, fill: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = cornerPx.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(d(1), stroke)
        }
    }

    private fun gradientPill(c1: Int, c2: Int, cornerPx: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(c1, c2)
        ).apply { cornerRadius = cornerPx.toFloat() }
    }

    private fun argbAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun flashButton(color: Int) {
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                btnToggle.background = roundRect(d(14), argbAlpha(color, v * 0.5f), Color.TRANSPARENT)
            }
            start()
        }
        handler.postDelayed({ updateUI() }, 450)
    }
}
