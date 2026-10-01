package com.siliconfet.thermalmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.BatteryManager

/**
 * Bateria. A parte em tempo real (potencia, temperatura, carregando) vem do
 * Android e funciona em qualquer aparelho. Os extras (ciclos, temperatura
 * maxima, primeiro uso, limite de carga) no original vinham de campos do
 * `dumpsys battery` que so existem na Samsung; aqui eles continuam sendo lidos
 * quando existem, com alternativas genericas:
 *  - ciclos: EXTRA_CYCLE_COUNT (Android 14+) ou power_supply/battery/cycle_count
 *  - temperatura maxima: registrada pelo proprio app
 */
object BatteryMonitor {

    var cycles: Int = 0
    var maxTempEver: Float = 0f
    var firstUseDate: String = ""
    var chargeLimit: Int = 0
    /** Nome mantido do original: "extras ja carregados". */
    var samsungDataLoaded = false

    @Volatile var powerWatts: Float = 0f
    @Volatile var batteryTemp: Float = 0f
    @Volatile var isCharging: Boolean = false
    @Volatile var currentMa: Float = 0f
    @Volatile var voltageMv: Float = 0f

    private var batteryManager: BatteryManager? = null
    private var prefs: SharedPreferences? = null
    private var recordedMax = 0f
    @Volatile private var currentIsMicro = false

    private const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"

    val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            batteryTemp = tempRaw / 10f
            val voltRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            // alguns kernels reportam em uV em vez de mV
            voltageMv = if (voltRaw > 100_000) voltRaw / 1000f else voltRaw.toFloat()

            val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL

            val cc = intent.getIntExtra(EXTRA_CYCLE_COUNT, -1)
            if (cc > 0 && cycles <= 0) cycles = cc

            if (batteryTemp in 1f..99f && batteryTemp > recordedMax) {
                recordedMax = batteryTemp
                if (batteryTemp > maxTempEver) maxTempEver = batteryTemp
                try { prefs?.edit()?.putFloat("max_temp", recordedMax)?.apply() } catch (_: Exception) {}
            }

            powerWatts = (currentMa * voltageMv) / 1_000_000f
        }
    }

    fun init(context: Context) {
        batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (prefs == null) {
            prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
            recordedMax = prefs?.getFloat("max_temp", 0f) ?: 0f
            if (recordedMax > maxTempEver) maxTempEver = recordedMax
        }
    }

    fun loadSamsungData(commandService: ICommandService?) {
        if (samsungDataLoaded || commandService == null) return
        try {
            val output = commandService.exec("dumpsys battery") ?: return
            for (line in output.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("mSavedBatteryUsage:") -> {
                        val raw = trimmed.substringAfter("[").substringBefore("]")
                        cycles = ((raw.toIntOrNull() ?: 0) + 50) / 100
                    }
                    trimmed.startsWith("mSavedBatteryMaxTemp:") -> {
                        val raw = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                        maxTempEver = maxOf(maxTempEver, raw / 10f)
                    }
                    trimmed.contains("FirstUseDate:") -> {
                        val raw = trimmed.substringAfter("[").substringBefore("]").trim()
                        if (raw.length == 8) {
                            firstUseDate = "${raw.substring(0, 4)}/${raw.substring(4, 6)}/${raw.substring(6, 8)}"
                        }
                    }
                    trimmed.startsWith("mProtectionThreshold:") -> {
                        chargeLimit = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
            }
            if (cycles <= 0) {
                // shell e root leem power_supply (sysfs_batteryinfo)
                val cc = commandService.exec(
                    "cat /sys/class/power_supply/battery/cycle_count 2>/dev/null || " +
                        "cat /sys/class/power_supply/bms/cycle_count 2>/dev/null"
                )?.trim()?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
                if (cc > 0) cycles = cc
            }
            samsungDataLoaded = true
        } catch (_: Exception) {}
    }

    fun updateRealtime() {
        val bm = batteryManager ?: return
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE) return
        val abs = Math.abs(raw.toLong())
        // O padrao e uA, mas alguns kernels reportam em mA. Um valor acima de
        // 50000 so existe em uA (seriam 50 A); visto uma vez, a unidade fica
        // decidida. Antes disso, numeros pequenos sao tratados como mA.
        if (abs > 50_000) currentIsMicro = true
        currentMa = if (currentIsMicro || abs >= 20_000) abs / 1000f else abs.toFloat()

        powerWatts = (currentMa * voltageMv) / 1_000_000f
    }
}
