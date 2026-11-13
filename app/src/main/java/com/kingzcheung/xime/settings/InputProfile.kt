package com.kingzcheung.xime.settings

/** Product language, never a keyboard shape or a recognizer. */
enum class InputLanguage(val id: String, val displayName: String) {
    CHINESE("zh", "中文"), JAPANESE("ja", "日语"), ENGLISH("en", "英文"), DANISH("da", "丹麦语"), SPANISH("es", "西班牙语"),
    GERMAN("de", "德语"), FRENCH("fr", "法语"), ITALIAN("it", "意大利语"), RUSSIAN("ru", "俄语"), KOREAN("ko", "韩语"),
    UNSPECIFIED("und", "未标注语言");

    /** Languages typed letter by letter straight into the editor (see [LetterLanguage]), with word completions above. */
    val typesLettersDirectly: Boolean get() = this == ENGLISH || LetterLanguage.of(this) != null

    companion object {
        /** The elder package offers only Chinese and English; see docs/development/elder-pack-spec.md. */
        val supported = if (com.kingzcheung.xime.BuildConfig.ELDER) listOf(CHINESE, ENGLISH)
            else listOf(CHINESE, JAPANESE, ENGLISH, DANISH, SPANISH, GERMAN, FRENCH, ITALIAN, RUSSIAN, KOREAN)
        /** Languages a shipped speech recognizer can transcribe. The [LetterLanguage]s have none yet. */
        val speech: List<InputLanguage> get() = supported.filter { LetterLanguage.of(it) == null }
        fun fromId(id: String): InputLanguage? = entries.firstOrNull { it.id == id }
        /** Compatibility boundary for stored Rime IDs. New business code uses InputProfile. */
        fun forSchema(id: String): InputLanguage = InputProfiles.describe(id).language
    }
}

/** Rules that turn codes into text. Layout and the particular dictionary are independent. */
enum class InputScheme(val id: String, val displayName: String) {
    PINYIN("pinyin", "拼音"), DOUBLE_PINYIN("double_pinyin", "双拼"),
    WUBI("wubi", "五笔"), WUBI_PINYIN("wubi_pinyin", "五笔拼音混输"), STROKE("stroke", "笔画"),
    ROMAJI("romaji", "罗马字"), KANA("kana", "假名"), DIRECT("direct", "直接输入"),
    EXTERNAL("external", "自定义编码"), ZHUYIN("zhuyin", "注音"), CANGJIE("cangjie", "仓颉"),
    /** 英文整词：字母先进组字区，从候选里选词上屏（[InputModes.ENGLISH_WORDS]）；[DIRECT] 是逐字直接上屏。 */
    WORDS("words", "单词");

    companion object { fun fromId(id: String): InputScheme? = entries.firstOrNull { it.id == id } }
}

enum class InputMode { KEYBOARD, HANDWRITING, VOICE }
/** ZHUYIN：大千式注音键盘，键帽是注音符号（11 列 4 行），不是字母键。 */
enum class LayoutKind { ALPHABETIC, T9, MERGED, KANA_KEYPAD, STROKE_KEYPAD, HANDWRITING, NUMBER, ZHUYIN }

data class InputLayout(val id: String, val displayName: String, val kind: LayoutKind) {
    companion object {
        val QWERTY = InputLayout("qwerty", "26键", LayoutKind.ALPHABETIC)
        val T9 = InputLayout("t9", "九键", LayoutKind.T9)
        val MERGED14 = InputLayout("qwerty_14", "14键", LayoutKind.MERGED)
        val KANA = InputLayout("japanese_kana", "假名九键", LayoutKind.KANA_KEYPAD)
        val STROKE = InputLayout("stroke", "笔画键盘", LayoutKind.STROKE_KEYPAD)
        val HANDWRITING = InputLayout("handwriting", "手写板", LayoutKind.HANDWRITING)
        val NUMBER = InputLayout("number", "数字键盘", LayoutKind.NUMBER)
        val ZHUYIN = InputLayout("zhuyin", "大千", LayoutKind.ZHUYIN)
    }
}

sealed interface EngineProfile {
    data class Rime(val schemaId: String) : EngineProfile
    data object Direct : EngineProfile
    data object Handwriting : EngineProfile
}

