package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.swipeHandlerFor
import com.kingzcheung.xime.keyboard.GestureAction

/** 半角 → 全角标点映射，中文模式下键帽显示用。提交仍走半角由 Rime 处理。 */
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.CharInfo
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.TextUnit



@Composable
fun KeyboardLayout(
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    isAsciiMode: Boolean,
    modifier: Modifier = Modifier,
    /** 打字中的状态；只给 Shift 键读（Shift 键分词、「ing 放在 Shift 键上」），整块键盘不读它，免得每按一键整块重组。 */
    candidateState: androidx.compose.runtime.State<com.kingzcheung.xime.service.CandidateState>? = null,
) {
    // 配置或方案变化时，行列与间距一起更新。
    val cfgVer by KeysConfigHelper.configVersion.collectAsState()
    // Chinese layout schemas, and the arrangement English / Japanese romaji chose, share one component.
    val letterLayout = remember(cfgVer, isAsciiMode, uiState.currentSchemaId) {
        KeysConfigHelper.letterLayout(uiState.currentSchemaId, isAsciiMode)
    }
    // 日语 26 键长音 ー（「26 键 › 长音键」）：默认 L 键就是长音，键盘不变；「L 右侧单独键」在 l 后面加一个 ー 键；
    // 「代替 Shift」左下角 Shift 键点按是 ー。配置里关掉了 Shift 键（geometry.shift_key）时改放 L 右侧。
    val shiftKeyConfigured = remember(cfgVer, uiState.currentSchemaId, isAsciiMode) {
        KeysConfigHelper.letterGeometryOverride(uiState.currentSchemaId, isAsciiMode)?.shiftKeyEnabled != false
    }
    val longVowelKey = japaneseLongVowelKey(LocalKeyboardInputPreferences.current.japaneseLongVowelKey, isAsciiMode, uiState.inputProfile)
    val choonpuOnShift = longVowelKey == JapaneseLongVowelKey.SHIFT && shiftKeyConfigured
    val choonpuBesideL = longVowelKey == JapaneseLongVowelKey.SEPARATE ||
        longVowelKey == JapaneseLongVowelKey.SHIFT && !shiftKeyConfigured
    val keyRows = remember(cfgVer, isAsciiMode, uiState.currentSchemaId, letterLayout, choonpuBesideL) {
        (letterLayout?.typingRows() ?: KeysConfigHelper.getKeyRows(uiState.currentSchemaId, isAsciiMode))
            .let { if (choonpuBesideL) withChoonpuKey(it) else it }
    }
    // Typewise honeycomb: a fourth letter row, short rows offset half a key, hexagonal letter caps.
    val honeycomb = letterLayout?.honeycomb == true
    val splitKeyboard = LocalKeyboardInputPreferences.current.splitKeyboardEnabled &&
        supportsSplitKeyboard(uiState.currentSchemaId, isAsciiMode)
    // 「邻键纠错」全拼、双拼共用（11-03 统一）：关了两边都不交键位表，引擎不纠。开着时双拼只在两个键拼不成任何音节时纠
    // （按偏成 wp 原来一个候选都没有，见 [letterNeighborsForEngine]）；「双拼邻键纠错」（默认关）再开才整串都纠。
    val doublePinyin = uiState.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN
    val neighborCorrection = !isAsciiMode && LocalKeyboardInputPreferences.current.neighborCorrection
    val doublePinyinCorrection = LocalKeyboardInputPreferences.current.doublePinyinCorrection
    val geometry = remember(cfgVer, keyRows, neighborCorrection, doublePinyin, doublePinyinCorrection, uiState.currentSchemaId, splitKeyboard) {
        LetterGeometry { encoded ->
            callbacks.onLetterNeighbors?.invoke(uiState.currentSchemaId,
                com.kingzcheung.xime.keyboard.letterNeighborsForEngine(encoded, neighborCorrection, doublePinyin, doublePinyinCorrection))
        }
    }
    DisposableEffect(geometry) {
        callbacks.onLetterNeighbors?.invoke(uiState.currentSchemaId, "")
        onDispose { }
    }
    // Shared physical frame and bottom-row spacing for 14/26 keys. The paired
    // alphabet keys still occupy their own five-column rows inside that frame.
    val fourteenKey = !isAsciiMode && uiState.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14
    // 逗号键分词（默认关）：中文全拼、双拼时标点键多一个 ' 角标，字母键角标里的 ' 打拼音时也是分词。
    val syllableSplit = syllableSplitActive(LocalKeyboardInputPreferences.current.syllableSplitKey, isAsciiMode, uiState.inputProfile)
    // Shift 键分词（默认关）：同样只管中文全拼、双拼；关着时 Shift 键原样不动。
    val shiftSplit = syllableSplitActive(LocalKeyboardInputPreferences.current.shiftSyllableSplit, isAsciiMode, uiState.inputProfile)
    val shiftComposingSplit: (() -> Boolean)? = if (shiftSplit && candidateState != null)
        remember(candidateState) { { shiftTapTypesSeparator(true, candidateState.value.isComposing) } } else null
    // 双拼键位提示（默认关）：双拼 26 键每个键写出 zh/ch/sh 和韵母，和双拼方案页的键位图一样；14 键、九键不显示。
    // 自己排的合并键布局（一个键两个字母）也不显示：合并键写不下两个字母的提示，只有单字母键有会前后不一。
    val dpKeyHints = if (doublePinyin && !isAsciiMode && !fourteenKey && keyRows.all { row -> row.all { it.length <= 1 } } &&
        LocalKeyboardInputPreferences.current.doublePinyinKeyHints) remember(uiState.currentSchemaId, cfgVer) {
        com.kingzcheung.xime.settings.DoublePinyinSchemes.find(uiState.currentSchemaId)?.let(::doublePinyinKeyHints)
    } else null
    // 「ing 放在 Shift 键上」（默认关）：第二排不补分号键（KeysConfigHelper.setSemicolonOnShift，KeyboardView 交给它），
    // 微软双拼打了声母等韵母时，左下角的 Shift 键换成 ing（SemicolonShiftKey）。
    val semicolonOnShift = LocalKeyboardInputPreferences.current.doublePinyinSemicolonOnShift
    val schemeUsesSemicolon = remember(uiState.currentSchemaId, cfgVer) {
        com.kingzcheung.xime.settings.DoublePinyinSchemes.find(uiState.currentSchemaId)?.usesSemicolon == true
    }
    val ingOnShift = if (semicolonOnShift && schemeUsesSemicolon && doublePinyin && !isAsciiMode && !fourteenKey &&
        candidateState != null) remember(candidateState) {
        androidx.compose.runtime.derivedStateOf { awaitsDoublePinyinFinal(candidateState.value.inputText) }
    } else null
    // 注音（大千）：11 列，下面三行 10 键缩进半键，没有 Shift，删除键在第四行末尾。
    val zhuyin = !isAsciiMode && uiState.inputProfile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.ZHUYIN
    // 数字行（默认关）：字母上方一排，键和顺序在「按键符号 → 数字行」里改；注音不加（见 [numberRowShown]）。
    val numberRowPrefs = LocalKeyboardInputPreferences.current
    val numberKeys = remember(numberRowPrefs.numberRow, zhuyin, numberRowPrefs.sideSymbols) {
        if (numberRowShown(numberRowPrefs.numberRow, zhuyin)) numberRowPrefs.numberRowKeys() else emptyList()
    }
    val numberRowWeight = if (numberKeys.isEmpty()) 0f else NUMBER_ROW_HEIGHT_WEIGHT
    val metricContext = LocalContext.current
    val metricDisplay = LocalView.current.display
    val metricConfiguration = LocalConfiguration.current
    val pixelsPerDp = LocalDensity.current.density
    val physicalTargetCellDp = remember(metricContext, metricDisplay, metricConfiguration, pixelsPerDp) {
        keyboardPhysicalMetrics(metricContext, metricDisplay, pixelsPerDp)
            ?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
    }
    // 分体 = 正常 26 键 + 每行中线处同宽的一条缝：错位、键宽、按键功能都与正常键盘一致。
    val splitGap = LocalSplitKeyboardGap.current
    val splitGapSetting = LocalSplitGapPreview.current?.value ?: LocalKeyboardInputPreferences.current.let {
        if (metricConfiguration.screenWidthDp > metricConfiguration.screenHeightDp) it.splitGapLandscape else it.splitGapPortrait
    }
    BoxWithConstraints(modifier.then(if (splitKeyboard && splitGap != null)
        Modifier.onGloballyPositioned { splitGap.reportAreaTop(it.boundsInWindow().top) } else Modifier)) {
    val splitGutterDp = SplitKeyboardGeometry.gutterDp(uiState.isFloatingMode)
    val keyboardHeight = maxHeight
    val splitGapFraction = if (splitKeyboard) SplitKeyboardGeometry.resolvedGap(
        splitGapSetting, maxWidth.value, splitGutterDp, physicalTargetCellDp) else 0f
    val splitGapDp = (splitGapFraction * maxWidth.value).dp
    // 「中文键盘字母显示大写」：键盘里读 uppercaseKeyLabels 的地方（26 键三种行、14 键左右滑提示）按当前语言取值，只用于显示，不保存。
    val keyLabelPrefs = LocalKeyboardInputPreferences.current.let {
        it.copy(uppercaseKeyLabels = letterLabelsUppercase(it, isAsciiMode, uiState.inputProfile))
    }
    // 分体两半贴住左右边：不套用居中的键宽上限。
    CompositionLocalProvider(LocalKeyboardExplicitWidth provides (LocalKeyboardExplicitWidth.current || splitKeyboard),
        LocalDoublePinyinKeyHints provides dpKeyHints, LocalKeyboardInputPreferences provides keyLabelPrefs) {
    KeyboardKeySpacingScope(Modifier.fillMaxSize(),
        columns = letterKeyboardColumns(keyRows, fourteenKey),
        // 数字行和字母行共用键盘高度：多一排就按多出的份数算每排高度，键高与纵缝跟着变。
        rows = 4f + numberRowWeight,
        widthFraction = if (splitKeyboard) SplitKeyboardGeometry.keyWidthFraction(
            maxWidth.value, splitGapFraction, splitGutterDp) else 1f,
        policy = KeyVisualPolicy.Qwerty,
        allowShrink = uiState.isFloatingMode,
        applyGutter = true,
        growthSpacing = KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty"),
        // 分体两半贴边、中缝按 8dp 外边距算（SplitKeyboardGeometry.gutterDp），加宽按键不改分体。
        widenKeys = LocalKeyboardInputPreferences.current.wideKeys && !splitKeyboard,
    ) { bodyModifier ->
    val splitCellDp = LocalKeyboardKeyVisualMetrics.current.cellWidthDp
    val letterRowGeometry = standardLetterRowGeometry(
        keyRows = keyRows,
        cellWidthDp = splitCellDp,
        hasCustomLayout = letterLayout != null,
        isSplit = false,
        physicalTargetCellDp = physicalTargetCellDp,
        stagger = LocalKeyboardInputPreferences.current.let {
            resolvedQwertyStagger(it.qwertyStagger, it.qwertyStaggerCustom, splitCellDp, physicalTargetCellDp)
        },
    )
    // xime.custom.yaml keyboard.<section>.geometry：第二行缩进、删除键份数、Shift 开关和份数，写了哪项换哪项。
    // 方案换用应用内自定义布局时同样生效。
    val geometryOverride = remember(cfgVer, uiState.currentSchemaId, isAsciiMode) {
        KeysConfigHelper.letterGeometryOverride(uiState.currentSchemaId, isAsciiMode)
    }
    val overrideRowInset = geometryOverride?.middleRowInset?.let { cells ->
        (cells * firstRowCellDp(splitCellDp, letterKeyboardColumns(keyRows, fourteenKey),
            keyRows.firstOrNull().orEmpty(), weighted = fourteenKey || letterLayout != null)).dp
    }
    val standardRowInset = overrideRowInset ?: letterRowGeometry?.middleRowInsetDp?.dp
    val standardRowEndInset = overrideRowInset ?: letterRowGeometry?.middleRowEndInsetDp?.dp
    // geometry.shift_key：关掉时第三行不放 Shift（份数按 0 算），写了 weight 按它；默认照旧。
    val shiftKeyShown = geometryOverride?.shiftKeyEnabled != false
    val frameSideKeyWeight = letterRowGeometry?.outerKeyWeight ?: 1.4f
    val letterSideKeyWeight = if (!shiftKeyShown) 0f else geometryOverride?.shiftKeyWeight ?: frameSideKeyWeight
    val letterDeleteKeyWeight = geometryOverride?.deleteKeyWeight ?: letterRowGeometry?.deleteKeyWeight ?: 1.4f
    val letterBottomWeight = letterRowGeometry?.bottomLetterWeight ?: 7f
    val splitWeighted = letterLayout != null || fourteenKey
    val baseRow1 = keyRows.getOrElse(1) { listOf("a", "s", "d", "f", "g", "h", "j", "k", "l") }
    val splitRow1InsetDp = standardRowInset
        ?: if ((if (splitWeighted) baseRow1.sumOf { it.length } else baseRow1.size) > 9) 0.dp else 16.dp
    // 14 键分体：跨中线的双字母键拆成两个单字母键，其余行与键不变。
    val pairedRows = if (splitKeyboard && fourteenKey && keyRows.size >= 3) listOf(
        SplitKeyboardGeometry.splitStraddlingPairs(keyRows[0], 0f),
        SplitKeyboardGeometry.splitStraddlingPairs(keyRows[1], splitRow1InsetDp.value / splitCellDp.coerceAtLeast(1f)),
        SplitKeyboardGeometry.splitStraddlingPairs(keyRows[2], letterSideKeyWeight),
    ) + keyRows.drop(3) else keyRows
    val shiftSlideTargets = remember(uiState.currentSchemaId, isAsciiMode) { ShiftSlideTargets() }
    val dragSpace = LocalKeyDragSpace.current
    val shiftMode by viewModel.shiftMode.collectAsStateWithLifecycle()
    // 14 键按住 Shift 拖动时，双字母键拆回单字母键，键帽显示手指实际停的那个字母；松开 Shift 恢复（用户 10-21）。
    // 按住符号键当 Ctrl 时同样拆开，每个字母各显示自己的快捷键（用户 10-24）；和键面一起等按满时限或拖出才拆，点一下开符号不闪（10-31）。
    // Shift 亮着（单次、锁定、按住）时也拆开：大写只能是一个确定的字母，点哪个出哪个，bn 不再只出 B（10-27）。
    // 按字母数定宽，拆开后每个字母的位置和宽度不变。
    val renderRows = if (fourteenKey && (shiftSlideTargets.dragging || shiftSlideTargets.ctrlFaces || shiftMode.isShifted)) pairedRows.mapIndexed { index, row ->
        if (index > 2) row else row.flatMap { key ->
            if (key.length == 2 && key.all { it.lowercaseChar() in 'a'..'z' }) listOf(key.take(1), key.drop(1)) else listOf(key)
        }
    } else pairedRows
    // 「合并键均匀排布」：自建字母排列和 14 键（默认关）；分体按 26 键中线拆左右，仍严格对齐。
    val evenMergedKeys = (letterLayout != null || fourteenKey) && !splitKeyboard &&
        LocalKeyboardInputPreferences.current.evenMergedKeys
    // 14 键拆开时每个字母各占原合并键的一半，拆开前后不跳位；没拆或没打开时为 null，按键自己的份数。
    val renderSpans: List<List<Float>>? = if (evenMergedKeys && fourteenKey && renderRows !== pairedRows)
        pairedRows.take(3).map { splitPairSpans(it, true) } else null
    val splitRow0 = renderRows.getOrElse(0) { listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p") }
    val splitRow1 = renderRows.getOrElse(1) { listOf("a", "s", "d", "f", "g", "h", "j", "k", "l") }
    val splitRow2 = renderRows.getOrElse(2) { listOf("z", "x", "c", "v", "b", "n", "m") }
    // 每行左半的键数；-1 表示不分体。
    val splitAt0 = if (splitKeyboard) SplitKeyboardGeometry.leftCount(splitRow0, 0f, splitWeighted,
        letterLayout?.rowWidths(splitRow0)) else -1
    val splitAt1 = if (splitKeyboard) SplitKeyboardGeometry.leftCount(splitRow1,
        splitRow1InsetDp.value / splitCellDp.coerceAtLeast(1f), splitWeighted,
        letterLayout?.rowWidths(splitRow1)) else -1
    val splitAt2 = if (splitKeyboard) SplitKeyboardGeometry.leftCount(splitRow2, letterSideKeyWeight, splitWeighted,
        letterLayout?.rowWidths(splitRow2)) else -1
    // 「右半边也放 G 键」只在 26 键分体时生效；14 键、注音、蜂窝和不分体的键盘不受影响。
    val splitRightG = splitKeyboard && !fourteenKey && !zhuyin && !honeycomb &&
        LocalKeyboardInputPreferences.current.splitRightG
    val isShifted = shiftMode.isShifted

    val context = LocalContext.current
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val keyboardBackgroundColor = KeyboardThemes.getKeyboardBackgroundColor(uiState.themeId, uiState.isDarkTheme)
    val themeScheme = KeyboardThemes.getRenderingScheme(uiState.themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBackgroundColor = KeyboardThemes.getKeyBgColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = com.kingzcheung.xime.ui.theme.resolvedSpecialKeyColor(
        themeScheme, uiState.isDarkTheme,
        (if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark else kbColors.specialKeyBgColor)?.let(longToColor)
    )
    val specialKeyTextColor = if (uiState.isDarkTheme) Color.White
        else KeyboardThemes.getSpecialKeyTextColor(uiState.themeId, false)
    val bubbleBgColor = if (uiState.isDarkTheme) themeScheme.specialKeyDark
        else themeScheme.specialKeyLight
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val shadowEnabled = kbShadow.enabled
    val shadowElevation = kbShadow.elevation.dp
    val shadowShapeRadius = kbShadow.shapeRadius.dp
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val isDarkTheme = uiState.isDarkTheme
    val isSttEnabled = uiState.isSttEnabled
    val isVoiceMode = uiState.isVoiceMode
    val isVoiceSticky = uiState.voiceSticky
    val onKeyPressDown = callbacks.onKeyPressDown
    val onKeyRelease = callbacks.onKeyRelease
    val onVoiceModeChange = callbacks.onVoiceModeChange
    // 逗号键分词开着时，角标上屏的 ' 改走按键：打拼音时是分词，没在打拼音时照旧上屏 '。关着时原样不动。
    val onCommitText: ((String) -> Unit)? = remember<((String) -> Unit)?>(callbacks.onCommitText, onKeyPress, syllableSplit) {
        val commit = callbacks.onCommitText
        if (!syllableSplit || commit == null) commit
        else { text: String -> if (cornerTypesSeparator(true, text)) onKeyPress(SYLLABLE_SEPARATOR) else commit(text) }
    }
    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            GestureAction.SWITCH_ROUTE -> {
                val overlayRoute = when (value) {
                    "emoji" -> OverlayRoute.Emoji
                    "symbol" -> OverlayRoute.Symbol
                    else -> null
                }
                overlayRoute?.let { viewModel.showOverlay(it) }
            }
            GestureAction.TOGGLE_ASCII -> {
                FileLogger.i("XimeKeyboard", "earth key toggle_ascii tapped, ui ascii=${uiState.isAsciiMode}")
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            GestureAction.DELETE -> {
                callbacks.onKeyPress("delete", false)
            }
            GestureAction.TOGGLE_SYMBOLS -> {
                callbacks.onKeyPress("mode_change", false)
            }
            GestureAction.TOGGLE_SHIFT -> {
                viewModel.toggleShift()
            }
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }
    var swipeUpHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeUpHintsEnabled(
                context
            )
        )
    }
    var swipeDownHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeDownHintsEnabled(
                context
            )
        )
    }

    // 监听设置变化
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED ->
                        swipeUpHintsEnabled = SettingsPreferences.isSwipeUpHintsEnabled(context)

                    SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED ->
                        swipeDownHintsEnabled = SettingsPreferences.isSwipeDownHintsEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        SubcharHelper.init(context)
    }

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val liveKeyboardBounds = remember { LiveBounds() }
    // 滑行：英文 26 键出整词（「滑行输入」）；中文全拼、双拼 26 键（含分体、自定义字母排列）出一个词的编码交给 Rime
    // （「中文滑行输入」，单独的开关，默认关）。整键盘滑动移动光标与其冲突时让位。
    // 角标照常按两个角标提示开关显示，滑行不改它（用户 10-31：不能改他的习惯）。
    val glidePrefs = LocalKeyboardInputPreferences.current
    val glideProfile = uiState.inputProfile
    val chineseGlide = !isAsciiMode && glidePrefs.chineseGlideTyping &&
        com.kingzcheung.xime.glide.chineseGlideSupported(glideProfile, uiState.currentSchemaId)
    val glideEnabled = (isAsciiMode && glidePrefs.glideTyping && callbacks.onGlideWord != null || chineseGlide && callbacks.onGlideCode != null) &&
        glidePrefs.cursorGesture != CursorGestureMode.KEYBOARD && !(isVoiceMode && !isVoiceSticky)
    val glideTrail = remember { GlideTrail() }
    val glideScope = rememberCoroutineScope()
    val glideContext = LocalContext.current.applicationContext
    // 实时预览（「滑行时实时显示词」，默认关）：手指移动中每隔 [GLIDE_PREVIEW_INTERVAL_MS] 在后台按当前整条轨迹识别一次，
    // 写出现在抬手会出的词；同一时间只算一个，抬手时作废没算完的，不拖慢抬手后的识别。
    val glidePreviewOn = glideEnabled && glidePrefs.glidePreview
    val glidePreview = remember { GlidePreviewJob() }
    val onGlideProgress: ((() -> Pair<FloatArray, FloatArray>) -> Unit)? = if (!glidePreviewOn) null else progress@{ points ->
        val gesture = glideTrail.gesture
        if (glidePreview.gesture != gesture) {
            glidePreview.start(gesture)
            glidePreview.english = if (chineseGlide) null else runCatching { callbacks.glidePreviewer?.invoke() }.getOrNull()
        }
        glidePreview.points = points
        if (glidePreview.job?.isActive == true) { glidePreview.moved = true; return@progress }
        val chinese = chineseGlide
        val doublePinyin = if (chinese && glideProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN)
            com.kingzcheung.xime.settings.DoublePinyinSchemes.find(uiState.currentSchemaId) else null
        val english = glidePreview.english
        val shift = viewModel.shiftMode.value
        val case: (String) -> String = { word -> com.kingzcheung.xime.glide.glideCased(word, shift.isShifted,
            shift == com.kingzcheung.xime.viewmodel.ShiftMode.CAPS) }
        glidePreview.job = glideScope.launch {
            do {
                glidePreview.moved = false
                val keys = geometry.snapshot()
                val (xs, ys) = glidePreview.points?.invoke() ?: break
                if (keys.size < 26) break
                val preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        val keyboard = com.kingzcheung.xime.glide.GlideLexicon.keyboard(keys)
                        if (chinese) {
                            com.kingzcheung.xime.glide.GlideLexicon.chineseDecoder(glideContext, keyboard, doublePinyin).decode(xs, ys, limit = 1)
                                .firstOrNull()?.word?.let { it to null }
                        } else {
                            val candidates = com.kingzcheung.xime.glide.GlideLexicon.decoder(glideContext, keyboard).decode(xs, ys)
                            if (candidates.isEmpty()) null else {
                                val shown = english?.invoke(candidates, case) ?: GlideEnglishPreview(case(candidates.first().word),
                                    candidates.map { case(it.word) })
                                null to shown
                            }
                        }
                    }.getOrNull()
                }
                // 抬手或已经开始下一笔：这次的结果不再显示。
                if (glideTrail.gesture != gesture || glideTrail.points.isEmpty()) break
                if (chinese) callbacks.onGlidePreviewCode?.invoke(preview?.first.orEmpty(), uiState.inputSessionId, gesture)
                else preview?.second?.let { callbacks.onGlideEnglishPreview?.invoke(it, uiState.inputSessionId) }
                // 歇一会儿再看手指有没有动，限制识别频率（在后台计时，不占主线程）。
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(GLIDE_PREVIEW_INTERVAL_MS) }
            } while (glidePreview.moved)
        }
    }
    val onGlideTrace: (FloatArray, FloatArray) -> Unit = trace@{ xs, ys ->
        glidePreview.stop()
        val heldPreview = if (chineseGlide) callbacks.onGlidePreviewHold?.invoke() else null
        if (heldPreview == null) callbacks.onGlidePreviewEnd?.invoke()
        val keys = geometry.snapshot()
        if (keys.size < 26) { heldPreview?.let { callbacks.onGlidePreviewFinish?.invoke(it, false) }; return@trace }
        // 输入框归属在抬手时确定；识别完成前切换输入框则作废。
        val inputContext = runCatching { callbacks.captureInputContext() }.getOrNull() ?: run {
            heldPreview?.let { callbacks.onGlidePreviewFinish?.invoke(it, false) }; return@trace
        }
        val shift = viewModel.shiftMode.value
        // 中文：双拼按当前方案换码，全拼为空。
        val doublePinyin = if (chineseGlide && glideProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN)
            com.kingzcheung.xime.settings.DoublePinyinSchemes.find(uiState.currentSchemaId) else null
        val chinese = chineseGlide
        glideScope.launch {
            var handedOff = false
            try {
            val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val keyboard = com.kingzcheung.xime.glide.GlideLexicon.keyboard(keys)
                if (chinese) com.kingzcheung.xime.glide.GlideLexicon.chineseDecoder(glideContext, keyboard, doublePinyin).decode(xs, ys)
                else com.kingzcheung.xime.glide.GlideLexicon.decoder(glideContext, keyboard).decode(xs, ys)
            }
            if (candidates.isEmpty()) {
                // 很短又认不出词：快打时手指一抖被当成了滑行，按点了起点那个键算，不吞这一下（用户 11-11）。
                com.kingzcheung.xime.glide.shortGlideTapKey(keys, xs, ys)?.toString()?.let { key ->
                    val value = com.kingzcheung.xime.rime.MergedLetterInput.tapValue(key,
                        KeysConfigHelper.getKeyCommitValue(key, isAsciiMode), false, shift.isShifted)
                    kotlinx.coroutines.withContext(inputContext) { onKeyPress(value) }
                }
                return@launch
            }
            viewModel.onCharacterTyped()
            if (chinese) {
                kotlinx.coroutines.withContext(inputContext) { callbacks.onGlideCode?.invoke(candidates.first().word,
                    candidates.drop(1).map { it.word }, heldPreview) }
                handedOff = true
                return@launch
            }
            val case: (String) -> String = { word -> com.kingzcheung.xime.glide.glideCased(word, shift.isShifted,
                shift == com.kingzcheung.xime.viewmodel.ShiftMode.CAPS) }
            kotlinx.coroutines.withContext(inputContext) { callbacks.onGlideWord?.invoke(candidates, case) }
            } finally {
                if (!handedOff) heldPreview?.let { callbacks.onGlidePreviewFinish?.invoke(it, false) }
            }
        }
    }
    // 提前读入词表，第一次滑行不必等待。
    LaunchedEffect(glideEnabled, chineseGlide) {
        if (glideEnabled) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (chineseGlide) com.kingzcheung.xime.glide.GlideLexicon.pinyin(glideContext)
                else {
                    com.kingzcheung.xime.glide.GlideLexicon.words(glideContext)
                    com.kingzcheung.xime.glide.GlideLexicon.bigrams(glideContext)
                }
            }
        }
    }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        // 按键和键盘都按这一刻的位置算，见 [LiveBounds]。
        val origin = liveKeyboardBounds.current(keyboardBounds, "keyboard")
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - origin.left,
                top = bounds.top - origin.top,
                right = bounds.right - origin.left,
                bottom = bounds.bottom - origin.top
            )
        )
    }



    CompositionLocalProvider(
        LocalFourteenKeyLayout provides fourteenKey,
        LocalLongVowelOnL provides (longVowelKey == JapaneseLongVowelKey.L_KEY),
        LocalLetterGeometry provides geometry,
        LocalCustomLayout provides letterLayout,
        LocalEvenMergedKeys provides evenMergedKeys,
        LocalHoneycombKeys provides honeycomb,
        LocalCustomAccent provides KeyboardThemes.getPrimaryColor(uiState.themeId, uiState.isDarkTheme),
        LocalShiftSlideTargets provides shiftSlideTargets,
        LocalLetterGlide provides glideEnabled,
        LocalKeyCornerRadius provides kbKey.cornerRadius.dp,
        LocalEnterKeyColors provides KeyboardKeyColors(KeyboardThemes.getEnterKeyColor(uiState.themeId, uiState.isDarkTheme), specialKeyTextColor),
        LocalFunctionKeyColors provides KeyboardKeyColors(specialKeyBackgroundColor, specialKeyTextColor),
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = kbKey.spacingFor("qwerty").first?.dp ?: 2.dp,
            vertical = kbKey.spacingFor("qwerty").second?.dp ?: 2.dp,
        ),
    ) {
    Box(
        modifier = bodyModifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
                liveKeyboardBounds.coordinates = coordinates
                shiftSlideTargets.keyboardBounds = dragSpace.bounds(coordinates)
            }
            // 轨迹原点与键位同用键盘卡片坐标（未旋转时与 boundsInRoot 相同），旋转的悬浮键盘滑行落在手指下的键。
            .glideTyping(glideEnabled, geometry, { shiftSlideTargets.keyboardBounds.topLeft }, glideTrail, onGlideTrace, onGlideProgress,
                onEnd = { glidePreview.stop(); callbacks.onGlidePreviewEnd?.invoke() })

    ) {
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBgColor, keyTextColor, specialKeyTextColor, keyboardBounds.width)
        if (glideEnabled) GlideTrailOverlay(glideTrail,
            KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme),
            Modifier.matchParentSize().zIndex(1f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(bottom = KeyboardGridBottomMargin)
                        .then(if (splitKeyboard) Modifier.testTag("split-keyboard") else Modifier),
            ) {
                // Hexagon caps reach a sixth of a row beyond their cell; keep that room above the
                // first row and below the last letter row (five equal rows share the height).
                val honeycombOverhang = if (honeycomb) (keyboardHeight - 8.dp) / 32 else 0.dp

                // 删除键：26 键在第三行末尾，注音在第四行末尾。
                val deleteKey: @Composable (Modifier) -> Unit = { keyModifier ->
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = keyModifier
                            .fillMaxHeight().testTag("qwerty-delete-key").semantics { contentDescription = "删除" },
                        swipeText = "清空",
                        onSwipe = { onKeyPress("clear_composition") },
                        onLongClick = { onKeyPress("delete") },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        onRelease = { onKeyRelease?.invoke("delete") },
                        followSymbolSwipeDirection = true,
                        upFeedback = KeyFlickFeedback.DELETE,
                        swipeUpLabel = "上滑清空",
                        downFeedback = KeyFlickFeedback.UNDO,
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onKeyPress("clear_all") },
                        onSwipeDown = { onKeyPress("undo_clear") },
                        distanceDelete = true,
                        onSwipeStateChange = { state, bounds ->
                            processSwipeState(
                                state,
                                bounds
                            )
                        },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = honeycombOverhang),
                    verticalArrangement = Arrangement.Top,
                ) {
                    // 数字行：点按原样上屏，和字母键上角标的数字同一条路；分体时左右各占一半，中缝与字母行对齐。
                    if (numberKeys.isNotEmpty()) {
                        val numberSplitAt = numberRowSplitAt(numberKeys.size, splitKeyboard)
                        Box(modifier = Modifier.weight(numberRowWeight)) {
                            if (isVoiceMode && !isVoiceSticky) {
                                DummyKeyboardRow(
                                    keys = numberKeys,
                                    keySpans = numberRowSpans(numberKeys.size, numberSplitAt),
                                    weightedLetters = false,
                                    keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                    splitAt = numberSplitAt, splitGap = splitGapDp, splitRow = NUMBER_ROW_SPLIT_ROW,
                                )
                            } else {
                                NumberKeyRow(
                                    keys = numberKeys,
                                    splitAt = numberSplitAt,
                                    splitGap = splitGapDp,
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    onInput = { text -> (onCommitText ?: onKeyPress)(text) },
                                    onKeyPressDown = onKeyPressDown,
                                    onKeyRelease = onKeyRelease,
                                    showHints = swipeUpHintsEnabled || swipeDownHintsEnabled,
                                )
                            }
                        }
                    }

                    // 第一行
                    if (isVoiceMode && !isVoiceSticky) {
                        Box(modifier = Modifier.weight(1f)) {
                            DummyKeyboardRow(
                                keys = renderRows.getOrNull(0).orEmpty(),
                                keySpans = renderSpans?.getOrNull(0),
                                weightedLetters = LocalCustomLayout.current != null || fourteenKey,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor,
                                splitAt = splitAt0, splitGap = splitGapDp, splitRow = 0,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            val row0 = renderRows.getOrElse(0) { listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p") }
                            KeyboardRowWithConfig(
                                keys = row0,
                                keySpans = renderSpans?.getOrNull(0),
                                edgeInset = if (honeycomb) (splitCellDp / 2f).dp else 0.dp,
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                ),
                                isShifted = isShifted,
                                isAsciiMode = isAsciiMode,
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(
                                        state,
                                        bounds
                                    )
                                },
                                onKeyPressDown = onKeyPressDown,
                                onKeyRelease = onKeyRelease,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                onGestureAction = onGestureAction,
                                configVersion = cfgVer,
                                splitAt = splitAt0, splitGap = splitGapDp, splitRow = 0,
                            )
                        }
                    }

                    // 第二行
                    if (isVoiceMode && !isVoiceSticky) {
                        // 右半补 G 时行尾留白交给这一行自己算（右半贴到行尾），与正常按键行一致。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = standardRowInset ?: 16.dp, end = if (splitRightG) 0.dp else standardRowEndInset ?: 16.dp)
                        ) {
                            DummyKeyboardRow(
                                keys = renderRows.getOrNull(1).orEmpty(),
                                keySpans = renderSpans?.getOrNull(1),
                                weightedLetters = LocalCustomLayout.current != null || fourteenKey,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor,
                                splitAt = splitAt1, splitGap = splitGapDp, splitRow = 1,
                                splitRightG = splitRightG, endInset = standardRowEndInset ?: 16.dp,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            val row1 = renderRows.getOrElse(1) { listOf("a", "s", "d", "f", "g", "h", "j", "k", "l") }
                            val row1Inset = if (zhuyin) (splitCellDp / 2f).dp else standardRowInset
                                ?: if ((if (LocalCustomLayout.current != null) row1.sumOf { it.length } else row1.size) > 9) 0.dp else 16.dp
                            val (row1Start, row1End) = fourteenHomeRowInsets(fourteenKey, evenMergedKeys,
                                row1Inset.value, (standardRowEndInset ?: row1Inset).value)
                            KeyboardRowWithConfig(
                                keys = row1,
                                keySpans = renderSpans?.getOrNull(1),
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                ),
                                isShifted = isShifted,
                                isAsciiMode = isAsciiMode,
                                edgeInset = row1Start.dp,
                                edgeEndInset = row1End.dp,
                                extendLastKeyToEdge = fourteenHomeRowLastKeyToEdge(fourteenKey, row1, evenMergedKeys),
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(
                                        state,
                                        bounds
                                    )
                                },
                                onKeyPressDown = onKeyPressDown,
                                onKeyRelease = onKeyRelease,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                onGestureAction = onGestureAction,
                                configVersion = cfgVer,
                                splitAt = splitAt1, splitGap = splitGapDp, splitRow = 1,
                                splitRightG = splitRightG,
                            )
                        }
                    }

                    // 第三行
                    if (isVoiceMode && !isVoiceSticky) {
                        Box(modifier = Modifier.weight(1f)) {
                            DummyBottomRow(
                                keys = renderRows.getOrNull(2).orEmpty(),
                                keySpans = renderSpans?.getOrNull(2),
                                weightedLetters = LocalCustomLayout.current != null || fourteenKey,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                specialKeyBackgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor,
                                sideKeyWeight = if (!shiftKeyShown) 0f else geometryOverride?.shiftKeyWeight
                                    ?: if (letterRowGeometry != null) letterSideKeyWeight else 1.2f,
                                endKeyWeight = geometryOverride?.deleteKeyWeight
                                    ?: if (letterRowGeometry != null) letterDeleteKeyWeight else 1.2f,
                                letterWeight = letterBottomWeight,
                                splitAt = splitAt2, splitGap = splitGapDp,
                            )
                        }
                    } else if (zhuyin) {
                        // 注音第三行同第二行：10 键缩进半键，没有 Shift 和删除键。
                        Box(modifier = Modifier.weight(1f)) {
                            KeyboardRowWithConfig(
                                keys = renderRows.getOrNull(2).orEmpty(),
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                ),
                                isShifted = false,
                                isAsciiMode = isAsciiMode,
                                edgeInset = (splitCellDp / 2f).dp,
                                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                onKeyPressDown = onKeyPressDown,
                                onKeyRelease = onKeyRelease,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                onGestureAction = onGestureAction,
                                configVersion = cfgVer,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            if (shiftKeyShown) CompositionLocalProvider(LocalHoneycombEdge provides if (honeycomb) HoneycombEdge.START else null) {
                            val shiftSlot = Modifier.weight(letterSideKeyWeight)
                            SemicolonShiftKey(
                                ingActive = ingOnShift,
                                onKeyPress = onKeyPress,
                                onKeyPressDown = onKeyPressDown,
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = shiftSlot,
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            ) {
                            ShiftCapsKeyButton(
                                viewModel = viewModel,
                                shiftMode = shiftMode,
                                onKeyPress = onKeyPress,
                                onKeyPressDown = onKeyPressDown,
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                accentColor = KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme),
                                panelColor = keyboardBackgroundColor,
                                modifier = shiftSlot
                                    .fillMaxHeight(),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                composingSplit = shiftComposingSplit,
                                tapKey = CHOONPU_INPUT.takeIf { choonpuOnShift },
                                tapLabel = CHOONPU_KEY.takeIf { choonpuOnShift },
                            )
                            }
                            }

                                val bottomKeys = renderRows.getOrElse(2) { listOf("z", "x", "c", "v", "b", "n", "m") }
                                val bottomLayout = LocalCustomLayout.current
                                val bottomEvenMerged = LocalEvenMergedKeys.current
                                val bottomWeighted = bottomLayout != null || fourteenKey
                                // 调过宽度的键按调过的格数，其余仍按字母数/合并键均匀排布的规则。
                                fun bottomWeight(key: String): Float =
                                    if (!bottomWeighted) 1f
                                    else bottomLayout?.widths?.get(key) ?: letterKeySpan(key, true, bottomEvenMerged)
                                // 14 键的 m 与 26 键的 m 同位同宽；删除键保持与 Shift 对称的宽度。
                                // 宽屏错位让删除键比 Shift 宽时，m 只借这部分富余的一半，最多一个键宽。
                                val fourteenMExtra = if (fourteenKey && splitAt2 < 0 && bottomKeys.lastOrNull()?.length == 1)
                                    fourteenMBorrowedWeight(frameSideKeyWeight, letterDeleteKeyWeight) else 0f
                                val bottomSegments = if (splitAt2 < 0) listOf(bottomKeys)
                                    else listOf(bottomKeys.take(splitAt2), bottomKeys.drop(splitAt2))
                                bottomSegments.forEachIndexed { segment, segmentKeys ->
                                if (segment > 0) SplitGapSpacer(2, splitGapDp)
                                Row(
                                    modifier = Modifier
                                        .weight(if (splitAt2 < 0) letterBottomWeight + fourteenMExtra else segmentKeys
                                            .sumOf { bottomWeight(it).toDouble() }.toFloat().coerceAtLeast(0.001f))
                                        .fillMaxHeight(),
                                ) {
                                segmentKeys.forEach { key ->
                                    // 「按键符号」给这个键设的上下滑动作（如 Z X C V 预设）：角标写动作名，动作由 SwipeableKeyButton 套用。
                                    val (boundUpLabel, boundDownLabel) = letterSwipeCornerLabels(key)
                                    val rawSwipeUpLabel = boundUpLabel ?: KeysConfigHelper.getSwipeUpLabel(key, isAsciiMode)
                                    val swipeUpText =
                                        if (swipeUpHintsEnabled) rawSwipeUpLabel else null
                                    val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key, isAsciiMode)
                                    val swipeUpDisplay = if (boundUpLabel != null) DisplayMode.BOTH else KeysConfigHelper.getSwipeUpDisplay(key, isAsciiMode)
                                    val swipeUpKeyLabel =
                                        if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else ""
                                    val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
                                    val swipeDownRaw =
                                        KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
                                    val swipeDownLabel =
                                        swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
                                    val swipeDownAction = swipeDownRaw?.action
                                    val swipeDownValue = swipeDownRaw?.value
                                    val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
                                    val swipeDownBubbleText = if (swipeDownDisplay != DisplayMode.KEY && swipeDownHintsEnabled) swipeDownLabel else null
                                    val longPressConfig =
                                        KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
                                    val longPressDisplay = (longPressConfig?.display ?: "key").takeIf {
                letterLongPressMenuAllowed(key, LocalKeyboardInputPreferences.current.letterLongPressMenu ||
                    KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty()) } ?: "key"
                                    val longPressLabels = if (longPressDisplay == "bubble") {
                                        longPressConfig?.values?.map { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }
                                            ?.filter { it.isNotEmpty() }?.ifEmpty { null }
                                    } else null
                                    val longPressGestureMap = if (longPressDisplay == "bubble") {
                                        longPressConfig?.values?.associateBy { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }
                                    } else null

                                    val rawCommitValue = KeysConfigHelper.getKeyCommitValue(key, isAsciiMode)
                                    val commitValue = com.kingzcheung.xime.rime.MergedLetterInput.tapValue(
                key, rawCommitValue, LocalFourteenKeyLayout.current, isShifted)
                                    val displayText = KeysConfigHelper.getKeyDisplayLabel(key, isAsciiMode,
                isShifted || LocalKeyboardInputPreferences.current.uppercaseKeyLabels).let { semicolonKeyLabel(key, isAsciiMode, it) }.let { longVowelKeyLabel(key, it, LocalLongVowelOnL.current) }

                                    val onClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
                                    val onPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
                                    val onRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
                                    val onSwipeDown = if (swipeDownAction != null && swipeDownAction != GestureAction.NONE && swipeDownLabel != null) {
                                        remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                                            val label = swipeDownLabel
                                            { _: String ->
                                                if (swipeDownAction == GestureAction.COMMIT) {
                                                    (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                                                } else {
                                                    onGestureAction?.invoke(
                                                        swipeDownAction,
                                                        swipeDownValue?.ifEmpty { label } ?: label)
                                                }
                                                Unit
                                            }
                                        }
                                    } else null
                                    val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                                        val gesture = longPressGestureMap?.get(selectedLabel)
                                        if (gesture != null && gesture.action != GestureAction.COMMIT) {
                                            onGestureAction?.invoke(
                                                gesture.action!!,
                                                gesture.value.ifEmpty { selectedLabel })
                                        } else {
                                            // 气泡里显示的是 Shift 后的大写（È），送出的仍是原字母（è），大小写照点按一样由 Shift 决定。
                                            commitLongPressChoice(gesture?.label ?: selectedLabel, onCommitText, onKeyPress, KeysConfigHelper.longPressLetters(isAsciiMode))
                                        }
                                        Unit
                                    } }

                                    SwipeableKeyButton(
                                        layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                                        followSymbolSwipeDirection = true,
                                        text = displayText,
                                        letterGroup = if (isShifted) key.uppercase() else key,
                                        onLetterSelection = onKeyPress,
                                        onClick = onClick,
                                        backgroundColor = keyBackgroundColor,
                                        textColor = keyTextColor,
                                        modifier = Modifier.weight(if (bottomWeighted) (renderSpans?.getOrNull(2)?.getOrNull(bottomKeys.indexOf(key))
                                            ?: bottomWeight(key)) +
                                            (if (key === bottomKeys.last()) fourteenMExtra else 0f) else 1f)
                                            .testTag("qwerty-key:$key"),
                                        swipeText = swipeUpText,
                                        symbolInputText = symbolInputValue(swipeUpCommitValue, swipeUpAction)
                    .takeUnless { longPressLabels != null && KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty() },
                                        symbolInputDownText = symbolInputValue(swipeDownLabel, swipeDownAction),
                                        swipeDownText = swipeDownBubbleText,
                                        swipeUpKeyLabel = swipeUpKeyLabel,
                                        swipeDownKeyLabel = boundDownLabel?.takeIf { swipeDownHintsEnabled }
                    ?: if ((swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) && swipeDownHintsEnabled) swipeDownLabel else null,
                                        onSwipe = if (swipeUpCommitValue != null && swipeUpAction != GestureAction.NONE) { {
                    if (swipeUpAction == null || swipeUpAction == GestureAction.COMMIT) {
                        (onCommitText ?: onKeyPress)(swipeUpCommitValue)
                    } else {
                        onGestureAction?.invoke(swipeUpAction, swipeUpCommitValue)
                    }
                    Unit
                } } else null,
                                        onSwipeDown = onSwipeDown,
                                        onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                        onPress = onPress,
                                        onRelease = onRelease,
                                        onLongPressSelect = onLongPressSelect,
                                        longPressItems = longPressLabels,
                                        shadowEnabled = shadowEnabled,
                                        shadowElevation = shadowElevation,
                                        shadowShapeRadius = shadowShapeRadius,
                                    )
                                }
                            }
                            }

                            CompositionLocalProvider(LocalHoneycombEdge provides if (honeycomb) HoneycombEdge.END else null) {
                            deleteKey(Modifier.weight(letterDeleteKeyWeight - fourteenMExtra))
                            }
                        }
                    }

                    // 蜂窝布局的第四行字母（zxcvbnm），与第二行同宽对齐。注音的第四行 10 键，删除键占第 11 列。
                    renderRows.getOrNull(3)?.let { row3 ->
                        Row(modifier = Modifier.weight(1f)) {
                            if (isVoiceMode && !isVoiceSticky) DummyKeyboardRow(
                                keys = row3, weightedLetters = true,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor,
                            ) else KeyboardRowWithConfig(
                                modifier = Modifier.weight(row3.size.toFloat()),
                                keys = row3,
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                ),
                                isShifted = isShifted,
                                isAsciiMode = isAsciiMode,
                                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                onKeyPressDown = onKeyPressDown,
                                onKeyRelease = onKeyRelease,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                onGestureAction = onGestureAction,
                                configVersion = cfgVer,
                            )
                            if (zhuyin && !(isVoiceMode && !isVoiceSticky)) deleteKey(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(honeycombOverhang))
                    }

                    // 底排保留共用权重及逗号/地球等宽；宽屏实体字母错位不移动底排。
                    // 注音的底排同 26 键的比例（首行 11 键不是旧式布局）。
                    val bottom = letterKeyboardBottomRow(keyRows, fourteenKey || zhuyin)
                    // 空格左侧逗号、右侧句号；只开逗号时沿用原配置的逗号键。
                    val bottomPrefs = LocalKeyboardInputPreferences.current
                    val commaOnLeft = bottomPrefs.qwertyCommaKey
                    val periodOnRight = bottomPrefs.qwertyPeriodKey
                    val punctuationFull = LocalKeyboardPunctuation.current?.full ?: !isAsciiMode
                    val (leftPunctuation, rightPunctuation) = spacePunctuationKeys(commaOnLeft, periodOnRight,
                        punctuationFull, english = isAsciiMode || uiState.inputProfile.language.usesHalfWidthPunctuation,
                        japanese = LocalKeyboardPunctuation.current?.japanese == true)
                    val punctuationCount = (if (commaOnLeft) 1 else 0) + (if (periodOnRight) 1 else 0)
                    val bottomSpaceWeight = bottom.space + bottom.punctuation * (1 - punctuationCount)
                    // 键面写什么就上屏什么：只有与当前宽度一致的全角标点走普通按键；
                    // 半角模式下滑的全角标点、中文状态的半角标点都原样上屏，避免被宽度设置转回去。
                    val commitPunctuation: (String) -> Unit = { text ->
                        if (isAsciiMode || (punctuationFull && text.any { it.code > 0x7E })) onKeyPress(text)
                        else (callbacks.onCommitExactText ?: onCommitText ?: onKeyPress)(text)
                    }
                    // 逗号键分词：有逗号键放逗号键左下角，只开句号键放句号键。
                    val (leftSplit, rightSplit) = syllableSplitOnPunctuation(syllableSplit, leftPunctuation, rightPunctuation)
                    @Composable
                    fun RowScope.PunctuationKey(key: SpacePunctuationKey, tag: String, split: Boolean = false) =
                        // 主副字符已按全角/半角写定并原样上屏；键面不能再转一次，否则角标与副字符的宽度相反。
                        CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
                        SwipeableKeyButton(
                            layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                            followSymbolSwipeDirection = !keyLabelPrefs.bottomPunctuationSwipeUp,
                            keepSymbolSwipeOnLongPress = keyLabelPrefs.bottomPunctuationSwipeUp,
                            text = key.primary,
                            onClick = { commitPunctuation(key.primary) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(bottom.punctuation).testTag(tag),
                            swipeText = key.alternate.ifEmpty { null },
                            symbolInputText = key.alternate.ifEmpty { null },
                            onSwipe = if (key.alternate.isEmpty()) null else { _ -> commitPunctuation(key.alternate) },
                            // 分词占另一个方向；固定上滑副标点时，分词始终下滑。
                            swipeDownText = if (split) SYLLABLE_SEPARATOR else null,
                            swipeDownKeyLabel = if (split && swipeDownHintsEnabled) SYLLABLE_SEPARATOR else null,
                            onSwipeDown = if (split) { _ -> onKeyPress(SYLLABLE_SEPARATOR) } else null,
                            onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                            onPress = { onKeyPressDown?.invoke(key.primary) },
                            onRelease = { onKeyRelease?.invoke(key.primary) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        }
                    // 第四行（控制行）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        if (isVoiceMode && !isVoiceSticky) {
                            DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(bottom.mode * 2)
                            )
                            if (commaOnLeft) DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(bottom.punctuation)
                            )
                        } else {
                            for (slot in 1..2) {
                                KeyboardModeKey(
                                    slot = slot, onKeyPress = onKeyPress,
                                    backgroundColor = specialKeyBackgroundColor,
                                    textColor = specialKeyTextColor,
                                    modifier = Modifier.weight(bottom.mode),
                                    onKeyPressDown = onKeyPressDown,
                                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    ctrlTargets = shiftSlideTargets,
                                    onCtrlShortcut = { letter ->
                                        onGestureAction(GestureAction.COMMAND, com.kingzcheung.xime.keyboard.CtrlShortcuts.command(letter))
                                    },
                                )
                            }

                            if (leftPunctuation != null) PunctuationKey(leftPunctuation, "qwerty-punctuation-key", leftSplit)
                            else if (commaOnLeft) {
                            // 逗号 — 从配置读取 "'"
                            val k2KeyGesture = KeysConfigHelper.getKeyGesture("'", isAsciiMode)
                            val k2TapAction = k2KeyGesture?.tap?.action
                            val k2TapValue = k2KeyGesture?.tap?.value?.takeIf { it.isNotEmpty() }
                                ?: k2KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() }
                                ?: if (isAsciiMode) "," else "，"
                            val k2TapLabel = k2KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() } ?: k2TapValue
                            val k2SwipeUpRaw = k2KeyGesture?.swipeUp
                            val k2SwipeUpLabel = if (isAsciiMode)
                                (k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
                                else (k2SwipeUpRaw?.label?.takeIf { it.isNotEmpty() } ?: k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
                            val k2SwipeUpValue = k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() }
                                ?: k2SwipeUpRaw?.label?.takeIf { it.isNotEmpty() }
                            val k2SwipeUpDisplay = k2SwipeUpRaw?.display ?: DisplayMode.BOTH
                            val k2SwipeUpCommitValue = k2SwipeUpValue
                            val k2SwipeDownRaw = k2KeyGesture?.swipeDown
                            val k2SwipeDownLabel = k2SwipeDownRaw?.label?.takeIf { it.isNotEmpty() }
                            val k2SwipeDownAction = k2SwipeDownRaw?.action
                            val k2SwipeDownValue = k2SwipeDownRaw?.value
                            val k2SwipeDownDisplay = k2SwipeDownRaw?.display ?: DisplayMode.BOTH
                            val k2SwipeDownBubbleText = if (k2SwipeDownDisplay != DisplayMode.KEY) k2SwipeDownLabel else null
                            val k2LongPressConfig = k2KeyGesture?.longPress
                            val k2LongPressDisplay = k2LongPressConfig?.display ?: "key"
                            val k2LongPressLabels = if (k2LongPressDisplay == "bubble") {
                                k2LongPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                                    ?.ifEmpty { null }
                            } else null
                            val k2LongPressGestureMap = if (k2LongPressDisplay == "bubble") {
                                k2LongPressConfig?.values?.associateBy { it.label }
                            } else null
                            val k2OnClick: () -> Unit = remember(k2TapAction, k2TapValue, onKeyPress, onGestureAction) {
                                {
                                    if (k2TapAction != null && k2TapAction != GestureAction.COMMIT) {
                                        onGestureAction?.invoke(k2TapAction, k2TapValue)
                                    } else {
                                        onKeyPress(k2TapValue)
                                    }
                                }
                            }
                            val k2OnSwipeDown: ((String) -> Unit)? = if (k2SwipeDownAction != null && k2SwipeDownLabel != null) {
                                remember(k2SwipeDownAction, k2SwipeDownValue, k2SwipeDownLabel, onKeyPress, onGestureAction, onCommitText) {
                                    val label = k2SwipeDownLabel
                                    { _: String ->
                                        if (k2SwipeDownAction == GestureAction.COMMIT) {
                                            (onCommitText ?: onKeyPress)(k2SwipeDownValue?.ifEmpty { label } ?: label)
                                        } else {
                                            onGestureAction?.invoke(k2SwipeDownAction, k2SwipeDownValue?.ifEmpty { label } ?: label)
                                        }
                                        Unit
                                    }
                                }
                            } else null
                            val k2OnLongPressSelect: ((String) -> Unit)? = remember(k2LongPressGestureMap, onGestureAction, onCommitText, onKeyPress) {
                                { selectedLabel: String ->
                                    val gesture = k2LongPressGestureMap?.get(selectedLabel)
                                    if (gesture != null && gesture.action != GestureAction.COMMIT) {
                                        onGestureAction?.invoke(gesture.action!!, gesture.value.ifEmpty { selectedLabel })
                                    } else {
                                        // 气泡里写的就是要上屏的写法（如全角时选半角 , .），不再随全角/半角转换。
                                        (callbacks.onCommitExactText ?: onCommitText ?: onKeyPress)(selectedLabel)
                                    }
                                    Unit
                                }
                            }
                            if (k2TapAction == GestureAction.TOGGLE_ASCII) {
                                LanguageKeyButton(
                                    icon = rememberVectorPainter(Icons.Default.Language),
                                    onClick = k2OnClick,
                                    backgroundColor = specialKeyBackgroundColor,
                                    iconColor = specialKeyTextColor,
                                    modifier = Modifier.weight(bottom.punctuation).testTag("qwerty-punctuation-key"),
                                    onPress = { onKeyPressDown?.invoke(k2TapValue) },
                                    onRelease = { onKeyRelease?.invoke(k2TapValue) },
                                    swipeText = k2SwipeUpLabel,
                                    swipeDownText = k2SwipeDownBubbleText,
                                    swipeDownKeyLabel = if (k2SwipeDownDisplay != DisplayMode.BUBBLE) k2SwipeDownLabel else null,
                                    onSwipe = if (k2SwipeUpCommitValue != null) { { onKeyPress(k2SwipeUpCommitValue) } } else null,
                                    onSwipeDown = k2OnSwipeDown,
                                    onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            } else {
                                SwipeableKeyButton(
                                    layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                                    followSymbolSwipeDirection = true,
                                    text = k2TapLabel,
                                    onClick = k2OnClick,
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(bottom.punctuation).testTag("qwerty-punctuation-key"),
                                    swipeText = k2SwipeUpLabel,
                                    symbolInputText = symbolInputValue(k2SwipeUpCommitValue, k2SwipeUpRaw?.action),
                                    swipeDownText = k2SwipeDownBubbleText,
                                    swipeDownKeyLabel = if ((k2SwipeDownDisplay == DisplayMode.KEY || k2SwipeDownDisplay == DisplayMode.BOTH)) k2SwipeDownLabel else null,
                                    onSwipe = if (k2SwipeUpCommitValue != null) { { onKeyPress(k2SwipeUpCommitValue) } } else null,
                                    onSwipeDown = k2OnSwipeDown,
                                    onSwipeStateChange = { state, bounds ->
                                        processSwipeState(state, bounds)
                                    },
                                    onPress = { onKeyPressDown?.invoke(k2TapValue) },
                                    onRelease = { onKeyRelease?.invoke(k2TapValue) },
                                    onLongPressSelect = k2OnLongPressSelect,
                                    longPressItems = k2LongPressLabels,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                            }
                        }

                        // 分体时空格在中线处分成左右两块，两块都是完整的空格键。
                        val spaceParts = if (splitKeyboard) SplitKeyboardGeometry.spaceSplit(bottom, bottomSpaceWeight,
                            bottom.mode * 2f + if (commaOnLeft) bottom.punctuation else 0f).toList()
                            else listOf(bottomSpaceWeight)
                        spaceParts.forEachIndexed { part, spaceWeight ->
                            if (part > 0) SplitGapSpacer(3, splitGapDp)
                            SpaceKey(
                                schemaName = if (part == 0) schemaName else "",
                                isAsciiMode = isAsciiMode && part == 0,
                                isSttEnabled = isSttEnabled,
                                isVoiceMode = isVoiceMode,
                                voiceSticky = isVoiceSticky,
                                keyBackgroundColor = keyBackgroundColor,
                                keyTextColor = keyTextColor,
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                modifier = Modifier.weight(spaceWeight),
                                onKeyPress = onKeyPress,
                                onKeyPressDown = onKeyPressDown,
                                onKeyRelease = onKeyRelease,
                                onVoiceModeChange = onVoiceModeChange,
                            )
                        }

                        if (rightPunctuation != null) {
                            if (isVoiceMode && !isVoiceSticky) DummyKeyButton(
                                backgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(bottom.punctuation)
                            ) else PunctuationKey(rightPunctuation, "qwerty-period-key", rightSplit)
                        }

                        // 中/英切换 + 回车（硬编码 + 配置驱动）
                        if (isVoiceMode && !isVoiceSticky) {
                            DummyKeyButton(
                                backgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(bottom.language)
                            )
                            DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(bottom.enter)
                            )
                        } else {
                            // earth — 从配置读取
                            val k4KeyGesture = KeysConfigHelper.getKeyGesture("earth", isAsciiMode)
                            val k4TapAction = k4KeyGesture?.tap?.action
                            // 无 tap 手势配置时回退地球键默认语义：地球图标 + 中英切换。
                            // 否则键面空白（label=""/icon=null）且点击发出空键值——空键值在
                            // 中文模式会触发 ImeKeyRouter lowercase()[0] 越界崩溃（2026-09-14 实证）。
                            val isEarthDefaultTap = k4KeyGesture?.tap == null
                            val k4TapValue = k4KeyGesture?.tap?.value?.takeIf { it.isNotEmpty() } ?: "ime_switch"
                            val k4TapLabel = k4KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() } ?: "中"
                            val k4Icon: Painter? = k4KeyGesture?.tap?.icon?.takeIf { it.isNotEmpty() }?.let { iconName ->
                                val iv = when (iconName) {
                                    "language", "globe" -> Icons.Default.Language
                                    else -> null
                                }
                                iv?.let { rememberVectorPainter(it) }
                            } ?: if (isEarthDefaultTap) rememberVectorPainter(Icons.Default.Language) else null
                            val k4SwipeUpRaw = k4KeyGesture?.swipeUp
                            val k4SwipeUpLabel = if (isAsciiMode)
                                (k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
                                else (k4SwipeUpRaw?.label?.takeIf { it.isNotEmpty() } ?: k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
                            val k4SwipeUpValue = k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() }
                                ?: k4SwipeUpRaw?.label?.takeIf { it.isNotEmpty() }
                            val k4SwipeUpAction = k4SwipeUpRaw?.action
                            val k4SwipeDownRaw = k4KeyGesture?.swipeDown
                            val k4SwipeDownLabel = k4SwipeDownRaw?.label?.takeIf { it.isNotEmpty() }
                            val k4SwipeDownAction = k4SwipeDownRaw?.action
                            val k4SwipeDownValue = k4SwipeDownRaw?.value
                            val k4SwipeDownDisplay = k4SwipeDownRaw?.display ?: DisplayMode.BOTH
                            val k4SwipeDownBubbleText = if (k4SwipeDownDisplay != DisplayMode.KEY) k4SwipeDownLabel else null
                            val k4LongPressConfig = k4KeyGesture?.longPress
                            val k4LongPressDisplay = k4LongPressConfig?.display ?: "key"
                            val k4LongPressLabels = if (k4LongPressDisplay == "bubble") {
                                k4LongPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                                    ?.ifEmpty { null }
                            } else null
                            val k4LongPressGestureMap = if (k4LongPressDisplay == "bubble") {
                                k4LongPressConfig?.values?.associateBy { it.label }
                            } else null
                            val k4OnClick: () -> Unit = remember(k4TapAction, k4TapValue, onKeyPress, onGestureAction) {
                                {
                                    if (k4TapAction != null && k4TapAction != GestureAction.COMMIT) {
                                        onGestureAction?.invoke(k4TapAction, k4TapValue)
                                    } else {
                                        onKeyPress(k4TapValue)
                                    }
                                }
                            }
                            val k4OnSwipe: ((String) -> Unit)? = if (k4SwipeUpValue != null && k4SwipeUpAction != GestureAction.NONE) {
                                remember(k4SwipeUpValue, onKeyPress) { { onKeyPress(k4SwipeUpValue) } }
                            } else null
                            val k4OnSwipeDown: ((String) -> Unit)? = if (k4SwipeDownAction != null && k4SwipeDownLabel != null) {
                                remember(k4SwipeDownAction, k4SwipeDownValue, k4SwipeDownLabel, onKeyPress, onGestureAction, onCommitText) {
                                    val label = k4SwipeDownLabel
                                    { _: String ->
                                        if (k4SwipeDownAction == GestureAction.COMMIT) {
                                            (onCommitText ?: onKeyPress)(k4SwipeDownValue?.ifEmpty { label } ?: label)
                                        } else {
                                            onGestureAction.invoke(k4SwipeDownAction, k4SwipeDownValue?.ifEmpty { label } ?: label)
                                        }
                                        Unit
                                    }
                                }
                            } else null
                            val k4OnLongPressSelect: ((String) -> Unit)? = remember(k4LongPressGestureMap, onGestureAction, onCommitText, onKeyPress) {
                                { selectedLabel: String ->
                                    val gesture = k4LongPressGestureMap?.get(selectedLabel)
                                    if (gesture != null && gesture.action != GestureAction.COMMIT) {
                                        onGestureAction.invoke(gesture.action!!, gesture.value.ifEmpty { selectedLabel })
                                    } else {
                                        commitLongPressChoice(selectedLabel, onCommitText, onKeyPress)
                                    }
                                    Unit
                                }
                            }
                            if ((k4TapAction == GestureAction.TOGGLE_ASCII && k4Icon != null || isEarthDefaultTap) && k4LongPressLabels == null) {
                                LanguageKeyButton(
                                    icon = k4Icon ?: rememberVectorPainter(Icons.Default.Language),
                                    onClick = k4OnClick,
                                    backgroundColor = specialKeyBackgroundColor,
                                    iconColor = specialKeyTextColor,
                                    modifier = Modifier.weight(bottom.language),
                                    onPress = { onKeyPressDown?.invoke(k4TapValue) },
                                    onRelease = { onKeyRelease?.invoke(k4TapValue) },
                                    swipeText = k4SwipeUpLabel.takeIf { it.isNotEmpty() },
                                    swipeDownText = k4SwipeDownBubbleText,
                                    swipeDownKeyLabel = if (k4SwipeDownDisplay != DisplayMode.BUBBLE) k4SwipeDownLabel else null,
                                    onSwipe = k4OnSwipe,
                                    onSwipeDown = k4OnSwipeDown,
                                    onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            } else {
                                LanguageKeyButton(
                                    layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                                    text = k4TapLabel,
                                    onClick = k4OnClick,
                                    backgroundColor = specialKeyBackgroundColor,
                                    textColor = specialKeyTextColor,
                                    modifier = Modifier.weight(bottom.language),
                                    icon = k4Icon,
                                    swipeText = k4SwipeUpLabel.takeIf { it.isNotEmpty() },
                                    swipeDownText = k4SwipeDownBubbleText,
                                    swipeDownKeyLabel = if ((k4SwipeDownDisplay == DisplayMode.KEY || k4SwipeDownDisplay == DisplayMode.BOTH)) k4SwipeDownLabel else null,
                                    onSwipe = k4OnSwipe,
                                    onSwipeDown = k4OnSwipeDown,
                                    onSwipeStateChange = { state, bounds ->
                                        processSwipeState(state, bounds)
                                    },
                                    onPress = { onKeyPressDown?.invoke(k4TapValue) },
                                    onRelease = { onKeyRelease?.invoke(k4TapValue) },
                                    onLongPressSelect = k4OnLongPressSelect,
                                    longPressItems = k4LongPressLabels,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }

                            // 回车 — 硬编码。按住 !@# 时另一根手指点回车、或从 !@# 拖到回车上松手：Ctrl+回车，发送（用户 11-03）。
                            // 按住 Shift 点回车、或从 Shift 拖到回车上松手：Shift+回车，换行（用户 11-04）。
                            // 只认手指按着的 Shift：自动大写、单次大写、大写锁定时的回车照常（不把句首回车变成换行）。
                            // 和字母键同一种登记：位置记成状态，每次重组都按当前位置登记到当前这块键盘的目标里，
                            // 不靠某一次布局回调（键盘重建、目标换了一份时回车不会漏登记）；id 每个回车键各一个，别的键盘撤销不到它。
                            val enterTargetId = remember { Any() }
                            var enterBounds by remember { mutableStateOf(Rect.Zero) }
                            val shiftEnter: () -> Unit = {
                                onGestureAction(GestureAction.COMMAND, com.kingzcheung.xime.keyboard.CtrlShortcuts.SHIFT_ENTER_COMMAND)
                            }
                            DisposableEffect(shiftSlideTargets) { onDispose { shiftSlideTargets.remove(enterTargetId) } }
                            androidx.compose.runtime.SideEffect {
                                if (enterBounds != Rect.Zero) shiftSlideTargets.putEnter(enterTargetId, enterBounds, shiftEnter)
                            }
                            ActionKeyButton(
                                text = enterKeyText,
                                onClick = {
                                    if (!shiftSlideTargets.ctrlEnter()) {
                                        if (viewModel.shiftMode.value == ShiftMode.HELD) {
                                            // 算用过 Shift：松开 Shift 不再当点按切大小写。
                                            viewModel.onCharacterTyped()
                                            shiftEnter()
                                        } else onKeyPress("enter")
                                    }
                                },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(bottom.enter).testTag("qwerty-enter-key")
                                    .onGloballyPositioned { enterBounds = dragSpace.bounds(it) },
                                onPress = { onKeyPressDown?.invoke("enter") },
                                onRelease = { onKeyRelease?.invoke("enter") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                }

            }

        ShiftSlideLine(shiftSlideTargets, { shiftSlideTargets.keyboardBounds.topLeft },
            KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme), Modifier.matchParentSize())

        // 语音模式中央麦克风图标
        if (isVoiceMode && !isVoiceSticky) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = keyTextColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }

    }
    }
    }
    }
}

