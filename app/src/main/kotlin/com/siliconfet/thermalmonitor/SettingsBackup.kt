package com.siliconfet.thermalmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object SettingsBackup {

    private const val PREFS = "overlay_prefs"
    private const val FORMAT = "siliconfet.thermalmonitor.settings"
    const val VERSION = 1

    sealed class ImportResult {
        data class Ok(val applied: Int) : ImportResult()
        data class Error(val reason: String) : ImportResult()
    }

    fun exportToJson(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefs = JSONObject()
        for ((key, value) in sp.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int     -> { entry.put("t", "i"); entry.put("v", value) }
                is Long    -> { entry.put("t", "l"); entry.put("v", value) }
                is Float   -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is String  -> { entry.put("t", "s"); entry.put("v", value) }
                is Set<*>  -> {
                    entry.put("t", "ss")
                    val arr = JSONArray()
                    for (item in value) if (item is String) arr.put(item)
                    entry.put("v", arr)
                }
                else -> continue
            }
            prefs.put(key, entry)
        }
        return JSONObject().apply {
            put("_format", FORMAT)
            put("_version", VERSION)
            put("_generated_at", System.currentTimeMillis())
            put("prefs", prefs)
        }.toString(2)
    }

    fun importFromJson(ctx: Context, json: String): ImportResult {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return ImportResult.Error("Arquivo não é um JSON válido")
        }

        if (root.optString("_format") != FORMAT) {
            return ImportResult.Error("Arquivo não é um backup do Thermal Monitor")
        }
        if (root.optInt("_version", -1) > VERSION) {
            return ImportResult.Error("Backup de uma versão mais nova do app")
        }

        val prefs = root.optJSONObject("prefs")
            ?: return ImportResult.Error("Backup sem seção de configurações")

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = sp.edit()
        editor.clear()

        var applied = 0
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = prefs.optJSONObject(key) ?: continue
            try {
                when (entry.optString("t")) {
                    "b"  -> editor.putBoolean(key, entry.getBoolean("v"))
                    "i"  -> editor.putInt(key, entry.getInt("v"))
                    "l"  -> editor.putLong(key, entry.getLong("v"))
                    "f"  -> editor.putFloat(key, entry.getDouble("v").toFloat())
                    "s"  -> editor.putString(key, entry.getString("v"))
                    "ss" -> {
                        val arr = entry.getJSONArray("v")
                        val set = LinkedHashSet<String>(arr.length())
                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> continue
                }
                applied++
            } catch (_: Exception) {

            }
        }

        return if (editor.commit()) ImportResult.Ok(applied)
        else ImportResult.Error("Falha ao gravar as configurações")
    }

    /** Nome do preset embutido no APK. */
    private const val DEFAULTS_ASSET = "default_settings.json"

    /**
     * Aplica a configuração padrão que vem no APK (assets/default_settings.json).
     *
     * É o mesmo caminho do import: limpa as preferências e grava as do arquivo.
     * Usado na primeira execução e pelo botão RESTAURAR PADRÃO — antes esse botão
     * só apagava tudo, deixando os defaults do código, não o preset.
     *
     * @return true se o preset foi aplicado; false se não deu (aí o chamador
     *         decide se apenas limpa).
     */
    fun applyBundledDefaults(ctx: Context): Boolean {
        return try {
            val json = ctx.assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() }
            importFromJson(ctx, json) is ImportResult.Ok
        } catch (_: Exception) {
            false
        }
    }

    fun suggestedFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "thermalmonitor-config-$ts.json"
    }
}
