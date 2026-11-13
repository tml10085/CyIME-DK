package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.GestureAction
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ── 键盘手势配置 ──

enum class DisplayMode(val value: String) {
    KEY("key"), BUBBLE("bubble"), BOTH("both");

    companion object {
        fun fromValue(value: String): DisplayMode = entries.firstOrNull { it.value == value } ?: BOTH
    }
}

/** 按键布局模式，从 xime.yaml keyboard.button_layout 加载。 */
enum class ButtonLayout(val value: String) {
    STANDARD("standard"),
    COMPACT("compact");

    companion object {
        fun fromValue(value: String): ButtonLayout =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

data class GestureDef(
    val label: String = "",
    /** 多行标签（YAML 数组格式），每行作为独立字符串，用于多行显示 */
    val labels: List<String> = emptyList(),
    val action: GestureAction? = GestureAction.COMMIT,
    val value: String = "",
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
)

/**
 * 把滑动手势定义转换为布局层回调（九键/笔画布局共用，26 键在 KeyboardLayout 内联处理）。
 *
 * - COMMIT 走 [onCommitText]：落点由布局决定（九键直接上屏数字——T9 模式 onKeyPress(数字)
 *   会进拼音数字码组合，笔画则沿用按键路由保持原语义）；
 * - NONE 视为未绑定；
 * - 其余动作（复制/粘贴/光标移动/面板切换等）走 [onGestureAction]（UI 拦截层 → GestureAction.execute）。
 */
internal fun swipeHandlerFor(
    def: GestureDef?,
    onCommitText: (String) -> Unit,
    onGestureAction: ((GestureAction, String) -> Unit)?,
): (() -> Unit)? {
    val action = def?.action ?: return null
    if (action == GestureAction.NONE) return null
    val value = def.value.ifEmpty { def.label }
    return {
        if (action == GestureAction.COMMIT) onCommitText(value)
        else onGestureAction?.invoke(action, value)
    }
}

data class LongPressConfig(
    val display: String = "key", // "key"（默认）显示在按键上, "bubble" 气泡弹出
    val values: List<GestureDef> = emptyList(),
)

data class KeyGestureConfig(
    val tap: GestureDef? = null,
    val swipeUp: GestureDef? = null,
    val swipeDown: GestureDef? = null,
    val longPress: LongPressConfig? = null,
)

data class KeyboardConfig(
    val keys: Map<String, KeyGestureConfig> = emptyMap(),
)

/**
 * 键盘行布局配置，从 xime.yaml keyboard.<section>.layout 加载。
 * rows[i] 为第 i 行的按键 ID 列表。
 */
data class KeyboardLayoutConfig(
    val rows: List<List<String>> = emptyList(),
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

/**
 * 单个键盘的间距覆盖配置。
 */
data class KeyboardSpacingConfig(
    val spacingX: Float? = null,
    val spacingY: Float? = null,
)

/**
 * 键盘按键配置，从 xime.yaml keyboard.key 加载。
 */
data class KeyboardKeyConfig(
    val cornerRadius: Int = 8,
    /** 按键横向间距（dp），未配置时各布局使用自身默认值 */
    val spacingX: Float? = null,
    /** 按键竖向间距（dp），未配置时各布局使用自身默认值 */
    val spacingY: Float? = null,
    /** 各键盘单独覆盖的间距，key 为键盘名（qwerty/t9/number/stroke/symbol） */
    val spacingOverrides: Map<String, KeyboardSpacingConfig> = emptyMap(),
) {
    /**
     * 获取指定键盘的有效间距（覆盖值优先，回退全局值）。
     * @return Pair(横向, 竖向)，null 表示未配置
     */
    fun spacingFor(keyboard: String): Pair<Float?, Float?> {
        val override = spacingOverrides[keyboard]
        val sx = override?.spacingX ?: spacingX
        val sy = override?.spacingY ?: spacingY
        return sx to sy
    }
}

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
/** 全局键盘颜色后备，背景色已由 color_schemes 的 keyboard_background 接管。 */
data class KeyboardColorsConfig(
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
) {
    companion object {
        /** 键盘背景后备色，仅当 color_schemes 无 keyboard_background 时使用。 */
        const val FALLBACK_BG_LIGHT: Long = 0xE3E4E8
        const val FALLBACK_BG_DARK: Long = 0x202020
    }
}

/**
 * 从 YAML node 解析 KeyboardConfig。
 *
 * 支持两种格式：
 * - 字符串 `"q"` → 等价于 GestureDef(label="q", action="commit", value="q")
 * - 对象 `{ label: "复制", action: "copy" }` → 完整定义
 */
private fun parseKeyboardConfig(raw: com.charleskorn.kaml.YamlMap?): KeyboardConfig? {
    if (raw == null) return null
    val keysNode = raw["keys"] as? com.charleskorn.kaml.YamlMap ?: return KeyboardConfig()
    val keys = mutableMapOf<String, KeyGestureConfig>()
    for ((keyNode, valueNode) in keysNode.entries) {
        val key = (keyNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        val gestureMap = valueNode as? com.charleskorn.kaml.YamlMap ?: continue
        keys[key] = parseKeyGestureConfig(gestureMap)
    }
    return KeyboardConfig(keys)
}

private fun parseKeyGestureConfig(map: com.charleskorn.kaml.YamlMap): KeyGestureConfig {
    var tap: GestureDef? = null
    var swipeUp: GestureDef? = null
    var swipeDown: GestureDef? = null
    var longPress: LongPressConfig? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseGestureNode(vNode)
            "swipe_up" -> swipeUp = parseGestureNode(vNode)
            "swipe_down" -> swipeDown = parseGestureNode(vNode)
            "long_press" -> longPress = parseLongPress(vNode)
        }
    }
    return KeyGestureConfig(tap, swipeUp, swipeDown, longPress)
}

/**
 * 解析 long_press，支持两种格式：
 *   新格式（推荐）：{ display: "bubble", values: ["q", "Q"] }
 *   旧格式（兼容）：["q", "Q"]
 */
private fun parseLongPress(node: com.charleskorn.kaml.YamlNode): LongPressConfig? {
    // 旧格式：纯数组 → 默认 display="key"
    if (node is YamlList) {
        val values = node.items.map { parseGestureNode(it) }.take(10)
        return LongPressConfig(display = "key", values = values)
    }
    // 新格式：对象 { display, values }
    if (node is com.charleskorn.kaml.YamlMap) {
        var display = "key"
        var values: List<GestureDef> = emptyList()
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "display" -> display = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: "key"
                "values" -> if (v is YamlList) values = v.items.map { parseGestureNode(it) }.take(10)
            }
        }
        return LongPressConfig(display = display, values = values)
    }
    return null
}

private fun parseGestureNode(node: com.charleskorn.kaml.YamlNode): GestureDef {
    // 字符串 → commit
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        val icon = if (text.startsWith("@")) text.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else text
        return GestureDef(label = cleanLabel, action = GestureAction.COMMIT, value = text, icon = icon)
    }
    // 映射 → 完整定义
    if (node is com.charleskorn.kaml.YamlMap) {
        var label = ""
        var labels: List<String> = emptyList()
        var action: GestureAction? = GestureAction.COMMIT
        var value = ""
        var display = "key"
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        labels = v.items.mapNotNull { (it as? YamlScalar)?.content }
                        label = labels.joinToString("\n")
                    } else {
                        val vStr = (v as? YamlScalar)?.content ?: continue
                        label = vStr
                    }
                }
                "action" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    if (vStr == "null") {
                        action = null
                    } else {
                        action = GestureAction.fromValue(vStr)
                        if (action == null) {
                            Log.w("KeysConfigHelper", "手势配置包含未知 action: \"$vStr\"，该 tap/滑动将不生效")
                        }
                    }
                }
                "value" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    value = vStr
                }
                "display" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    display = vStr
                }
            }
        }
        val icon = if (label.startsWith("@")) label.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else label
        return GestureDef(label = cleanLabel, labels = labels, action = action, value = value, icon = icon, display = DisplayMode.fromValue(display))
    }
    return GestureDef()
}

// ── 原有配置类 ──

/**
 * 背景配置，支持纯色/渐变/图片三种类型。
 *
 * YAML 示例：
 * ```yaml
 * # 纯色
 * keyboard_background:
 *   type: solid
 *   color: 0x8F73E2
 *   color_dark: 0x4A3F7A   # 可选，不指定则自动暗化
 *
 * # 渐变
 * keyboard_background:
 *   type: gradient
 *   colors: [0x8F73E2, 0xE8DEF8]        # 亮色渐变断点
 *   colors_dark: [0x4A3F7A, 0x2D2040]    # 暗色渐变断点（可选）
 *   angle: 135  # 角度制，0=左→右，90=下→上，180=右→左，270=上→下
 *
 * # 图片
 * keyboard_background:
 *   type: image
 *   src: "themes/lavender_bg.png"          # 相对于 assets/
 *   src_dark: "themes/lavender_bg_dark.png" # 暗色变体（可选）
 *   fit: cover  # cover | contain | fill | fit_width | fit_height | none
 * ```
 */
