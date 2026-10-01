package com.siliconfet.thermalmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "thermal_overlay"
        const val NOTIFICATION_ID = 1
        var isRunning = false
            private set

        private val HASH_ZONES = arrayOf("CPUCL0","CPUCL1","CPUCL2","CPUCL3","G3D")

        private val DEVFREQ_IDS = arrayOf(
            "dsu", "mif", "npu0", "npu1", "npucon", "isp", "int", "disp", "icpu"
        )

    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pool: ScheduledExecutorService = Executors.newScheduledThreadPool(3) { r ->
        Thread(r, "tm-sched").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var fastTask: ScheduledFuture<*>? = null
    @Volatile private var loadsTask: ScheduledFuture<*>? = null

    /**
     * Uma tarefa periodica, nao dez.
     *
     * Cada metrica mantem o proprio prazo; o tick coleta o que venceu e pede
     * tudo numa chamada binder so. Eram ~55 chamadas por segundo na
     * configuracao do usuario, cada uma com marshalling de String nos dois
     * processos, e ate 9 invalidates por ciclo — agora e um.
     */
    @Volatile private var metricTask: ScheduledFuture<*>? = null
    private var powerFuture: ScheduledFuture<*>? = null
    private var running = true

    @Volatile private var paused = false

    @Volatile private var lastRenderHash = 0L
    @Volatile private var hashCpuFreq = 0L
    @Volatile private var hashGpuFreq = 0L
    @Volatile private var hashCpuLoads = 0L
    @Volatile private var hashGpuLoad = 0L
    @Volatile private var hashGpuTemp = 0L
    @Volatile private var hashRam = 0L
    @Volatile private var hashGpuMem = 0L
    @Volatile private var hashCpuTemp = 0L
    @Volatile private var hashVolts = 0L
    @Volatile private var hashNpu = 0L

    @Volatile private var overlayVisible = true

    /**
     * Servidor para o qual intervalos/visibilidade/mapa ja foram enviados.
     * Antes era um booleano que nunca voltava a false: se o servidor morresse e
     * fosse recriado, ele rodava com os intervalos padrao ate reiniciar o app.
     */
    @Volatile private var syncedServer: ICommandService? = null
    @Volatile private var visSyncPosted = false

    /** Varias chaves `visible_*` de uma vez (ex.: perfil aplicado) viram um sync so. */
    private fun postVisibilitySync() {
        if (visSyncPosted) return
        visSyncPosted = true
        mainHandler.postDelayed({
            visSyncPosted = false
            Thread { try { ThermalReader.syncVisibility(this@OverlayService) } catch (_: Exception) {} }
                .apply { isDaemon = true; start() }
        }, 150)
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseAll()
                Intent.ACTION_SCREEN_ON -> resumeAll()
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var mainView: ThermalOverlayView? = null
    private var mainParams: WindowManager.LayoutParams? = null

    private var devfreqView: InfoPanelView? = null
    private var devfreqParams: WindowManager.LayoutParams? = null

    private var batteryView: InfoPanelView? = null
    private var batteryParams: WindowManager.LayoutParams? = null

    private var historyView: HistoryOverlayView? = null
    private var historyParams: WindowManager.LayoutParams? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->

        if (key != null && (key.startsWith("gpu_") || key.startsWith("elemcolor_") ||
                key.startsWith("tel_off_") || key.startsWith("tel_mem_") || key.startsWith("tel_bar_") ||
                key.startsWith("main_off_") || key == "main_mem_visible" ||
                key == "mem_split" || key == "chip_threshold")) {
            postMainReload()
            return@OnSharedPreferenceChangeListener
        }

        if (key != null && (key.startsWith("visible_") || key.startsWith("freq_visible_") ||
                key.startsWith("volt_visible_"))) {
            postVisibilitySync()
            postMainReload()
            return@OnSharedPreferenceChangeListener
        }

        if (key != null && key.startsWith("interval_")) {
            if (!intervalRestartPosted) {
                intervalRestartPosted = true
                mainHandler.postDelayed({
                    intervalRestartPosted = false
                    Thread { try { ThermalReader.syncIntervals(this@OverlayService) } catch (_: Exception) {} }
                        .apply { isDaemon = true; start() }
                    restartMetricLoop()
                }, 150)
            }
            return@OnSharedPreferenceChangeListener
        }

        when (key) {
            "font_family" -> mainHandler.post {
                Fonts.reloadMode(this@OverlayService)
                mainView?.apply { reload(); requestLayout(); invalidate() }
                devfreqView?.apply { requestLayout(); invalidate() }
                batteryView?.apply { requestLayout(); invalidate() }
                historyView?.apply { requestLayout(); invalidate() }
            }
            "power_interval" -> restartPowerLoop()
            "panel_main" -> mainHandler.post { toggleMain() }
            "panel_devfreq" -> mainHandler.post { toggleDevfreq() }
            "panel_battery" -> mainHandler.post { toggleBattery() }
            "panel_history" -> mainHandler.post { toggleHistory() }
            "minimal_mode" -> mainHandler.post {

                mainView?.apply { requestLayout(); invalidate() }
            }

            "show_hotspot_95" -> mainHandler.post {
                mainView?.apply { requestLayout(); invalidate() }
            }

            "hotspot_count_95" -> mainHandler.post {
                mainView?.invalidate()
            }

            "hotspot_threshold" -> mainHandler.post {
                mainView?.invalidate()
            }

            "col_gap_name", "col_gap_freq", "col_gap_temp", "col_gap_volt",
            "col_gap_load", "gap_power_section", "tel_off_ramlbl_x", "tel_off_ramlbl_y", "tel_off_swaplbl_x", "tel_off_swaplbl_y", "tel_mem_label_size", "main_show_power" -> mainHandler.post {
                mainView?.apply { requestLayout(); invalidate() }
            }
            "title_devfreq" -> mainHandler.post {
                devfreqView?.apply { title = OverlayPrefs.getDevfreqTitle(this@OverlayService); requestLayout(); invalidate() }
            }
            "title_battery" -> mainHandler.post {
                batteryView?.apply { title = OverlayPrefs.getBatteryTitle(this@OverlayService); requestLayout(); invalidate() }
            }
            "title_history" -> mainHandler.post {
                historyView?.apply { requestLayout(); invalidate() }
            }
        }
    }

    @Volatile private var reloadPosted = false
    @Volatile private var intervalRestartPosted = false

    /** Uma rajada de mudancas de preferencia vira um reload so. */
    private fun postMainReload() {
        if (reloadPosted) return
        reloadPosted = true
        mainHandler.post {
            reloadPosted = false
            mainView?.apply { reload(); requestLayout(); invalidate() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        Fonts.init(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ThermalMonitor:OverlayWL"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        try { KeepAliveJobService.schedule(this) } catch (_: Exception) {}

        BatteryMonitor.init(this)

        try {
            registerReceiver(BatteryMonitor.batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}
        ThermalHistory.init(this)

        ThermalReader.bindService(this)

        if (OverlayPrefs.isMainOverlayEnabled(this)) showMain()
        if (OverlayPrefs.isDevfreqOverlayEnabled(this)) showDevfreq()
        if (OverlayPrefs.isBatteryOverlayEnabled(this)) showBattery()
        if (OverlayPrefs.isHistoryOverlayEnabled(this)) showHistory()

        getSharedPreferences("overlay_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}

        startFastLoop()
        startLoadsLoop()
        startMetricLoops()
        startPowerLoop()
    }

    private fun showMain() {
        if (mainView != null) return
        mainView = ThermalOverlayView(this)
        mainParams = makeParams(0, 100)
        mainView!!.onDragListener = { dx, dy -> moveView(mainView!!, mainParams!!, dx, dy) }
        mainView!!.onDragEndListener = { mainHandler.post { mainView?.invalidate() } }
        mainView!!.onCloseListener = { hideMain(); OverlayPrefs.setMainOverlayEnabled(this, false) }

        mainView!!.onStopListener = {

            UserIntent.setRunning(this, false)
            try { KeepAliveJobService.cancel(this) } catch (_: Exception) {}
            Thread { try { ThermalReader.stopDataCollection() } catch (_: Exception) {} }.start()
            stopSelf()
        }
        wm.addView(mainView, mainParams)
    }

    private fun hideMain() {
        mainView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        mainView = null; mainParams = null
    }

    private fun toggleMain() {
        if (OverlayPrefs.isMainOverlayEnabled(this)) showMain() else hideMain()
    }

    private fun showDevfreq() {
        if (devfreqView != null) return
        devfreqView = InfoPanelView(this).apply { title = OverlayPrefs.getDevfreqTitle(this@OverlayService) }
        devfreqParams = makeParams(0, 600)
        devfreqView!!.onDragListener = { dx, dy -> moveView(devfreqView!!, devfreqParams!!, dx, dy) }
        devfreqView!!.onCloseListener = {
            hideDevfreq()
            OverlayPrefs.setDevfreqOverlayEnabled(this, false)
        }
        wm.addView(devfreqView, devfreqParams)
    }

    private fun hideDevfreq() {
        devfreqView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        devfreqView = null; devfreqParams = null
    }

    private fun toggleDevfreq() {
        if (OverlayPrefs.isDevfreqOverlayEnabled(this)) showDevfreq()
        else hideDevfreq()
    }

    private fun showBattery() {
        if (batteryView != null) return
        batteryView = InfoPanelView(this).apply { title = OverlayPrefs.getBatteryTitle(this@OverlayService) }
        batteryParams = makeParams(0, 800)
        batteryView!!.onDragListener = { dx, dy -> moveView(batteryView!!, batteryParams!!, dx, dy) }
        batteryView!!.onCloseListener = { hideBattery(); OverlayPrefs.setBatteryOverlayEnabled(this, false) }
        wm.addView(batteryView, batteryParams)
    }

    private fun hideBattery() {
        batteryView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        batteryView = null; batteryParams = null
    }

    private fun toggleBattery() {
        if (OverlayPrefs.isBatteryOverlayEnabled(this)) showBattery() else hideBattery()
    }

    private fun showHistory() {
        if (historyView != null) return
        historyView = HistoryOverlayView(this)
        historyParams = makeParams(0, 1000)
        historyView!!.onDragListener = { dx, dy -> moveView(historyView!!, historyParams!!, dx, dy) }
        historyView!!.onCloseListener = { hideHistory(); OverlayPrefs.setHistoryOverlayEnabled(this, false) }
        wm.addView(historyView, historyParams)
    }

    private fun hideHistory() {
        historyView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        historyView = null; historyParams = null
    }

    private fun toggleHistory() {
        if (OverlayPrefs.isHistoryOverlayEnabled(this)) showHistory() else hideHistory()
    }

    private fun makeParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y

    }

    private fun moveView(view: View, params: WindowManager.LayoutParams, dx: Float, dy: Float) {
        params.x += dx.toInt(); params.y += dy.toInt()
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private var lastWatchdogActionMs = 0L

    private fun checkHealthWatchdog() {
        val now = android.os.SystemClock.elapsedRealtime()
        val lastOk = ThermalReader.lastSuccessMs

        if (lastOk == 0L) return
        val elapsed = now - lastOk
        if (elapsed < TmConstants.WATCHDOG_STALE_MS) return

        if (now - lastWatchdogActionMs < TmConstants.WATCHDOG_COOLDOWN_MS) return
        lastWatchdogActionMs = now

        ThermalReader.forceReconnect(this)
    }

    @Volatile private var lastDragInvalidateMs = 0L
    @Volatile private var dragInvalidatePending = false

    private fun postMainInvalidate() {
        mainHandler.post {
            val v = mainView ?: return@post
            if (v.isDragging()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastDragInvalidateMs >= TmConstants.DRAG_INVALIDATE_MS) {
                    lastDragInvalidateMs = now
                    v.invalidate()
                } else if (!dragInvalidatePending) {
                    dragInvalidatePending = true
                    mainHandler.postDelayed({
                        dragInvalidatePending = false
                        lastDragInvalidateMs = android.os.SystemClock.uptimeMillis()
                        mainView?.let { if (it.isDragging()) it.invalidate() }
                    }, TmConstants.DRAG_INVALIDATE_MS)
                }
                return@post
            }
            v.invalidate()
        }
    }

    private fun computeRenderHash(): Long {
        var h = 0L
        h = h * 31 + ThermalReader.tempsHash()
        h = h * 37 + ThermalReader.freqsHash()
        for (zone in HASH_ZONES) {
            h = h * 41 + ThermalReader.getLoad(zone)
        }
        h = h * 43 + (BatteryMonitor.powerWatts * 100).toLong()
        h = h * 47 + (BatteryMonitor.batteryTemp * 10).toLong()
        return h
    }

    private fun startFastLoop() {

        val interval = TmConstants.FAST_INTERVAL_DEFAULT_MS.toLong()
        try { fastTask?.cancel(false) } catch (_: Exception) {}
        fastTask = pool.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate

            if (!BatteryMonitor.samsungDataLoaded && ThermalReader.isServiceBound()) {
                try { BatteryMonitor.loadSamsungData(ThermalReader.getCommandService()) }
                catch (_: Exception) {}
            }

            val svc = ThermalReader.getCommandService()
            if (svc != null && svc !== syncedServer && ThermalReader.isServiceBound()) {
                // servidor novo (primeira conexao ou recriado): mapa + config
                try { ThermalReader.syncHwInfo(this@OverlayService) } catch (_: Exception) {}
                try { ThermalReader.syncIntervals(this@OverlayService) } catch (_: Exception) {}
                syncedServer = svc
            }

            val connected = try { ThermalReader.updateFast() } catch (_: Throwable) { false }
            try { checkHealthWatchdog() } catch (_: Exception) {}

            val v = mainView
            val visible = v == null || v.isShown
            overlayVisible = visible
            if (!visible) return@scheduleAtFixedRate

            val hash = try { computeRenderHash() } catch (_: Throwable) { lastRenderHash + 1 }
            if (hash == lastRenderHash) {
                return@scheduleAtFixedRate
            }
            lastRenderHash = hash

            mainHandler.post {
                mainView?.apply { isConnected = connected; if (!isDragging()) invalidate() }
                historyView?.invalidate()
                devfreqView?.apply {
                    val cP = Color.parseColor("#BD93F9")
                    val items = mutableListOf<Triple<String, String, Int>>()
                    for (fId in DEVFREQ_IDS) {
                        if (!OverlayPrefs.isFreqVisible(this@OverlayService, fId)) continue
                        val lbl = OverlayPrefs.getFreqLabel(this@OverlayService, fId)
                        val mhz = ThermalReader.getFreq(fId)
                        val txt = when {
                            mhz == ThermalReader.FREQ_OFF -> "OFFLINE"
                            mhz > 0 -> "$mhz MHz"
                            else -> "--"
                        }
                        items.add(Triple(lbl, txt, cP))
                    }
                    setItemsIfChanged(items)
                }
                batteryView?.apply {
                    val items = mutableListOf<Triple<String, String, Int>>()
                    val ctx = this@OverlayService
                    val cG = Color.parseColor("#50FA7B"); val cY = Color.parseColor("#FFFF55")
                    val cO = Color.parseColor("#FFB86C")
                    val cR = Color.parseColor("#FF5555"); val cC = Color.parseColor("#8BE9FD")
                    val cW = Color.parseColor("#F8F8F2")

                    for (k in OverlayPrefs.DEFAULT_BATT_LABELS.keys) {
                        if (!OverlayPrefs.isBatteryVisible(ctx, k)) continue
                        val lbl = OverlayPrefs.getBattLabel(ctx, k)
                        when (k) {
                            "power" -> {
                                val pw = BatteryMonitor.powerWatts
                                val c = if (pw >= OverlayPrefs.getPowerThreshold(ctx))
                                    OverlayPrefs.getPowerAlertColor(ctx) else OverlayPrefs.getPowerNormalColor(ctx)
                                items.add(Triple(lbl, String.format("%.2fW", pw), c))
                            }
                            "batt_temp" -> {
                                val bt = BatteryMonitor.batteryTemp
                                val c = when {
                                    bt >= 50 -> cR
                                    bt >= 42 -> cO
                                    bt >= 38 -> cY
                                    else -> cG
                                }
                                items.add(Triple(lbl, String.format("%.1f°C", bt), c))
                            }
                            "cycles" -> items.add(Triple(lbl,
                                if (BatteryMonitor.cycles > 0) "${BatteryMonitor.cycles}" else "--", cW))
                            "max_temp" -> items.add(Triple(lbl,
                                if (BatteryMonitor.maxTempEver > 0f) String.format("%.1f°C", BatteryMonitor.maxTempEver) else "--", cY))
                            "first_use" -> items.add(Triple(lbl, BatteryMonitor.firstUseDate.ifEmpty { "--" }, cW))
                            "charge_limit" -> items.add(Triple(lbl,
                                if (BatteryMonitor.chargeLimit > 0) "${BatteryMonitor.chargeLimit}%" else "--", cC))
                        }
                    }
                    setItemsIfChanged(items)
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS)
    }

    private fun startLoadsLoop() {
        try { loadsTask?.cancel(false) } catch (_: Exception) {}
        loadsTask = pool.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate

            if (!overlayVisible) return@scheduleAtFixedRate
            try {
                if (ThermalReader.isConnected() && OverlayPrefs.isHistoryEnabled(this@OverlayService)) {
                    ThermalHistory.addSample { zoneId -> ThermalReader.getTemp(zoneId) }
                }
            } catch (_: Exception) {}
        }, 0, TmConstants.LOADS_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun startMetricLoops() = restartMetricLoop()

    private fun stopMetricLoops() {
        try { metricTask?.cancel(false) } catch (_: Exception) {}
        metricTask = null
    }

    private fun restartMetricLoop() {
        try { metricTask?.cancel(false) } catch (_: Exception) {}

        val iCpuFreq  = OverlayPrefs.getIntervalCpuFreq(this).toLong().coerceIn(100, 2000)
        val iGpuFreq  = OverlayPrefs.getIntervalGpuFreq(this).toLong().coerceIn(100, 2000)
        val iCpuLoads = OverlayPrefs.getIntervalCpuLoads(this).toLong().coerceIn(100, 2000)
        val iGpuLoad  = OverlayPrefs.getIntervalGpuLoad(this).toLong().coerceIn(100, 2000)
        val iGpuTemp  = OverlayPrefs.getIntervalGpuTemp(this).toLong().coerceIn(100, 5000)
        val iCpuTemp  = OverlayPrefs.getIntervalCpuTemp(this).toLong().coerceIn(100, 1000)
        val iDevfreq  = OverlayPrefs.getIntervalDevfreq(this).toLong().coerceIn(100, 5000)
        val iNpu      = OverlayPrefs.getIntervalNpu(this).toLong().coerceIn(100, 5000)
        val iRam      = OverlayPrefs.getIntervalRam(this).toLong().coerceIn(100, 2000)
        val iGpuMem   = OverlayPrefs.getIntervalGpuMem(this).toLong().coerceIn(100, 5000)

        val tick = minOf(iCpuFreq, iGpuFreq, iCpuLoads, iGpuLoad, iGpuTemp,
            iCpuTemp, iDevfreq, iNpu, iRam, iGpuMem).coerceIn(50L, 1000L)

        var dCpuFreq = 0L; var dGpuFreq = 0L; var dCpuLoads = 0L; var dGpuLoad = 0L
        var dGpuTemp = 0L; var dCpuTemp = 0L; var dDevfreq = 0L; var dNpu = 0L
        var dRam = 0L; var dGpuMem = 0L

        metricTask = pool.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate
            if (!overlayVisible) return@scheduleAtFixedRate
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                var mask = 0
                if (now >= dCpuFreq)  { mask = mask or CommandService.B_CPUFREQ;  dCpuFreq = now + iCpuFreq }
                if (now >= dGpuFreq)  { mask = mask or CommandService.B_GPUFREQ;  dGpuFreq = now + iGpuFreq }
                if (now >= dCpuLoads) { mask = mask or CommandService.B_CPULOADS; dCpuLoads = now + iCpuLoads }
                if (now >= dGpuLoad)  { mask = mask or CommandService.B_GPULOAD;  dGpuLoad = now + iGpuLoad }
                if (now >= dGpuTemp)  { mask = mask or CommandService.B_GPUTEMP;  dGpuTemp = now + iGpuTemp }
                if (now >= dCpuTemp)  { mask = mask or CommandService.B_TEMPS;    dCpuTemp = now + iCpuTemp }
                if (now >= dDevfreq)  { mask = mask or CommandService.B_DEVFREQS or CommandService.B_VOLTS; dDevfreq = now + iDevfreq }
                if (now >= dNpu)      { mask = mask or CommandService.B_NPU;      dNpu = now + iNpu }
                val wantGpuMem = now >= dGpuMem
                if (wantGpuMem) mask = mask or CommandService.B_GPUMEM

                if (mask != 0) ThermalReader.updateBulk(mask)

                // RAM e memória da GPU saem de /proc/meminfo: leitura local, sem binder
                if (now >= dRam)    { dRam = now + iRam;       ThermalReader.updateRamOnly() }
                if (wantGpuMem) { dGpuMem = now + iGpuMem; ThermalReader.updateGpuMemOnly() }

                var changed = false
                var h = ThermalReader.cpuFreqHash();  if (h != hashCpuFreq)  { hashCpuFreq = h;  changed = true }
                h = ThermalReader.gpuFreqHash();      if (h != hashGpuFreq)  { hashGpuFreq = h;  changed = true }
                h = ThermalReader.cpuLoadsHash();     if (h != hashCpuLoads) { hashCpuLoads = h; changed = true }
                h = ThermalReader.gpuLoadHash();      if (h != hashGpuLoad)  { hashGpuLoad = h;  changed = true }
                h = ThermalReader.gpuTempHash();      if (h != hashGpuTemp)  { hashGpuTemp = h;  changed = true }
                h = ThermalReader.tempsHash();        if (h != hashCpuTemp)  { hashCpuTemp = h;  changed = true }
                h = ThermalReader.voltsHash();        if (h != hashVolts)    { hashVolts = h;    changed = true }
                h = ThermalReader.npuFreqHash();      if (h != hashNpu)      { hashNpu = h;      changed = true }
                h = ThermalReader.ramHash();          if (h != hashRam)      { hashRam = h;      changed = true }
                h = ThermalReader.gpuMemHash();       if (h != hashGpuMem)   { hashGpuMem = h;   changed = true }
                if (changed) postMainInvalidate()
            } catch (_: Throwable) {
                // uma excecao nao tratada cancelaria a tarefa em definitivo
            }
        }, 0, tick, TimeUnit.MILLISECONDS)
    }

    private fun startPowerLoop() {
        val interval = OverlayPrefs.getPowerInterval(this).toLong()
        try { powerFuture?.cancel(false) } catch (_: Exception) {}
        powerFuture = pool.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate
            if (!overlayVisible) return@scheduleAtFixedRate
            // Estava fora de try: uma excecao aqui cancelava o loop de potencia
            // em definitivo, sem erro visivel (BAT/PD congelavam).
            try { BatteryMonitor.updateRealtime() } catch (_: Throwable) {}

            val bt = BatteryMonitor.batteryTemp
            mainView?.let { v ->
                if (bt > 0) {
                    v.cachedBatStr = String.format("%.1f°C", bt)
                    v.cachedBatColor = when {
                        bt >= 50f -> android.graphics.Color.parseColor("#FF5555")
                        bt >= 42f -> android.graphics.Color.parseColor("#FFB86C")
                        bt >= 38f -> android.graphics.Color.parseColor("#FFFF55")
                        else      -> android.graphics.Color.parseColor("#50FA7B")
                    }
                } else {
                    v.cachedBatStr = "--"
                    v.cachedBatColor = android.graphics.Color.parseColor("#6272A4")
                }
                v.cachedPdStr = String.format("%.2fW", BatteryMonitor.powerWatts)
            }
            mainHandler.post { batteryView?.invalidate() }
        }, 0, interval, TimeUnit.MILLISECONDS)
    }

    private fun restartPowerLoop() {
        powerFuture?.cancel(false)
        startPowerLoop()
    }

    private fun pauseAll() {
        if (paused) return
        paused = true
        try { fastTask?.cancel(false) } catch (_: Exception) {}
        try { loadsTask?.cancel(false) } catch (_: Exception) {}
        stopMetricLoops()
        try { powerFuture?.cancel(false) } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}

        try { unregisterReceiver(BatteryMonitor.batteryReceiver) } catch (_: Exception) {}

        try { KeepAliveJobService.cancel(this) } catch (_: Exception) {}

        // Por padrao a coleta no servidor CONTINUA com a tela apagada (o contador
        // de hotspot segue contando). Quem preferir bateria liga a pausa profunda.
        if (OverlayPrefs.isDeepPauseOnScreenOff(this)) {
            Thread { try { ThermalReader.stopDataCollection() } catch (_: Exception) {} }
                .apply { isDaemon = true; start() }
        }

        mainHandler.post {
            try { mainView?.visibility = android.view.View.GONE } catch (_: Exception) {}
            try { devfreqView?.visibility = android.view.View.GONE } catch (_: Exception) {}
            try { batteryView?.visibility = android.view.View.GONE } catch (_: Exception) {}
            try { historyView?.visibility = android.view.View.GONE } catch (_: Exception) {}
        }
    }

    private fun resumeAll() {
        if (!paused) return
        if (!running) return
        paused = false
        try { wakeLock?.let { if (!it.isHeld) it.acquire() } } catch (_: Exception) {}

        try {
            registerReceiver(BatteryMonitor.batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        try { KeepAliveJobService.schedule(this) } catch (_: Exception) {}
        syncedServer = null
        mainHandler.post {
            try { mainView?.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
            try { devfreqView?.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
            try { batteryView?.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
            try { historyView?.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
        }
        startFastLoop()
        startLoadsLoop()
        startMetricLoops()
        startPowerLoop()
    }

    override fun onDestroy() {
        running = false; isRunning = false

        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}

        try { unregisterReceiver(BatteryMonitor.batteryReceiver) } catch (_: Exception) {}

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
        getSharedPreferences("overlay_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        powerFuture?.cancel(false)

        try { fastTask?.cancel(false) } catch (_: Exception) {}
        try { loadsTask?.cancel(false) } catch (_: Exception) {}
        stopMetricLoops()
        try { powerFuture?.cancel(false) } catch (_: Exception) {}
        try { pool.shutdownNow() } catch (_: Exception) {}
        try { ThermalHistory.flush() } catch (_: Exception) {}

        if (!UserIntent.isRunning(this)) {
            try { KeepAliveJobService.cancel(this) } catch (_: Exception) {}
        }

        Thread {
            try { ThermalReader.unbindService() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        hideMain(); hideDevfreq(); hideBattery(); hideHistory()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Thermal Monitor", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Monitor térmico"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra("action", "stop")
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thermal Monitor").setContentText(
                when (ThermalReader.activeBackend) {
                    AccessManager.Backend.ROOT -> "Monitorando · root"
                    AccessManager.Backend.SHIZUKU -> "Monitorando · ADB (Shizuku)"
                    else -> "Monitorando"
                })
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
