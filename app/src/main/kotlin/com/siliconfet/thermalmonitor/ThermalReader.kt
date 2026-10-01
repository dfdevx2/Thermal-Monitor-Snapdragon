package com.siliconfet.thermalmonitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.siliconfet.thermalmonitor.TmConstants.BIND_DEATH_REBIND_DELAY_MS
import com.siliconfet.thermalmonitor.TmConstants.BIND_KILL_ZOMBIE_GRACE_MS
import com.siliconfet.thermalmonitor.TmConstants.BIND_MAX_ATTEMPTS
import com.siliconfet.thermalmonitor.TmConstants.BIND_RETRY_AFTER_REJECT_MS
import com.siliconfet.thermalmonitor.TmConstants.BIND_RETRY_BACKOFF_MS
import com.siliconfet.thermalmonitor.TmConstants.BIND_TIMEOUT_MS
import com.siliconfet.thermalmonitor.TmConstants.EMPTY_SNAPSHOT_LIMIT
import com.siliconfet.thermalmonitor.TmConstants.NUKE_TIMEOUT_MS
import com.siliconfet.thermalmonitor.TmConstants.UPDATE_FAIL_THRESHOLD
import com.siliconfet.thermalmonitor.TmConstants.USER_SERVICE_VERSION
import com.siliconfet.thermalmonitor.TmConstants.logBind
import com.siliconfet.thermalmonitor.TmConstants.logHealth
import java.util.concurrent.atomic.AtomicBoolean
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku

object ThermalReader {

    const val FREQ_OFF = TmConstants.FREQ_OFF
    /** Acima disso a ultima amostra do servidor nao conta mais como dado vivo. */
    private const val SERVER_STALE_MS = 15_000L
    val ALL_ZONE_IDS = listOf(
        "CPUCL3", "CPUCL2", "CPUCL1", "CPUCL0",
        "DSU", "MIF", "G3D", "NPU0", "NPU1", "ISP"
    )

    private val currentTemps = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val currentFreqs = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val currentCpuLoads = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val currentDevfreqLoads = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val currentVolts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile var ramUsedMb: Int = 0
    @Volatile var ramTotalMb: Int = 0
    @Volatile var swapFreeMb: Int = 0
    @Volatile var gpuMemUsedMb: Int = 0
    @Volatile var swapTotalMb: Int = 0

    enum class Health { OK, PARTIAL, OFFLINE }

    @Volatile var rootHealthy: Boolean = false
        private set
    @Volatile var lastSuccessMs: Long = 0L
        private set

    /** Idade da ultima amostra de temperatura NO SERVIDOR, em ms (-1 = nenhuma). */
    @Volatile var serverTempAgeMs: Long = -1L
        private set
    @Volatile var serverZones: Int = 0
        private set
    @Volatile var serverRails: Int = 0
        private set
    @Volatile var serverNpuNodes: Int = -1
        private set

    /** Por onde a coleta esta rodando agora (root ou ADB/Shizuku). */
    @Volatile var activeBackend: AccessManager.Backend = AccessManager.Backend.NONE
        private set

    /** Resumo do mapeamento de hardware feito pelo servidor (JSON), ou null. */
    @Volatile var hwInfoJson: String? = null
        private set

    private var emptySnapshotStreak = 0

    @Volatile private var appContext: Context? = null


    @Volatile private var lastBindTimeMs: Long = 0L

    private val bindInProgress = AtomicBoolean(false)

