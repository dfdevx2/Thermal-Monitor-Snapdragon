package com.siliconfet.thermalmonitor

/**
 * Tabelas fixas do porte Snapdragon.
 *
 * Tudo aqui e so fallback: o mapeamento de verdade e feito em tempo de execucao
 * pelo [HwProbe], lendo o proprio sysfs do aparelho. As tabelas entram quando o
 * kernel nao expoe algo de forma inequivoca (ex.: qual sensor termico pertence a
 * qual nucleo).
 */
internal object SocTable {

    /** Numero de peca (Build.SOC_MODEL / ro.soc.model) -> nome comercial. */
    private val SOC_NAMES = linkedMapOf(
        // 8 series
        "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM8845" to "Snapdragon 8 Gen 5",
        "SM8750" to "Snapdragon 8 Elite",
        "SM8735" to "Snapdragon 8s Gen 4",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8635" to "Snapdragon 8s Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8350" to "Snapdragon 888",
        "SM8250" to "Snapdragon 865/870",
        "SM8150" to "Snapdragon 855/860",
        "SDM845" to "Snapdragon 845",
        // 7 series
        "SM7750" to "Snapdragon 7 Gen 4",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "SM7635" to "Snapdragon 7s Gen 3",
        "SM7550" to "Snapdragon 7 Gen 3",
        "SM7475" to "Snapdragon 7+ Gen 2",
        "SM7450" to "Snapdragon 7 Gen 1",
        "SM7435" to "Snapdragon 7s Gen 2",
        "SM7350" to "Snapdragon 780G",
        "SM7325" to "Snapdragon 778G",
        "SM7250" to "Snapdragon 765/768G",
        "SM7225" to "Snapdragon 750G",
        "SM7150" to "Snapdragon 730/732G",
        "SM7125" to "Snapdragon 720G",
        "SDM712" to "Snapdragon 712",
        "SDM710" to "Snapdragon 710",
        // 6 / 4 series
        "SM6850" to "Snapdragon 6 Gen 5",
        "SM6650" to "Snapdragon 6 Gen 4",
        "SM6475" to "Snapdragon 6 Gen 3",
        "SM6450" to "Snapdragon 6 Gen 1",
        "SM6435" to "Snapdragon 6s Gen 4",
        "SM6375" to "Snapdragon 695",
        "SM6225" to "Snapdragon 680",
        "SM6150" to "Snapdragon 675",
        "SM6125" to "Snapdragon 665",
        "SM6115" to "Snapdragon 662",
        "SM4850" to "Snapdragon 4 Gen 5",
        "SM4635" to "Snapdragon 4s Gen 2",
        "SM4450" to "Snapdragon 4 Gen 2",
        "SM4375" to "Snapdragon 4 Gen 1",
        "SM4350" to "Snapdragon 480"
    )

    /** Codinome da plataforma (ro.board.platform) -> nome comercial. */
    private val PLATFORM_NAMES = mapOf(
        "sun" to "Snapdragon 8 Elite",
        "canoe" to "Snapdragon 8 Elite Gen 5",
        "pineapple" to "Snapdragon 8 Gen 3",
        "cliffs" to "Snapdragon 8s Gen 3 / 7+ Gen 3",
        "kalama" to "Snapdragon 8 Gen 2",
        "cape" to "Snapdragon 8+ Gen 1",
        "taro" to "Snapdragon 8 Gen 1",
        "lahaina" to "Snapdragon 888",
        "kona" to "Snapdragon 865/870",
        "msmnile" to "Snapdragon 855/860",
        "sdm845" to "Snapdragon 845",
        "crow" to "Snapdragon 7 Gen 3",
        "yupik" to "Snapdragon 778G",
        "shima" to "Snapdragon 780G",
        "lito" to "Snapdragon 765G",
        "holi" to "Snapdragon 480/695",
        "bengal" to "Snapdragon 662/460",
        "trinket" to "Snapdragon 665"
    )