@Serializable
data class BackgroundConfig(
    val type: String = "solid",
    // solid
    val color: Long? = null,
    @SerialName("color_dark")
    val colorDark: Long? = null,
    // gradient
    val colors: List<Long>? = null,
    @SerialName("colors_dark")
    val colorsDark: List<Long>? = null,
    val angle: Int? = null,
    // image
    val src: String? = null,
    @SerialName("src_dark")
    val srcDark: String? = null,
    val fit: String? = null,
    /** 图片在 [fit] 的基准大小上再放大的倍数（cover 至少 1，contain 可小于 1），未配置为 1。 */
    val zoom: Float? = null,
    /** 图片在可移动范围里的横向位置：0 左边对齐，1 右边对齐，未配置居中。 */
    @SerialName("align_x")
    val alignX: Float? = null,
    /** 纵向位置：0 上边对齐，1 下边对齐，未配置居中。 */
    @SerialName("align_y")
    val alignY: Float? = null,
    /** 图片背景遮罩：半透明黑色覆盖层降低背景亮度（0~1，如 0.35 表示压暗 35%）。 */
    @SerialName("overlay_alpha")
    val overlayAlpha: Float? = null,
    /** 暗色模式下的遮罩强度，未配置时沿用 [overlayAlpha]。 */
    @SerialName("overlay_alpha_dark")
    val overlayAlphaDark: Float? = null,
    /** 遮罩颜色（0xRRGGBB），未配置时为黑色；浅色主题用同色调浅色遮罩提亮图片。 */
    @SerialName("overlay_color")
    val overlayColor: Long? = null,
    /** 暗色模式下的遮罩颜色，未配置时沿用 [overlayColor]。 */
    @SerialName("overlay_color_dark")
    val overlayColorDark: Long? = null,
)

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("key_bg_color_dark")
    val keyBgColorDark: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("special_key_bg_color_dark")
    val specialKeyBgColorDark: Long? = null,
    @SerialName("enter_key_bg_color")
    val enterKeyBgColor: Long? = null,
    @SerialName("enter_key_bg_color_dark")
    val enterKeyBgColorDark: Long? = null,
    @SerialName("special_key_text_color")
    val specialKeyTextColor: Long? = null,
    @SerialName("special_key_text_color_dark")
    val specialKeyTextColorDark: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("key_text_color_dark")
    val keyTextColorDark: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
    @SerialName("candidate_text_color_dark")
    val candidateTextColorDark: Long? = null,
    @SerialName("candidate_selected_text_color")
    val candidateSelectedTextColor: Long? = null,
    @SerialName("candidate_selected_text_color_dark")
    val candidateSelectedTextColorDark: Long? = null,
    @SerialName("keyboard_background")
    val keyboardBackground: BackgroundConfig? = null,
    @SerialName("key_background")
    val keyBackground: BackgroundConfig? = null,
    @SerialName("candidate_bar_background")
    val candidateBarBackground: BackgroundConfig? = null,
    /** Material You 动态配色：忽略静态颜色字段，运行时从壁纸调色板取色（仅 Android 12+）。 */
    @SerialName("dynamic_color")
    val dynamicColor: Boolean = false,
)

@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String = "CyIME",
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("config_version")
    val configVersion: Int = 1,
    @SerialName("generator")
    val generator: String = "",
    @SerialName("modified_time")
    val modifiedTime: String = "",
)

@Serializable
data class ColorSchemeModeConfig(
    @SerialName("light")
    val light: String? = null,
    @SerialName("dark")
    val dark: String? = null,
)

/** 兼容 color_scheme 的两种写法：对象（{light, dark}）或标量字符串。 */
@OptIn(ExperimentalSerializationApi::class)
object ColorSchemeModeConfigSerializer :
    YamlContentPolymorphicSerializer<ColorSchemeModeConfig>(ColorSchemeModeConfig::class) {

    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<ColorSchemeModeConfig> {
        // 标量写法（config generator 输出）：color_scheme: mu_shan_zi → light 使用该值。
        return if (node is YamlScalar) ScalarColorSchemeDeserializer else ColorSchemeModeConfig.serializer()
    }

    /** 把单个字符串的 color_scheme 反序列化为 light=该字符串。 */
    private object ScalarColorSchemeDeserializer : KSerializer<ColorSchemeModeConfig> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ScalarColorScheme", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ColorSchemeModeConfig =
            ColorSchemeModeConfig(light = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: ColorSchemeModeConfig) {
            encoder.encodeString(value.light ?: "")
        }
    }
}

/** 字体配置，从 xime.yaml keyboard.fonts 节点加载。字体文件放在 rime/fonts/ 目录下。 */
data class KeyboardFontConfig(
    val keyFont: String = "",
    val keyLabelFont: String = "",
    val candidateFont: String = "",
    val commentFont: String = "",
)

/**
 * 九键（T9）键盘配置，从 xime.yaml keyboard.t9 加载。
 */