@Composable
private fun DummyKeyboardRow(
    keys: List<String>,
    weightedLetters: Boolean,
    /** 每个键的份数（14 键拆开时，见 [splitPairSpans]）；null 按 [letterKeySpan]。 */
    keySpans: List<Float>? = null,
    keyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    splitAt: Int = -1,
    splitGap: Dp = 0.dp,
    splitRow: Int = 0,
    /** 分体中排右半补 G（同 [KeyboardRowWithConfig]）；为 true 时行尾留白 [endInset] 由这里计算，外层不再留。 */
    splitRightG: Boolean = false,
    endInset: Dp = 0.dp,
) {
    val customLayout = LocalCustomLayout.current
    val evenMerged = LocalEvenMergedKeys.current
    fun spanOf(key: String): Float =
        customLayout?.widths?.get(key) ?: letterKeySpan(key, weightedLetters, evenMerged)
    if (!splitRightG) {
        Row(
            modifier = modifier
                .fillMaxWidth(),
        ) {
            keys.forEachIndexed { index, key ->
                if (index == splitAt) SplitGapSpacer(splitRow, splitGap)
                DummyKeyButton(
                    backgroundColor = keyBackgroundColor,
                    modifier = Modifier.weight(keySpans?.getOrNull(index) ?: spanOf(key))
                )
            }
            if (splitAt == keys.size) SplitGapSpacer(splitRow, splitGap)
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = if (splitAt in 0..keys.size) splitGap.coerceAtMost(maxWidth) else 0.dp
        val unitWidth = (maxWidth - endInset - gap) / keys.size.coerceAtLeast(1)
        val homeRow = SplitKeyboardGeometry.homeRowWithRightG(keys, splitAt, !weightedLetters,
            gap.value, endInset.value, unitWidth.value)
        Row(Modifier.fillMaxWidth().padding(end = homeRow.endInset.dp)) {
            homeRow.keys.forEachIndexed { index, key ->
                if (index == splitAt) SplitGapSpacer(splitRow, homeRow.gap.dp)
                DummyKeyButton(
                    backgroundColor = keyBackgroundColor,
                    modifier = Modifier.weight(spanOf(key))
                )
            }
            if (splitAt == homeRow.keys.size) SplitGapSpacer(splitRow, homeRow.gap.dp)
        }
    }
}

@Composable
private fun DummyBottomRow(
    keys: List<String>,
    weightedLetters: Boolean,
    /** 每个键的份数（14 键拆开时）；null 按 [letterKeySpan]。 */
    keySpans: List<Float>? = null,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    sideKeyWeight: Float = 1.2f,
    endKeyWeight: Float = sideKeyWeight,
    letterWeight: Float = 7f,
    splitAt: Int = -1,
    splitGap: Dp = 0.dp,
) {
    val customLayout = LocalCustomLayout.current
    val evenMerged = LocalEvenMergedKeys.current
    fun spanOf(key: String): Float =
        customLayout?.widths?.get(key) ?: letterKeySpan(key, weightedLetters, evenMerged)
    Row(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        // geometry.shift_key 关掉时没有 Shift（份数 0），占位行也不画。
        if (sideKeyWeight > 0f) DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(sideKeyWeight)
        )
        val segments = if (splitAt < 0) listOf(keys) else listOf(keys.take(splitAt), keys.drop(splitAt))
        segments.forEachIndexed { segment, segmentKeys ->
            if (segment > 0) SplitGapSpacer(2, splitGap)
            Row(
                modifier = Modifier
                    .weight(if (splitAt < 0) letterWeight else segmentKeys
                        .sumOf { spanOf(it).toDouble() }.toFloat().coerceAtLeast(0.001f))
                    .fillMaxHeight(),
            ) {
                segmentKeys.forEach { key ->
                    DummyKeyButton(
                        backgroundColor = keyBackgroundColor,
                        modifier = Modifier.weight(keySpans?.getOrNull(keys.indexOf(key)) ?: spanOf(key))
                    )
                }
            }
        }
        DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(endKeyWeight)
        )
    }
}

