package com.siliconfet.thermalmonitor

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Fonte do app. Padrao Styrene B embutida; o usuario pode trocar pela fonte do
 * sistema ou pela monoespacada em Configuracoes.
 *
 * A Styrene B e proporcional: o digito "1" mede 445/1000 e o "0" mede 692/1000.
 * Sem correcao, qualquer numero em tela muda de largura a cada leitura. A fonte
 * traz a feature OpenType `tnum` (todos os digitos em 650/1000), entao TODO Paint
 * que desenha numero recebe [NUM_FEATURES].
 */
object Fonts {

    /** Digitos tabulares: obrigatorio em qualquer texto numerico. */
    const val NUM_FEATURES = "tnum"

    const val REGULAR = 0
    const val MEDIUM = 1
    const val BOLD = 2

    @Volatile private var regularTf: Typeface? = null
    @Volatile private var mediumTf: Typeface? = null
    @Volatile private var boldTf: Typeface? = null
    @Volatile private var loaded = false

    /** Modo em cache: ler SharedPreferences a cada Paint sairia caro. */
    @Volatile private var mode: String = OverlayPrefs.FONT_STYRENE
    @Volatile private var modeRead = false

    /** Carrega as tres variantes uma vez. Seguro chamar de qualquer thread. */
    @Synchronized
    fun init(ctx: Context) {
        if (!modeRead) { mode = OverlayPrefs.getFontFamily(ctx); modeRead = true }
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        regularTf = load(app, R.font.styreneb_regular)
        mediumTf = load(app, R.font.styreneb_medium)
        boldTf = load(app, R.font.styreneb_bold)
    }

    /** Rele a preferencia de familia. Chamar quando o usuario trocar. */
    fun reloadMode(ctx: Context) {
        mode = OverlayPrefs.getFontFamily(ctx)
        modeRead = true
    }

    fun mode(): String = mode

    /** A escolhida tem digitos tabulares? So a Styrene B e a monoespacada. */
    fun hasTabularNums(): Boolean = mode != OverlayPrefs.FONT_SYSTEM

    private fun load(ctx: Context, resId: Int): Typeface? =
        try { ResourcesCompat.getFont(ctx, resId) } catch (_: Exception) { null }

    fun regular(ctx: Context): Typeface {
        init(ctx)
        return when (mode) {
            OverlayPrefs.FONT_SYSTEM -> Typeface.DEFAULT
            OverlayPrefs.FONT_MONO -> Typeface.MONOSPACE
            else -> regularTf ?: Typeface.DEFAULT
        }
    }

    fun medium(ctx: Context): Typeface {
        init(ctx)
        return when (mode) {
            OverlayPrefs.FONT_SYSTEM -> Typeface.DEFAULT
            OverlayPrefs.FONT_MONO -> Typeface.MONOSPACE
            else -> mediumTf ?: regularTf ?: Typeface.DEFAULT
        }
    }

    fun bold(ctx: Context): Typeface {
        init(ctx)
        return when (mode) {
            OverlayPrefs.FONT_SYSTEM -> Typeface.DEFAULT_BOLD
            OverlayPrefs.FONT_MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> boldTf ?: Typeface.DEFAULT_BOLD
        }
    }

    /**
     * Aplica a variante pedida e liga os digitos tabulares.
     *
     * [Paint.isFakeBoldText] fica desligado quando ha Bold real (Styrene B):
     * o negrito sintetico do Android engrossaria o traco duas vezes. Nas outras
     * familias o peso vem do proprio Typeface.
     */
    fun apply(p: Paint, ctx: Context, weight: Int, tabularNums: Boolean = true) {
        p.typeface = when (weight) {
            BOLD -> bold(ctx)
            MEDIUM -> medium(ctx)
            else -> regular(ctx)
        }
        p.isFakeBoldText = false
        p.fontFeatureSettings = if (tabularNums && hasTabularNums()) NUM_FEATURES else null
    }
}