data class KeyboardT9Config(
    /** 左侧快捷符号栏（空闲态），列表长度不限，超过 4 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 字母键盘的对齐参数，从 xime.yaml / xime.custom.yaml keyboard.<section>.geometry 读（字段级 custom → 内置）。
 * 都不写时为 null，键盘照旧：标准排列按字母格自动算，其余第二行缩进 16dp、Shift 和删除键各 1.4 份。
 * 这个方案用应用内自定义布局时同样生效。
 */
data class LetterGeometryOverride(
    /** 第二行左右各缩进几格；一格 = 第一行一个格子宽（合并键按字母数占格时是一个字母宽，否则是一个键宽）。 */
    val middleRowInset: Float? = null,
    /** 删除键在第三行占的份数，和 Shift、底排字母区按份数分这一行。 */
    val deleteKeyWeight: Float? = null,
    /** shift_key.enabled：false 时第三行不放 Shift，字母区和删除键分这一行。 */
    val shiftKeyEnabled: Boolean? = null,
    /** shift_key.weight：Shift 占的份数，范围同删除键。 */
    val shiftKeyWeight: Float? = null,
) {
    val isEmpty: Boolean get() =
        middleRowInset == null && deleteKeyWeight == null && shiftKeyEnabled == null && shiftKeyWeight == null

    companion object {
        const val MAX_MIDDLE_ROW_INSET = 3f
        const val MIN_DELETE_KEY_WEIGHT = 0.2f
        const val MAX_DELETE_KEY_WEIGHT = 5f
    }
}

/**
 * 笔画键盘配置，从 xime.yaml keyboard.stroke 加载。
 */
data class KeyboardStrokeConfig(
    /** 左侧快捷符号列，列表长度不限，超过 3 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 部分配置：仅包含 YAML 中显式配置的字段，null = 未配置。
 * 用于 custom → builtIn → 代码默认值的字段级一路 fallback 合并。
 */
internal data class KeyboardFontPartial(
    val keyFont: String? = null,
    val keyLabelFont: String? = null,
    val candidateFont: String? = null,
    val commentFont: String? = null,
)

internal data class KeyboardColorsPartial(
    val keyBgColor: Long? = null,
    val keyBgColorDark: Long? = null,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long? = null,
    val keyTextColorDark: Long? = null,
    val candidateTextColor: Long? = null,
    val candidateTextColorDark: Long? = null,
)

internal data class KeyboardShadowPartial(
    val enabled: Boolean? = null,
    val elevation: Int? = null,
    val shapeRadius: Int? = null,
)

internal data class KeyboardKeyPartial(
    val cornerRadius: Int? = null,
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    val spacingOverrides: Map<String, KeyboardSpacingConfig>? = null,
)

internal data class KeyboardT9Partial(
    val sideSymbols: List<String>? = null,
)

internal data class KeyboardStrokePartial(
    val sideSymbols: List<String>? = null,
)

/** 字段级一路 fallback：custom → builtIn → 代码默认值。 */
internal inline fun <T> tiered(custom: T?, builtIn: T?, default: T): T = custom ?: builtIn ?: default

/** 字符串字段的 fallback：空白视为未配置，路由到下一级。 */
internal fun tieredText(custom: String?, builtIn: String?, default: String): String =
    custom?.takeIf { it.isNotBlank() }
        ?: builtIn?.takeIf { it.isNotBlank() }
        ?: default

/**
 * kaml 0.104 的 [YamlMap.get] 是 reified 泛型取值，值类型与期望类型不符时抛
 * IncorrectTypeException（如 `fonts:` 段整体被注释时值为 YamlNull，`["fonts"] as? YamlMap`
 * 会在 get 内部直接抛异常，`as?` 无机会返回 null）。
 * 统一经 YamlNode 取值后再做真正的安全转换：段缺失/为 null/类型不符时返回 null 而非抛异常。
 */
private inline fun <reified T : YamlNode> YamlMap.opt(key: String): T? =
    get<YamlNode>(key) as? T

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    @Serializable(with = ColorSchemeModeConfigSerializer::class)
    val colorScheme: ColorSchemeModeConfig? = null,
    @SerialName("dark_mode")
    val darkMode: Int? = null,
    @SerialName("key_font")
    val keyFont: String? = null,
    @SerialName("key_label_font")
    val keyLabelFont: String? = null,
    @SerialName("candidate_font")
    val candidateFont: String? = null,
    @SerialName("comment_font")
    val commentFont: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("metadata")
    val metadata: MetadataConfig? = null,
)

/**
 * CyIME 自己的商店目录（仓库 store/），与 assets/xime.yaml 的 xime_index.base_urls 保持一致。
 * 顺序只是登记顺序，实际按地区排（[StoreMirrors.orderedBases]）：国内先魔搭，海外先 jsDelivr/GitHub。
 */
internal val CYIME_STORE_BASE_URLS = listOf(
    "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/store/",
    "https://cdn.jsdelivr.net/gh/Cyletix/CyIME@main/store/",
    "https://fastly.jsdelivr.net/gh/Cyletix/CyIME@main/store/",
    "https://raw.githubusercontent.com/Cyletix/CyIME/main/store/",
)

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = CYIME_STORE_BASE_URLS
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    /** 九键左侧快捷符号栏内置默认值：最常用的中文标点，按使用频率排，括号只留一对（用户 10-31）。 */
    /** 都取自常用符号面板（commonSymbolsFor 中文）；超过 4 个时侧栏可滑动。 */
    val DEFAULT_T9_SIDE_SYMBOLS: List<String> = listOf("，", "。", "？", "！", "…", "～", "、", "：",
        "“", "”", "；", "（", "）")

    /** 笔画键盘左侧符号列内置默认（与历史硬编码一致）。 */
    val DEFAULT_STROKE_SIDE_SYMBOLS: List<String> = listOf("。", "？", "！", "~")
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 手势配置缓存（mutableStateOf 让 Compose 直接观察变更）
    // 中文键盘（qwerty）手势配置缓存
    private val _keyGestureConfig = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfig: Map<String, KeyGestureConfig> get() = _keyGestureConfig.value
    
    // 英文键盘（qwerty_en）手势配置缓存
    private val _keyGestureConfigEn = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfigEn: Map<String, KeyGestureConfig> get() = _keyGestureConfigEn.value
    private var _keyGestureConfigEnBase: Map<String, KeyGestureConfig> = emptyMap()
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()

    // 键盘按键配置缓存
    private var keyboardKeyConfig: KeyboardKeyConfig = KeyboardKeyConfig()

    // 九键（T9）键盘配置缓存
    private var keyboardT9Config: KeyboardT9Config = KeyboardT9Config(sideSymbols = DEFAULT_T9_SIDE_SYMBOLS)
    private var keyboardStrokeConfig: KeyboardStrokeConfig = KeyboardStrokeConfig(sideSymbols = DEFAULT_STROKE_SIDE_SYMBOLS)

    // 字体配置缓存
    private var keyboardFontConfig: KeyboardFontConfig = KeyboardFontConfig()
    fun getKeyboardFontConfig(): KeyboardFontConfig = keyboardFontConfig
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        if (isAsciiMode) _buttonLayoutEn else _buttonLayoutZh

    // 键盘行布局默认值
    private val DEFAULT_ZH_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    private val DEFAULT_EN_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    // 键盘行布局缓存（中文 / 英文）
    private var _zhRows: List<List<String>> = DEFAULT_ZH_ROWS
    private var _enRows: List<List<String>> = DEFAULT_EN_ROWS
    private var _enRowsBase: List<List<String>> = DEFAULT_EN_ROWS

    // 标准 26 键的基线缓存（合并键布局切换退出时恢复）
    private var _zhRowsBase: List<List<String>> = DEFAULT_ZH_ROWS
    private var _keyGestureConfigZhBase: Map<String, KeyGestureConfig> = emptyMap()

    // 合并键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 行布局 / 手势配置
    private var _mergedRows: Map<String, List<List<String>>> = emptyMap()
    private var _mergedGestureConfigs: Map<String, Map<String, KeyGestureConfig>> = emptyMap()
    // 字母布局 section → 对齐参数（keyboard.<section>.geometry），只存写了的 section
    private var _sectionGeometry: Map<String, LetterGeometryOverride> = emptyMap()
    private var _activeMergedSection: String? = null
    private var _appliedLayoutSchemaId: String? = null
    private var _activeSchemaId: String = ""

    // 合并键绑定缓存：schemaId → 键盘 section（xime.yaml keyboard.<section>.schemas 声明）
    private var _schemaSectionBindings: Map<String, String> = emptyMap()
    @Volatile internal var hasLoadedLayoutBindings: Boolean = false
        private set

    /**
     * 代码布局 section：由专属组件渲染（T9KeyboardLayout / StrokeKeyboardLayout /
     * HandwritingKeyboardLayout），无 layout.rows。schemas 绑定到这些 section 的方案
     * 走 [codeLayoutForSchema] 查询，不进入合并键行布局缓存
     * （[mergedSectionForSchema] 对其返回 null）。
     */
    internal val CODE_LAYOUT_SECTIONS = setOf("t9", "stroke", "handwriting", "japanese_kana")

    // 九键/笔画手势配置缓存（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）。
    // 键 id 不做大小写归一：九键为数字字符串 "1"~"9"，笔画为键面标签（一/丨/丿/丶/乛 等）。
    private var _t9GestureConfigs: Map<String, KeyGestureConfig> = emptyMap()
    private var _strokeGestureConfigs: Map<String, KeyGestureConfig> = emptyMap()

    /** 合并键方案（pinyin_14jian 等）对应的 xime.yaml 键盘 section，非合并键方案返回 null。 */
    internal fun mergedSectionForSchema(schemaId: String): String? =
        resolveMergedSection(schemaId, _schemaSectionBindings) ?: generatedSection(schemaId)

    /**
     * 绑定解析：仅认 schemas 声明（xime.yaml / xime.custom.yaml keyboard.<section>.schemas），
     * 未声明的方案一律全键盘（26 键），不做 id 关键字猜测。
     * 代码布局 section（t9/stroke）不是行数据布局，不作为合并键解析结果。
     */
    internal fun resolveMergedSection(schemaId: String, bindings: Map<String, String>): String? =
        bindings[schemaId]?.takeIf { it !in CODE_LAYOUT_SECTIONS }

    /** 查询方案声明的键盘 section（任意类型，含代码布局），未声明返回 null。 */
    fun boundSectionForSchema(schemaId: String): String? = _schemaSectionBindings[schemaId]
        ?: "t9".takeIf { DoublePinyinSchemes.isT9(schemaId) } ?: generatedSection(schemaId)

    /**
     * 由键位表生成的双拼 14 键版本不写进 xime.yaml，用 14 键小鹤双拼的布局；用户在 xime.custom.yaml
     * 给它声明了别的布局时以声明为准（上面先查绑定）。
     */
    private fun generatedSection(schemaId: String): String? =
        _schemaSectionBindings[DoublePinyinScheme.k14Id("double_pinyin_flypy")]?.takeIf { DoublePinyinSchemes.isK14(schemaId) }

    /** 查询方案绑定的代码布局 section（t9 九键 / stroke 笔画），未绑定返回 null。 */
    fun codeLayoutForSchema(schemaId: String): String? =
        boundSectionForSchema(schemaId)?.takeIf { it in CODE_LAYOUT_SECTIONS }

    /**
     * 仅供单测：清空方案绑定缓存。
     *
     * 绑定只在 [loadConfig] 里由 xime.yaml / xime.custom.yaml 写入，是进程级状态；
     * 其他用例（如 [CyimeResearchPresetTest]）会加载绑定，若先于"未声明即不识别"的断言执行，
     * 断言就会随用例执行顺序变红。需要"未加载绑定"场景的用例先调用本方法。
     */
    internal fun clearSchemaBindingsForTest() {
        _schemaSectionBindings = emptyMap()
        hasLoadedLayoutBindings = false
    }

    /**
     * 按当前方案切换中文行布局与手势缓存（合并键布局 ↔ 标准 26 键）。
     * 在 KeyboardViewModel.dispatch/resetKeyboard 收到 schemaId 时调用；
     * 英文键盘不受影响（合并键布局仅中文拼音方案使用）。
     */
    fun setActiveKeyboardSchema(schemaId: String) {
        _activeSchemaId = schemaId
        val section = mergedSectionForSchema(schemaId)
        if (schemaId == _appliedLayoutSchemaId && section == _activeMergedSection) return
        _appliedLayoutSchemaId = schemaId
        _activeMergedSection = section
        // 自建布局的拼音方案，或双拼在自建合并键布局上的版本：按那个布局的键和手势。
        val custom = CustomKeyboardLayouts.forSchema(schemaId)
        _zhRows = getKeyRows(schemaId, false)
        if (custom != null) {
            _keyGestureConfig.value = custom.gestures(_keyGestureConfigZhBase)
        } else if (section != null) {
            // Japanese romaji types on the arrangement chosen for Japanese; its own section keeps the swipes.
            val arranged = letterLayout(schemaId, false)
            val gestures = _mergedGestureConfigs[section] ?: emptyMap()
            _keyGestureConfig.value = arranged?.gestures(gestures) ?: gestures
        } else {
            // Double pinyin and code tables type on the arrangement chosen for them (QWJRTK, Colemak, the user's own …); pinyin has none.
            val arranged = letterLayout(schemaId, false)
            _keyGestureConfig.value = arranged?.gestures(_keyGestureConfigZhBase) ?: _keyGestureConfigZhBase
        }
    }

    /**
     * 「ing 放在 Shift 键上」（默认关，[com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.doublePinyinSemicolonOnShift]）：
     * 开着时不补第二排的分号键，L 留在最右边，分号由打字中的 Shift 键送出（KeyboardLayout）。
     */
    @Volatile private var semicolonOnShift = false

    /** 当前中文方案把分号当编码键（微软双拼的 ing）；键面据此把分号写成实际上屏的标点。 */
    fun semicolonIsCode(isAsciiMode: Boolean): Boolean =
        !isAsciiMode && DoublePinyinSchemes.find(_activeSchemaId)?.usesSemicolon == true

    /** 开关变了就重排当前键盘；没变什么都不做。 */
    fun setSemicolonOnShift(on: Boolean) {
        if (semicolonOnShift == on) return
        semicolonOnShift = on
        applyLetterLayouts()
    }

    /**
     * ing 在分号上的双拼（微软双拼）：排列里没有分号键时在第二排末尾补一个，第二排正好排满十格；
     * 排不下（第二排已满、蜂窝排列）就保持原样，换个带分号的排列再打。分号键按下时送进 Rime，由方案组成编码。
     */
    internal fun withSemicolonKey(rows: List<List<String>>): List<List<String>> =
        if (rows.size != 3 || rows.any { ";" in it } || rows[1].sumOf { it.length } != 9) rows
        else rows.mapIndexed { index, row -> if (index == 1) row + ";" else row }

    /**
     * The letter layout the keyboard is typing on: a Chinese layout schema, or the arrangement chosen
     * for English / Japanese romaji / double pinyin ([LetterLayouts]). null is the plain 26/14 keys of the section.
     */
    fun letterLayout(schemaId: String, isAsciiMode: Boolean): CustomKeyboardLayout? =
        CustomKeyboardLayouts.find(InputProfiles.current(schemaId, isAsciiMode).layout.id)

    /** Re-apply the per-language arrangements after a choice changes. */
    fun applyLetterLayouts() {
        applyEnglishLetterLayout()
        _appliedLayoutSchemaId = null
        setActiveKeyboardSchema(_activeSchemaId)
        _configVersion.value++
    }

    private fun applyEnglishLetterLayout() {
        val english = LetterLayouts.chosen(InputLanguage.ENGLISH)
        _enRows = english?.typingRows() ?: _enRowsBase
        _keyGestureConfigEn.value = english?.gestures(_keyGestureConfigEnBase) ?: _keyGestureConfigEnBase
    }

    /** 获取键盘行布局，每个子 List 为一行的按键 ID 列表，索引 0=第一行。
     *  合并键布局中合并键的 ID 为组内字母拼接（如 "qw"），渲染层按 ID 长度分配键宽。 */
    fun getKeyRows(isAsciiMode: Boolean): List<List<String>> =
        if (isAsciiMode) _enRows else _zhRows

    /**
     * 当前方案对应 YAML section 的对齐参数，没写 geometry 时为 null。
     * 方案换用应用内自建/选定的字母排列时照样生效：参数跟着方案的 section 走，不跟行从哪来。
     */
    fun letterGeometryOverride(schemaId: String, isAsciiMode: Boolean): LetterGeometryOverride? =
        if (isAsciiMode) _sectionGeometry["qwerty_en"]
        else _sectionGeometry[mergedSectionForSchema(schemaId) ?: "qwerty"]

    /** A rendered frame can arrive before the active gesture cache has switched schemes. */
    fun getKeyRows(schemaId: String, isAsciiMode: Boolean): List<List<String>> {
        if (isAsciiMode) return _enRows
        CustomKeyboardLayouts.forSchema(schemaId)?.let { return it.typingRows() }
        val arranged = letterLayout(schemaId, false)?.typingRows()
        val section = mergedSectionForSchema(schemaId)
        if (section != null) return arranged ?: _mergedRows[section] ?: DEFAULT_ZH_ROWS
        val rows = arranged ?: _zhRowsBase
        return if (!semicolonOnShift && DoublePinyinSchemes.find(schemaId)?.usesSemicolon == true)
            withSemicolonKey(rows) else rows
    }

    /** Read a thumbnail without changing the active keyboard or its gesture state. */
    fun previewKeyRows(layout: InputLayout, language: InputLanguage): List<List<String>> =
        CustomKeyboardLayouts.find(layout.id)?.typingRows() ?: when {
            layout.kind == LayoutKind.MERGED || layout.kind == LayoutKind.ZHUYIN -> _mergedRows[layout.id].orEmpty()
            // 有自己排列的字母键盘（西班牙语多一个 ñ）按它自己的行画。
            layout.kind == LayoutKind.ALPHABETIC -> _mergedRows[layout.id]
                ?: if (language == InputLanguage.ENGLISH) _enRowsBase else _zhRowsBase
            else -> emptyList()
        }
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
    // The same two YAML documents are read by many section parsers. Share their
    // immutable syntax trees only within one load; every reload sees fresh text.
    // Thread-local scope avoids retaining user configuration or mixing concurrent loads.
    private val loadYamlNodes = ThreadLocal<MutableMap<String, YamlNode>>()
    private fun keyboardYamlNode(text: String): YamlNode {
        val nodes = loadYamlNodes.get()
        return if (nodes == null) yaml.parseToYamlNode(text)
        else nodes.getOrPut(text) { yaml.parseToYamlNode(text) }
    }

    fun loadConfig(context: Context): KeysConfig {
        val previousNodes = loadYamlNodes.get()
        loadYamlNodes.set(mutableMapOf())
        try { loadXimeConfig(context) }
        finally {
            if (previousNodes == null) loadYamlNodes.remove() else loadYamlNodes.set(previousNodes)
        }
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    /**
     * 字母键盘上方开着「数字行」：26 键第一排的角标换成 [QwertySwipeSymbols.numberRowUp] 那一套。
     * 输入法读设置时（[com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read]）和 [loadConfig] 时跟着开关更新。
     */
    @Volatile var numberRowCorners: Boolean = false

    private var symbolOverridesZh: Map<String, String> = emptyMap()
    private var symbolOverridesEn: Map<String, String> = emptyMap()
    private var symbolCustomZh: Map<String, KeyGestureConfig> = emptyMap()
    private var symbolCustomEn: Map<String, KeyGestureConfig> = emptyMap()
    private var symbolOverridesK14: Map<String, String> = emptyMap()
    /** 自建双字母布局各自的角标修改，按布局 id。 */
    private var symbolOverridesCustom: Map<String, Map<String, String>> = emptyMap()

    private fun loadXimeConfig(context: Context) {
        try {
            val customLayouts = CustomKeyboardLayouts.load(context)
            LetterLayouts.load(context)
            semicolonOnShift = SettingsPreferences.getPrefsPublic(context).getBoolean(
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY, false)
            val symbolCustomText = readCustomText(context)
            symbolCustomZh = symbolCustomText?.let { parseKeyboardYamlSection(it, "qwerty") } ?: emptyMap()
            symbolCustomEn = symbolCustomText?.let { parseKeyboardYamlSection(it, "qwerty_en") } ?: emptyMap()
            runCatching { QwertySwipeSymbols.preserveLegacyLayout(context, symbolCustomZh) }
                .onFailure { Log.e(TAG, "无法保存旧布局", it) }
            runCatching { QwertySwipeSymbols.adoptFixedPreset(context, symbolCustomZh) }
                .onFailure { Log.e(TAG, "无法保存10-12布局", it) }
            numberRowCorners = SettingsPreferences.isNumberRowEnabled(context)
            symbolOverridesZh = QwertySwipeSymbols.load(context, false)
            // 英文26键有自己的半角预设，只用英文页的修改。
            symbolOverridesEn = QwertySwipeSymbols.load(context, true)
            symbolOverridesK14 = FourteenKeySymbols.load(context)
            symbolOverridesCustom = customLayouts.filter(CustomLayoutSymbols::editable)
                .associate { it.id to CustomLayoutSymbols.load(context, it) }
            // 键盘手势（从原始 YAML 手动解析）
            val parsed = parseKeyboardFromAssets(context)
            _keyGestureConfigZhBase = parsed?.first ?: emptyMap()
            _keyGestureConfigEnBase = parsed?.second ?: emptyMap()
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            // 键盘按键（从原始 YAML 手动解析）
            keyboardKeyConfig = parseKeyboardKeyFromAssets(context)
            // 九键键盘配置（从原始 YAML 手动解析）
            keyboardT9Config = parseKeyboardT9FromAssets(context)
            // 笔画键盘配置（从原始 YAML 手动解析）
            keyboardStrokeConfig = parseKeyboardStrokeFromAssets(context)
            // 字体配置（从原始 YAML 手动解析）
            keyboardFontConfig = parseKeyboardFontsFromAssets(context)
            com.kingzcheung.xime.ui.keyboard.AppFonts.loadCustomFonts(keyboardFontConfig)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 键盘行布局
            val parsedRows = parseKeyboardLayoutFromAssets(context)
            _zhRowsBase = parsedRows.first
            _enRowsBase = parsedRows.second
            applyEnglishLetterLayout()
            // 合并键绑定（keyboard.<section>.schemas，custom 覆盖 builtIn 同名方案的绑定）
            val builtInBindings = readAssetText(context, XIME_CONFIG_FILE)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            val customBindings = readCustomText(context)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            _schemaSectionBindings = builtInBindings + customBindings
            hasLoadedLayoutBindings = true
            // 合并键布局 sections：由 schemas 绑定动态发现（内置 qwerty_14/17/18 + custom 新增）；
            // 代码布局 section（t9/stroke）无行数据，走各自的专属配置解析，不在此加载
            val mergedRowsMap = mutableMapOf<String, List<List<String>>>()
            val mergedGesturesMap = mutableMapOf<String, Map<String, KeyGestureConfig>>()
            for (section in _schemaSectionBindings.values.filter { it !in CODE_LAYOUT_SECTIONS }.distinct()) {
                parseLayoutSection(context, section)?.let { mergedRowsMap[section] = it }
                mergedGesturesMap[section] = parseGesturesSection(context, section)
            }
            _mergedRows = mergedRowsMap
            _mergedGestureConfigs = mergedGesturesMap
            _sectionGeometry = parseSectionGeometries(context, mergedRowsMap.keys + listOf("qwerty", "qwerty_en"))
            // 九键/笔画手势（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）
            _t9GestureConfigs = parseGesturesSection(context, "t9")
            _strokeGestureConfigs = parseGesturesSection(context, "stroke")
            // 基线缓存已刷新，先落标准 26 键的行布局/手势，合并键方案再由
            // setActiveKeyboardSchema 覆盖。不能只依赖 setActiveKeyboardSchema：
            // 清除已应用方案标记，保证同一方案重载时也采用最新配置。
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
            // 重新应用当前方案对应的合并键布局（上面重置了基线缓存）
            _activeMergedSection = null
            _appliedLayoutSchemaId = null
            setActiveKeyboardSchema(_activeSchemaId)
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val meta = merged?.metadata
            if (meta != null && meta.appVersion.isNotBlank()) {
                if (!checkVersionConstraint(BuildConfig.VERSION_NAME, meta.appVersion)) {
                    Log.w(TAG, "Config requires app_version ${meta.appVersion}, current is ${BuildConfig.VERSION_NAME}")
                }
            }
            _configVersion.value++
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    private fun checkVersionConstraint(current: String, constraint: String): Boolean {
        val operator = when {
            constraint.startsWith(">=") -> constraint.substring(0, 2)
            constraint.startsWith("<=") -> constraint.substring(0, 2)
            constraint.startsWith(">") -> constraint.substring(0, 1)
            constraint.startsWith("<") -> constraint.substring(0, 1)
            constraint.startsWith("^") -> constraint.substring(0, 1)
            constraint.startsWith("~") -> constraint.substring(0, 1)
            else -> return true
        }
        val targetVerStr = constraint.removePrefix(operator).trim('"', '\'', ' ')
        val targetParts = targetVerStr.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (targetParts.size < 2 || currentParts.size < 2) return true
        val t = Triple(targetParts.getOrElse(0) { 0 }, targetParts.getOrElse(1) { 0 }, targetParts.getOrElse(2) { 0 })
        val c = Triple(currentParts[0], currentParts.getOrElse(1) { 0 }, currentParts.getOrElse(2) { 0 })
        val cmp = compareVersions(c, t)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">"  -> cmp > 0
            "<"  -> cmp < 0
            "^"  -> c.first == t.first && (c.first != 0 || cmp >= 0)
            "~"  -> c.first == t.first && c.second >= t.second
            else -> true
        }
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        return when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Pair<Map<String, KeyGestureConfig>, Map<String, KeyGestureConfig>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        val defaultZh = parseKeyboardYamlSection(defaultText, "qwerty") ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, "qwerty_en") ?: emptyMap()
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh: Map<String, KeyGestureConfig>?
        val customEn: Map<String, KeyGestureConfig>?
        if (userData != null) {
            customZh = parseKeyboardYamlSection(userData, "qwerty")
            customEn = parseKeyboardYamlSection(userData, "qwerty_en")
        } else {
            val assetText = readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
            customZh = assetText?.let { parseKeyboardYamlSection(it, "qwerty") }
            customEn = assetText?.let { parseKeyboardYamlSection(it, "qwerty_en") }
        }
        val zh = if (customZh != null) defaultZh + customZh else defaultZh
        val en = if (customEn != null) defaultEn + customEn else defaultEn
        return Pair(zh, en)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置（字段级一路 fallback）。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardColorsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardColorsYamlText(it) }
        return mergeColorsConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并颜色配置：custom → builtIn → 代码默认值。 */
    internal fun mergeColorsConfigs(custom: KeyboardColorsPartial?, builtIn: KeyboardColorsPartial?): KeyboardColorsConfig =
        KeyboardColorsConfig(
            keyBgColor = tiered(custom?.keyBgColor, builtIn?.keyBgColor, 0xFFFFFF),
            keyBgColorDark = tiered(custom?.keyBgColorDark, builtIn?.keyBgColorDark, 0x4A4A4A),
            specialKeyBgColor = tiered(custom?.specialKeyBgColor, builtIn?.specialKeyBgColor, null),
            specialKeyBgColorDark = tiered(custom?.specialKeyBgColorDark, builtIn?.specialKeyBgColorDark, null),
            keyTextColor = tiered(custom?.keyTextColor, builtIn?.keyTextColor, 0x202124),
            keyTextColorDark = tiered(custom?.keyTextColorDark, builtIn?.keyTextColorDark, 0xE8EAED),
            candidateTextColor = tiered(custom?.candidateTextColor, builtIn?.candidateTextColor, 0x1A73E8),
            candidateTextColorDark = tiered(custom?.candidateTextColorDark, builtIn?.candidateTextColorDark, 0x8AB4F8),
        )

    /** 从 YAML 文本中提取 keyboard.colors 段（仅显式字段非 null）。 */
    internal fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val colorsNode = keyboardNode.opt<YamlMap>("colors") ?: return null
            var kBg: Long? = null
            var kBgDark: Long? = null
            var spKeyBg: Long? = null
            var spKeyBgDark: Long? = null
            var kTxt: Long? = null
            var kTxtDark: Long? = null
            var candTxt: Long? = null
            var candTxtDark: Long? = null
            for ((kNode, vNode) in colorsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
                when (key) {
                    "key_bg_color" -> kBg = hex
                    "key_bg_color_dark" -> kBgDark = hex
                    "special_key_bg_color" -> spKeyBg = hex
                    "special_key_bg_color_dark" -> spKeyBgDark = hex
                    "key_text_color" -> kTxt = hex
                    "key_text_color_dark" -> kTxtDark = hex
                    "candidate_text_color" -> candTxt = hex
                    "candidate_text_color_dark" -> candTxtDark = hex
                }
            }
            KeyboardColorsPartial(
                keyBgColor = kBg,
                keyBgColorDark = kBgDark,
                specialKeyBgColor = spKeyBg,
                specialKeyBgColorDark = spKeyBgDark,
                keyTextColor = kTxt,
                keyTextColorDark = kTxtDark,
                candidateTextColor = candTxt,
                candidateTextColorDark = candTxtDark,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard colors config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置（字段级一路 fallback）。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardShadowYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardShadowYamlText(it) }
        return mergeShadowConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并阴影配置：custom → builtIn → 代码默认值。 */
    internal fun mergeShadowConfigs(custom: KeyboardShadowPartial?, builtIn: KeyboardShadowPartial?): KeyboardShadowConfig =
        KeyboardShadowConfig(
            enabled = tiered(custom?.enabled, builtIn?.enabled, true),
            elevation = tiered(custom?.elevation, builtIn?.elevation, 1),
            shapeRadius = tiered(custom?.shapeRadius, builtIn?.shapeRadius, 8),
        )

    /** 从 YAML 文本中提取 keyboard.shadow 段（仅显式字段非 null）。 */
    internal fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val shadowNode = keyboardNode.opt<YamlMap>("shadow") ?: return null
            var enabled: Boolean? = null
            var elevation: Int? = null
            var shapeRadius: Int? = null
            for ((kNode, vNode) in shadowNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "enabled" -> enabled = value.toBooleanStrictOrNull() ?: continue
                    "elevation" -> elevation = value.toIntOrNull() ?: continue
                    "shape_radius" -> shapeRadius = value.toIntOrNull() ?: continue
                }
            }
            KeyboardShadowPartial(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard shadow config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘按键配置（字段级一路 fallback）。 */
    private fun parseKeyboardKeyFromAssets(context: Context): KeyboardKeyConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardKeyYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardKeyYamlPartial(it) }
        return mergeKeyConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并按键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeKeyConfigs(custom: KeyboardKeyPartial?, builtIn: KeyboardKeyPartial?): KeyboardKeyConfig =
        KeyboardKeyConfig(
            cornerRadius = tiered(custom?.cornerRadius, builtIn?.cornerRadius, 8),
            spacingX = tiered(custom?.spacingX, builtIn?.spacingX, null),
            spacingY = tiered(custom?.spacingY, builtIn?.spacingY, null),
            spacingOverrides = mergeSpacingOverrides(custom?.spacingOverrides, builtIn?.spacingOverrides),
        )

    /** 按键盘名合并间距覆盖：同名项字段级 fallback（custom 非空字段覆盖 builtIn）。 */
    internal fun mergeSpacingOverrides(
        custom: Map<String, KeyboardSpacingConfig>?,
        builtIn: Map<String, KeyboardSpacingConfig>?,
    ): Map<String, KeyboardSpacingConfig> {
        if (custom == null) return builtIn ?: emptyMap()
        if (builtIn == null) return custom
        val result = LinkedHashMap<String, KeyboardSpacingConfig>(custom)
        for ((key, builtInValue) in builtIn) {
            val customValue = result[key]
            result[key] = if (customValue == null) {
                builtInValue
            } else {
                KeyboardSpacingConfig(
                    spacingX = customValue.spacingX ?: builtInValue.spacingX,
                    spacingY = customValue.spacingY ?: builtInValue.spacingY,
                )
            }
        }
        return result
    }

    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.key 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardKeyYamlPartial]。
     */
    internal fun parseKeyboardKeyYamlText(yamlText: String): KeyboardKeyConfig? {
        val partial = parseKeyboardKeyYamlPartial(yamlText) ?: return null
        return KeyboardKeyConfig(
            cornerRadius = partial.cornerRadius ?: 8,
            spacingX = partial.spacingX,
            spacingY = partial.spacingY,
            spacingOverrides = partial.spacingOverrides ?: emptyMap(),
        )
    }

    /** 从 YAML 文本中提取 keyboard.key 段（仅显式字段非 null）。 */
    private fun parseKeyboardKeyYamlPartial(yamlText: String): KeyboardKeyPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            var cornerRadius: Int? = null
            var spacingX: Float? = null
            var spacingY: Float? = null
            val spacingOverrides = mutableMapOf<String, KeyboardSpacingConfig>()
            // 优先读取 key.corner_radius
            val keyNode = keyboardNode.opt<YamlMap>("key")
            if (keyNode != null) {
                for ((kNode, vNode) in keyNode.entries) {
                    val key = (kNode as? YamlScalar)?.content ?: continue
                    if (key == "corner_radius") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        cornerRadius = value.toIntOrNull()
                    } else if (key == "spacing_x") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingX = value.toFloatOrNull()
                    } else if (key == "spacing_y") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingY = value.toFloatOrNull()
                    } else if (vNode is YamlMap) {
                        // 键盘级间距覆盖：keyboard.key.<键盘名>.spacing_x/spacing_y
                        var overrideX: Float? = null
                        var overrideY: Float? = null
                        for ((skNode, svNode) in vNode.entries) {
                            val skey = (skNode as? YamlScalar)?.content ?: continue
                            val svalue = (svNode as? YamlScalar)?.content ?: continue
                            if (skey == "spacing_x") {
                                overrideX = svalue.toFloatOrNull()
                            } else if (skey == "spacing_y") {
                                overrideY = svalue.toFloatOrNull()
                            }
                        }
                        spacingOverrides[key] = KeyboardSpacingConfig(overrideX, overrideY)
                    }
                }
            }
            // 未设置 key.corner_radius 时，回退读取 shadow.shape_radius
            if (cornerRadius == null) {
                val shadowNode = keyboardNode.opt<YamlMap>("shadow")
                if (shadowNode != null) {
                    for ((kNode, vNode) in shadowNode.entries) {
                        val key = (kNode as? YamlScalar)?.content ?: continue
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        if (key == "shape_radius") {
                            cornerRadius = value.toIntOrNull()
                        }
                    }
                }
            }
            KeyboardKeyPartial(
                cornerRadius = cornerRadius,
                spacingX = spacingX,
                spacingY = spacingY,
                spacingOverrides = spacingOverrides.ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard key config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析九键键盘配置。 */
    private fun parseKeyboardT9FromAssets(context: Context): KeyboardT9Config {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardT9YamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardT9YamlPartial(it) }
        return mergeT9Configs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并九键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeT9Configs(custom: KeyboardT9Partial?, builtIn: KeyboardT9Partial?): KeyboardT9Config =
        KeyboardT9Config(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_T9_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.t9 段（仅显式字段非 null）。 */
    internal fun parseKeyboardT9YamlPartial(yamlText: String): KeyboardT9Partial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val t9Node = keyboardNode.opt<YamlMap>("t9") ?: return null
            val sideSymbols = t9Node.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardT9Partial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard t9 config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析笔画键盘配置。 */
    private fun parseKeyboardStrokeFromAssets(context: Context): KeyboardStrokeConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardStrokeYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardStrokeYamlPartial(it) }
        return mergeStrokeConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并笔画配置：custom → builtIn → 代码默认值。 */
    internal fun mergeStrokeConfigs(custom: KeyboardStrokePartial?, builtIn: KeyboardStrokePartial?): KeyboardStrokeConfig =
        KeyboardStrokeConfig(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_STROKE_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.stroke 段（仅显式字段非 null）。 */
    internal fun parseKeyboardStrokeYamlPartial(yamlText: String): KeyboardStrokePartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val strokeNode = keyboardNode.opt<YamlMap>("stroke") ?: return null
            val sideSymbols = strokeNode.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardStrokePartial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard stroke config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析字体配置（字段级一路 fallback）。 */
    private fun parseKeyboardFontsFromAssets(context: Context): KeyboardFontConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardFontsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardFontsYamlText(it) }
        return mergeFontConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并字体配置：custom → builtIn → 代码默认值（空字符串视为未配置）。 */
    internal fun mergeFontConfigs(custom: KeyboardFontPartial?, builtIn: KeyboardFontPartial?): KeyboardFontConfig =
        KeyboardFontConfig(
            keyFont = tieredText(custom?.keyFont, builtIn?.keyFont, ""),
            keyLabelFont = tieredText(custom?.keyLabelFont, builtIn?.keyLabelFont, ""),
            candidateFont = tieredText(custom?.candidateFont, builtIn?.candidateFont, ""),
            commentFont = tieredText(custom?.commentFont, builtIn?.commentFont, ""),
        )

    /** 从 YAML 文本中提取 keyboard.fonts 字体配置段（仅显式字段非 null）。 */
    internal fun parseKeyboardFontsYamlText(yamlText: String): KeyboardFontPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val fontsNode = keyboardNode.opt<YamlMap>("fonts") ?: return null
            var keyFont: String? = null
            var keyLabelFont: String? = null
            var candidateFont: String? = null
            var commentFont: String? = null
            for ((kNode, vNode) in fontsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "key_font" -> keyFont = value
                    "key_label_font" -> keyLabelFont = value
                    "candidate_font" -> candidateFont = value
                    "comment_font" -> commentFont = value
                }
            }
            KeyboardFontPartial(
                keyFont = keyFont,
                keyLabelFont = keyLabelFont,
                candidateFont = candidateFont,
                commentFont = commentFont,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard fonts config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析按键布局模式（中英文分开）。 */
    private fun parseButtonLayoutFromAssets(context: Context): Pair<ButtonLayout, ButtonLayout> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(ButtonLayout.STANDARD, ButtonLayout.STANDARD)
        val defaultZh = parseButtonLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseButtonLayoutYamlText(defaultText, "qwerty_en")
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh = customText?.let { parseButtonLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, "qwerty_en") }
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        return result
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘行布局。 */
    private fun parseKeyboardLayoutFromAssets(context: Context): Pair<List<List<String>>, List<List<String>>> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(DEFAULT_ZH_ROWS, DEFAULT_EN_ROWS)

        val defaultZh = parseKeyboardLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseKeyboardLayoutYamlText(defaultText, "qwerty_en")

        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

        val customZh = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty_en") }

        return Pair(
            customZh ?: defaultZh ?: DEFAULT_ZH_ROWS,
            customEn ?: defaultEn ?: DEFAULT_EN_ROWS,
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的键盘行布局（custom 整段覆盖 built-in）。 */
    private fun parseLayoutSection(context: Context, section: String): List<List<String>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardLayoutYamlText(it, section) }
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardLayoutYamlText(it, section) }
        return custom ?: default
    }

    /** 仅供单测：直接放入各 section 的对齐参数（正常由 [loadConfig] 从 YAML 读）。 */
    internal fun setSectionGeometryForTest(geometry: Map<String, LetterGeometryOverride>) {
        _sectionGeometry = geometry
    }

    /** 各 section 的对齐参数：custom 写了的字段覆盖内置，两边都没写的 section 不进表。 */
    private fun parseSectionGeometries(context: Context, sections: Collection<String>): Map<String, LetterGeometryOverride> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val customText = readCustomText(context)
        return sections.distinct().mapNotNull { section ->
            val custom = customText?.let { parseLetterGeometryYamlText(it, section) }
            val default = defaultText?.let { parseLetterGeometryYamlText(it, section) }
            mergeLetterGeometry(custom, default)?.let { section to it }
        }.toMap()
    }

    internal fun mergeLetterGeometry(custom: LetterGeometryOverride?, builtIn: LetterGeometryOverride?): LetterGeometryOverride? {
        val merged = LetterGeometryOverride(
            middleRowInset = custom?.middleRowInset ?: builtIn?.middleRowInset,
            deleteKeyWeight = custom?.deleteKeyWeight ?: builtIn?.deleteKeyWeight,
            shiftKeyEnabled = custom?.shiftKeyEnabled ?: builtIn?.shiftKeyEnabled,
            shiftKeyWeight = custom?.shiftKeyWeight ?: builtIn?.shiftKeyWeight,
        )
        return merged.takeUnless { it.isEmpty }
    }

    /**
     * 从 YAML 文本提取 keyboard.<section>.geometry：
     * middle_row_inset（格，0～3）、delete_key_weight（份，0.2～5）、
     * shift_key: { enabled: true/false, weight: 份，0.2～5 }（也可直接写 shift_key: false）。
     * 超范围的夹到范围内，写错的项当没写。
     */
    internal fun parseLetterGeometryYamlText(yamlText: String, section: String): LetterGeometryOverride? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val sectionNode = root.opt<YamlMap>("keyboard")?.opt<YamlMap>(section) ?: return null
            val node = sectionNode.opt<YamlMap>("geometry") ?: return null
            fun number(map: YamlMap, key: String): Float? =
                map.opt<YamlScalar>(key)?.content?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
            fun weight(map: YamlMap, key: String): Float? = number(map, key)?.coerceIn(
                LetterGeometryOverride.MIN_DELETE_KEY_WEIGHT, LetterGeometryOverride.MAX_DELETE_KEY_WEIGHT)
            fun flag(scalar: YamlScalar?): Boolean? = scalar?.content?.trim()?.lowercase()?.toBooleanStrictOrNull()
            val inset = number(node, "middle_row_inset")?.coerceIn(0f, LetterGeometryOverride.MAX_MIDDLE_ROW_INSET)
            val delete = weight(node, "delete_key_weight")
            val shiftNode = node.opt<YamlMap>("shift_key")
            val shiftEnabled = shiftNode?.let { flag(it.opt<YamlScalar>("enabled")) } ?: flag(node.opt<YamlScalar>("shift_key"))
            val shiftWeight = shiftNode?.let { weight(it, "weight") }
            LetterGeometryOverride(inset, delete, shiftEnabled, shiftWeight).takeUnless { it.isEmpty }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard geometry config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的手势配置（custom 键级覆盖 built-in）。 */
    private fun parseGesturesSection(context: Context, section: String): Map<String, KeyGestureConfig> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardYamlSection(it, section) } ?: emptyMap()
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = (userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE))
            ?.let { parseKeyboardYamlSection(it, section) }
        return if (custom != null) default + custom else default
    }

    /**
     * 从 YAML 文本提取合并键绑定：keyboard.<section>.schemas 列出的每个方案 id 映射到该 section。
     * 无 schemas 声明的 section 忽略；同一方案 id 在多个 section 声明时以后出现的为准。
     */
    internal fun parseSchemaBindingsYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val bindings = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = (kNode as? YamlScalar)?.content ?: continue
                val schemas = (vNode as? YamlMap)?.opt<YamlList>("schemas") ?: continue
                for (item in schemas.items) {
                    val schemaId = (item as? YamlScalar)?.content?.trim().orEmpty()
                    if (schemaId.isNotEmpty()) bindings[schemaId] = section
                }
            }
            bindings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schema bindings", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows。
     * 行元素支持嵌套子数组表示合并键：[[q, w], [e, r], t] → ["qw", "er", "t"]，
     * 合并键 ID 为组内字母拼接，渲染层按 ID 长度分配键宽。
     */
    internal fun parseKeyboardLayoutYamlText(yamlText: String, section: String): List<List<String>>? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlMap>("layout") ?: return null
            val rowsNode = layoutNode.opt<YamlList>("rows") ?: return null
            val rows = mutableListOf<List<String>>()
            for (rowNode in rowsNode.items) {
                val row = when (rowNode) {
                    is YamlList -> rowListToKeyIds(rowNode)
                    is YamlScalar -> scalarRowToKeyIds(rowNode.content)
                    else -> emptyList()
                }
                if (row.isNotEmpty()) {
                    rows.add(row)
                }
            }
            rows.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard layout config", e)
            null
        }
    }

    /** 行节点 → 按键 ID 列表：子数组为合并键（组内字母拼接为 ID），标量为单字母键。 */
    private fun rowListToKeyIds(rowList: YamlList): List<String> =
        rowList.items.mapNotNull { item ->
            when (item) {
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }
                is YamlList -> item.items
                    .mapNotNull { (it as? YamlScalar)?.content }
                    .filter { it.isNotBlank() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
                else -> null
            }
        }

    /**
     * 标量行兜底：YAML 中以普通字母开头的行（如 "- z, [x, c], v"）不会成为 flow 序列，
     * 而是整行解析为单个标量。此处手动拆分：逗号为键分隔，方括号内为合并键组。
     */
    private fun scalarRowToKeyIds(content: String): List<String> {
        val text = content.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.contains(',') && !text.contains('[')) return listOf(text)
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        fun flushItem() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) items.add(t)
            sb.clear()
        }
        for (ch in text) {
            when {
                ch == '[' -> { depth++; if (depth == 1) sb.clear() else sb.append(ch) }
                ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        flushItem()
                    } else if (depth < 0) {
                        depth = 0
                    } else {
                        sb.append(ch)
                    }
                }
                ch == ',' && depth == 0 -> flushItem()
                ch == ',' && depth > 0 -> {} // 组内逗号仅分隔字母，拼接时丢弃
                ch == ' ' && depth > 0 -> {} // 组内空格一并丢弃（[x, c] → xc）
                else -> sb.append(ch)
            }
        }
        flushItem()
        return items
    }

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout。 */
    private fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlScalar>("button_layout") ?: return null
            ButtonLayout.fromValue(layoutNode.content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse button layout config", e)
            null
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.keys 段。 */
    internal fun parseKeyboardYamlSection(yamlText: String, section: String): Map<String, KeyGestureConfig>? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val keysNode = sectionNode.opt<YamlMap>("keys") ?: return null
            val result = mutableMapOf<String, KeyGestureConfig>()
            for ((kNode, vNode) in keysNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val gestureMap = vNode as? YamlMap ?: continue
                result[key] = parseKeyGestureConfig(gestureMap)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard gesture section", e)
            null
        }
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
            // 合并而非替换：custom 覆盖同名字题，其余保留内置主题，
            // 这样用户只需在 xime.custom.yaml 中添加自定义背景主题而不会丢失内置主题。
            colorSchemes = mergeColorSchemes(default.colorSchemes, custom.colorSchemes),
            style = mergeStyle(default.style, custom.style),
            metadata = custom.metadata ?: default.metadata,
        )
    }

    /** style 字段级深合并：custom 只覆盖显式配置的字段。 */
    private fun mergeStyle(default: StyleConfig?, custom: StyleConfig?): StyleConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return StyleConfig(
            colorScheme = mergeColorSchemeMode(default.colorScheme, custom.colorScheme),
            darkMode = custom.darkMode ?: default.darkMode,
        )
    }

    /** color_scheme 的 light/dark 字段级合并：custom 只覆盖显式配置的模式。 */
    private fun mergeColorSchemeMode(
        default: ColorSchemeModeConfig?,
        custom: ColorSchemeModeConfig?,
    ): ColorSchemeModeConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return ColorSchemeModeConfig(
            light = custom.light ?: default.light,
            dark = custom.dark ?: default.dark,
        )
    }

    /** 仅供单元测试验证 style 合并逻辑。 */
    internal fun mergeStyleForTest(default: StyleConfig?, custom: StyleConfig?): StyleConfig? =
        mergeStyle(default, custom)

    private fun mergeColorSchemes(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? {
        if (custom == null) return default
        if (default == null) return custom
        // 字段级深合并：custom 只覆盖显式配置的字段，同名主题其余字段保留内置值。
        // 例如 custom 只写 primary_color 时，内置主题的 keyboard_background 不会被清掉。
        val result = default.toMutableMap()
        for ((id, customEntry) in custom) {
            val base = default[id] ?: run {
                result[id] = customEntry
                continue
            }
            result[id] = base.copy(
                name = customEntry.name.ifEmpty { base.name },
                primaryColor = customEntry.primaryColor.takeIf { it != 0L } ?: base.primaryColor,
                keyboardBgColor = customEntry.keyboardBgColor ?: base.keyboardBgColor,
                keyBgColor = customEntry.keyBgColor ?: base.keyBgColor,
                keyBgColorDark = customEntry.keyBgColorDark ?: base.keyBgColorDark,
                specialKeyBgColor = customEntry.specialKeyBgColor ?: base.specialKeyBgColor,
                specialKeyBgColorDark = customEntry.specialKeyBgColorDark ?: base.specialKeyBgColorDark,
                enterKeyBgColor = customEntry.enterKeyBgColor ?: base.enterKeyBgColor,
                enterKeyBgColorDark = customEntry.enterKeyBgColorDark ?: base.enterKeyBgColorDark,
                specialKeyTextColor = customEntry.specialKeyTextColor ?: base.specialKeyTextColor,
                specialKeyTextColorDark = customEntry.specialKeyTextColorDark ?: base.specialKeyTextColorDark,
                candidateBarBgColor = customEntry.candidateBarBgColor ?: base.candidateBarBgColor,
                keyTextColor = customEntry.keyTextColor ?: base.keyTextColor,
                keyTextColorDark = customEntry.keyTextColorDark ?: base.keyTextColorDark,
                candidateTextColor = customEntry.candidateTextColor ?: base.candidateTextColor,
                candidateTextColorDark = customEntry.candidateTextColorDark ?: base.candidateTextColorDark,
                candidateSelectedTextColor = customEntry.candidateSelectedTextColor ?: base.candidateSelectedTextColor,
                candidateSelectedTextColorDark = customEntry.candidateSelectedTextColorDark ?: base.candidateSelectedTextColorDark,
                keyboardBackground = customEntry.keyboardBackground ?: base.keyboardBackground,
                keyBackground = customEntry.keyBackground ?: base.keyBackground,
                candidateBarBackground = customEntry.candidateBarBackground ?: base.candidateBarBackground,
                dynamicColor = customEntry.dynamicColor || base.dynamicColor,
            )
        }
        return result
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
    }

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        if (!file.exists()) return null
        return try {
            val text = file.readText().trimStart('\uFEFF')
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    /** 读取自定义配置文本：优先用户数据目录（浏览器导入），回退 assets 内置。 */
    private fun readCustomText(context: Context): String? =
        readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme 的 light 字段）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme?.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
    }

    /** 根据显示模式加载对应的默认主题 ID。 */
    fun loadThemeIdForMode(context: Context, isDark: Boolean): String {
        val merged = loadMergedConfig(context)
        val cs = merged.style?.colorScheme ?: return com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
        return if (isDark) (cs.dark ?: cs.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID)
               else cs.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
    }

    /** 从 xime.yaml 加载默认显示模式（style.dark_mode）。 */
    fun loadDefaultDarkMode(context: Context): Int {
        val merged = loadMergedConfig(context)
        return merged.style?.darkMode ?: 1
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取键盘按键配置（从 xime.yaml keyboard.key 加载）；圆角按主题里选的按键形状收小。 */
    fun getKeyboardKeyConfig(): KeyboardKeyConfig = keyboardKeyConfig.let { config ->
        val radius = com.kingzcheung.xime.ui.theme.KeyShapes.current.radius(config.cornerRadius)
        if (radius == config.cornerRadius) config else config.copy(cornerRadius = radius)
    }

    /** xime.yaml 配置的按键圆角，未按按键形状收小；设置页预览各形状用。 */
    fun configuredKeyCornerRadius(): Int = keyboardKeyConfig.cornerRadius

    /** 获取九键左侧快捷符号栏配置（xime.custom.yaml → xime.yaml → 内置默认值）。 */
    fun getT9SideSymbols(): List<String> =
        keyboardT9Config.sideSymbols ?: DEFAULT_T9_SIDE_SYMBOLS

    /** 获取九键数字键手势配置（xime.yaml keyboard.t9.keys，custom 键级覆盖）。
     *  键 id 为数字字符串 "1"~"9"；无配置返回 null（布局不启用滑动）。 */
    fun getT9KeyGesture(key: String): KeyGestureConfig? = _t9GestureConfigs[key]

    /** 获取笔画左侧快捷符号列（keyboard.stroke.side_symbols，可自定义）。 */
    fun getStrokeSideSymbols(): List<String> =
        keyboardStrokeConfig.sideSymbols ?: DEFAULT_STROKE_SIDE_SYMBOLS

    /** 获取笔画键手势配置（xime.yaml keyboard.stroke.keys，custom 键级覆盖）。
     *  键 id 为键面标签：一、丨、丿、丶、乛、*、分词、，、。 */
    fun getStrokeKeyGesture(key: String): KeyGestureConfig? = _strokeGestureConfigs[key]

    internal fun qwertySymbolEditorValues(context: Context, english: Boolean): Map<String, String> {
        val overrides = QwertySwipeSymbols.load(context, english)
        val custom = if (english) symbolCustomEn else symbolCustomZh
        return buildMap {
            val editable = QwertySwipeSymbols.keys.map(Char::toString) + if (english) QwertySwipeSymbols.extraKeys else emptyList()
            for (key in editable) {
                val gesture = QwertySwipeSymbols.apply(key, null, overrides, custom[key], english, numberRowCorners)!!
                put("up.$key", gesture.swipeUp!!.value.ifEmpty { gesture.swipeUp.label })
                put("down.$key", gesture.swipeDown!!.value.ifEmpty { gesture.swipeDown.label })
            }
        }
    }

    internal fun customLayoutSymbolEditorValues(context: Context, layout: CustomKeyboardLayout): Map<String, String> {
        val overrides = CustomLayoutSymbols.load(context, layout)
        val keys = CustomLayoutSymbols.keys(layout)
        return buildMap {
            for (key in keys) {
                // 和键盘上一样：自建布局的手势表按旧位置填过 swipe，不算用户写的，只看预设和这里的修改。
                val gesture = MergedKeySymbols.apply(keys, key, null, overrides)!!
                put("up.$key", gesture.swipeUp!!.value)
                put("down.$key", gesture.swipeDown!!.value)
            }
        }
    }

    internal fun fourteenSymbolEditorValues(context: Context): Map<String, String> {
        val overrides = FourteenKeySymbols.load(context)
        val custom = _mergedGestureConfigs[InputLayout.MERGED14.id].orEmpty()
        return buildMap {
            for (key in FourteenKeySymbols.keys) {
                val gesture = FourteenKeySymbols.apply(key, custom[key], overrides)!!
                put("up.$key", gesture.swipeUp!!.value.ifEmpty { gesture.swipeUp.label })
                put("down.$key", gesture.swipeDown!!.value.ifEmpty { gesture.swipeDown.label })
            }
        }
    }

    /**
     * 西班牙、德、法、俄、韩语键盘（[LetterLanguage]）长按字母总是弹出 á ä ß ё ㄲ 这些字母（打这些语言离不开），
     * 不受「长按弹出字母气泡」开关影响；返回长按选中后当作字母打进去的那些字母，其他键盘为空。
     * 在它上面切到英文时照英文键盘的规则。
     */
    fun longPressLetters(isAsciiMode: Boolean): String =
        if (isAsciiMode) "" else LetterLanguage.of(InputLanguage.forSchema(_activeSchemaId))?.extraLetters.orEmpty()

    /**
     * 不在 26 格里的字母键（俄语 й、韩语 ㅂ、西班牙语 ñ）：角标跟着位置走，取 26 键同一行同一格那个字母的角标；
     * 那一格超出 26 键的（ñ、х）不带角标。
     */
    private fun cornerCellOwner(key: String, letters: LetterLanguage?, isAsciiMode: Boolean): String? {
        if (letters == null || isAsciiMode || key in QwertySwipeSymbols.keys.map(Char::toString)) return null
        _zhRows.forEachIndexed { row, keys ->
            val column = keys.indexOf(key)
            if (column >= 0) return QwertySwipeSymbols.rows.getOrNull(row)?.getOrNull(column)?.toString() ?: key
        }
        return null
    }

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyGestureConfig? = getKeyGesture(key, false)

    /**
     * 日语 26 键等自带角标的键盘：第一排的角标是数字（xime.yaml）。开着数字行时数字已经在上面一排，
     * 第一排不再重复画数字（用户 11-11「开启数字行多了一堆符号」）；不是数字的角标照旧。
     */
    internal fun withoutNumberRowDigits(key: String, config: KeyGestureConfig): KeyGestureConfig {
        if (!numberRowCorners || key !in QwertySwipeSymbols.numberRowUp) return config
        fun digit(def: GestureDef?) = def != null && def.value.ifEmpty { def.label }.let { it.length == 1 && it[0] in '0'..'9' }
        // 换成空的（不是 null）：null 时 getSwipeUpLabel 等会退回全局默认角标，又把数字画出来。
        val empty = GestureDef(action = GestureAction.NONE)
        return config.copy(swipeUp = if (digit(config.swipeUp)) empty else config.swipeUp,
            swipeDown = if (digit(config.swipeDown)) empty else config.swipeDown)
    }

    /** 根据输入模式获取某个按键的手势配置。 */
    fun getKeyGesture(key: String, isAsciiMode: Boolean): KeyGestureConfig? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val normalized = key.lowercase()
        val profile = InputProfiles.current(_activeSchemaId, isAsciiMode)
        val letters = LetterLanguage.of(profile.language)
        if (profile.language !in setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH) && letters == null)
            return config[normalized]?.let { withoutNumberRowDigits(normalized, it) }
        // 西班牙、德、法、俄、韩语键盘用英文键盘的角标（同一位置同一个半角符号）。
        val english = isAsciiMode || letters != null
        val custom = letterLayout(_activeSchemaId, isAsciiMode)
        // 所有字母布局的角标都由下面两条规则出，不再各布局各配一套（用户 10-30：模式太多难免疏忽，统一）。
        return when (profile.layout.kind) {
            // 单字母键：角标跟着位置走。换了字母排列或用自建布局，同一格还是 26 键那一格的上下角标，符号设置页改的也是这一格。
            LayoutKind.ALPHABETIC -> {
                // 排列里的分号键本身就是符号，不带角标；它占的那一格给了被挤开的字母（见 positionLetter）。
                if (custom != null && normalized == ";" && custom.hasSemicolon())
                    return config[normalized]?.copy(swipeUp = null, swipeDown = null)
                val owner = custom?.positionLetter(normalized) ?: cornerCellOwner(normalized, letters, isAsciiMode) ?: normalized
                QwertySwipeSymbols.apply(owner, config[normalized], if (english) symbolOverridesEn else symbolOverridesZh,
                    (if (english) symbolCustomEn else symbolCustomZh)[owner], english, numberRowCorners)
            }
            // 合并键（14/17/18 键、自建双字母布局）：按键的读顺序用同一张表（MergedKeySymbols），14 键可在设置页改。
            // Shift 或分体把双字母键拆成单字母时，两个字母都沿用原来那个键的角标，符号位置不跟着变。
            LayoutKind.MERGED -> {
                if (profile.language != InputLanguage.CHINESE) return config[normalized]
                val fourteen = profile.layout == InputLayout.MERGED14
                val keys = if (fourteen) FourteenKeySymbols.keys
                    else _zhRows.flatten().filter { cell -> cell.isNotEmpty() && cell.all { it in 'a'..'z' } }
                val owner = MergedKeySymbols.ownerOf(keys, normalized)
                if (owner !in keys) return config[normalized]
                // 自建布局的手势表按旧的位置填过 swipe，不算用户写的；内置 14/17/18 键只认 xime.custom.yaml 写的。
                val configured = config[owner]?.let { if (custom != null) it.copy(swipeUp = null, swipeDown = null) else it }
                // 14 键和自建布局在符号设置页改的；17/18 键没有设置页。
                val overrides = if (fourteen) symbolOverridesK14 else custom?.let { symbolOverridesCustom[it.id] }.orEmpty()
                val symbols = MergedKeySymbols.apply(keys, owner, configured, overrides)
                if (owner == normalized) symbols
                else (config[normalized] ?: KeyGestureConfig()).copy(swipeUp = symbols?.swipeUp, swipeDown = symbols?.swipeDown)
            }
            else -> config[normalized]
        }
    }

    fun getKeyDisplayLabel(key: String, isAsciiMode: Boolean = false, isShifted: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val label = config[key.lowercase()]?.tap?.label
        return keyLabelWithCase(label?.takeIf { it.isNotEmpty() } ?: key, isShifted)
    }

    fun getKeyCommitValue(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val value = config[key.lowercase()]?.tap?.value
        return value?.takeIf { it.isNotEmpty() }
            // 合并键 ID（如 "qw"）无手势配置时回退为代表字母（组内第一个字母）
            ?: key.takeIf { it.length > 1 }?.first()?.toString()
            ?: key
    }

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String, isAsciiMode: Boolean = false): String? {
        val kc = getKeyGesture(key, isAsciiMode) ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.values?.firstOrNull()?.label
            else -> null
        }
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }

    fun getSwipeUpAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        return getKeyGesture(key, isAsciiMode)?.swipeUp?.action
    }

    /** 获取上滑显示文本（优先 label，fallback value） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String, isAsciiMode: Boolean = false): String? {
        val fromYaml = getKeyGesture(key, isAsciiMode)?.swipeDown?.label
        if (fromYaml != null) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        return getKeyGesture(key, isAsciiMode)?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        return getKeyGesture(key, isAsciiMode)?.swipeDown?.display ?: DisplayMode.BOTH
    }

    /** 获取上滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeUpDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        return getKeyGesture(key, isAsciiMode)?.swipeUp?.display ?: DisplayMode.BOTH
    }

    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}