@Composable
private fun DummyKeyButton(
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(frostedKeyColor(backgroundColor, Color.White,
                LocalKeyboardInputPreferences.current.frostedGlass, opacityScale = 0.5f))
    )
}

@Composable
fun KeyboardRowWithConfig(
    keys: List<String>,
    /** 每个键的份数，与 [keys] 一一对应（14 键拆开时，见 [splitPairSpans]）；null 按 [letterKeySpan]。 */
    keySpans: List<Float>? = null,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    isAsciiMode: Boolean = false,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    configVersion: Int = 0,
    edgeInset: Dp = 0.dp,
    edgeStartInset: Dp = edgeInset,
    edgeEndInset: Dp = edgeInset,
    /** 最后一个键画到行尾（14 键的 l 与 p 右边对齐）。 */
    extendLastKeyToEdge: Boolean = false,
    /** 分体：在第 [splitAt] 个键之前插入宽 [splitGap] 的中缝；-1 不分体。 */
    splitAt: Int = -1,
    splitGap: Dp = 0.dp,
    splitRow: Int = 0,
    /** 分体中排右半开头再放一个 G（设置「右半边也放 G 键」），见 [SplitKeyboardGeometry.homeRowWithRightG]。 */
    splitRightG: Boolean = false,
) {
    val customLayout = LocalCustomLayout.current
    // 14 键合并键与自定义布局一样按字母数占位，和 26 键同一网格对齐。
    val weighted = customLayout != null || LocalFourteenKeyLayout.current
    // 「合并键均匀排布」打开时合并键少占、单字母键多占，整行宽度不变（见 [letterKeySpan]）；
    // 自建布局单独调过宽度的键优先按调过的格数。
    val evenMerged = LocalEvenMergedKeys.current
    val spans = keySpans?.takeIf { it.size == keys.size }
        ?: keys.map { customLayout?.widths?.get(it) ?: letterKeySpan(it, weighted, evenMerged) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val totalWeight = spans.sum().coerceAtLeast(1f)
    val startInset = edgeStartInset.coerceAtMost(maxWidth / 4)
    val endInset = edgeEndInset.coerceAtMost(maxWidth / 4)
    val gap = if (splitAt in 0..keys.size) splitGap.coerceAtMost(maxWidth) else 0.dp
    val unitWidth = (maxWidth - startInset - endInset - gap) / totalWeight
    // 没打开或不适用时原样返回：键、缝宽、行尾留白都不变。
    val homeRow = SplitKeyboardGeometry.homeRowWithRightG(keys, splitAt, splitRightG && !weighted,
        gap.value, endInset.value, unitWidth.value)
    val rowKeys = homeRow.keys
    val rowGap = homeRow.gap.dp
    val rowEndInset = homeRow.endInset.dp
    val letterGeometry = LocalLetterGeometry.current
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        rowKeys.forEachIndexed { index, key ->
            if (index == splitAt) SplitGapSpacer(splitRow, rowGap)
            // 「按键符号」给这个键设的上下滑动作（如 Z X C V 预设）：角标写动作名，动作由 SwipeableKeyButton 套用。
            val (boundUpLabel, boundDownLabel) = letterSwipeCornerLabels(key)
            val rawSwipeUpLabel = boundUpLabel ?: KeysConfigHelper.getSwipeUpLabel(key, isAsciiMode)
            val swipeUpText = if (swipeUpHintsEnabled) rawSwipeUpLabel else null
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key, isAsciiMode)
            val swipeUpDisplay = if (boundUpLabel != null) DisplayMode.BOTH else KeysConfigHelper.getSwipeUpDisplay(key, isAsciiMode)
            val swipeUpKeyLabel =
                if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else ""
            val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubbleText =
                if (swipeDownDisplay != DisplayMode.KEY && swipeDownHintsEnabled) swipeDownLabel else null

            // 长按选项
            val longPressConfig = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
            val longPressDisplay = (longPressConfig?.display ?: "key").takeIf {
                letterLongPressMenuAllowed(key, LocalKeyboardInputPreferences.current.letterLongPressMenu ||
                    KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty()) } ?: "key"
            val longPressLabels = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.map { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.associateBy { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }
            } else null

            // 键帽显示文本
            val rawCommitValue = KeysConfigHelper.getKeyCommitValue(key, isAsciiMode)
            val commitValue = com.kingzcheung.xime.rime.MergedLetterInput.tapValue(
                key, rawCommitValue, LocalFourteenKeyLayout.current, isShifted)
            val displayText = KeysConfigHelper.getKeyDisplayLabel(key, isAsciiMode,
                isShifted || LocalKeyboardInputPreferences.current.uppercaseKeyLabels).let { semicolonKeyLabel(key, isAsciiMode, it) }.let { longVowelKeyLabel(key, it, LocalLongVowelOnL.current) }

            val onClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val onPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val onRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            val onSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownAction != GestureAction.NONE && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label)
                        }
                        Unit
                    }
                }
            } else null
            val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    // 气泡里显示的是 Shift 后的大写（È），送出的仍是原字母（è），大小写照点按一样由 Shift 决定。
                    commitLongPressChoice(gesture?.label ?: selectedLabel, onCommitText, onKeyPress, KeysConfigHelper.longPressLetters(isAsciiMode))
                }
                Unit
            } }

            // 补上的 G 不登记字母位置：滑行与邻键纠错仍只认左半那个 G。
            CompositionLocalProvider(LocalLetterGeometry provides if (index == homeRow.mirrorIndex) null else letterGeometry) {
            SwipeableKeyButton(
                layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                followSymbolSwipeDirection = true,
                text = displayText,
                letterGroup = if (isShifted) key.uppercase() else key,
                onLetterSelection = onKeyPress,
                onClick = onClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight((unitWidth * (spans.getOrNull(index) ?: letterKeySpan(key, weighted, evenMerged)) +
                    (if (index == 0) startInset else 0.dp) + (if (index == rowKeys.lastIndex) rowEndInset else 0.dp)).value.coerceAtLeast(1f))
                    .testTag(if (index == homeRow.mirrorIndex) "qwerty-key-right:$key" else "qwerty-key:$key"),
                edgeContentPadding = PaddingValues(
                    start = if (index == 0) startInset else 0.dp,
                    end = if (index == rowKeys.lastIndex && !extendLastKeyToEdge) rowEndInset else 0.dp,
                ),
                swipeText = swipeUpText,
                symbolInputText = symbolInputValue(swipeUpCommitValue, swipeUpAction)
                    .takeUnless { longPressLabels != null && KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty() },
                symbolInputDownText = symbolInputValue(swipeDownLabel, swipeDownAction),
                swipeDownText = swipeDownBubbleText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = boundDownLabel?.takeIf { swipeDownHintsEnabled }
                    ?: if ((swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) && swipeDownHintsEnabled) swipeDownLabel else null,
                onSwipe = if (swipeUpCommitValue != null && swipeUpAction != GestureAction.NONE) { {
                    if (swipeUpAction == null || swipeUpAction == GestureAction.COMMIT) {
                        (onCommitText ?: onKeyPress)(swipeUpCommitValue)
                    } else {
                        onGestureAction?.invoke(swipeUpAction, swipeUpCommitValue)
                    }
                    Unit
                } } else null,
                onSwipeDown = onSwipeDown,
                onSwipeStateChange = onSwipeStateChange,
                onPress = onPress,
                onRelease = onRelease,
                onLongPressSelect = onLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
            }
        }
        if (splitAt == rowKeys.size) SplitGapSpacer(splitRow, rowGap)
    }
    }
}