    /**
     * Sensor termico -> nucleo, por plataforma. Extraido dos `*-thermal.dtsi`
     * da Qualcomm (os cooling-maps de pause/hotplug de cada zona dizem de qual
     * nucleo ela e). Chave sem o prefixo `cpu-` e sem sufixo `-usr`/`-step`.
     *
     * Necessario porque o numero do grupo no nome NAO segue o cluster de
     * cpufreq: no 8 Gen 3 (pineapple) o grupo `cpu-2-*` sao os nucleos 2-4 e
     * `cpu-1-*` sao 5-7; no 865 (kona) os sensores do grupo 1 sao intercalados.
     */
    private val ZONE_CORE_MAP = mapOf(
        "sun" to "0-0-0=0,0-0-1=0,0-1-0=1,0-1-1=1,0-2-0=2,0-2-1=2,0-3-0=3,0-3-1=3,0-4-0=4,0-4-1=4,0-5-0=5,0-5-1=5,1-0-0=6,1-0-1=6,1-1-0=7,1-1-1=7",
        "pineapple" to "2-0-0=2,2-0-1=2,2-1-0=3,2-1-1=3,2-2-0=4,2-2-1=4,1-0-0=5,1-0-1=5,1-1-0=6,1-1-1=6,1-2-0=7,1-2-1=7,1-2-2=7,0-0-0=0,0-1-0=1",
        "cliffs" to "1-0-0=3,1-0-1=3,1-1-0=4,1-1-1=4,1-2-0=5,1-2-1=5,1-3-0=6,1-3-1=6,2-0-0=7,2-0-1=7,2-0-2=7,0-0-0=0,0-1-0=1,0-2-0=2",
        "tuna" to "2-0-0=7,2-0-1=7,2-0-2=7,1-0-0=5,1-0-1=5,1-1-0=6,1-1-1=6,1-2-0=2,1-2-1=2,1-3-0=3,1-3-1=3,1-4-0=4,1-4-1=4,0-0-0=0,0-1-0=1",
        "kera" to "1-0-0=3,1-0-1=3,1-1-0=4,1-1-1=4,1-2-0=5,1-2-1=5,1-3-0=6,1-3-1=6,2-0-0=7,2-0-1=7,0-0-0=0,0-1-0=1,0-2-0=2",
        "volcano" to "1-0-0=3,1-0-1=3,1-1-0=4,1-1-1=4,1-2-0=5,1-2-1=5,1-3-0=6,1-3-1=6,0-0-0=0,0-1-0=1,0-2-0=2,0-3-0=2",
        "kalama" to "1-0=3,1-1=3,1-2=4,1-3=4,1-4=5,1-5=5,1-6=6,1-7=6,1-8=7,1-9=7,1-10=7,0-0=0,0-1=1,0-2=2",
        "cape" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,1-8=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "taro" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,1-8=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "waipio" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,1-8=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "lahaina" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7",
        "shima" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7",
        "yupik" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7",
        "kona" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=5,1-2=6,1-3=7,1-4=4,1-5=5,1-6=6,1-7=7",
        "msmnile" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=5,1-2=6,1-3=7,1-4=4,1-5=5,1-6=6,1-7=7",
        "sm8150" to "0-0=0,0-1=1,0-2=2,0-3=3,1-0=4,1-1=5,1-2=6,1-3=7,1-4=4,1-5=5,1-6=6,1-7=7",
        "crow" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "parrot" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "diwali" to "1-0=4,1-1=4,1-2=5,1-3=5,1-4=6,1-5=6,1-6=7,1-7=7,0-0=0,0-1=1,0-2=2,0-3=3",
        "ravelin" to "0-0=0,0-1=1,0-2=2,0-3=3,0-4=4,0-5=5,1-0=6,1-1=6,1-2=7,1-3=7",
        "holi" to "0-0=0,0-1=1,0-2=2,0-3=3,0-4=4,0-5=5,1-0=6,1-1=6,1-2=7,1-3=7",
        "pitti" to "0-0=0,0-1=1,0-2=2,0-3=3,0-4=4,0-5=5,2-0=7,2-1=7,1-0=6,1-1=6",
        "bengal" to "1-0=4,1-1=5,1-2=6,1-3=7",
        "trinket" to "1-0=4,1-1=5,1-2=6,1-3=7",
        "sm6150" to "1-0=6,1-1=6,1-2=7,1-3=7"
    )

    fun zoneCoreMap(platform: String?): Map<String, Int> {
        val raw = ZONE_CORE_MAP[platform?.lowercase() ?: return emptyMap()] ?: return emptyMap()
        val out = HashMap<String, Int>(32)
        for (e in raw.split(',')) {
            val eq = e.indexOf('=')
            if (eq > 0) out["cpu-" + e.substring(0, eq)] = e.substring(eq + 1).toInt()
        }
        return out
    }

    /**
     * Nome comercial a partir do numero de peca. Aceita variantes com sufixo
     * (SM8550-AB) e as versoes "QCS"/"QCM" usadas em portateis (ex.: QCS8550
     * do AYN Odin 2), que sao o mesmo silicio do SM correspondente.
     */
    fun marketingName(model: String?, platform: String?): String? {
        if (!model.isNullOrBlank()) {
            val m = model.uppercase().trim()
            val base = m.substringBefore('-').substringBefore(' ')
            SOC_NAMES[base]?.let { return it }
            val alt = base.replace(Regex("^(QCS|QCM|SXR|SA|SC)"), "SM")
            SOC_NAMES[alt]?.let { return it }
            if (m.startsWith("SDM") || m.startsWith("SM") || m.startsWith("QC")) {
                // Peca desconhecida mas claramente Qualcomm.
                return "Snapdragon ($base)"
            }
        }
        platform?.lowercase()?.let { p -> PLATFORM_NAMES[p]?.let { return it } }
        return null
    }

    /**
     * Nome curto do nucleo pelo "CPU implementer" + "CPU part" do /proc/cpuinfo.
     * Os Kryo sao Cortex customizados; mostramos o Cortex correspondente, que e
     * o que o usuario reconhece.
     */
    fun corePartName(implementer: Int, part: Int): String? = when (implementer) {
        0x41 -> when (part) {
            0xd03 -> "A53"; 0xd04 -> "A35"; 0xd05 -> "A55"; 0xd07 -> "A57"
            0xd08 -> "A72"; 0xd09 -> "A73"; 0xd0a -> "A75"; 0xd0b -> "A76"
            0xd0d -> "A77"; 0xd0e -> "A76AE"; 0xd41 -> "A78"; 0xd44 -> "X1"
            0xd46 -> "A510"; 0xd47 -> "A710"; 0xd48 -> "X2"; 0xd4b -> "A78C"
            0xd4d -> "A715"; 0xd4e -> "X3"; 0xd80 -> "A520"; 0xd81 -> "A720"
            0xd82 -> "X4"; 0xd85 -> "X925"; 0xd87 -> "A725"; 0xd8e -> "A520"
            else -> null
        }
        0x51 -> when (part) {
            0x001, 0x002, 0x003 -> "ORYON"
            0x800 -> "A73"; 0x801 -> "A53"; 0x802 -> "A75"; 0x803 -> "A55"
            0x804 -> "A76"; 0x805 -> "A55"
            else -> null
        }
        else -> null
    }
}