    @Volatile private var bindToken = 0L
    @Volatile private var lastHealth: Health = Health.OFFLINE
    @Volatile private var commandService: ICommandService? = null
    @Volatile private var serviceBound = false
    @Volatile private var streamStarted = false
    @Volatile private var boundBinder: IBinder? = null
    @Volatile private var lastUpdateOk = false
    private var bindAttempts = 0
    private var listenersRegistered = false
    private var consecutiveFailures = 0
    private var reconnectAttempts = 0

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            BuildConfig.APPLICATION_ID,
            CommandService::class.java.name
        )
    ).daemon(false).processNameSuffix("cmd").debuggable(BuildConfig.DEBUG).version(USER_SERVICE_VERSION)

    private val retryHandler = Handler(Looper.getMainLooper())

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {

            retryHandler.post {
                logBind { "binderDied: scheduling rebind in ${BIND_DEATH_REBIND_DELAY_MS}ms" }
                clearBoundState()
                try { boundBinder?.unlinkToDeath(this, 0) } catch (_: Exception) {}
                boundBinder = null
                retryHandler.postDelayed({
                    if (!serviceBound) {
                        bindAttempts = 0
                        attemptBind()
                    }
                }, BIND_DEATH_REBIND_DELAY_MS)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val ping = try { binder?.pingBinder() ?: false } catch (_: Exception) { false }
            logBind { "onServiceConnected: binder=${binder != null} ping=$ping" }
            if (binder != null && ping) {
                try {
                    binder.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    logBind { "linkToDeath EX ${e.javaClass.simpleName}" }
                }
                boundBinder = binder
                commandService = ICommandService.Stub.asInterface(binder)
                serviceBound = true
                bindAttempts = 0
                bindInProgress.set(false)

                lastBindTimeMs = SystemClock.elapsedRealtime()
                emptySnapshotStreak = 0
                logBind { "onServiceConnected: SUCCESS" }
            } else {

                logBind { "onServiceConnected: REJECTED (binder dead), will forceUnbind+retry" }
                bindInProgress.set(false)
                retryHandler.post {
                    forceUnbind()
                    scheduleRetry(BIND_RETRY_AFTER_REJECT_MS)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logBind { "onServiceDisconnected" }
            try { boundBinder?.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
            boundBinder = null
            clearBoundState()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logBind { "binderReceivedListener fired: bound=$serviceBound" }
        retryHandler.post {
            if (!serviceBound) {

                if (!bindInProgress.get()) {
                    bindAttempts = 0
                }
                attemptBind()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logBind { "binderDeadListener fired: clearing state" }
        clearBoundState()
    }

    fun bindService(ctx: Context? = null) {
        if (ctx != null) appContext = ctx.applicationContext
        registerShizukuListeners()
        forceUnbind()
        bindAttempts = 0
        attemptBind()
    }

    fun unbindService() {
        try { if (serviceBound) commandService?.stopThermalStream() } catch (_: Exception) {}
        unregisterShizukuListeners()
        forceUnbind()
    }

    fun stopDataCollection() {
        try { if (serviceBound) commandService?.stopThermalStream() } catch (_: Exception) {}
        streamStarted = false
    }

    private fun registerShizukuListeners() {
        if (listenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterShizukuListeners() {
        if (!listenersRegistered) return
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Exception) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Exception) {}
        listenersRegistered = false
    }

    private fun clearBoundState() {
        commandService = null
        serviceBound = false
        streamStarted = false
        lastUpdateOk = false
        serverTempAgeMs = -1L
        serverZones = 0
        serverRails = 0
        serverNpuNodes = -1

        // O servidor morreu: o que esta em cache nao vale mais. Sem isso os
        // valores antigos continuavam na tela como se fossem leitura viva.
        currentTemps.clear(); currentFreqs.clear()
        currentCpuLoads.clear(); currentDevfreqLoads.clear()
        currentVolts.clear()

        lastBindTimeMs = 0L
    }

    private fun forceUnbind() {
        logBind { "forceUnbind: bound=$serviceBound svc=${commandService != null} backend=$activeBackend" }
        try { boundBinder?.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
        boundBinder = null
        when (activeBackend) {
            AccessManager.Backend.SHIZUKU -> try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Exception) {}
            AccessManager.Backend.ROOT -> {
                // RootService exige a main thread
                val r = Runnable { try { RootService.unbind(serviceConnection) } catch (_: Exception) {} }
                if (Looper.myLooper() == Looper.getMainLooper()) r.run() else retryHandler.post(r)
            }
            AccessManager.Backend.NONE -> {}
        }
        clearBoundState()
    }

    private fun attemptBind() {
        if (!bindInProgress.compareAndSet(false, true)) {
            logBind { "attemptBind: SKIP (already in progress)" }
            return
        }

        val myToken = ++bindToken
        logBind { "attemptBind: ENTER token=$myToken (bindAttempts=$bindAttempts)" }

        if (appContext == null) {
            bindInProgress.set(false)
            return
        }

        Thread { doBindOffThread(myToken) }.apply {
            name = "tm-bind"
            isDaemon = true
            start()
        }
    }

    private fun doBindOffThread(myToken: Long) {
        val ctx = appContext
        // Decide o backend fora da main thread: pode abrir o prompt do su.
        val backend = if (ctx != null) AccessManager.resolveBlocking(ctx) else AccessManager.Backend.NONE
        if (backend == AccessManager.Backend.NONE) {
            logBind { "attemptBind: no backend (sem root e sem Shizuku autorizado)" }
            bindInProgress.set(false)
            retryHandler.post { scheduleRetry(BIND_RETRY_BACKOFF_MS) }
            return
        }
        if (backend == AccessManager.Backend.SHIZUKU) {
            val tStart = SystemClock.elapsedRealtime()
            try {
                val killed = ShizukuShell.killZombiesInline()
                logBind { "killZombiesInline: result=$killed elapsed=${SystemClock.elapsedRealtime() - tStart}ms" }
            } catch (e: Exception) {
                logBind { "killZombiesInline: EX ${e.javaClass.simpleName}" }
            }
            try { Thread.sleep(BIND_KILL_ZOMBIE_GRACE_MS) } catch (_: Exception) {}
        }

        retryHandler.post { doBindOnHandler(myToken, backend) }
    }

    private fun doBindOnHandler(myToken: Long, backend: AccessManager.Backend) {

        if (myToken != bindToken) {
            logBind { "doBindOnHandler: stale token $myToken (current=$bindToken), abort" }
            bindInProgress.set(false)
            return
        }
        val ctx = appContext
        if (ctx == null) { bindInProgress.set(false); return }
        scheduleBindTimeout(myToken)

        try {
            activeBackend = backend
            if (backend == AccessManager.Backend.ROOT) {
                RootService.bind(AccessManager.rootIntent(ctx), serviceConnection)
            } else {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
            logBind { "bind($backend): invoked token=$myToken" }
        } catch (e: Exception) {
            logBind { "bindUserService: EX ${e.javaClass.simpleName} msg=${e.message}" }

            bindInProgress.set(false)
            forceUnbind()
            scheduleRetry(BIND_RETRY_AFTER_REJECT_MS)
        }
    }

    private fun scheduleBindTimeout(myToken: Long) {
        retryHandler.postDelayed({

            if (myToken != bindToken) return@postDelayed
            if (bindInProgress.get() && !serviceBound) {
                logBind { "bindUserService: TIMEOUT token=$myToken" }
                bindInProgress.set(false)

                forceUnbind()
                scheduleRetry(BIND_RETRY_AFTER_REJECT_MS)
            }
        }, BIND_TIMEOUT_MS)
    }

    private fun scheduleRetry(delayMs: Long) {
        if (bindAttempts < BIND_MAX_ATTEMPTS) {
            bindAttempts++
            retryHandler.postDelayed({ attemptBind() }, delayMs)
        }
    }

    fun forceReconnect(ctx: Context? = null) {
        if (bindInProgress.get()) {
            logBind { "forceReconnect: SKIP (bind already in progress)" }
            return
        }
        reconnectAttempts++
        when {
            reconnectAttempts <= 2 -> doSimpleReconnect()
            reconnectAttempts == 3 -> doInlineKillReconnect(ctx)
            else -> doScriptKillReconnect(ctx)
        }
    }

    private fun doSimpleReconnect() {
        forceUnbind()
        bindAttempts = 0
        retryHandler.post { attemptBind() }
    }

    private fun doInlineKillReconnect(ctx: Context?) {
        if (activeBackend != AccessManager.Backend.SHIZUKU) { doSimpleReconnect(); return }
        Thread {

            val killed = try { ShizukuShell.killZombiesInline() } catch (_: Exception) { false }
            if (!killed) {
                logBind { "doInlineKillReconnect: kill failed, escalating to script now" }
                reconnectAttempts = 4
                doScriptKillReconnect(ctx)
                return@Thread
            }
            try { Thread.sleep(500) } catch (_: Exception) {}
            retryHandler.post {
                forceUnbind()
                bindAttempts = 0
                attemptBind()
            }
        }.apply { name = "tm-reconnect-inline"; isDaemon = true; start() }
    }

    private fun doScriptKillReconnect(ctx: Context?) {
        if (activeBackend != AccessManager.Backend.SHIZUKU) { doSimpleReconnect(); return }
        Thread {
            try { ShizukuShell.killZombiesInline() } catch (_: Exception) {}
            try { Thread.sleep(300) } catch (_: Exception) {}
            if (ctx != null) try { ShizukuShell.runKillScript(ctx) } catch (_: Exception) {}
            try { Thread.sleep(500) } catch (_: Exception) {}
            retryHandler.post {
                forceUnbind()
                bindAttempts = 0
                attemptBind()
            }
        }.apply { name = "tm-reconnect-script"; isDaemon = true; start() }
    }

    fun isServiceBound(): Boolean = serviceBound && commandService != null
    fun isConnected(): Boolean = lastUpdateOk && serviceBound
    fun getCommandService(): ICommandService? = commandService

    fun syncIntervals(ctx: android.content.Context) {
        try {
            commandService?.exec("config:cpu_freq_interval:${OverlayPrefs.getIntervalCpuFreq(ctx)}")
            commandService?.exec("config:gpu_freq_interval:${OverlayPrefs.getIntervalGpuFreq(ctx)}")
            commandService?.exec("config:cpu_loads_interval:${OverlayPrefs.getIntervalCpuLoads(ctx)}")
            commandService?.exec("config:gpu_load_interval:${OverlayPrefs.getIntervalGpuLoad(ctx)}")
            commandService?.exec("config:gpu_temp_interval:${OverlayPrefs.getIntervalGpuTemp(ctx)}")
            commandService?.exec("config:cpu_temp_interval:${OverlayPrefs.getIntervalCpuTemp(ctx)}")
            commandService?.exec("config:hot_threshold:${OverlayPrefs.getHotspotThreshold(ctx)}")
            commandService?.exec("config:npu_interval:${OverlayPrefs.getIntervalNpu(ctx)}")
            commandService?.exec("config:devfreq_interval:${OverlayPrefs.getIntervalDevfreq(ctx)}")
            commandService?.exec("config:gpu_mem_interval:${OverlayPrefs.getIntervalGpuMem(ctx)}")
            syncVisibility(ctx)
        } catch (_: Exception) {}
    }

    /**
     * Diz ao servidor o que esta visivel, para ele nao amostrar o resto.
     * Cada leitura de sysfs custa de 100 a 300 us; ler o que ninguem ve era o
     * maior item do ciclo.
     */
    fun syncVisibility(ctx: android.content.Context) {
        try {
            val zones = OverlayPrefs.allDrawableZones()
                .filter { OverlayPrefs.isZoneVisible(ctx, it) }
                .joinToString(",")
            val freqs = OverlayPrefs.KNOWN_FREQ_IDS.keys
                .filter { OverlayPrefs.isFreqVisible(ctx, it) }
                .joinToString(",")
            val volts = OverlayPrefs.VOLT_ZONES
                .filter { OverlayPrefs.isVoltVisible(ctx, it) }
                .joinToString(",")
            commandService?.exec("config:zones:$zones")
            commandService?.exec("config:freqs:$freqs")
            commandService?.exec("config:volts:$volts")
        } catch (_: Exception) {}
    }

    /** Pede ao servidor o mapa de hardware (uma vez por conexao) e aplica o perfil. */
    fun syncHwInfo(ctx: Context, reprobe: Boolean = false): String? {
        val svc = commandService ?: return null
        val info = try { svc.exec(if (reprobe) "get:reprobe" else "get:hwinfo") } catch (_: Exception) { null }
        if (info.isNullOrBlank()) return null
        hwInfoJson = info
        try { HwProfile.onHwInfo(ctx, info, force = reprobe) } catch (_: Exception) {}
        return info
    }

    fun startStream(): Boolean {
        if (!isServiceBound()) return false
        return try {
            commandService?.startThermalStream()
            streamStarted = true
            true
        } catch (_: Exception) {
            clearBoundState()
            false
        }
    }

    fun updateFast(): Boolean {
        if (!isServiceBound()) {
            lastUpdateOk = false
            attemptBind()
            return false
        }
        if (!streamStarted) {
            if (!startStream()) {
                lastUpdateOk = false
                registerFailure()
                return false
            }
            return false
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (lastSuccessMs == 0L && lastBindTimeMs > 0L &&
            (nowMs - lastBindTimeMs) > NUKE_TIMEOUT_MS) {
            logHealth { "L4 NUKE: ${nowMs - lastBindTimeMs}ms bound w/o data, destroying server" }
            destroyServerAndRebind()
            return false
        }

        val snapshot = try {
            commandService?.healthStatus
        } catch (e: java.lang.LinkageError) {
            logHealth { "L3 AIDL incompat (fast): ${e.javaClass.simpleName}, destroying server" }
            destroyServerAndRebind()
            return false
        } catch (e: java.lang.NoSuchMethodError) {
            logHealth { "L3 AIDL incompat (fast): NoSuchMethodError, destroying server" }
            destroyServerAndRebind()
            return false
        } catch (e: Exception) {
            clearBoundState()
            registerFailure()
            return false
        }

        if (snapshot.isNullOrBlank()) {
            return false
        }
        parseHealthBlock(snapshot)

        // Antes: `currentTemps.isNotEmpty() || currentCpuLoads.isNotEmpty()`.
        // Esses mapas do CLIENTE nunca eram limpos, entao depois da primeira
        // leitura hasData era true para sempre e o watchdog L2 nunca disparava.
        // Agora a fonte da verdade e a idade da ultima amostra NO SERVIDOR.
        val age = serverTempAgeMs
        val cached = currentTemps.isNotEmpty() || currentCpuLoads.isNotEmpty()
        // So exigimos amostra fresca de temperatura quando o servidor TEM de
        // onde ler temperatura (no ADB alguns fabricantes fecham o sysfs termico
        // e nao ha HAL). Caso contrario o watchdog L2 destruiria o servidor em loop.
        val tempsExpected = serverZones > 0
        val hasData = cached && (!tempsExpected || age in 0 until SERVER_STALE_MS)
        if (!hasData) {
            emptySnapshotStreak++
            if (emptySnapshotStreak >= EMPTY_SNAPSHOT_LIMIT) {
                logHealth { "L2 empty snapshots ${emptySnapshotStreak}x, destroying server" }
                emptySnapshotStreak = 0
                destroyServerAndRebind()
                return false
            }
        } else {
            emptySnapshotStreak = 0
        }

        val newHealth = computeHealth()
        if (newHealth != lastHealth) {
            logHealth {
                "transition $lastHealth -> $newHealth | bound=$serviceBound svc=${commandService != null} " +
                    "temps=${currentTemps.size} loads=${currentCpuLoads.size} " +
                    "root=$rootHealthy"
            }
            lastHealth = newHealth
        }

        lastUpdateOk = hasData
        if (lastUpdateOk) {
            consecutiveFailures = 0
            reconnectAttempts = 0
            lastSuccessMs = nowMs

        }
        return lastUpdateOk
    }

    fun resetHotspotEdgeState() {
        lastServerHotCount = 0
    }



    fun updateRamOnly(): Boolean {
        updateRamInfo()
        return true
    }

    private fun kvMb(txt: String, key: String): Long {
        val i = txt.indexOf(key)
        if (i < 0) return -1L
        var j = i + key.length
        while (j < txt.length && (txt[j] == ' ' || txt[j] == '	')) j++
        var v = 0L; var any = false
        while (j < txt.length && txt[j] in '0'..'9') { v = v * 10 + (txt[j] - '0'); any = true; j++ }
        return if (any) v / 1024 else -1L
    }

    private fun readMeminfo(): String? =
        try { java.io.File("/proc/meminfo").readText() } catch (_: Exception) { null }

    private fun updateRamInfo() {
        val txt = readMeminfo() ?: return
        val total = kvMb(txt, "MemTotal:")
        if (total <= 0) return
        val available = kvMb(txt, "MemAvailable:")
        val swapTotal = kvMb(txt, "SwapTotal:")
        val swapFree = kvMb(txt, "SwapFree:")
        ramTotalMb = total.toInt()
        ramUsedMb = (total - available).toInt().coerceAtLeast(0)
        swapTotalMb = swapTotal.toInt().coerceAtLeast(0)
        swapFreeMb = swapFree.toInt().coerceAtLeast(0)
    }

    /**
     * `GpuTotal` so existe no /proc/meminfo de kernels Mali/Samsung. No Adreno a
     * memoria vem do servidor (kgsl page_alloc, secao #12 do bulk).
     */
    @Volatile private var gpuMemFromServer = false

    fun updateGpuMemOnly(): Boolean {
        if (gpuMemFromServer) return true
        val txt = readMeminfo() ?: return false
        val g = kvMb(txt, "GpuTotal:")
        if (g > 0) gpuMemUsedMb = g.toInt()
        return true
    }

    fun gpuMemHash(): Long = gpuMemUsedMb.toLong()

    private fun destroyServerAndRebind() {
        try { commandService?.destroy() } catch (_: Exception) {}
        clearBoundState()
        retryHandler.postDelayed({
            forceUnbind()
            bindAttempts = 0
            attemptBind()
        }, 500)
    }










    @Volatile private var lastServerHotCount = 0


    private fun applyServerHotCount(serverCount: Int) {
        val ctx = appContext ?: return
        if (serverCount < lastServerHotCount) lastServerHotCount = 0
        if (serverCount > lastServerHotCount) {
            val delta = serverCount - lastServerHotCount
            lastServerHotCount = serverCount
            OverlayPrefs.setHotspot95Count(ctx, OverlayPrefs.getHotspot95Count(ctx) + delta)
        }
    }

    /**
     * Busca numa unica chamada tudo que venceu no tick.
     *
     * Substitui os getters por metrica. As secoes vem marcadas com `#N` e cada
     * uma alimenta o mapa correspondente; um `-1` em frequencia significa
     * "nao lido" e remove a chave, enquanto FREQ_OFF significa domi­nio desligado.
     */
    fun updateBulk(mask: Int): Boolean {
        if (mask == 0) return true
        if (!isServiceBound()) return false
        val snap = try {
            commandService?.exec(CommandService.BULK_PREFIX + mask) ?: return false
        } catch (_: Exception) { return false }
        if (snap.isEmpty()) return false

        var target: java.util.concurrent.ConcurrentHashMap<String, Int>? = null
        var isFreq = false
        var isHot = false
        var isGpuMem = false
        // Secao de temperaturas completa (#7): o que nao veio foi ocultado ou
        // perdeu a fonte, e nao pode ficar congelado na tela.
        var tempsSeen: HashSet<String>? = null
        for (line in snap.lineSequence()) {
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                isHot = false; isFreq = false; isGpuMem = false
                target = when (line) {
                    "#1", "#2", "#9", "#11" -> { isFreq = true; currentFreqs }
                    "#3" -> currentCpuLoads
                    "#5" -> currentDevfreqLoads
                    "#6" -> currentTemps
                    "#7" -> { tempsSeen = HashSet(); currentTemps }
                    "#8" -> { isHot = true; null }
                    "#10" -> currentVolts
                    "#12" -> { isGpuMem = true; null }
                    else -> null
                }
                continue
            }
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val v = line.substring(sp + 1).toIntOrNull() ?: continue
            if (isHot) { applyServerHotCount(v); continue }
            if (isGpuMem) {
                if (v >= 0) { gpuMemUsedMb = v; gpuMemFromServer = true }
                continue
            }
            if (target === currentTemps) tempsSeen?.add(line.substring(0, sp))
            val t = target ?: continue
            val k = line.substring(0, sp)
            if (isFreq && v == -1) t.remove(k) else t[k] = v
        }
        tempsSeen?.let { seen -> currentTemps.keys.retainAll(seen) }
        return true
    }


    fun npuFreqHash(): Long {
        var h = 0L
        for (k in NPU_KEYS) h = h * 31 + (currentFreqs[k] ?: Int.MIN_VALUE)
        return h
    }

    private val NPU_KEYS = arrayOf("npu0", "npu1", "npucon")



    fun getVoltUv(zoneId: String): Int = currentVolts[zoneId] ?: -1

    fun voltsHash(): Long {
        var h = 0L
        for (e in currentVolts.entries) h += e.key.hashCode().toLong() * 1000003L + e.value
        return h
    }

    private fun parseHealthBlock(block: String) {
        if (block.isBlank()) return
        for (line in block.lineSequence()) {

            val parts = line.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isEmpty()) continue
            when (parts[0]) {
                "root" -> rootHealthy = parts[1] == "true"
                "temp_age_ms" -> serverTempAgeMs = parts[1].trim().toLongOrNull() ?: -1L
                "zones" -> serverZones = parts[1].trim().toIntOrNull() ?: 0
                "rails" -> serverRails = parts[1].trim().toIntOrNull() ?: 0
                "npu_nodes" -> serverNpuNodes = parts[1].trim().toIntOrNull() ?: -1
            }
        }
    }

    private fun computeHealth(): Health = when {
        !serviceBound || commandService == null -> Health.OFFLINE
        currentTemps.isEmpty() && currentCpuLoads.isEmpty() -> Health.OFFLINE
        rootHealthy -> Health.OK
        else -> Health.PARTIAL
    }

    private fun registerFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= UPDATE_FAIL_THRESHOLD) {
            consecutiveFailures = 0
            forceReconnect(null)
        }
    }

    fun getFreq(key: String): Int = currentFreqs[key] ?: 0

    fun cpuFreqHash(): Long {
        var h = 0L
        for (k in arrayOf("cpucl0", "cpucl1", "cpucl2", "cpucl3")) h = h * 31 + (currentFreqs[k] ?: 0)
        return h
    }
    fun gpuFreqHash(): Long = (currentFreqs["gpu"] ?: 0).toLong()
    fun cpuLoadsHash(): Long {
        var h = 0L
        for (k in arrayOf("cpucl0", "cpucl1", "cpucl2", "cpucl3")) h = h * 31 + (currentCpuLoads[k] ?: 0)
        return h
    }
    fun gpuLoadHash(): Long {
        var h = 0L
        for ((k, v) in currentDevfreqLoads) if (k == "g3d") h = h * 31 + v
        return h
    }
    fun gpuTempHash(): Long = (currentTemps["G3D"] ?: 0).toLong()
    fun ramHash(): Long = ramUsedMb.toLong() * 100000L + (swapTotalMb - swapFreeMb).toLong()
    fun getLoad(zoneId: String): Int = when (zoneId) {
        "CPUCL0" -> currentCpuLoads["cpucl0"] ?: -1
        "CPUCL1" -> currentCpuLoads["cpucl1"] ?: -1
        "CPUCL2" -> currentCpuLoads["cpucl2"] ?: -1
        "CPUCL3" -> currentCpuLoads["cpucl3"] ?: -1
        "G3D"    -> currentDevfreqLoads["g3d"] ?: -1
        else     -> -1
    }

    fun tempsHash(): Long {
        var h = 0L
        for (e in currentTemps.entries) h += e.key.hashCode().toLong() * 1000003L + e.value
        return h
    }

    fun freqsHash(): Long {
        var h = 0L
        for (e in currentFreqs.entries) h += e.key.hashCode().toLong() * 1000033L + e.value
        return h
    }

    fun getTemp(zoneId: String): Int = currentTemps[zoneId] ?: 0
}