@Composable
internal fun ShiftCapsKeyButton(
    viewModel: KeyboardViewModel,
    shiftMode: ShiftMode,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    iconColor: Color,
    accentColor: Color,
    panelColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** Shift 键分词（默认关）：返回 true 时点按是分词 '，键面写「分词」；null 为普通 Shift。 */
    composingSplit: (() -> Boolean)? = null,
    /**
     * 点按送出的键（日语长音键「代替 Shift」：ー）；非 null 时键面写 [tapLabel]，按住满 [SHIFT_TAP_KEY_HOLD_MS] 才是按住 Shift，
     * 松手前没打别的键就照 Shift 的点按切换大小写（[shiftTapKeyLongPressAction]）。null 为普通 Shift。
     */
    tapKey: String? = null,
    tapLabel: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val targets = LocalShiftSlideTargets.current
    val dragSpace = LocalKeyDragSpace.current
    // 在这里读「是否在打拼音」，变化时只重组 Shift 键。
    val splitLabel = composingSplit?.invoke() == true
    val currentSplit by rememberUpdatedState(composingSplit)
    val suppressCursorMove = LocalSuppressCursorMove.current
    val currentPress by rememberUpdatedState(onKeyPressDown)
    val currentKey by rememberUpdatedState(onKeyPress)
    val currentTapKey by rememberUpdatedState(tapKey)
    var bounds by remember { mutableStateOf(Rect.Zero) }

    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val honeycombEdge = LocalHoneycombEdge.current
    val keyClipShape = remember(keyCornerRadius, honeycombEdge) {
        honeycombEdge?.let(::HalfHexagonShape) ?: RoundedCornerShape(keyCornerRadius)
    }

    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha)
    }

    val colors = shiftKeyColors(backgroundColor, iconColor, accentColor, panelColor,
        frostedGlass, shiftMode, isPressed,
        legacyStateColor = if (isPressed) darkenColor(backgroundColor, 0.1f)
        else if (shiftMode == ShiftMode.CAPS) darkenColor(backgroundColor, 0.2f)
        else if (shiftMode == ShiftMode.SINGLE) darkenColor(backgroundColor, 0.1f)
        else backgroundColor,
        opacityScale = glassFunctionKeyScale(backgroundColor),
        functionDepth = glassFunctionKeyDepth(backgroundColor))

    Box(
        modifier = modifier
            .fillMaxHeight()
            .testTag("shift-key")
            .onGloballyPositioned { bounds = Rect(dragSpace.point(it), it.size.toSize()) }
            .pointerInput(viewModel, targets) {
                var lastTap = Long.MIN_VALUE
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    isPressed = true
                    suppressCursorMove.value = true
                    currentPress?.invoke("shift")
                    // Typing consumed the previous one-shot Shift; it cannot start a double tap.
                    if (viewModel.shiftMode.value != ShiftMode.SINGLE) lastTap = Long.MIN_VALUE
                    // 点按另有用途时（长音键）不一按下就亮 Shift：按满时限才算按住，点按时整块键帽不闪大写。
                    val tapOutput = currentTapKey
                    var holding = tapOutput == null
                    if (holding) viewModel.beginShiftHold()
                    var released = false
                    var dragged = false
                    var upTime = down.uptimeMillis
                    try {
                        while (true) {
                            // 拖出去以后不再计时（交给滑到字母上的大写），只等松手。
                            val event = if (holding || dragged) awaitPointerEvent()
                                else withTimeoutOrNull((down.uptimeMillis + SHIFT_TAP_KEY_HOLD_MS - upTime).coerceAtLeast(1L)) { awaitPointerEvent() }
                            if (event == null) {
                                viewModel.beginShiftHold()
                                holding = true
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!holding) upTime = change.uptimeMillis
                            if (change.isConsumed) break
                            val pointer = bounds.topLeft + change.position
                            dragged = dragged || !bounds.contains(pointer) ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            val target = if (dragged) targets?.move(bounds, pointer) else null
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                upTime = change.uptimeMillis
                                if (dragged) target?.commit?.invoke()
                                break
                            }
                        }
                    } finally {
                        val used = holding && viewModel.endShiftHold()
                        targets?.finish()
                        isPressed = false
                        suppressCursorMove.value = false
                        if (tapOutput != null) {
                            // 长音键：点按送 ー；按住没打别的键松手，照 Shift 点按切换（小写→单次大写→锁定→小写）。
                            // 计时没赶上（松手事件先到）时按事件时间判断，不把长按当成点按。
                            if (released && !dragged && !used) {
                                if (!holding && upTime - down.uptimeMillis < SHIFT_TAP_KEY_HOLD_MS) currentKey(tapOutput)
                                else currentKey(shiftTapKeyLongPressAction(viewModel.shiftMode.value))
                            }
                            lastTap = Long.MIN_VALUE
                        } else if (released && !dragged && !used && upTime - down.uptimeMillis < 350L) {
                            // 打拼音时点按插入分词；不算进双击，连点两下不会锁成大写。
                            if (currentSplit?.invoke() == true) {
                                currentKey(SYLLABLE_SEPARATOR)
                                lastTap = Long.MIN_VALUE
                            } else if (lastTap != Long.MIN_VALUE && down.uptimeMillis - lastTap in 0..viewConfiguration.doubleTapTimeoutMillis) {
                                currentKey("shift_caps")
                                lastTap = Long.MIN_VALUE
                            } else {
                                currentKey("shift_single")
                                lastTap = upTime
                            }
                        } else lastTap = Long.MIN_VALUE
                    }
                }
            }
            .padding(scaledKeyVisualPadding())
            .then(if (honeycombEdge != null) Modifier.honeycombCap() else shadowModifier)
            .clip(keyClipShape)
            .background(colors.background).visualMaterial(VisualStyles.current, keyCornerRadius, com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED),
        contentAlignment = Alignment.Center
    ) {
        // 空心箭头＝小写，实心＝下一个字母大写，实心加底横线＝大写锁定。
        // Shift 键分词开着并且在打拼音：键面写「分词」，点了就是它（印什么出什么）。
        if (splitLabel) Text("分词", color = colors.foreground, fontSize = 14.sp, maxLines = 1,
            modifier = Modifier.testTag("shift-key-split"))
        else if (tapLabel != null) {
            // 长音键：中间是 ー，右上角小箭头表示长按是 Shift（实心、带横线同普通 Shift 键）。
            Text(tapLabel, color = colors.foreground, fontSize = 20.sp, maxLines = 1,
                modifier = Modifier.testTag("shift-key-tap"))
            Icon(
                imageVector = ShiftKeyIcons.forMode(shiftMode),
                contentDescription = ShiftKeyIcons.description(shiftMode),
                tint = colors.foreground.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp).size(12.dp)
            )
        }
        else Icon(
            imageVector = ShiftKeyIcons.forMode(shiftMode),
            contentDescription = ShiftKeyIcons.description(shiftMode),
            tint = colors.foreground,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 横屏紧凑版按键 — 主字符和上滑字符垂直堆叠居中
 */
@Composable
fun SwipeableKeyButtonLandscape(
    text: String,
    onClick: () -> Unit,
    letterGroup: String? = null,
    onLetterSelection: ((String) -> Unit)? = null,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    swipeText: String? = null,
    symbolInputText: String? = null,
    symbolInputDownText: String? = null,
    swipeDownText: String? = null,
    swipeUpKeyLabel: String? = null,
    swipeDownKeyLabel: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {

    SwipeableKeyButton(text = text, onClick = onClick, letterGroup = letterGroup, onLetterSelection = onLetterSelection, backgroundColor = backgroundColor,
        followSymbolSwipeDirection = true,
        textColor = textColor, modifier = modifier, swipeText = swipeText, swipeDownText = swipeDownText,
        symbolInputText = symbolInputText, symbolInputDownText = symbolInputDownText,
        swipeUpKeyLabel = swipeUpKeyLabel, swipeDownKeyLabel = swipeDownKeyLabel,
        onSwipe = onSwipe, onSwipeDown = onSwipeDown, onPress = onPress, onRelease = onRelease,
        onLongPressSelect = onLongPressSelect, longPressItems = longPressItems,
        fontSize = if (fontSize == TextUnit.Unspecified) 14.sp else fontSize, swipeFontSize = swipeFontSize,
        onSwipeStateChange = onSwipeStateChange, shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
}

@Composable
fun CompactKeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    isAsciiMode: Boolean = false,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    configVersion: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxSize(),
    ) {
        keys.forEach { key ->
            // 「按键符号」给这个键设的上下滑动作（如 Z X C V 预设）：角标写动作名，动作由 SwipeableKeyButton 套用。
            val (boundUpLabel, boundDownLabel) = letterSwipeCornerLabels(key)
            val rawSwipeUpLabel = boundUpLabel ?: KeysConfigHelper.getSwipeUpLabel(key, isAsciiMode)
            val swipeUpText = if (swipeUpHintsEnabled) rawSwipeUpLabel else null
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key, isAsciiMode)
            val swipeUpDisplay = if (boundUpLabel != null) DisplayMode.BOTH else KeysConfigHelper.getSwipeUpDisplay(key, isAsciiMode)
            val swipeUpKeyLabel =
                if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else ""
            val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubbleText =
                if (swipeDownDisplay != DisplayMode.KEY && swipeDownHintsEnabled) swipeDownLabel else null
            val swipeDownKeyLabel = boundDownLabel?.takeIf { swipeDownHintsEnabled }
                ?: if ((swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) && swipeDownHintsEnabled) swipeDownLabel else null

            val longPressConfig = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
            val longPressDisplay = (longPressConfig?.display ?: "key").takeIf {
                letterLongPressMenuAllowed(key, LocalKeyboardInputPreferences.current.letterLongPressMenu ||
                    KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty()) } ?: "key"
            val longPressLabels = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.map { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.associateBy { longPressShownLabel(it.label, isShifted, KeysConfigHelper.longPressLetters(isAsciiMode)) }
            } else null

            val rawCommitValue = KeysConfigHelper.getKeyCommitValue(key, isAsciiMode)
            val commitValue = com.kingzcheung.xime.rime.MergedLetterInput.tapValue(
                key, rawCommitValue, LocalFourteenKeyLayout.current, isShifted)
            val compactDisplayText = KeysConfigHelper.getKeyDisplayLabel(key, isAsciiMode,
                isShifted || LocalKeyboardInputPreferences.current.uppercaseKeyLabels).let { semicolonKeyLabel(key, isAsciiMode, it) }.let { longVowelKeyLabel(key, it, LocalLongVowelOnL.current) }
            val compactOnClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val compactOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val compactOnRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            val compactOnSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownAction != GestureAction.NONE && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label!!)
                        }
                        Unit
                    }
                }
            } else null
            val compactOnLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    // 气泡里显示的是 Shift 后的大写（È），送出的仍是原字母（è），大小写照点按一样由 Shift 决定。
                    commitLongPressChoice(gesture?.label ?: selectedLabel, onCommitText, onKeyPress, KeysConfigHelper.longPressLetters(isAsciiMode))
                }
                Unit
            } }

            SwipeableKeyButtonLandscape(
                text = compactDisplayText,
                letterGroup = if (isShifted) key.uppercase() else key,
                onLetterSelection = onKeyPress,
                onClick = compactOnClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(LocalCustomLayout.current?.widths?.get(key)
                    ?: if (LocalCustomLayout.current != null) key.length.toFloat() else 1f),
                swipeText = swipeUpText,
                symbolInputText = symbolInputValue(swipeUpCommitValue, swipeUpAction)
                    .takeUnless { longPressLabels != null && KeysConfigHelper.longPressLetters(isAsciiMode).isNotEmpty() },
                symbolInputDownText = symbolInputValue(swipeDownLabel, swipeDownAction),
                swipeDownText = swipeDownBubbleText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = swipeDownKeyLabel,
                onSwipe = if (swipeUpCommitValue != null && swipeUpAction != GestureAction.NONE) { {
                    if (swipeUpAction == null || swipeUpAction == GestureAction.COMMIT) {
                        (onCommitText ?: onKeyPress)(swipeUpCommitValue)
                    } else {
                        onGestureAction?.invoke(swipeUpAction, swipeUpCommitValue)
                    }
                    Unit
                } } else null,
                onSwipeDown = compactOnSwipeDown,
                onSwipeStateChange = onSwipeStateChange,
                onPress = compactOnPress,
                onRelease = compactOnRelease,
                onLongPressSelect = compactOnLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
        }
    }
}

