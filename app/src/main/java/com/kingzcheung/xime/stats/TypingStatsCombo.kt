package com.kingzcheung.xime.stats

/**
 * 打字统计里的一种输入方式：语言 · 方案 · 布局（另有实体键盘、语音、手写）。
 * [id] 只由内置的语言、方案、布局 ID 拼成，存盘和分享用；[label] 是给人看的内置名称。
 * 用户自建布局的名字是用户写的，不进统计，一律记成「自定义布局」。纯 Kotlin，电脑上能测。
 */
data class TypingStatsCombo(val id: String, val label: String) {
    companion object {
        const val CUSTOM_LAYOUT_ID = "custom"
        const val CUSTOM_LAYOUT_LABEL = "自定义布局"
        val VOICE = TypingStatsCombo("voice", "语音")

        /**
         * 由当前状态拼出一种输入方式。参数都是内置 ID 和名称，调用方负责把自建布局标成 [customLayout]。
         * [lettersDirect] 为真时（英文、西班牙语）方案就是「直接输入」，名字里不写方案。
         */
        fun of(
            source: TypingStatsData.Source,
            languageId: String, languageName: String,
            schemeId: String, schemeName: String,
            layoutId: String, layoutName: String,
            customLayout: Boolean,
            lettersDirect: Boolean,
            hardwareKeyboard: Boolean,
        ): TypingStatsCombo {
            val lang = clean(languageId)
            return when {
                source == TypingStatsData.Source.VOICE -> VOICE
                source == TypingStatsData.Source.HANDWRITING -> TypingStatsCombo("hand/$lang", "$languageName · 手写")
                hardwareKeyboard -> if (lettersDirect) TypingStatsCombo("hw/$lang", "$languageName · 实体键盘")
                    else TypingStatsCombo("hw/$lang/${clean(schemeId)}", "$languageName · $schemeName · 实体键盘")
                else -> {
                    val layout = if (customLayout) CUSTOM_LAYOUT_ID else normalizeLayout(layoutId)
                    val layoutLabel = if (customLayout) CUSTOM_LAYOUT_LABEL else layoutName
                    if (lettersDirect) TypingStatsCombo("kb/$lang/direct/$layout", "$languageName · $layoutLabel")
                    else TypingStatsCombo("kb/$lang/${clean(schemeId)}/$layout", "$languageName · $schemeName · $layoutLabel")
                }
            }
        }

        /** 各语言自己的 26 键（qwerty_en、qwerty_spanish……）是同一种布局，合成 qwerty。 */
        private fun normalizeLayout(id: String): String {
            val c = clean(id)
            return if (c == "qwerty_en" || c == "qwerty_japanese" || c == "qwerty_spanish" || c == "qwerty_cangjie") "qwerty" else c
        }

        private fun clean(id: String): String =
            id.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '.' || it == '-') it else '_' }
                .joinToString("").ifEmpty { "_" }

        /** 拉丁字母语言按字母算速度，单位写「字母/分」；其他写「字/分」。 */
        fun speedUnit(id: String): String =
            if (id.startsWith("kb/en/") || id.startsWith("kb/da/") || id.startsWith("kb/es/") || id == "hw/en" || id == "hw/da" || id == "hw/es") "字母/分" else "字/分"
    }
}
