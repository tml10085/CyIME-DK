package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 角标都是固定字符，印什么上屏什么，不随全角／半角转换（只有空格两侧的逗号句号跟随）。
 * 中文：上角标是数字和常用全角符号，下角标是半角符号；英文上角标同位置换半角，下角标放补充符号。
 */
internal object QwertySwipeSymbols {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val keys = rows.joinToString("")
    /**
     * 丹麦语 29 键（和 xime.yaml 的 qwerty_danish 同排列）：a–z 和英文26键共用角标，
     * 26 格之外的 å æ ø 没有可沿用的位置，角标单独存在英文那一套里，默认空着（「按键符号」的「丹麦语29键」页改）。
     */
    val danishRows = listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm")
    val extraKeys = listOf("å", "æ", "ø")
    /** 存得下角标的键：中文只有 26 键，英文另带 [extraKeys]。 */
    private fun storedKeys(english: Boolean) = keys.map(Char::toString) + if (english) extraKeys else emptyList()
    private fun preset(vararg values: String) = keys.map(Char::toString).zip(values.toList()).toMap()
    // 用户 11-10 定：用他手机上存的「new」这一套作中文默认。
    val up = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "｀", "～", "…", "—", "（", "）", "【", "】", "｜",
        "！", "？", "：", "；", "《", "》", "、")
    val down = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "`", "~", ".", "_", "'", "\"", "[", "]", "|",
        "-", "=", ":", ";", "<", ">", "/")
    // 英文上角标和中文同一位置，换成对应的半角（没有半角的 … · ¥ 照旧）：中文上滑哪个，英文同一个键就是它的半角。
    val englishUp = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "`", "~", "…", "-", "(", ")", "[", "]", "|",
        "!", "?", ":", ";", "<", ">", "\\")
    // 英文下角标作补充：中文下角标里不和上角标重复的留在原位，其余换成不常用符号（用户 10-19 定）。
    val englishDown = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "≈", "+", "_", "'", "=", "|", "{", "}", "/",
        "\"", "`", "×", "÷", "≤", "≥", "°")
    // 开了「数字行」：数字和 ! @ # … 都在数字行上（和电脑键帽一样，见 numberRowShiftSymbol），第一排 Q 到 P 的上下 20 格空出来。
    // 用户 11-11「开启数字行多了一堆乱七八糟的符号」：只放中文第二、三排没有的半角符号，其余留空；第二、三排不变。
    private fun firstRow(vararg values: String) = rows[0].map(Char::toString).zip(values.toList()).toMap()
    // 中文缺的只有 + \ { } ?：位置照 11-01 起的不动（Q 下 +，I 下 \，O P 下 { }，P 上 ?）。
    val numberRowUp = firstRow("", "", "", "", "", "", "", "", "", "?")
    val numberRowDown = firstRow("+", "", "", "", "", "", "", "\\", "{", "}")
    // 英文第二、三排已经有 ? \ + { }，第一排全空。
    val numberRowEnglishUp = firstRow("", "", "", "", "", "", "", "", "", "")
    val numberRowEnglishDown = numberRowEnglishUp
    // 11-01 到 11-11 的数字行预设（11-10 前 P 上是 ※、I 下是 |）：载入的版本或旧修改里存着的这些格子算预设，换成上面这套；
    // 自己改成别的符号的格子照旧。
    private val oldNumberRowUp = listOf(firstRow("「", "」", "『", "』", "℃", "←", "→", "√", "★", "?"),
        firstRow("「", "」", "『", "』", "℃", "←", "→", "√", "★", "※"))
    private val oldNumberRowDown = listOf(firstRow("+", "×", "÷", "≈", "≤", "≥", "°", "\\", "{", "}"),
        firstRow("+", "×", "÷", "≈", "≤", "≥", "°", "|", "{", "}"))
    private val oldNumberRowEnglishDown = listOf(firstRow("€", "£", "±", "≠", "∞", "©", "®", "™", "§", "‰"))
    private fun oldNumberRow(direction: String, english: Boolean, key: String, saved: String?): Boolean = saved != null &&
        (if (direction == "up") oldNumberRowUp else if (english) oldNumberRowEnglishDown else oldNumberRowDown).any { it[key] == saved }
    fun up(english: Boolean, numberRow: Boolean = false) =
        (if (english) englishUp else up) + (if (!numberRow) emptyMap<String, String>() else if (english) numberRowEnglishUp else numberRowUp)
    fun down(english: Boolean, numberRow: Boolean = false) =
        (if (english) englishDown else down) + (if (!numberRow) emptyMap<String, String>() else if (english) numberRowEnglishDown else numberRowDown)
    // 2026-10-12 至 10-16 的预设：首次换成固定字符预设时，把当时看到的中文角标另存一份。
    private val v1012Up = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "`", "=", "…", "—", "√", "、", "[", "]", "\\",
        "'", "\"", ":", ";", "<", ">", "?")
    private val v1012Down = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "~", "¥", "·", "_", "°", "！", "{", "}", "|",
        "+", "-", "×", "÷", ",", ".", "/")
    // 2026-10-12 之前的预设，只用于把升级前看到的布局另存为「旧布局」。
    private val legacyUp = keys.map(Char::toString).zip(listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "@", "-", "*", "_", "[", "]", "(", ")", "\\",
        "'", "\"", ":", ";", "!", "?", "/")).toMap()
    private val legacyDown = keys.map(Char::toString).zip(listOf(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "`", "~", "·", "—", "…", "°", "{", "}", "|",
        "=", "+", "-", "×", "÷", "<", ">")).toMap()
    private fun prefix(english: Boolean) = "qwerty_symbols_${if (english) "en" else "zh"}_"

    const val LEGACY_VERSION = "旧布局"
    private const val VERSIONS_KEY = "qwerty_symbol_versions"
    /** 英文26键自己的版本表，和中文分开存，两边的版本列表互不混入。 */
    private const val ENGLISH_VERSIONS_KEY = "qwerty_symbol_versions_en"
    private fun versionsKey(english: Boolean) = if (english) ENGLISH_VERSIONS_KEY else VERSIONS_KEY
    private const val LEGACY_SAVED_KEY = "qwerty_symbol_versions_legacy_saved"
    const val V1012_VERSION = "10-12布局"
    private const val FIXED_PRESET_KEY = "qwerty_symbol_fixed_preset_adopted"
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'
    /** 一个版本的 52 个位置：先全部上方，再全部下方。 */
    val slotIds: List<String> get() = listOf("up", "down").flatMap { direction -> keys.map { "$direction.$it" } }

    /** 用户另存的整套角标，按保存顺序；[english] 为 true 时是英文26键的版本。 */
    fun versions(context: Context, english: Boolean = false): Map<String, Map<String, String>> {
        val raw = SettingsPreferences.getPrefsPublic(context).getString(versionsKey(english), null).orEmpty()
        val ids = slotIds
        return raw.split(RECORD).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size != ids.size + 1 || fields[0].isEmpty()) null
            else fields[0] to ids.zip(fields.drop(1)).toMap()
        }.toMap(LinkedHashMap<String, Map<String, String>>())
    }

    fun saveVersion(context: Context, name: String, values: Map<String, String>, english: Boolean = false) =
        writeVersions(context, versions(context, english) + (versionName(name) to values), english)

    fun deleteVersion(context: Context, name: String, english: Boolean = false) =
        writeVersions(context, versions(context, english) - name, english)

    fun versionName(name: String) = name.filter { it != RECORD && it != FIELD }.trim()

    private fun writeVersions(context: Context, all: Map<String, Map<String, String>>, english: Boolean = false) {
        val ids = slotIds
        val raw = all.entries.joinToString(RECORD.toString()) { (name, values) ->
            (listOf(name) + ids.map { values[it].orEmpty().filter { c -> c != RECORD && c != FIELD } })
                .joinToString(FIELD.toString())
        }
        check(SettingsPreferences.getPrefsPublic(context).edit().putString(versionsKey(english), raw).commit()) { "无法保存布局版本" }
    }

    /**
     * 升级到新预设时只执行一次：把升级前实际看到的中文角标（用户修改 > 自定义 YAML > 旧预设）
     * 另存为「旧布局」，再清掉中文修改让新预设生效。旧布局随时可在符号设置页载入。
     */
    fun preserveLegacyLayout(context: Context, custom: Map<String, KeyGestureConfig>) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(LEGACY_SAVED_KEY, false)) return
        val overrides = load(context, false)
        fun configured(def: GestureDef?) = def?.let { it.value.ifEmpty { it.label } }
        val legacy = buildMap {
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                put("up.$key", overrides["up.$key"] ?: configured(custom[key]?.swipeUp) ?: legacyUp.getValue(key))
                put("down.$key", overrides["down.$key"] ?: configured(custom[key]?.swipeDown) ?: legacyDown.getValue(key))
            }
        }
        if (LEGACY_VERSION !in versions(context)) saveVersion(context, LEGACY_VERSION, legacy)
        val edit = prefs.edit()
        for (id in slotIds) edit.remove(prefix(false) + id)
        check(edit.putBoolean(LEGACY_SAVED_KEY, true).commit()) { "无法保存旧布局" }
    }

    /**
     * 换成固定字符预设时只执行一次：把当时看到的中文角标另存为「10-12布局」，
     * 再清掉中文和英文的修改，让新的中英预设生效。
     */
    fun adoptFixedPreset(context: Context, custom: Map<String, KeyGestureConfig>) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(FIXED_PRESET_KEY, false)) return
        val overrides = load(context, false)
        fun configured(def: GestureDef?) = def?.let { it.value.ifEmpty { it.label } }
        val previous = buildMap {
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                put("up.$key", overrides["up.$key"] ?: configured(custom[key]?.swipeUp) ?: v1012Up.getValue(key))
                put("down.$key", overrides["down.$key"] ?: configured(custom[key]?.swipeDown) ?: v1012Down.getValue(key))
            }
        }
        if (V1012_VERSION !in versions(context)) saveVersion(context, V1012_VERSION, previous)
        val edit = prefs.edit()
        for (id in slotIds) { edit.remove(prefix(false) + id); edit.remove(prefix(true) + id) }
        check(edit.putBoolean(FIXED_PRESET_KEY, true).commit()) { "无法保存10-12布局" }
    }

    fun load(context: Context, english: Boolean): Map<String, String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val prefix = prefix(english)
        return buildMap {
            for (key in storedKeys(english)) for (direction in listOf("up", "down")) {
                val id = "$direction.$key"
                if (prefs.contains(prefix + id)) put(id, prefs.getString(prefix + id, "").orEmpty())
            }
        }
    }

    fun save(context: Context, english: Boolean, values: Map<String, String>, swipes: Map<String, String>? = null) {
        val edit = SettingsPreferences.getPrefsPublic(context).edit()
        val prefix = prefix(english)
        for (key in storedKeys(english)) for (direction in listOf("up", "down")) {
            val id = "$direction.$key"
            val value = values[id]
            if (value == null) edit.remove(prefix + id) else edit.putString(prefix + id, value)
        }
        if (swipes != null) edit.putStringSet(LetterSwipeBindings.key(english),
            swipes.map { (slot, value) -> "$slot\t$value" }.toSet())
        check(edit.commit()) { "无法保存符号设置" }
        KeysConfigHelper.loadConfig(context)
    }

    fun apply(key: String, original: KeyGestureConfig?, overrides: Map<String, String>,
        custom: KeyGestureConfig?, english: Boolean = false, numberRow: Boolean = false): KeyGestureConfig? {
        // å æ ø 只在英文那一套里有角标（预设为空）；其余 26 格之外的键（ñ、й…）照旧只用键盘配置。
        if (key !in up && !(english && key in extraKeys)) return original
        fun gesture(direction: String, preset: String, configured: GestureDef?): GestureDef {
            // 开着数字行时，第一排存着的数字或 ! @ # …（「恢复预设」或载入版本时写进去的旧预设）和 11-11 以前的数字行预设算预设，
            // 换成数字行这一套。
            val saved = overrides["$direction.$key"]
            val legacyFirstRow = numberRow && key in numberRowUp &&
                (saved == (if (direction == "up") up(english) else down(english))[key] || oldNumberRow(direction, english, key, saved))
            val override = if (legacyFirstRow) preset else saved
            if (override == null && configured != null) return configured
            val value = override ?: preset
            return GestureDef(label = value, value = value,
                display = if (direction == "up") original?.swipeUp?.display ?: DisplayMode.BOTH else DisplayMode.BOTH,
                action = if (value.isEmpty()) com.kingzcheung.xime.keyboard.GestureAction.NONE
                    else com.kingzcheung.xime.keyboard.GestureAction.COMMIT)
        }
        return (original ?: KeyGestureConfig()).copy(
            swipeUp = gesture("up", up(english, numberRow)[key].orEmpty(), custom?.swipeUp ?: original?.swipeUp.takeIf { key in extraKeys }),
            swipeDown = gesture("down", down(english, numberRow)[key].orEmpty(), custom?.swipeDown ?: original?.swipeDown.takeIf { key in extraKeys }))
    }
}