/** Capabilities describe backend semantics, not guessed substrings in a schema ID. */
data class InputCapabilities(
    /** 组字区里的编码可以挪光标改（拼音、双拼，以及英文单词方案的字母）。 */
    val editablePinyin: Boolean = false,
    /** 编码分大小写（英文单词方案打 Hello 出 Hello）：改编码时不能把字母转成小写。 */
    val caseSensitiveCode: Boolean = false,
    val kanaCase: Boolean = false,
    val japaneseConversion: Boolean = false,
)

data class InputProfile(
    val language: InputLanguage,
    val scheme: InputScheme,
    val layout: InputLayout,
    val engineProfile: EngineProfile,
    val mode: InputMode = InputMode.KEYBOARD,
    val capabilities: InputCapabilities = InputCapabilities(),
) {
    val summary: String get() = if (mode == InputMode.HANDWRITING) "手写" else
        "${scheme.displayName} · ${layout.displayName}"

    /** 字母逐字直接上屏、补全另给（英文直接输入、西班牙语等）；英文单词方案走 Rime 组字，不算。 */
    val typesLettersDirectly: Boolean get() = language.typesLettersDirectly && scheme == InputScheme.DIRECT

    /** Entering handwriting retains language and the keyboard scheme to return to. */
    fun handwriting(): InputProfile = copy(layout = InputLayout.HANDWRITING,
        mode = InputMode.HANDWRITING, engineProfile = EngineProfile.Handwriting,
        capabilities = InputCapabilities())
}

/** The only product ↔ built-in backend mapping. No file IO, deployment, or preference writes. */
object InputProfiles {
    /** Display samples are backend metadata; unknown double-pinyin variants must not borrow Flypy codes. */
    fun schemePreview(profile: InputProfile): String = when (profile.scheme) {
        InputScheme.PINYIN -> "pin'yin"
        InputScheme.DOUBLE_PINYIN -> when ((profile.engineProfile as? EngineProfile.Rime)?.schemaId) {
            "double_pinyin_flypy", "double_pinyin_flypy_14jian" -> "xn'he'ul'pb"
            else -> DoublePinyinSchemes.find((profile.engineProfile as? EngineProfile.Rime)?.schemaId.orEmpty())
                ?.preview() ?: "双拼编码"
        }
        InputScheme.ROMAJI -> "romaji"
        InputScheme.KANA -> "かな"
        InputScheme.DIRECT -> "abc"
        InputScheme.WORDS -> "word"
        InputScheme.STROKE -> "一丨丿丶乛"
        InputScheme.WUBI, InputScheme.WUBI_PINYIN -> "字根编码"
        InputScheme.EXTERNAL -> "自定义编码"
        InputScheme.ZHUYIN -> "ㄓㄨˋㄧㄣ"
        // 倉：仓颉 oiar，速成取首尾 or
        InputScheme.CANGJIE -> if ((profile.engineProfile as? EngineProfile.Rime)?.schemaId == "cyime_quick") "人口" else "人戈日口"
    }
    private val descriptions = java.util.concurrent.ConcurrentHashMap<String, InputProfile>()
    @Volatile private var installed: Map<String, InputProfile> = emptyMap()
    private val pinyinIds = setOf("rime_ice", "pinyin_simp", "luna_pinyin", "luna_pinyin_simp",
        "luna_pinyin_fluency", "t9", "t9_pinyin", "pinyin_14jian", "pinyin_17jian", "pinyin_18jian")
    private val doublePinyinIds = setOf("double_pinyin", "double_pinyin_flypy", "double_pinyin_flypy_14jian", "double_pinyin_abc",
        "double_pinyin_mspy", "double_pinyin_sogou", "double_pinyin_ziguang", "double_pinyin_pyjj")
    private val wubiIds = setOf("wubi86", "wubi86_trad", "wubi98")
    private val wubiPinyinIds = setOf("wubi86_pinyin", "wubi86_trad_pinyin")