/** QWERTY 空格键 */
@Composable
private fun SpaceKey(
    schemaName: String,
    isAsciiMode: Boolean,
    isSttEnabled: Boolean,
    isVoiceMode: Boolean,
    voiceSticky: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    modifier: Modifier = Modifier,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onKeyRelease: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
) {

    SpaceKeyButton(onClick = { onKeyPress("space") }, backgroundColor = keyBackgroundColor,
        textColor = keyTextColor, schemaName = if (isAsciiMode) "英文" else schemaName,
        modifier = modifier, onPress = { onKeyPressDown?.invoke("space") },
        onRelease = { onKeyRelease?.invoke("space") }, isVoiceMode = isVoiceMode,
        voiceSticky = voiceSticky, isSttEnabled = isSttEnabled, onVoiceModeChange = onVoiceModeChange,
        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
}

/**
 * 长按菜单选中的小写字母当作点按：拼音组词中接到编码后面，不先上屏首选（h + 长按 g 不再变成「和g」）。
 * 其他字符（大写、变音字母、符号）照旧直接上屏。
 */
/**
 * 长按气泡里这个键盘自己的重音字母（è é ñ ä…）跟着 Shift 显示大写，和键帽一致（用户 11-09：Shift 后还显示 è é）。
 * 只改显示；ß 这类没有单个大写字母的照旧，标点、数字和别的语言的字母不动。
 */
internal fun longPressShownLabel(label: String, isShifted: Boolean, accentLetters: String): String {
    if (!isShifted || label.length != 1 || label[0] !in accentLetters || label[0].isUpperCase()) return label
    return label.uppercase().takeIf { it.length == 1 && it[0] in accentLetters } ?: label
}

internal fun commitLongPressChoice(label: String, onCommitText: ((String) -> Unit)?, onKeyPress: (String) -> Unit,
    accentLetters: String = "") {
    // 西班牙、德、法、俄语键盘长按出的 á、ä、ё 也是字母：像点按一样接进正在打的词，补全才认得出整个词；
    // 韩语长按出的 ㄲ 同样交给组字。大写的照旧直接上屏。
    val letter = label.length == 1 && (label[0] in 'a'..'z' ||
        !label[0].isUpperCase() && label[0] in accentLetters)
    if (letter) onKeyPress(label) else (onCommitText ?: onKeyPress)(label)
}

/**
 * 字母键长按的大小写（及带音调字母）气泡默认关：它会打断上下拖动输入角标（用户 10-20）。
 * 打开“长按弹出字母气泡”才恢复；非字母键（标点、地球键等）的长按不受影响。
 */
internal fun letterLongPressMenuAllowed(key: String, enabled: Boolean): Boolean =
    // 字母键是 1～2 个字母（26 键 q、14 键 qw）；earth 之类的功能键名不算。
    enabled || key.length !in 1..2 || !key.all { it in 'a'..'z' || it in 'A'..'Z' }
