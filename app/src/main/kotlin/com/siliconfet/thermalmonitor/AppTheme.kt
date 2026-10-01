package com.siliconfet.thermalmonitor

import android.content.Context
import android.graphics.Color

data class AppTheme(
    val id: String,
    val displayName: String,

    val cBg: Int,
    val cCard: Int,
    val cCardStroke: Int,
    val cText: Int,
    val cDim: Int,
    val cBorder: Int,

    val accent1: Int,
    val accent2: Int,

    val cGreen: Int = Color.parseColor("#50FA7B"),
    val cRed: Int = Color.parseColor("#FF5555"),
    val cOrange: Int = Color.parseColor("#FFB86C"),
    val cCyan: Int = Color.parseColor("#8BE9FD"),
    val cPurple: Int = Color.parseColor("#BD93F9"),

    val cardCorner: Int = 12,
    val cardElevation: Float = 2f,
    val hasGlow: Boolean = false,
    val useGradient: Boolean = false,
    val padScale: Float = 1.0f,
    val showCardBg: Boolean = true,
    val cardAlpha: Int = 255
) {
    companion object {
        const val PREF_KEY = "app_theme"

        val DEFAULT = AppTheme(
            id = "default",
            displayName = "Default",
            cBg = Color.parseColor("#0F0F17"),
            cCard = Color.parseColor("#16162A"),
            cCardStroke = Color.parseColor("#2E2E45"),
            cText = Color.parseColor("#F8F8F2"),
            cDim = Color.parseColor("#6272A4"),
            cBorder = Color.parseColor("#2E2E45"),
            accent1 = Color.parseColor("#BD93F9"),
            accent2 = Color.parseColor("#BD93F9")
        )

        val GLASS = AppTheme(
            id = "glass",
            displayName = "Glass",
            cBg = Color.parseColor("#08080F"),
            cCard = Color.parseColor("#FFFFFF"),
            cCardStroke = Color.parseColor("#40FFFFFF"),
            cText = Color.parseColor("#F8F8F2"),
            cDim = Color.parseColor("#7080A0"),
            cBorder = Color.parseColor("#30FFFFFF"),
            accent1 = Color.parseColor("#8BE9FD"),
            accent2 = Color.parseColor("#BD93F9"),
            cardCorner = 18,
            cardElevation = 0f,
            cardAlpha = 24,
            useGradient = true,
            padScale = 1.1f
        )

        val NEON = AppTheme(
            id = "neon",
            displayName = "Neon",
            cBg = Color.parseColor("#0A0420"),
            cCard = Color.parseColor("#1A0E35"),
            cCardStroke = Color.parseColor("#FF00D4"),
            cText = Color.parseColor("#F8F8F2"),
            cDim = Color.parseColor("#A080C0"),
            cBorder = Color.parseColor("#5A2080"),
            accent1 = Color.parseColor("#FF00D4"),
            accent2 = Color.parseColor("#00FFE5"),
            cardCorner = 8,
            cardElevation = 6f,
            hasGlow = true,
            useGradient = true,
            padScale = 1.0f
        )

        val MINIMAL = AppTheme(
            id = "minimal",
            displayName = "Minimal",
            cBg = Color.parseColor("#0F0F17"),
            cCard = Color.parseColor("#0F0F17"),
            cCardStroke = Color.parseColor("#1A1A2A"),
            cText = Color.parseColor("#F8F8F2"),
            cDim = Color.parseColor("#5A6080"),
            cBorder = Color.parseColor("#1A1A2A"),
            accent1 = Color.parseColor("#BD93F9"),
            accent2 = Color.parseColor("#BD93F9"),
            cardCorner = 0,
            cardElevation = 0f,
            padScale = 1.4f,
            showCardBg = false
        )

        val CYBERPUNK = AppTheme(
            id = "cyberpunk",
            displayName = "Cyberpunk",
            cBg = Color.parseColor("#05050F"),
            cCard = Color.parseColor("#0F0F25"),
            cCardStroke = Color.parseColor("#00FFE5"),
            cText = Color.parseColor("#F8F8F2"),
            cDim = Color.parseColor("#80A0C0"),
            cBorder = Color.parseColor("#1A4060"),
            accent1 = Color.parseColor("#00FFE5"),
            accent2 = Color.parseColor("#FF00D4"),
            cardCorner = 4,
            cardElevation = 4f,
            hasGlow = true,
            useGradient = true,
            padScale = 1.2f
        )

        val SIGNAL = AppTheme(
            id = "signal",
            displayName = "Signal",
            cBg = Color.parseColor("#0A0C0B"),
            cCard = Color.parseColor("#11140F"),
            cCardStroke = Color.parseColor("#2A3025"),
            cText = Color.parseColor("#F4F8EE"),
            cDim = Color.parseColor("#6F7A64"),
            cBorder = Color.parseColor("#1F241C"),
            accent1 = Color.parseColor("#2FE05A"),
            accent2 = Color.parseColor("#16A138"),
            cGreen = Color.parseColor("#2FE05A"),
            cCyan = Color.parseColor("#4AD2FF"),
            cardCorner = 14,
            cardElevation = 2f,
            padScale = 1.1f
        )

        val PURE_DARK = AppTheme(
            id = "puredark",
            displayName = "Pure Dark",
            cBg = Color.parseColor("#000000"),
            cCard = Color.parseColor("#121212"),
            cCardStroke = Color.parseColor("#262626"),
            cText = Color.parseColor("#E0E0E0"),
            cDim = Color.parseColor("#7A7A7A"),
            cBorder = Color.parseColor("#262626"),
            accent1 = Color.parseColor("#9E9E9E"),
            accent2 = Color.parseColor("#616161"),
            cGreen = Color.parseColor("#A8A8A8"),
            cRed = Color.parseColor("#BDBDBD"),
            cOrange = Color.parseColor("#9E9E9E"),
            cCyan = Color.parseColor("#B0B0B0"),
            cPurple = Color.parseColor("#8C8C8C"),
            cardCorner = 8,
            cardElevation = 0f,
            useGradient = false,
            padScale = 1.0f
        )

        val ALL = listOf(DEFAULT, GLASS, NEON, MINIMAL, CYBERPUNK, SIGNAL, PURE_DARK)

        fun current(ctx: Context): AppTheme {
            val id = ctx.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                .getString(PREF_KEY, "default") ?: "default"
            return ALL.firstOrNull { it.id == id } ?: DEFAULT
        }

        fun setCurrent(ctx: Context, themeId: String) {
            val valid = ALL.any { it.id == themeId }
            val idToSave = if (valid) themeId else "default"
            ctx.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, idToSave).apply()
        }
    }
}