    fun describe(id: String, language: InputLanguage? = null, scheme: InputScheme? = null): InputProfile {
        // Built-in descriptions are reused on every key; custom layouts remain live after edits.
        val base = installed[id] ?: builtin(id)
        val custom = CustomKeyboardLayouts.forSchema(id)
        val actualLayout = custom?.let(::customLayout) ?: base.layout
        if ((language == null || language == base.language) && (scheme == null || scheme == base.scheme) && actualLayout == base.layout) return base
        val actualScheme = scheme ?: base.scheme
        return base.copy(language = language ?: base.language, scheme = actualScheme,
            layout = actualLayout, capabilities = capabilities(actualScheme))
    }

    internal fun builtin(id: String): InputProfile = descriptions.getOrPut(id) { describeBackend(id) }

    /** Publish after discovery, outside the key path. Removing metadata also removes its old meaning. */
    internal fun updateInstalled(entries: List<SchemaMeta>) {
        installed = entries.associate { entry ->
            val base = builtin(entry.schemaId)
            entry.schemaId to base.copy(language = entry.language, scheme = entry.scheme,
                capabilities = capabilities(entry.scheme))
        }
    }

    private fun capabilities(scheme: InputScheme) = InputCapabilities(
        editablePinyin = scheme == InputScheme.PINYIN || scheme == InputScheme.DOUBLE_PINYIN || scheme == InputScheme.WORDS,
        caseSensitiveCode = scheme == InputScheme.WORDS,
        kanaCase = scheme == InputScheme.ROMAJI,
        japaneseConversion = scheme == InputScheme.ROMAJI || scheme == InputScheme.KANA,
    )

    private fun describeBackend(id: String): InputProfile {
        val inputScheme = when {
            id == InputModes.ENGLISH || LetterLanguage.ofSchema(id) != null -> InputScheme.DIRECT
            id == InputModes.ENGLISH_WORDS -> InputScheme.WORDS
            id in pinyinIds || CustomKeyboardLayouts.isCustom(id) -> InputScheme.PINYIN
            id in doublePinyinIds || DoublePinyinSchemes.isGenerated(id) -> InputScheme.DOUBLE_PINYIN
            id in wubiIds -> InputScheme.WUBI
            id in wubiPinyinIds -> InputScheme.WUBI_PINYIN
            id == "stroke" -> InputScheme.STROKE
            id == "japanese" || id == "jaroomaji" -> InputScheme.ROMAJI
            id == "japanese_kana" -> InputScheme.KANA
            id == "cyime_zhuyin" -> InputScheme.ZHUYIN
            id == "cyime_cangjie" || id == "cyime_quick" -> InputScheme.CANGJIE
            else -> InputScheme.EXTERNAL
        }
        val inputLanguage = when {
            id == InputModes.ENGLISH || id == InputModes.ENGLISH_WORDS -> InputLanguage.ENGLISH
            LetterLanguage.ofSchema(id) != null -> LetterLanguage.ofSchema(id)!!.language
            inputScheme in setOf(InputScheme.ROMAJI, InputScheme.KANA) -> InputLanguage.JAPANESE
            inputScheme in setOf(InputScheme.PINYIN, InputScheme.DOUBLE_PINYIN, InputScheme.WUBI,
                InputScheme.WUBI_PINYIN, InputScheme.STROKE, InputScheme.ZHUYIN, InputScheme.CANGJIE) || id == "handwriting" -> InputLanguage.CHINESE
            else -> InputLanguage.UNSPECIFIED
        }
        val layout = when {
            id == "t9_pinyin" || id == "t9" || DoublePinyinSchemes.isT9(id) -> InputLayout.T9
            id == "pinyin_14jian" || DoublePinyinSchemes.isK14(id) -> InputLayout.MERGED14
            id == "pinyin_17jian" -> InputLayout("qwerty_17", "17键", LayoutKind.MERGED)
            id == "pinyin_18jian" -> InputLayout("qwerty_18", "18键", LayoutKind.MERGED)
            id == "japanese_kana" -> InputLayout.KANA
            id == "stroke" -> InputLayout.STROKE
            id == "handwriting" -> InputLayout.HANDWRITING
            id == "numbers" -> InputLayout.NUMBER
            id == "cyime_zhuyin" -> InputLayout.ZHUYIN
            else -> InputLayout.QWERTY
        }
        return InputProfile(inputLanguage, inputScheme, layout,
            if (id == InputModes.ENGLISH) EngineProfile.Direct else EngineProfile.Rime(id),
            capabilities = capabilities(inputScheme)).let { if (id == "handwriting") it.handwriting() else it }
    }

    internal fun customLayout(layout: CustomKeyboardLayout) = InputLayout(layout.id, layout.name,
        if (layout.mergedGroups.isEmpty()) LayoutKind.ALPHABETIC else LayoutKind.MERGED)

    /** English (direct and words) and Japanese romaji type on the letter arrangement chosen for their language. */
    fun arranged(profile: InputProfile): InputProfile {
        if (profile.layout.kind != LayoutKind.ALPHABETIC || !LetterLayouts.appliesTo(profile.language, profile.scheme)) return profile
        return LetterLayouts.chosen(profile.language)?.let { profile.copy(layout = customLayout(it)) } ?: profile
    }

    /** Actual layout bindings win over built-in defaults, including an explicitly unbound schema. */
    fun resolveLayout(section: String?, custom: CustomKeyboardLayout? = null): InputLayout = when (section) {
        "qwerty", "qwerty_japanese", "qwerty_spanish", "qwerty_german", "qwerty_french",
        "qwerty_italian" ->
            InputLayout(section, "26键", LayoutKind.ALPHABETIC)
        "qwerty_danish" -> InputLayout(section, "29键", LayoutKind.ALPHABETIC)
        // 英文单词方案用英文键盘的键帽和角标，和英文直接输入是同一个 26 键：布局栏里不出两个「26键」。
        "qwerty_en" -> InputLayout.QWERTY
        "jcuken_russian" -> InputLayout(section, "ЙЦУКЕН", LayoutKind.ALPHABETIC)
        "dubeolsik_korean" -> InputLayout(section, "두벌식", LayoutKind.ALPHABETIC)
        // 仓颉只是键帽换成字根，仍是中文的 26 键：同一个布局，方案行里与拼音、双拼、五笔并列。
        "qwerty_cangjie" -> InputLayout.QWERTY
        "t9" -> InputLayout.T9
        "stroke" -> InputLayout.STROKE
        "handwriting" -> InputLayout.HANDWRITING
        "japanese_kana" -> InputLayout.KANA
        "zhuyin" -> InputLayout.ZHUYIN
        else -> if (custom != null) customLayout(custom) else when (section) {
            null -> InputLayout.QWERTY
            "qwerty_14" -> InputLayout.MERGED14
            "qwerty_17" -> InputLayout("qwerty_17", "17键", LayoutKind.MERGED)
            "qwerty_18" -> InputLayout("qwerty_18", "18键", LayoutKind.MERGED)
            else -> InputLayout(section, section, LayoutKind.MERGED)
        }
    }

    /** Runtime projection. Legacy schema storage stays at this boundary during migration. */
    fun current(id: String, asciiMode: Boolean = false, language: InputLanguage? = null,
        scheme: InputScheme? = null, arranged: Boolean = true): InputProfile {
        if (asciiMode || id == InputModes.ENGLISH) return describe(InputModes.ENGLISH).let { if (arranged) arranged(it) else it }
        val profile = describe(id, language, scheme)
        val layout = resolveLayout(KeysConfigHelper.boundSectionForSchema(id), CustomKeyboardLayouts.forSchema(id))
        val bound = if (layout.kind == LayoutKind.HANDWRITING) profile.handwriting()
            else if (profile.mode == InputMode.HANDWRITING) profile.copy(layout = layout,
                mode = InputMode.KEYBOARD, engineProfile = EngineProfile.Rime(id))
            else if (layout == profile.layout) profile else profile.copy(layout = layout)
        return if (arranged) arranged(bound) else bound
    }

    /** A combination only exists when a corresponding installed entry exists. Never invent a schema ID. */
    fun matching(entries: List<SchemaInfo>, language: InputLanguage, scheme: InputScheme,
        layoutId: String): List<SchemaInfo> = entries.filter {
        val profile = it.profile
        profile.language == language && profile.scheme == scheme && profile.layout.id == layoutId
    }
}
