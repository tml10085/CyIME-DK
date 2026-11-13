package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.isSingleCharacter
import androidx.compose.ui.zIndex

import androidx.compose.ui.draw.drawWithContent
import com.kingzcheung.xime.util.InputLatencyTrace

import androidx.compose.ui.platform.testTag

import android.content.res.Configuration
import com.kingzcheung.xime.clipboard.ClipboardDraftSaveResult
import com.kingzcheung.xime.clipboard.submitClipboardDraft
import com.kingzcheung.xime.service.QuickSendFormEditTextHolder
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.keyboard.textMainType
import com.kingzcheung.xime.keyboard.underlyingPage
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.keyboard.resolveToolbarButtonItem
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.highlightedCandidateIndex
import com.kingzcheung.xime.service.shownPreedit
import com.kingzcheung.xime.service.focusedT9AssociationIndex
import com.kingzcheung.xime.service.usesT9CandidateNavigation
import com.kingzcheung.xime.service.ExpandedCandidatePager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.gloss.CandidateGlossary
import com.kingzcheung.xime.ui.menubar.ClipboardView
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.menubar.ToolbarCustomizeView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.cySwapFadeIn
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

val LocalStretchFactor = compositionLocalOf { 1f }
val LocalSuppressCursorMove = compositionLocalOf { mutableStateOf(false) }
internal val LocalDeleteDrag = staticCompositionLocalOf<(() -> DeleteDragSession)?> { null }

/** How long startup may take before the loading panel shows "正在加载词库" and its progress bar. */
internal const val DEPLOY_NOTICE_DELAY_MS = 600L

@Composable
fun KeyboardView(
    viewModel: KeyboardViewModel,
    state: KeyboardUiState,
    callbacks: KeyboardCallbacks,
    modifier: Modifier = Modifier,
    inlineSuggestions: List<*> = listOf<Any>(),
    resizeOverlay: (@Composable () -> Unit)? = null,
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
    /** 悬浮卡片外四角角标把手的热区（窗口坐标），宿主并进触摸区；角标没亮时为空表。 */
    onCornerHandlesPositioned: (List<android.graphics.Rect>) -> Unit = {},
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    predictionTransition: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    glideCandidatePreview: State<String> = remember { mutableStateOf("") },
    glideRimePreview: State<RimeProcessResult?> = remember { mutableStateOf(null) },
    glideEnglishPreview: State<GlideEnglishPreview?> = remember { mutableStateOf(null) },
    voiceAmplitudeState: State<Float> = remember { mutableFloatStateOf(0f) },
    voiceSpectrumState: State<FloatArray> = remember { mutableStateOf(FloatArray(16)) },
    /**
     * 非按键交互（符号/表情面板、菜单栏、候选栏按钮等）的振动钩子。
     * 按键本身的反馈走 onKeyPressDown 回调（服务层 FeedbackManager），
     * 此处只覆盖没有经过按键回调链路的点击交互，宿主按按键振动同款语义实现。
     */
    onHapticFeedback: (() -> Unit)? = null,
    /** 固定键盘底边的系统留白，与 Service 正常显示分支完全相同。 */
    fixedBottomInsetDp: Int = 0,
    fixedHeightRange: IntRange? = null,
    inputSessionManagedByHost: Boolean = false,
    /** Index in the displayed candidate source, including the expanded page's optional filter. */
    hardwareCandidateHighlight: Int? = null,
    /** Number shortcuts are useful only while a physical keyboard is connected. */
    hardwareKeyboardConnected: Boolean = false,
    /** Exact fixed-mode destination supplied by the service; absent means docking is unavailable. */
    dockTarget: KeyboardDockTarget? = null,
    onPreviewArrangePositioned: ((android.graphics.Rect) -> Unit)? = null,
    previewLetterLayout: Boolean = false,
    // Older hosts can discard the separate window touch region when the lock moves into the footer.
    onFloatingLockPositioned: ((android.graphics.Rect?) -> Unit)? = null,
) {
    if (onFloatingLockPositioned != null) {
        LaunchedEffect(onFloatingLockPositioned) { onFloatingLockPositioned(null) }
    }
    // 状态栏按钮走一次完整按键反馈，声音和振动均由用户设置控制。
    val toolbarFeedback = { callbacks.onKeyPressDown?.invoke("toolbar"); Unit }
    // Docking defaults also depend on reloaded gaps when the visible size stays unchanged.
    KeysConfigHelper.configVersion.collectAsStateWithLifecycle().value
    val pageKeyboardState by viewModel.keyboardState.collectAsStateWithLifecycle()
    // The host publishes language and scheme together. Navigation can still carry the previous
    // letter keyboard for a frame after a switch. Use the host for both key routing and rendering.
    val hostLetterLayout = inputSessionManagedByHost && state.currentSchemaId.isNotEmpty() &&
        (pageKeyboardState == KeyboardLayoutState.English || pageKeyboardState == KeyboardLayoutState.Chinese)
    val keyboardState = if (previewLetterLayout || hostLetterLayout) initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId) else pageKeyboardState
    val pickedKaomoji = remember(state.inputSessionId) { ArrayDeque<String>() }
    val sessionPage by viewModel.page.collectAsStateWithLifecycle()
    val page = if (previewLetterLayout) KeyboardPage.Main(MainType.FULL) else sessionPage
    val underlyingPage = page.underlyingPage()
    val overlayVisibility = remember { MutableTransitionState(false) }
    overlayVisibility.targetState = page is KeyboardPage.Overlay
    val overlayVisible = overlayVisibility.currentState || overlayVisibility.targetState || !overlayVisibility.isIdle
    // The same host also retains the toolbar and panel throughout the closing animation.
    var retainedOverlay by remember { mutableStateOf<KeyboardPage.Overlay?>(null) }
    (page as? KeyboardPage.Overlay)?.let { if (retainedOverlay != it) retainedOverlay = it }
    val textLayout by viewModel.lastMainLayout.collectAsStateWithLifecycle()
    val candidatePageExpanded by viewModel.candidatePageExpanded.collectAsStateWithLifecycle()
    var candidatePageOpening by remember { mutableIntStateOf(0) }
    val candidateStripExtended by viewModel.candidateStripExtended.collectAsStateWithLifecycle()
    // 符号页与数字页同属键盘页：直接换页，不走工具覆盖层的揭幕动画，也不在动画期间吞掉点击。
    val symbolPageSwap = ((page as? KeyboardPage.Overlay) ?: retainedOverlay)?.route is OverlayRoute.Symbol
    val overlayReveal = rememberKeyboardPanelRevealState(page is KeyboardPage.Overlay, overlayVisibility,
        instant = symbolPageSwap)
    val candidateReveal = rememberKeyboardPanelRevealState(candidatePageExpanded)
    val singleCharFilter by viewModel.singleCharFilter.collectAsStateWithLifecycle()

    // 候选展开页自动收起：编码删空（无候选也无联想）时不留空页。
    // 展开态是候选栏的在位扩展（非 Overlay），删除实时更新页内候选，删空即回到键盘。
    CollapseEmptyCandidatePage(candidatePageExpanded, candidateState, callbacks.isDeleteDragging) {
        viewModel.setCandidatePageExpanded(false)
    }
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val isLandscape = if (state.isFloatingMode) false
        else LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SideEffect {
        val isHandwriting = underlyingPage is KeyboardPage.Main && underlyingPage.type == MainType.HANDWRITING
        val active = isHandwriting || (
            (keyboardState is KeyboardLayoutState.Chinese || keyboardState is KeyboardLayoutState.Stroke || keyboardState is KeyboardLayoutState.T9Pinyin)
            && underlyingPage is KeyboardPage.Main && underlyingPage.type == MainType.FULL
        )
        if (!previewLetterLayout) callbacks.onKeyboardModeChange?.invoke(active)
    }

    val t9Controller = remember(viewModel) {
        viewModel.bindT9Controller(callbacks)
    }
    val nineFuzzyReadings = rememberNineKeyFuzzyReadings(state.currentSchemaId, state.inputProfile)
    SideEffect {
        viewModel.bindT9Controller(callbacks)
        t9Controller.fuzzyReadings = nineFuzzyReadings
    }

    LaunchedEffect(inputSessionManagedByHost, state.inputSessionId, state.t9ResetSignal) {
        // The service synchronizes before accepting input. A delayed effect from an old
        // composition must not roll that editor boundary back to an obsolete session.
        if (!inputSessionManagedByHost) viewModel.synchronizeInputSession(
            state.inputSessionId, state.t9ResetSignal, state.isAsciiMode, state.currentSchemaId
        )
    }

    LaunchedEffect(state.isAsciiMode, state.currentSchemaId, previewLetterLayout) {
        if (previewLetterLayout) return@LaunchedEffect
        FileLogger.i("XimeKeyboard", "AsciiModeChanged: isAsciiMode=${state.isAsciiMode}, schemaId=${state.currentSchemaId}, kb=$keyboardState, vs=$viewState, page=$page")
        viewModel.dispatch(
            KeyboardDispatchAction.AsciiModeChanged(state.isAsciiMode, state.currentSchemaId)
        )
    }

    // 键盘 ascii 同步点（attach/detach）：键盘上下文切换时，
    // 先保存离开键盘的记忆，再按进入键盘的记忆同步引擎。
    var lastAsciiContext by remember { mutableStateOf<AsciiKeyboardContext?>(null) }
    val inSettingsPreview = onPreviewArrangePositioned != null
    LaunchedEffect(viewState, inSettingsPreview) {
        val prevContext = lastAsciiContext
        val curContext = viewState.asciiContext()
        lastAsciiContext = curContext
        if (inSettingsPreview || prevContext == null || prevContext == curContext) return@LaunchedEffect
        viewModel.asciiStateMachine.saveMemory(prevContext, state.isAsciiMode)
        val target = viewModel.asciiStateMachine.targetFor(curContext, state.isAsciiMode)
        if (target != null) {
            FileLogger.i("XimeKeyboard", "ascii sync: ${prevContext.name}(${state.isAsciiMode}) -> ${curContext.name}($target)")
            callbacks.onKeyPress("ime_switch", false)
        }
    }

    LaunchedEffect(keyboardState) {
        FileLogger.i("XimeKeyboard", "keyboardState switched: $keyboardState, vs=$viewState, page=$page, ascii=${state.isAsciiMode}")
    }

    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val palette = com.kingzcheung.xime.ui.theme.resolveKeyboardPalette(state.themeId, state.isDarkTheme)
    val keyboardBgColor = palette.background
    val keyBgColor = palette.key
    val keyTextColor = palette.text
    val accentColor = palette.accent
    val themeScheme = KeyboardThemes.getRenderingScheme(state.themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(state.themeId, state.isDarkTheme)
    // 悬浮拖动条颜色：键盘背景（渐变取各色平均，纯色取本色）与按键文字色各半，始终不透明。
    val floatingDragIndicatorColor = remember(themeScheme, state.isDarkTheme, keyboardBgColor, keyTextColor) {
        val bg = themeScheme.keyboardBackground
        val hexes = if (bg != null && bg.type == "gradient") (if (state.isDarkTheme) bg.colorsDark ?: bg.colors else bg.colors) else null
        val base = if (!hexes.isNullOrEmpty()) {
            val colors = hexes.map { Color(0xFF000000 or it) }
            Color(colors.map { it.red }.average().toFloat(), colors.map { it.green }.average().toFloat(),
                colors.map { it.blue }.average().toFloat())
        } else keyboardBgColor.copy(alpha = 1f)
        lerp(base, keyTextColor.copy(alpha = 1f), 0.5f)
    }
    val specialKeyBgColor = palette.function
    val specialKeyTextColor = palette.functionText
    val candidateTextColor = palette.candidateText
    val candidateSelectedTextColor = palette.selectedText
    val dividerColor = if (state.isDarkTheme) androidx.compose.ui.graphics.Color(0xFF3C4043) else androidx.compose.ui.graphics.Color(0xFFDADCE0)

    val clipboardTab = (page as? KeyboardPage.Overlay)?.let {
        (it.route as? OverlayRoute.Clipboard)?.tab
    } ?: 0
    val footerDp = LocalConfiguration.current.floatingFooterHeightDp
    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp
    val wideFloating = !isT9Schema(state.currentSchemaId) && state.inputProfile.layout.kind != com.kingzcheung.xime.settings.LayoutKind.KANA_KEYPAD && keyboardState !is KeyboardLayoutState.Stroke
    val keyboardContext = androidx.compose.ui.platform.LocalContext.current
    val portraitHeight = SettingsPreferences.getKeyboardHeightDp(androidx.compose.ui.platform.LocalContext.current, false)
    // Service resolves normal geometry before sizing the IME window; expanded panels keep that width.
    val cardWidthDp = state.keyboardWidthDp.takeIf { it > 0 } ?: resolvedFloatingWidth(
        screenWidth = screenW,
        screenHeight = screenH,
        height = LocalClipboardPanelExpansion.current?.collapsedHeight ?: state.keyboardHeightDp,
        portraitHeight = portraitHeight,
        wide = wideFloating,
        overrideWidth = if (state.resizePreviewWidthDp > 0) state.resizePreviewWidthDp else state.floatingWidthDp,
    )
    val floatScaleFactor = if (state.isFloatingMode) cardWidthDp.toFloat() / screenW.toFloat() else 0.85f
    // 调节态不再读取 window 坐标，也不再依赖 onGloballyPositioned 回灌。
    // 预览矩形直接使用 KeyboardView 根容器的本地坐标；真实卡片、边框、命中共用这一份 Rect。
    val resizeActive = resizeOverlay != null
    val opacityEditingState = remember(resizeActive) { mutableStateOf(false) }
    val resizeControlDensity = LocalDensity.current
    val storedInputPreferences = rememberKeyboardInputPreferences(state.isDarkTheme)
    // 屏幕太窄（手机竖屏）时分体自动用完整键盘：整个键盘（字母区、中缝、调节框）都按不分体排；
    // 转到够宽的方向再分开。存着的「分体键盘」不改。
    val splitFitsScreen = SplitKeyboardGeometry.fitsScreen(LocalConfiguration.current.screenWidthDp)
    val inputPreferences = remember(storedInputPreferences, splitFitsScreen) {
        if (storedInputPreferences.splitKeyboardEnabled && !splitFitsScreen)
            storedInputPreferences.copy(splitKeyboardEnabled = false) else storedInputPreferences
    }
    // 「ing 放在 Shift 键上」决定 26 键第二排补不补分号键；只由输入法自己的键盘交给 KeysConfigHelper（设置页的预览不改它）。
    LaunchedEffect(inputPreferences.doublePinyinSemicolonOnShift) {
        com.kingzcheung.xime.settings.KeysConfigHelper.setSemicolonOnShift(inputPreferences.doublePinyinSemicolonOnShift)
    }
    // 分体中缝：服务提供同一份测量，触摸区与玻璃模糊也据此避开中缝。
    val splitKeyboardGap = LocalSplitKeyboardGap.current ?: remember { SplitKeyboardGap() }
    val splitGapPreview = remember(resizeActive) { mutableStateOf<Float?>(null) }
    // 面板或展开候选盖住字母键盘时，不能在它们上面挖出中缝。
    // 两半靠中缝的角和卡片外侧四角同一圆角：悬浮四角圆，固定看「顶部圆角」「底部圆角」（GitHub #78）。
    val splitCornerPx = with(LocalDensity.current) { FloatingKeyboardCardCorner.toPx() }
    val splitRoundedTop = rememberRoundedKeyboardTop()
    val splitRoundedBottom = rememberRoundedKeyboardBottom()
    SideEffect {
        splitKeyboardGap.updateExposed(!(candidatePageExpanded || overlayVisible))
        splitKeyboardGap.updateFilled(inputPreferences.fillBlankAreas)
        splitKeyboardGap.updateCorners(splitCornerPx, state.isFloatingMode || splitRoundedTop,
            state.isFloatingMode || splitRoundedBottom)
    }
    val keyboardLayerOpacity = state.keyboardOpacity
    val previewModifier = if (resizeOverlay != null && !state.isFloatingMode) Modifier.graphicsLayer {
        alpha = keyboardLayerOpacity
        compositingStrategy = CompositingStrategy.Offscreen
    } else Modifier
    // 动态背景：背景图换到最底下单独一层（KeyboardBackgroundMotionLayer），每帧只动这一层；面板或展开候选盖住键盘时钟停。
    val backgroundMotion = com.kingzcheung.xime.ui.theme.LocalKeyboardBackgroundMotion.current
        ?.takeIf { !state.handwritingExpanded }
    val backgroundImageSlot = remember(backgroundMotion != null) {
        if (backgroundMotion != null) com.kingzcheung.xime.ui.theme.BackgroundImageSlot() else null
    }
    if (backgroundMotion != null) SideEffect { backgroundMotion.covered = candidatePageExpanded || overlayVisible }
    // Compose background and keys into the same layer before applying opacity.
    val contentModifier = if (state.handwritingExpanded) previewModifier else previewModifier
        .keyboardBackground(themeScheme.keyboardBackground, state.isDarkTheme, keyboardBgColor,
            imageSlot = backgroundImageSlot)
        .splitGapCutout(splitKeyboardGap)
    val panelMaterial = Modifier.inheritKeyboardBackground(!state.handwritingExpanded)
    // 面板透出键盘本身的背景时（玻璃，或图片、渐变主题），面板下面的按键要挖掉；见 panelSeesKeyboardBackground。
    val panelsSeeKeyboardBackground = panelSeesKeyboardBackground(inputPreferences.frostedGlass.enabled,
        themeScheme.keyboardBackground)
    // 全屏手写时表情、编辑(方向)等面板回到平时键盘的大小（11-05），不再铺满整个屏幕。
    val handwritingOverlaySlot = handwritingPanelSlot(state.handwritingExpanded, state.handwritingNormalHeightDp,
        state.handwritingControlsWidthDp, state.handwritingControlsOffsetX, state.keyboardWidthDp, state.keyboardHeightDp)
    var cursorControlActive by remember { mutableStateOf(false) }
    val floatingBottomClearance = remember(state.isFloatingMode) { mutableFloatStateOf(0f) }
    // 上拉变悬浮后接着跟手：换模式时这一层不变，由它接着读手指。
    val undockFollow = remember { KeyboardUndockFollow() }
    CompositionLocalProvider(
        LocalKeyboardUndockFollow provides undockFollow,
        LocalKeyboardResizeOpacityEditing provides opacityEditingState.takeIf { resizeActive },
        LocalSplitKeyboardGap provides splitKeyboardGap,
        LocalDpPerMillimeter provides rememberDpPerMillimeter(),
        LocalSplitGapPreview provides splitGapPreview.takeIf { resizeActive },
        LocalFloatingBottomClearance provides if (state.isFloatingMode) floatingBottomClearance else null,
        com.kingzcheung.xime.ui.theme.LocalMaterialPalette provides
            com.kingzcheung.xime.ui.theme.MaterialPalette(keyboardBgColor, accentColor),
        LocalKeyboardExplicitWidth provides (state.keyboardWidthDp > 0 || (!state.isFloatingMode && (state.fixedWidthDp > 0 || resizeActive))),
        LocalModeSlotWeight provides if (
            page.textMainType() != MainType.HANDWRITING &&
            (textLayout is KeyboardLayoutState.Chinese || textLayout is KeyboardLayoutState.English) &&
            (state.isAsciiMode || state.inputProfile.layout.kind != com.kingzcheung.xime.settings.LayoutKind.KANA_KEYPAD) &&
            !(inputPreferences.splitKeyboardEnabled && supportsSplitKeyboard(state.currentSchemaId, state.isAsciiMode))
        ) 0.5f else 0.8f,
        LocalKeyCornerRadius provides kbKey.cornerRadius.dp,
        LocalTextModeLabel provides when {
            page.textMainType() == MainType.HANDWRITING -> state.inputProfile.language.displayName
            textLayout is KeyboardLayoutState.English -> "ABC"
            state.inputProfile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.KANA_KEYPAD ||
                com.kingzcheung.xime.service.JapaneseTyping.usesKanaCase(state.currentSchemaId, false) -> "あいう"
            else -> state.inputProfile.language.displayName
        },
        LocalKeyboardPunctuation provides if (state.isCalculatorMode &&
            state.inputProfile.language != com.kingzcheung.xime.settings.InputLanguage.JAPANESE) null else KeyboardPunctuation(
            // 全角/半角只作用于中文与日文：英文键盘的键面提示与上滑标点保持半角
            full = !state.isAsciiMode && !state.inputProfile.language.usesHalfWidthPunctuation && (state.schemaSwitches.firstOrNull { it.name == "full_shape" }?.currentIndex?.let { it == 1 }
                ?: SettingsPreferences.punctuationFullWidth(androidx.compose.ui.platform.LocalContext.current, true)),
            japanese = state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.JAPANESE,
            chinese = !state.isAsciiMode && state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE),
        LocalKeyboardInputPreferences provides inputPreferences,
        LocalDeleteDrag provides callbacks.startDeleteDrag.takeUnless {
            page.textMainType() == MainType.HANDWRITING || !inputPreferences.swipeDistanceDelete },
        LocalEnterKeyColors provides KeyboardKeyColors(KeyboardThemes.getEnterKeyColor(state.themeId, state.isDarkTheme), specialKeyTextColor),
        LocalFunctionKeyColors provides KeyboardKeyColors(specialKeyBgColor, specialKeyTextColor),
        LocalKeyboardInputActions provides KeyboardInputActions(
            englishLetters = state.isAsciiMode,
            onLetterSwipeAction = { action ->
                when {
                    action.startsWith("text:") -> callbacks.onCommitText?.invoke(action.removePrefix("text:"))
                    action == "clipboard" -> viewModel.showOverlay(OverlayRoute.Clipboard(0))
                    else -> callbacks.onToolbarEditingAction?.invoke(action)
                }
            },
            onCursorMove = callbacks.onCursorMove,
            onCursorMoveVertical = callbacks.onCursorMoveVertical,
            onCursorModeChange = {
                cursorControlActive = it
                if (it) callbacks.onPreeditCursorMove?.invoke("preedit_cursor:0")
            },
            onVoiceModeChange = callbacks.onVoiceModeChange,
            onVoiceToggle = callbacks.onVoiceStickyToggle,
            onVoiceHoldEnd = callbacks.onVoiceHoldEnd,
            onVoiceHoldCancel = callbacks.onVoiceHoldCancel,
            isSttEnabled = state.isSttEnabled,
            schemas = com.kingzcheung.xime.settings.InputModes.available(state.schemas),
            currentInputModeId = com.kingzcheung.xime.settings.InputModes.selectedId(state.currentSchemaId, state.isAsciiMode),
            fieldNativeModeId = state.currentSchemaId.takeIf { state.isAddressField },
            onSwitchSchema = callbacks.onSwitchSchema,
            onCommitText = callbacks.onCommitText,
            onCommitExactText = callbacks.onCommitExactText,
            isVoiceMode = state.isVoiceMode,
            voiceSticky = state.voiceSticky,
        ),
    ) {
    // 调节态的坐标宿主。Service 在“悬浮 + 调节”时把这一层扩成整个可用 IME 区域；
    // 因而这里的 (0,0) 就是卡片与调节框共同使用的唯一坐标原点。
    BoxWithConstraints(modifier = Modifier.undockFollowTracker(undockFollow).then(modifier).fillMaxSize()) {
        val hostWidthPx = with(resizeControlDensity) { maxWidth.toPx() }
        val hostHeightPx = with(resizeControlDensity) { maxHeight.toPx() }
        val dragBarPx = with(resizeControlDensity) { footerDp.dp.toPx() }

        val hostLandscape = screenW > screenH
        val hostHeightDp = maxHeight.value.roundToInt().coerceAtLeast(1)
        val fixedRange = fixedHeightRange ?: keyboardHeightBounds(screenH, hostLandscape)
        val minBottomPx = state.floatingMinOffsetY.coerceAtLeast(0) * resizeControlDensity.density
        val normalFloatingRect = floatingCardRect(
            hostWidthPx = hostWidthPx,
            hostHeightPx = hostHeightPx,
            widthPx = (state.keyboardWidthDp.takeIf { it > 0 }?.toFloat()
                ?: (maxWidth.value * floatScaleFactor)) * resizeControlDensity.density,
            totalHeightPx = state.keyboardHeightDp * resizeControlDensity.density + dragBarPx,
            horizontalOffsetPx = state.floatingOffsetX * resizeControlDensity.density,
            bottomOffsetPx = state.floatingOffsetY * resizeControlDensity.density,
            minBottomInsetPx = minBottomPx,
        )
        // 转着的悬浮键盘按旋转规则摆的位置可能超出直角限位；调节从它真实所在的位置开始。
        val rotatedFloatingRect = if (state.isFloatingMode) floatingRotatedCardRect(hostWidthPx, hostHeightPx,
            normalFloatingRect.width, normalFloatingRect.height, state.floatingOffsetX.toFloat(),
            state.floatingOffsetY.toFloat(), state.floatingMinOffsetY.coerceAtLeast(0).toFloat(),
            state.floatingRotation, resizeControlDensity.density, footerDp) else null
        val seedPreviewRect = if (state.isFloatingMode) rotatedFloatingRect ?: normalFloatingRect else fixedKeyboardRect(
            hostWidthPx, hostHeightPx,
            state.keyboardHeightDp * resizeControlDensity.density,
            state.keyboardBottomPaddingDp.coerceAtLeast(0) * resizeControlDensity.density,
            fixedBottomInsetDp.coerceAtLeast(0) * resizeControlDensity.density,
            widthPx = (state.keyboardWidthDp.takeIf { it > 0 }
                ?: resolvedFixedKeyboardWidth(maxWidth.value.roundToInt(), state.fixedWidthDp)) * resizeControlDensity.density,
            horizontalOffsetPx = state.fixedOffsetX * resizeControlDensity.density,
            density = resizeControlDensity.density,
        )
        // 调节里关掉分体时退回的非分体位置：分体期间服务不改存着的宽度和水平位置（见 onGeometryChange），
        // 所以一进调节就是分体的也能退回原来的窄键盘。悬浮没存过宽度时为 null，保持原样。
        val nonSplitPreviewRect = if (state.isFloatingMode)
            (state.resizePreviewWidthDp.takeIf { it > 0 } ?: state.floatingWidthDp.takeIf { it > 0 })?.let { widthDp ->
                floatingCardRect(
                    hostWidthPx = hostWidthPx,
                    hostHeightPx = hostHeightPx,
                    widthPx = widthDp * resizeControlDensity.density,
                    totalHeightPx = state.keyboardHeightDp * resizeControlDensity.density + dragBarPx,
                    horizontalOffsetPx = state.floatingOffsetX * resizeControlDensity.density,
                    bottomOffsetPx = state.floatingOffsetY * resizeControlDensity.density,
                    minBottomInsetPx = minBottomPx,
                )
            }
        else fixedKeyboardRect(
            hostWidthPx, hostHeightPx,
            state.keyboardHeightDp * resizeControlDensity.density,
            state.keyboardBottomPaddingDp.coerceAtLeast(0) * resizeControlDensity.density,
            fixedBottomInsetDp.coerceAtLeast(0) * resizeControlDensity.density,
            widthPx = resolvedFixedKeyboardWidth(maxWidth.value.roundToInt(), state.fixedWidthDp) * resizeControlDensity.density,
            horizontalOffsetPx = state.fixedOffsetX * resizeControlDensity.density,
            density = resizeControlDensity.density,
        )
        // 只在进入调节、切模式或视口变化时创建事务。主题/透明度/偏好监听不能重置它。
        val sessionKey = listOf(resizeActive, state.isFloatingMode, maxWidth, maxHeight, fixedBottomInsetDp,
            state.keyboardBottomPaddingDp)
        val initialPreviewRect = remember(sessionKey) { seedPreviewRect }
        val initialPadding = remember(sessionKey) { state.keyboardBottomPaddingDp }
        val resizePreviewRect = remember(sessionKey) { mutableStateOf(initialPreviewRect) }
        val previewPaddingDp = remember(sessionKey) { mutableIntStateOf(initialPadding) }
        // 调节界面里转动的角度：只在本次调节里改，确认才写回，取消即丢。
        val resizeRotation = remember(sessionKey) { mutableFloatStateOf(if (state.isFloatingMode) state.floatingRotation else 0f) }
        val renderedBottomPaddingDp = if (resizeActive && !state.isFloatingMode)
            previewPaddingDp.intValue else state.keyboardBottomPaddingDp
        // 调节键盘时底部留白在被拖动改高度，不当移光标区。
        val bottomLiftCursorOn = inputPreferences.bottomLiftCursor && !resizeActive && !state.isFloatingMode

    FloatingKeyboardContainer(
        isFloatingMode = state.isFloatingMode,
        opacity = keyboardLayerOpacity,
        scaleFactor = floatScaleFactor,
        // KeyButton already fits text to the actual key bounds; do not shrink it again against the tablet screen.
        fontScaleFactor = 1f,
        offsetX = state.floatingOffsetX,
        offsetY = state.floatingOffsetY,
        minOffsetY = state.floatingMinOffsetY,
        availableHeightDp = state.floatingScreenHeightDp,
        // A floating card holds the search results and bar above its keys; without them in its height
        // the results squeezed the keys.
        contentHeightDp = state.keyboardHeightDp + clipboardSearchExtraHeight(
            state.isFloatingMode && state.clipboardSearchVisible, state.clipboardSearchResultsHeight,
            state.clipboardSearchInline),
        bottomContentPaddingDp = renderedBottomPaddingDp,
        bottomKeyClearance = floatingBottomClearance,
        fixedWidthDp = state.fixedWidthDp,
        fixedOffsetX = state.fixedOffsetX,
        resolvedWidthDp = state.keyboardWidthDp,
        dockTarget = dockTarget,
        backgroundColor = keyboardBgColor,
        dragIndicatorColor = floatingDragIndicatorColor,
        onDrag = { dx, dy -> callbacks.onFloatingKeyboardDrag?.invoke(dx, dy) },
        onDragEnd = { callbacks.onFloatingKeyboardDragEnd?.invoke() },
        onDock = { callbacks.onFloatingModeChange?.invoke(false) },
        // 固定键盘顶边的把手：往上拉变悬浮，和工具栏「悬浮」同一个切换。平板都有；手机上卡片比屏幕窄时也有
        // （横屏收窄居中、两边露出应用，看着就像悬浮，用户往上拽就该真的变悬浮，GitHub #155 等好几人当成悬浮）。
        onUndock = callbacks.onFloatingModeChange?.takeIf {
            val configuration = LocalConfiguration.current
            val cardWidthDp = state.keyboardWidthDp.takeIf { w -> w > 0 }
                ?: resolvedFixedKeyboardWidth(configuration.screenWidthDp, state.fixedWidthDp)
            !state.isFloatingMode && !resizeActive && !state.isHandwritingMode &&
                (configuration.smallestScreenWidthDp >= 600 || cardWidthDp < configuration.screenWidthDp - 1)
        }?.let { change -> { change(true) } },
        rotationDegrees = state.floatingRotation,
        previewRotationState = if (resizeActive && state.isFloatingMode) resizeRotation else null,
        previewRectState = if (resizeActive) resizePreviewRect else null,
        onCardPositioned = onCardPositioned,
        // 普通悬浮：亮着的四角角标拖动即调大小，点一下打开调节界面。剪贴板搜索临时撑高卡片时不调。
        onCornerResize = callbacks.onFloatingKeyboardResize?.takeIf {
            state.isFloatingMode && !resizeActive && !state.clipboardSearchVisible && !state.handwritingExpanded
        }?.let { save -> { g: ResizeGeometry -> save(g.widthDp, g.heightDp, g.horizontalOffsetDp, g.bottomOffsetDp) } },
        cornerResizeAspect = KeyboardAspectLimits.forLayout(state.inputProfile.layout.kind),
        cornerResizeHeightOnly = inputPreferences.splitKeyboardEnabled &&
            supportsSplitKeyboard(state.currentSchemaId, state.isAsciiMode),
        onCornerTap = callbacks.onKeyboardResize,
        onCornerHandlesPositioned = onCornerHandlesPositioned,
        sidesFilled = inputPreferences.fillBlankAreas && !state.handwritingExpanded,
    ) {
        // 候选栏随键盘半速放大：按卡片实际尺寸算，调节尺寸时跟着预览走；剪贴板上拉和搜索时卡片临时变高，不跟。
        val barScaleFrozen = LocalClipboardPanelExpansion.current != null || state.clipboardSearchVisible
        val candidateBarScaleState = remember {
            mutableFloatStateOf(candidateBarScale(
                (if (state.isFloatingMode) cardWidthDp else state.keyboardWidthDp.takeIf { it > 0 }
                    ?: state.fixedWidthDp.takeIf { it > 0 } ?: screenW).toFloat(),
                state.keyboardHeightDp.toFloat()))
        }
        // 候选栏上面那一截的高度（翻译栏、工具面板、剪贴板搜索、拼音编辑条）；菜单等面板据此让开工具栏，见 belowCandidateBar。
        val aboveCandidateBarPx = remember { mutableIntStateOf(0) }
        Box(modifier = Modifier.fillMaxSize().then(contentModifier).onSizeChanged { size ->
            if (!barScaleFrozen && size.width > 0 && size.height > 0) {
                candidateBarScaleState.floatValue = candidateBarScale(size.width / resizeControlDensity.density,
                    size.height / resizeControlDensity.density - renderedBottomPaddingDp)
            }
        }) {
        if (backgroundMotion != null && backgroundImageSlot != null) {
            KeyboardBackgroundMotionLayer(backgroundImageSlot, backgroundMotion, splitKeyboardGap,
                glass = inputPreferences.frostedGlass.enabled)
        }
        // 长按候选删除自造词：确认覆盖层状态（键盘视图内渲染，不弹独立
        // 窗口——焦点型弹窗会抢焦点导致 IME 被系统收起）
        // 长按删除待确认项：词文本 + 确认后执行（候选栏/展开页共用同一确认覆盖层）
        // 换输入框、关掉再打开键盘或候选变化后，未确认的删除自动取消，避免回来后误点。
        var deletePending by remember(state.inputSessionId) { mutableStateOf<DeletePendingWord?>(null) }
        // 候选变化就取消待确认的删除。用观察代替把候选状态当 remember 的 key：在这一层读候选状态，
        // 每按一个键都会让整块键盘重组（1.12.36 实测每键约 30 帧）。
        LaunchedEffect(state.inputSessionId) {
            snapshotFlow { candidateState.value.engineRevision to candidateState.value.inputText }
                .drop(1).collect { deletePending = null }
        }
        var preeditEditSession by remember(state.inputSessionId, state.currentSchemaId, state.isAsciiMode) {
            mutableStateOf<com.kingzcheung.xime.rime.PinyinEditSession?>(null)
        }
        var preeditRequest by remember(state.inputSessionId, state.currentSchemaId) { mutableIntStateOf(0) }
        var preeditFrame by remember { mutableStateOf<com.kingzcheung.xime.rime.PinyinEditSession?>(null) }
        var preeditOpening by remember(state.inputSessionId, state.currentSchemaId, state.isAsciiMode) { mutableStateOf(false) }
        val pendingPreeditMoves = remember(state.inputSessionId, state.currentSchemaId, state.isAsciiMode) { mutableListOf<String>() }
        fun editPreedit(key: String? = null, caret: Int? = null, text: String? = null) {
            val owner = preeditEditSession ?: return
            callbacks.onLivePreeditEdit?.invoke(owner, key, caret, text) { next ->
                if (owner.isActive() && preeditEditSession === owner) {
                    preeditFrame = next
                    if (next == null || next.text.isEmpty()) { preeditEditSession = null; preeditFrame = null; preeditRequest++ }
                }
            }
        }
        fun closePreeditEditor() {
            // Queued keystrokes must finish before a following commit/candidate event.
            // Ownership checks still reject work after a host or schema change.
            preeditEditSession = null
            callbacks.onPreeditKeyInput = null
            callbacks.onDismissPreeditEditor = null
            preeditFrame = null
            preeditOpening = false
            pendingPreeditMoves.clear()
            preeditRequest++
        }
        fun openPreeditEditor() {
            val open = callbacks.onOpenPreeditEditor ?: return
            if (preeditOpening) return
            preeditOpening = true
            val request = ++preeditRequest
            open { session ->
                if (request == preeditRequest) {
                    preeditOpening = false
                    preeditEditSession = session
                    preeditFrame = session
                    val moves = pendingPreeditMoves.toList()
                    pendingPreeditMoves.clear()
                    if (session != null) {
                        viewModel.setCandidatePageExpanded(false)
                        moves.forEach { editPreedit(key = it) }
                    }
                }
            }
        }
        SideEffect {
            callbacks.onPreeditCursorMove = { key ->
                when {
                    state.quickSendFormFocused || state.toolPanelInputFocused -> false
                    preeditEditSession != null -> { editPreedit(key = key); true }
                    !state.isAsciiMode && state.inputProfile.capabilities.editablePinyin &&
                        callbacks.onOpenPreeditEditor != null &&
                        (preeditOpening || candidateState.value.isComposing || candidateState.value.inputText.isNotEmpty()) -> {
                        // Opening is asynchronous. Retain every step, and never leak it to
                        // the host even if this composition cannot currently be edited.
                        pendingPreeditMoves.add(key)
                        openPreeditEditor()
                        true
                    }
                    else -> false
                }
            }
            callbacks.onDismissPreeditEditor = if (preeditEditSession != null || preeditOpening) ({ closePreeditEditor() }) else null
            callbacks.onPreeditKeyInput = if (preeditEditSession != null) ({ key ->
                val t9 = preeditEditSession?.isT9 == true
                if (key.startsWith("preedit_cursor:")) {
                    editPreedit(key = key); true
                } else if (key == "delete" || key == "'" || key.length == 1 &&
                    (key[0].lowercaseChar() in 'a'..'z' || t9 && key[0] in '1'..'9')) {
                    editPreedit(key = key); true
                } else { closePreeditEditor(); false }
            }) else null
        }
        DisposableEffect(callbacks) {
            onDispose {
                callbacks.onDismissPreeditEditor = null
                callbacks.onPreeditKeyInput = null
                callbacks.onPreeditCursorMove = null
                preeditRequest++
                pendingPreeditMoves.clear()
                preeditEditSession?.invalidate()
            }
        }
        LaunchedEffect(underlyingPage, resizeOverlay != null, inputPreferences.showCodeInCandidateBar) { closePreeditEditor() }
        val closePreeditOnEmpty by rememberUpdatedState { closePreeditEditor() }
        LaunchedEffect(Unit) {
            snapshotFlow { candidateState.value.isComposing || candidateState.value.inputText.isNotEmpty() }
                .collect { composing -> if (!composing) closePreeditOnEmpty() }
        }

        // 工具栏与菜单共用一组入口：动作、开/关状态和按键反馈相同，放在哪一处都一样。
        val quickContext = LocalContext.current
        val quickToggles = rememberQuickToggleStates()
        // 开无痕前先确认：菜单里一碰就开，之后复制的内容不再进剪贴板，用户不知道为什么。关掉不用确认。
        var incognitoConfirm by remember(state.inputSessionId) { mutableStateOf(false) }
        // 中文全拼、双拼 26 键：菜单里的「滑行输入」开关的是「中文滑行输入」（其他中文键盘不滑行，不出现）。
        val chineseGlideMenu = !state.isAsciiMode &&
            com.kingzcheung.xime.glide.chineseGlideSupported(state.inputProfile, state.currentSchemaId)
        // 九键：菜单里的「滑行输入」开关「九键滑行」（用户 11-09：开了以后键盘上找不到地方关）。
        val nineGlideMenu = state.inputProfile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.T9
        val traditionalSwitchApplies = !state.isAsciiMode && !state.isHandwritingMode &&
            state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
            state.inputProfile.scheme in setOf(com.kingzcheung.xime.settings.InputScheme.PINYIN,
                com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN, com.kingzcheung.xime.settings.InputScheme.CANGJIE,
                com.kingzcheung.xime.settings.InputScheme.ZHUYIN)
        fun quickAction(id: String): ToolbarAction? {
            val item = resolveToolbarButtonItem(id, state.toolbarPluginButtons) ?: return null
            val widthSwitch = if (state.isAsciiMode) null else state.schemaSwitches.firstOrNull { it.name == "full_shape" }
            val onClick: () -> Unit = when (item) {
                is ToolbarButtonItem.Builtin -> when (item.button) {
                    ToolbarButton.EMOJI -> ({ viewModel.toggleOverlay(OverlayRoute.Emoji) })
                    ToolbarButton.CLIPBOARD -> ({ viewModel.toggleOverlay(OverlayRoute.Clipboard(0)) })
                    ToolbarButton.OCR -> ({
                        com.kingzcheung.xime.ocr.OcrGallerySelection.uri.value = null
                        viewModel.toggleOverlay(OverlayRoute.Ocr)
                    })
                    ToolbarButton.SCHEMA -> ({ viewModel.toggleOverlay(OverlayRoute.SchemaList) })
                    ToolbarButton.SYMBOL -> ({ viewModel.toggleOverlay(OverlayRoute.Symbol) })
                    ToolbarButton.FLOAT -> ({ viewModel.closeOverlay(); callbacks.onKeyboardResize?.invoke() })
                    ToolbarButton.HANDWRITING_LOOKUP -> ({
                        if (!state.isHandwritingMode && !viewModel.hasTemporaryHandwriting) {
                            if (keyboardState is KeyboardLayoutState.T9Pinyin) callbacks.onT9SwitchAway?.invoke()
                            else callbacks.onCommitCandidateBeforeModeChange?.invoke()
                        }
                        callbacks.onHandwritingToggle?.invoke()
                    })
                    ToolbarButton.EDIT -> ({ viewModel.toggleOverlay(OverlayRoute.Edit) })
                    ToolbarButton.SETTINGS -> ({ callbacks.onSettings?.invoke(); viewModel.closeOverlay() })
                    // 字幕是独立全屏页面，键盘随之收起，麦克风交给字幕页。没装「字幕」插件就不出现（排列页也不列）。
                    ToolbarButton.CAPTION -> if (com.kingzcheung.xime.ui.caption.CaptionPlugin.isActive(quickContext))
                        ({ viewModel.closeOverlay(); com.kingzcheung.xime.ui.caption.CaptionActivity.open(quickContext) }) else return null
                    ToolbarButton.ARRANGE -> ({ viewModel.showOverlay(OverlayRoute.ToolbarCustomize) })
                    // 布局手势开关只在适用的布局出现；排列页用同一规则（[quickAction] 为 null 即不列出）。
                    ToolbarButton.GLIDE, ToolbarButton.T9_FLICK, ToolbarButton.FOURTEEN_SWIPE,
                    ToolbarButton.KANA_HINTS, ToolbarButton.FLICK_DISC ->
                        if (item.button.appliesTo(state.inputProfile.layout.kind) &&
                            // 滑行开关：英文 26 键开关英文滑行，中文全拼、双拼 26 键开关中文滑行，九键开关九键滑行；
                            // 被「键盘滑动」占用时不出现。
                            (item.button != ToolbarButton.GLIDE ||
                                ((nineGlideMenu || state.isAsciiMode || chineseGlideMenu) && inputPreferences.cursorGesture != CursorGestureMode.KEYBOARD)))
                            ({ item.button.flipGesture(quickContext, inputPreferences, chineseGlideMenu, nineGlideMenu) }) else return null
                    ToolbarButton.SOUND -> ({
                        val on = !(quickToggles.sound || quickToggles.vibration)
                        SettingsPreferences.setSoundEnabled(quickContext, on)
                        SettingsPreferences.setVibrationEnabled(quickContext, on)
                    })
                    ToolbarButton.FLOATING -> ({
                        viewModel.closeOverlay()
                        callbacks.onFloatingModeChange?.invoke(!state.isFloatingMode)
                    })
                    ToolbarButton.GLASS -> ({
                        val glass = com.kingzcheung.xime.settings.FrostedGlassPreferences.read(quickContext)
                        com.kingzcheung.xime.settings.FrostedGlassPreferences.save(quickContext, glass.copy(enabled = !glass.enabled))
                    })
                    ToolbarButton.INCOGNITO -> ({
                        if (quickToggles.incognito) SettingsPreferences.setIncognito(quickContext, false)
                        else incognitoConfirm = true
                    })
                    // 英文状态标点固定半角，入口不出现，避免“点了看不到变化，实际却改了状态”。
                    ToolbarButton.FULL_SHAPE -> widthSwitch?.let { sw -> { callbacks.onToggleSchemaSwitch?.invoke(sw) } } ?: return null
                    // 拼音、双拼、仓颉、注音共用一个简繁开关。
                    ToolbarButton.TRADITIONAL -> if (traditionalSwitchApplies) ({
                        inputPreferences.copy(traditionalOutput = !inputPreferences.traditionalOutput).save(quickContext)
                    }) else return null
                    // 「语音转文本」关着时麦克风入口不出现（工具栏和菜单都是），不再显示一个点了只弹提示的图标。
                    ToolbarButton.VOICE -> if (!state.isSttEnabled) return null else ({
                        if (PermissionHelper.hasRecordAudioPermission(quickContext)) {
                            if (!state.isVoiceMode) {
                                if (keyboardState is KeyboardLayoutState.T9Pinyin) callbacks.onT9SwitchAway?.invoke()
                                else callbacks.onCommitCandidateBeforeModeChange?.invoke()
                            }
                            callbacks.onVoiceStickyToggle?.invoke()
                        } else {
                            android.widget.Toast.makeText(quickContext, "需要麦克风权限才能使用语音输入", android.widget.Toast.LENGTH_SHORT).show()
                            PermissionHelper.requestRecordAudioPermission(quickContext)
                        }
                    })
                }
                is ToolbarButtonItem.Plugin -> ({
                    if (item.action == "open_panel") {
                        callbacks.onOpenToolPanel?.invoke(item.pluginId)
                    }
                })
            }
            val settingsEntry = com.kingzcheung.xime.ui.settings.menuSettingsEntry(item)
            val openSettings: (() -> Unit)? = settingsEntry?.let { entry -> {
                toolbarFeedback()
                com.kingzcheung.xime.ui.settings.SettingsSearchFocus.open(entry)
                callbacks.onSettingsPage?.invoke(entry.target)
                viewModel.closeOverlay()
            } }
            return ToolbarAction(item, onLongClick = openSettings, active = item is ToolbarButtonItem.Builtin && when (item.button) {
                ToolbarButton.EDIT -> (page as? KeyboardPage.Overlay)?.route == OverlayRoute.Edit
                ToolbarButton.EMOJI -> (page as? KeyboardPage.Overlay)?.route == OverlayRoute.Emoji
                ToolbarButton.CLIPBOARD -> (page as? KeyboardPage.Overlay)?.route is OverlayRoute.Clipboard
                ToolbarButton.OCR -> (page as? KeyboardPage.Overlay)?.route == OverlayRoute.Ocr
                ToolbarButton.SYMBOL -> (page as? KeyboardPage.Overlay)?.route == OverlayRoute.Symbol
                ToolbarButton.SCHEMA -> (page as? KeyboardPage.Overlay)?.route == OverlayRoute.SchemaList
                // 表情、剪贴板等面板盖住手写板时只亮面板那个按钮（#185）
                ToolbarButton.HANDWRITING_LOOKUP -> handwritingToolbarActive(page,
                    state.isHandwritingMode || viewModel.hasTemporaryHandwriting)
                ToolbarButton.VOICE -> state.voiceSticky
                ToolbarButton.SOUND -> quickToggles.sound || quickToggles.vibration
                ToolbarButton.FLOATING -> state.isFloatingMode
                ToolbarButton.GLASS -> inputPreferences.frostedGlass.enabled
                ToolbarButton.INCOGNITO -> quickToggles.incognito
                in ToolbarButton.LAYOUT_GESTURES -> item.button.gestureOn(inputPreferences, chineseGlideMenu, nineGlideMenu)
                ToolbarButton.FULL_SHAPE -> widthSwitch?.let { it.states.getOrNull(it.currentIndex) == "全角" } == true
                ToolbarButton.TRADITIONAL -> inputPreferences.traditionalOutput
                else -> false
            }) {
                toolbarFeedback()
                onClick()
            }
        }
        // 无痕只在它自己的位置（工具栏或菜单）显示开启状态，不额外插入图标。
        val toolbarQuickActions = state.toolbarButtons.mapNotNull(::quickAction)
        val menuQuickActions = state.menuButtons.mapNotNull(::quickAction)
        // 面板高度包含工具栏/候选栏；按键区只分配剩余高度。
        // Service 以整张卡片顶部上报避让区域，不能只上报按键区。
        // 拼音放在键盘内时，光标调整移动的就是候选栏里的拼音：候选栏不压淡，只压淡按键区（#162）。
        // 只在光标调整中读候选状态，平时按键不连带整块键盘重组。
        val cursorKeepsBar = cursorControlActive && inputPreferences.preeditInsideKeyboard &&
            candidateState.value.shownPreedit(state.inputProfile).isNotEmpty()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (onPreviewArrangePositioned != null) Modifier.clearAndSetSemantics {} else Modifier)
                .hiddenDuringCursorControl(cursorControlActive && !cursorKeepsBar)
        ) {
            var handwritingCandidates by remember(state.inputSessionId, state.inputProfile.language) { mutableStateOf<List<String>>(emptyList()) }
            var handwritingClearSignal by remember(state.inputSessionId, state.inputProfile.language) { mutableIntStateOf(0) }
            val handwritingPending = remember(state.inputSessionId, state.inputProfile.language) { HandwritingCandidateQueue() }
            fun refreshHandwritingCandidates() {
                handwritingCandidates = handwritingPending.candidates
            }
            fun clearHandwriting() {
                handwritingPending.clear()
                handwritingCandidates = emptyList()
                handwritingClearSignal++
            }
            val handwritingContext = LocalContext.current
            fun selectHandwriting(index: Int) {
                callbacks.onKeyPressDown?.invoke("standard")
                handwritingPending.select(index)?.let {
                    callbacks.onCommitText?.invoke(it)
                    // 假名 / 字母边用边学：选中的字记成这次笔迹的新模板（英文新词前补的空格不算）
                    com.kingzcheung.xime.handwriting.HandwritingTemplates.learnSelection(handwritingContext, it.trim())
                    clearHandwriting()
                }
            }
            fun confirmHandwriting() {
                handwritingPending.confirmAll().takeIf { it.isNotEmpty() }?.let {
                    callbacks.onCommitText?.invoke(it)
                    // 空格确认的首选和点选一样学（只有整段是一个字时才学，无痕时不学）
                    com.kingzcheung.xime.handwriting.HandwritingTemplates.learnSelection(handwritingContext, it.trim())
                }
                clearHandwriting()
            }
            DisposableEffect(handwritingPending) {
                viewModel.confirmPendingHandwriting = { if (handwritingPending.size > 0) confirmHandwriting() }
                onDispose { viewModel.confirmPendingHandwriting = null }
            }

            // 数据源（展开与否即切换点）：展开态下候选栏与展开页同吃全量列表
            // （expandedCandidates，展开时服务层重新拉取）；非展开态候选栏保持
            // 引擎当前页（"每页候选词数"）
            val isHandwritingPage = underlyingPage is KeyboardPage.Main && underlyingPage.type == MainType.HANDWRITING
            // Panel/input-session changes discard unconfirmed ink, never alter host text.
            LaunchedEffect(isHandwritingPage, state.inputSessionId, state.inputProfile.language) {
                clearHandwriting()
            }
            val showHandwritingCandidates = isHandwritingPage && handwritingCandidates.isNotEmpty()
            // 中文手写候选的注释是读音（带声调），忘了读音的字写出来对一下；释义模式下也显示读音。
            val handwritingChinese = isHandwritingPage && state.inputProfile.language == InputLanguage.CHINESE
            val wubiHints = handwritingChinese && inputPreferences.handwritingWubiHints &&
                state.inputProfile.scheme in setOf(com.kingzcheung.xime.settings.InputScheme.WUBI,
                    com.kingzcheung.xime.settings.InputScheme.WUBI_PINYIN)
            LaunchedEffect(handwritingChinese, wubiHints) {
                if (handwritingChinese && !wubiHints) com.kingzcheung.xime.handwriting.HanziPinyin.load(handwritingContext)
            }
            val hanziPinyinRevision = com.kingzcheung.xime.handwriting.HanziPinyin.revision
            val handwritingComments = if (wubiHints) androidx.compose.runtime.key(state.currentSchemaId, handwritingCandidates) {
                androidx.compose.runtime.produceState<List<String>>(emptyList()) {
                    if (handwritingCandidates.isNotEmpty()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.kingzcheung.xime.rime.RimeEngine.getInstance().currentSchemeCodes(state.currentSchemaId, handwritingCandidates)
                    }
                }.value
            } else remember(handwritingCandidates, handwritingChinese, hanziPinyinRevision) {
                if (handwritingChinese) com.kingzcheung.xime.handwriting.HanziPinyin.comments(handwritingCandidates) else emptyList()
            }

            if (state.showQuickSendForm && state.ocrDraft) {
                val ocrContext = LocalContext.current
                com.kingzcheung.xime.ocr.OcrReviewEditor(
                    text = state.quickSendEditingItemText, textColor = keyTextColor, background = keyBgColor, accent = accentColor,
                    onFocus = { callbacks.onQuickSendFormFocusChange?.invoke(it) },
                    onSave = { text ->
                        val result = com.kingzcheung.xime.ocr.saveOcrDraft(ocrContext, text)
                        if (result == ClipboardDraftSaveResult.ACCEPTED) callbacks.onHideQuickSendForm?.invoke()
                        else QuickSendFormEditTextHolder.editText?.error = result.errorMessage
                    },
                    onCancel = { callbacks.onHideQuickSendForm?.invoke(); viewModel.closeOverlay() },
                    onRetake = { callbacks.onHideQuickSendForm?.invoke(); viewModel.showOverlay(OverlayRoute.Ocr) },
                )
            } else if (state.showQuickSendForm) {
                val clipboardGroups = com.kingzcheung.xime.clipboard.ClipboardGroupStore.getInstance(LocalContext.current)
                // Opened from a group's tab: the record goes into that group and is not fixed, so the title says so.
                val formTitle = remember(state.quickSendEditingItemId) {
                    if (state.quickSendEditingItemId != null) "编辑剪贴板"
                    else clipboardGroups.addTargetGroup()?.let { "新增到「${it.name}」" } ?: "新增固定"
                }
                QuickSendFormArea(
                    title = formTitle,
                    backgroundColor = Color.Transparent,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    isFocused = state.quickSendFormFocused,
                    initialText = state.quickSendEditingItemText,
                    initialCode = state.quickSendEditingItemCode,
                    cardBgColor = keyBgColor,
                    editingItemId = state.quickSendEditingItemId,
                    onClose = { text: String, code: String ->
                        android.util.Log.d("QuickSendForm", "onClose: textLen=${text.length}, editingId=${state.quickSendEditingItemId}, showForm=${state.showQuickSendForm}")
                        val result = submitClipboardDraft(
                            text, code, state.quickSendEditingItemId,
                            // Added from a group's tab: the new record goes into that group (not fixed).
                            add = { newText, newCode -> viewModel.addPinnedText(newText, newCode, clipboardGroups.takeAddTarget()) },
                            update = viewModel::updateClipboardItem,
                        )
                        if (result == ClipboardDraftSaveResult.ACCEPTED) {
                            callbacks.onHideQuickSendForm?.invoke()
                        } else {
                            QuickSendFormEditTextHolder.editText?.apply {
                                error = result.errorMessage
                                requestFocus()
                            }
                        }
                    },
                    onCancel = { clipboardGroups.takeAddTarget(); callbacks.onHideQuickSendForm?.invoke() },
                    onFocusChange = { focused: Boolean ->
                        callbacks.onQuickSendFormFocusChange?.invoke(focused)
                    },
                    onCodeFocusChange = { focused: Boolean ->
                        callbacks.onQuickSendCodeFocusChange?.invoke(focused)
                    },
                )
            }

            // ACTIVE 输入面板在候选栏上方（需要 EditText 输入，保留候选栏可见）；
            // PASSIVE 纯展示面板走 Overlay 全屏（KeyboardView 底部 Overlay 分支渲染 InfoPanel）
            if (state.toolPanelVisible && state.toolPanelDisplay == "LIVE") {
                // 翻译栏：一行，圆钮和提示由插件给（第一个按钮、第一段文字）
                val nodes = state.toolPanelUiNodes.orEmpty()
                val chip = nodes.firstOrNull { it.type == com.kingzcheung.xime.plugin.core.config.UiNodeType.BUTTON }
                val hintNode = nodes.firstOrNull { it.type == com.kingzcheung.xime.plugin.core.config.UiNodeType.TEXT }
                ToolPanelBar(
                    chipLabel = chip?.label,
                    hint = hintNode?.value ?: state.toolPanelTitle,
                    isFocused = state.toolPanelInputFocused,
                    isLoading = state.toolPanelLoading,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    cardBgColor = keyBgColor,
                    onChipClick = { chip?.key?.let { callbacks.onToolPanelAction?.invoke(it) } },
                    onClose = { callbacks.onToolPanelClose?.invoke() },
                    onFocusChange = { focused -> callbacks.onToolPanelFocusChange?.invoke(focused) },
                    onTextChange = { text -> callbacks.onToolPanelTextChange?.invoke(text) },
                )
            } else if (state.toolPanelVisible && state.toolPanelDisplay != "PASSIVE") {
                ToolPanel(
                    title = state.toolPanelTitle,
                    isFocused = state.toolPanelInputFocused,
                    isLoading = state.toolPanelLoading,
                    initialText = state.toolPanelPrefillText,
                    backgroundColor = Color.Transparent,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    cardBgColor = keyBgColor,
                    onClose = { callbacks.onToolPanelClose?.invoke() },
                    onFocusChange = { focused -> callbacks.onToolPanelFocusChange?.invoke(focused) },
                )
            }

            // 剪贴板搜索：键盘尺寸和位置不变。搜索框就是工具面板（同一组件、同一位置）；
            // 上面是剪贴板，用剪贴板上拉展开的高度和动画打开，只显示搜索结果。
            if (state.clipboardSearchVisible) {
                val clipboardContext = androidx.compose.ui.platform.LocalContext.current
                val clipboard = remember(clipboardContext) {
                    com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(clipboardContext) }
                ClipboardSearchResults(clipboard, candidateState, isT9Schema(state.currentSchemaId), state.inputProfile,
                    state.clipboardItems, callbacks, viewModel::removeClipboardItems,
                    Modifier.fillMaxWidth().height(state.clipboardSearchResultsHeight.dp),
                    showControls = !state.clipboardSearchInline)
                ToolPanel(
                    title = "搜索剪贴板",
                    isFocused = true,
                    backgroundColor = Color.Transparent,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    cardBgColor = keyBgColor,
                    onClose = { callbacks.onClipboardSearchClose?.invoke() },
                    onFocusChange = { focused -> callbacks.onToolPanelFocusChange?.invoke(focused) },
                    heightDp = clipboardSearchPanelHeight(state.clipboardSearchInline),
                    hint = "文字或拼音首字母",
                    statusText = "",
                    singleLine = true,
                    onTextChange = { clipboard.boardQuery.value = it },
                    inline = state.clipboardSearchInline,
                )
            }

            val editFrame = preeditFrame.takeIf { inputPreferences.showCodeInCandidateBar && preeditEditSession != null }
            val drawPreeditEditor: (@Composable () -> Unit)? = if (editFrame == null) null else ({
                PreeditEditorBar(editFrame, keyBgColor, candidateTextColor, accentColor,
                    onClose = { closePreeditEditor() },
                    onCaret = { editPreedit(caret = it) },
                    onReplace = { editPreedit(text = it) },
                    preedit = candidateState.value.preeditText, inline = inputPreferences.inlinePreeditPreview)
            })
            if (!inputPreferences.inlinePreeditPreview) drawPreeditEditor?.invoke()

            // 候选栏与展开页共用：展开页从同一版本中实际显示的候选之后接着排。
            var visibleBarAssociations by remember(singleCharFilter) { mutableStateOf(emptyList<String>()) }
            var visibleBarCandidates by remember(singleCharFilter) { mutableStateOf(emptyList<String>()) }
            var visibleBarCandidateRevision by remember(singleCharFilter) { mutableLongStateOf(Long.MIN_VALUE) }
            var visibleBarUsesExpanded by remember(singleCharFilter) { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                snapshotFlow { candidateState.value.inputText }.drop(1).collect {
                    visibleBarAssociations = emptyList()
                }
            }
            // 上屏后换了一组联想：栏里上一组放下了几个不再作数，展开页按新的一组重新接。
            LaunchedEffect(Unit) {
                snapshotFlow { candidateState.value.associationCandidates }.drop(1).collect {
                    visibleBarAssociations = emptyList()
                }
            }
            // 候选状态只在候选栏自己的组合范围里读：每次按键只重组候选栏，不再连带整块键盘
            // （Box/Column 是内联的，在外层读候选状态会让整个键盘一起重组）。
            val renderCandidateBar: @Composable (KeyboardPage) -> Unit = { barPage ->
            // 收起的候选栏滑到后面接上展开列表时，数据源同展开态（不打开展开页）。
            val expandedDataMode = (candidatePageExpanded || candidateStripExtended) &&
                candidateState.value.expandedCandidatesLoaded

            val cs = candidateState.value
            val liveGlide = glideRimePreview.value
            val englishGlide = glideEnglishPreview.value
            androidx.compose.runtime.SideEffect { com.kingzcheung.xime.service.KeyLatencyProbe.composed() }
            // 展开态候选栏数据源：全量列表（筛选态取单字子列表，仍是同源关系）。
            // 每项携带全量原索引：筛选态过滤掉词组后位置索引 ≠ 全局索引，
            // 点选/长按必须经 first 换算回全局索引，与展开页 globalIndices 同口径
            val railExpanded = if (expandedDataMode) {
                if (singleCharFilter) {
                    cs.expandedCandidates.withIndex()
                        .mapNotNull { (i, c) -> if (c.text.isSingleCharacter()) i to c else null }
                } else {
                    cs.expandedCandidates.withIndex().map { it.index to it.value }
                }
            } else {
                emptyList()
            }
            // 释义模式只换显示用注释；点选仍按索引回到原候选数据（九键靠其中的拼音定位）。
            val glossRevision = CandidateGlossary.revision
            val glossChinese = !state.isAsciiMode && state.inputProfile.language == InputLanguage.CHINESE
            // 按住 Shift（屏幕键、物理键或九键长按“下一项”）时有英文的候选中英对调，此时点选上屏英文。
            val shiftModeNow by viewModel.shiftMode.collectAsStateWithLifecycle()
            val glossSwap = glossChinese &&
                CandidateGlossary.swapHeld(shiftModeNow == com.kingzcheung.xime.viewmodel.ShiftMode.HELD)
            val candidateBarState = remember(
                cs.candidates, cs.candidateComments, cs.inputText, cs.preeditText, cs.isComposing,
                cs.associationCandidates, cs.pendingEnglishText, cs.isShowingRecentClipboard, cs.hasNextPage,
                cs.calculatorCandidates, handwritingCandidates, handwritingComments, showHandwritingCandidates,
                railExpanded, expandedDataMode, isHandwritingPage, cs.smsVerificationCode, cs.clipboardImage, cs.clipboardExtractedCount,
                cs.candidateFocus, cs.engineRevision, cs.candidateActions, state.inputProfile,
                hardwareCandidateHighlight, glossRevision, glossChinese, glossSwap,
                liveGlide, englishGlide,
            ) {
                if (showHandwritingCandidates) {
                    CandidateBarState.AssociationOnly(
                        candidates = handwritingCandidates,
                        comments = handwritingComments,
                        highlightIndex = 0,
                    )
                } else if (isHandwritingPage) {
                    CandidateBarState.Idle
                } else if (englishGlide != null) {
                    CandidateBarState.AssociationOnly(candidates = englishGlide.candidates, highlightIndex = 0)
                } else if (liveGlide != null) {
                    val texts = liveGlide.candidates.map { it.text }
                    val comments = liveGlide.candidates.map { it.comment }
                    val (shownTexts, shownComments) = when {
                        glossSwap -> CandidateGlossary.swapped(texts, comments)
                        glossChinese -> texts to CandidateGlossary.annotate(texts, comments)
                        else -> texts to comments
                    }
                    CandidateBarState.ChineseCandidates(
                        candidates = shownTexts, comments = shownComments,
                        inputText = liveGlide.inputText, preeditText = liveGlide.preeditText,
                        hasMore = liveGlide.hasNextPage,
                    )
                } else {
                    // 计算器结果只看候选状态自己带的标记（GitHub #289），不受展开页数据影响。
                    val calculator = cs.showsCalculator
                    val expandedBar = expandedDataMode && !calculator
                    val barTexts = if (expandedBar) railExpanded.map { it.second.text } else cs.candidates
                    val barComments = if (expandedBar) railExpanded.map { it.second.comment } else cs.candidateComments
                    val (shownTexts, shownComments) = when {
                        glossSwap -> CandidateGlossary.swapped(barTexts, barComments)
                        glossChinese -> barTexts to CandidateGlossary.annotate(barTexts, barComments)
                        else -> barTexts to barComments
                    }
                    CandidateBarState.from(
                        candidates = shownTexts,
                        candidateComments = shownComments,
                        inputText = cs.inputText,
                        preeditText = cs.shownPreedit(state.inputProfile),
                        isComposing = cs.isComposing,
                        associationCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
                            listOf(cs.pendingEnglishText) + cs.associationCandidates
                        } else {
                            cs.associationCandidates
                        },
                        isShowingRecentClipboard = cs.isShowingRecentClipboard,
                        hasNextPage = cs.hasNextPage,
                        isCalculatorActive = calculator,
                        smsVerificationCode = cs.smsVerificationCode,
                        clipboardImage = cs.clipboardImage,
                        clipboardExtractedCount = cs.clipboardExtractedCount,
                        highlightIndex = if (expandedDataMode || !cs.usesT9CandidateNavigation(state.inputProfile)) 0
                            else cs.highlightedCandidateIndex,
                        hardwareHighlightIndex = hardwareCandidateHighlight,
                        associationHighlightIndex = cs.focusedT9AssociationIndex,
                        expandAssociations = cs.pendingEnglishText.isEmpty(),
                    )
                }
            }

            val predictionPending = predictionTransition?.collectAsStateWithLifecycle()?.value == true
            var previousBar by remember(state.inputSessionId) { mutableStateOf<CandidateBarState?>(null) }
            SideEffect {
                if (candidateBarState is CandidateBarState.ChineseCandidates || candidateBarState is CandidateBarState.AssociationOnly) {
                    previousBar = candidateBarState
                } else if (candidateBarState is CandidateBarState.Idle && !predictionPending) {
                    previousBar = null
                }
            }
            val holdingBar = predictionPending && candidateBarState is CandidateBarState.Idle && previousBar != null
            val correctionVisible = rememberCorrectionMarksVisible()
            val doublePinyinKeysShown = rememberDoublePinyinKeysShown()
            val correctionScheme = remember(state.currentSchemaId) {
                com.kingzcheung.xime.settings.DoublePinyinSchemes.find(state.currentSchemaId)
            }
            val correctionMarks = remember(cs.correctedCandidates, correctionVisible, correctionScheme, doublePinyinKeysShown) {
                when {
                    !correctionVisible -> emptyMap()
                    correctionScheme == null || !doublePinyinKeysShown -> cs.correctedCandidates
                    else -> cs.correctedCandidates.mapValues { (_, marks) ->
                        com.kingzcheung.xime.rime.CorrectionMarks.doublePinyin(marks, correctionScheme)
                    }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalCorrectedCandidates provides correctionMarks,
                LocalCorrectionFullScheme provides correctionScheme.takeIf { !doublePinyinKeysShown }) {
            ScaledCandidateBar(candidateBarScaleState.floatValue) {
            CandidateBar(
                modifier = Modifier.then(if (holdingBar) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                .changes.forEach { it.consume() }
                        }
                    }
                } else Modifier)
                    .clipboardPanelExpandGesture().drawWithContent {
                    drawContent()
                    InputLatencyTrace.finish(candidateState.value.traceEventId, "draw-submitted")
                },
                state = if (barPage is KeyboardPage.Overlay || candidateBarState is CandidateBarState.ClipboardDisplay &&
                    (state.voiceSticky || state.isHandwritingMode || viewModel.hasTemporaryHandwriting))
                    CandidateBarState.Idle else if (holdingBar) previousBar!! else candidateBarState,
                glidePreviewText = glideCandidatePreview.value,
                isGlidePreview = liveGlide != null || englishGlide != null,
                page = barPage,
                candidatePageExpanded = candidatePageExpanded,
                commentsAreGlosses = glossChinese && CandidateGlossary.isLoaded,
                showNumberLabels = hardwareKeyboardConnected && !state.isAsciiMode && cs.pendingEnglishText.isEmpty() &&
                    !showHandwritingCandidates && !expandedDataMode,
                isFloatingMode = state.isFloatingMode,
                isVoiceSticky = state.voiceSticky,
                voiceAmplitude = voiceAmplitudeState.value,
                voiceSpectrum = voiceSpectrumState.value,
                voiceRecognitionState = state.voiceRecognitionState,
                voicePluginName = state.voicePluginName,
                toolbarActions = toolbarQuickActions,
                visuals = CandidateBarVisuals(
                    backgroundColor = Color.Transparent,
                    textColor = candidateTextColor,
                    dividerColor = dividerColor,
                    accentColor = accentColor,
                    selectedTextColor = candidateSelectedTextColor,
                    isDarkTheme = state.isDarkTheme,
                    preeditBackgroundColor = keyBgColor,
                ),
                callbacks = CandidateBarCallbacks(
                    onVisibleCandidatesChanged = {
                        visibleBarCandidates = it
                        visibleBarCandidateRevision = cs.engineRevision
                        visibleBarUsesExpanded = expandedDataMode
                    },
                    onVisibleAssociationsChanged = { visibleBarAssociations = it },
                    onReorderToolbar = callbacks.onUpdateToolbarButtons,
                    onCancelInput = {
                        closePreeditEditor()
                        toolbarFeedback()
                        viewModel.setCandidatePageExpanded(false)
                        if (showHandwritingCandidates) {
                            clearHandwriting()
                        } else if (candidateBarState is CandidateBarState.AssociationOnly &&
                            cs.pendingEnglishText.isEmpty() && callbacks.onClearAssociation != null) {
                            callbacks.onClearAssociation.invoke()
                        } else {
                            callbacks.onKeyPress("clear_composition", false)
                        }
                    },
                    onDismissClipboardPreview = {
                        toolbarFeedback()
                        callbacks.onDismissClipboardPreview?.invoke()
                    },
                    onOpenClipboard = {
                        toolbarFeedback()
                        callbacks.onDismissClipboardPreview?.invoke()
                        viewModel.showOverlay(OverlayRoute.Clipboard(0))
                    },
                    onClipboardImageSelect = callbacks.onClipboardImageSelect,
                    onExpandClipboardImages = callbacks.onExpandClipboardImages,
                    onCandidateSelect = select@{ index ->
                        if (liveGlide != null || englishGlide != null) return@select
                        if (!showHandwritingCandidates && !callbacks.isCandidateSnapshotCurrent(cs)) return@select
                        closePreeditEditor()
                        if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            selectHandwriting(index)
                        } else if (expandedDataMode && !cs.showsCalculator) {
                            // 展开态候选栏数据源=全量列表（筛选态为其单字子列表）：
                            // 位置索引经 railExpanded 换算全局索引再走全局链路
                            val globalIndex = railExpanded.getOrNull(index)?.first
                            if (globalIndex != null) {
                                callbacks.onGlobalCandidateSelect?.invoke(globalIndex)
                            }
                        } else {
                            callbacks.onCandidateSelect(index)
                        }
                    },
                    onCandidateStripScrolled = {
                        // 展开着滑候选栏：收起展开页，候选栏接着用展开列表滑下去，同一个候选不出现两次。
                        if (candidatePageExpanded) {
                            viewModel.setCandidateStripExtended(true)
                            viewModel.setCandidatePageExpanded(false)
                        }
                    },
                    onCandidateStripNearEnd = {
                        // 只在引擎第一页、没有键盘焦点时接：展开列表从第一个候选排起，位置才对得上。
                        if (!candidatePageExpanded && !showHandwritingCandidates && cs.hasNextPage &&
                            !cs.hasPrevPage && cs.candidateFocus == null) callbacks.onExtendCandidateStrip?.invoke()
                    },
                    onCandidateLongPress = { index ->
                        // 手写候选：读手写列表（不是引擎列表），长按＝忘掉这个字从手写里学到的笔迹
                        val handwritingWord = if (showHandwritingCandidates) handwritingCandidates.getOrNull(index) else null
                        val railEntry = if (expandedDataMode && !showHandwritingCandidates) railExpanded.getOrNull(index) else null
                        val word = railEntry?.second?.text
                            ?: cs.candidates.getOrNull(index)
                        if (showHandwritingCandidates) {
                            if (!handwritingWord.isNullOrEmpty()) deletePending = DeletePendingWord(handwritingWord,
                                "忘掉「$handwritingWord」从手写里学到的笔迹？内置的写法和训练样本照常能认。") {
                                com.kingzcheung.xime.handwriting.HandwritingTemplates.forget(handwritingContext, handwritingWord)
                            }
                        } else if (!word.isNullOrEmpty()) {
                            val parts = sentenceParts(callbacks, cs, word, first = (railEntry?.first ?: index) == 0)
                            deletePending = DeletePendingWord(word, pin = pinChoice(callbacks, cs, word), parts = parts) {
                                if (!callbacks.isCandidateSnapshotCurrent(cs)) return@DeletePendingWord
                                if (railEntry != null) {
                                    callbacks.onGlobalCandidateDelete?.invoke(railEntry.first)
                                } else {
                                    callbacks.onCandidateDelete?.invoke(index)
                                }
                            }
                        }
                    },
                    onClearAssociation = {
                        toolbarFeedback()
                        if (showHandwritingCandidates) {
                            clearHandwriting()
                        } else {
                            callbacks.onClearAssociation?.invoke()
                        }
                    },
                    onLogoClick = {
                        toolbarFeedback()
                        viewModel.showOverlay(OverlayRoute.Menu)
                    },
                    onBack = {
                        toolbarFeedback()
                        if (page is KeyboardPage.Overlay) {
                            viewModel.backFromOverlay()
                        } else if (isHandwritingPage) {
                            callbacks.onHandwritingToggle?.invoke()
                        } else if (showHandwritingCandidates) {
                            clearHandwriting()
                        } else if (page is KeyboardPage.Panel) {
                            viewModel.exitPanel()
                        } else {
                            viewModel.setCandidatePageExpanded(false)
                        }
                    },
                    onHideKeyboard = {
                        toolbarFeedback()
                        callbacks.onHideKeyboard?.invoke()
                        viewModel.resetKeyboard(state.isAsciiMode, state.currentSchemaId)
                    },
                    onShowMoreCandidates = {
                        toolbarFeedback()
                        candidatePageOpening++
                        viewModel.setCandidatePageExpanded(true)
                        // 拉取跨页全量候选（展开页数据源，含首次展开）
                        if (candidateBarState !is CandidateBarState.AssociationOnly) callbacks.onRequestExpandedCandidates?.invoke()
                    },
                    onInputTextClick = {
                        if (candidateState.value.inputText.isNotEmpty()) {
                            callbacks.onClipboardSelect?.invoke(candidateState.value.inputText)
                        }
                    },
                    onAssociationSelect = select@{ index ->
                        if (englishGlide != null || liveGlide != null) return@select
                        if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            selectHandwriting(index)
                        } else {
                            callbacks.onAssociationSelect?.invoke(index)
                            // 展开着点栏里的联想和点展开页里的一样：上屏后收起。
                            if (candidatePageExpanded) viewModel.setCandidatePageExpanded(false)
                        }
                    },
                ),
                inlineSuggestions = if (barPage is KeyboardPage.Overlay) emptyList<Any>() else inlineSuggestions,
                showPreeditPreview = preeditEditSession == null,
                inlinePreeditEditor = drawPreeditEditor,
                onEditPreedit = if (liveGlide == null && englishGlide == null && glideCandidatePreview.value.isEmpty() && !state.isAsciiMode && !isHandwritingPage &&
                    com.kingzcheung.xime.rime.PinyinEditBuffer.supports(state.currentSchemaId) &&
                    callbacks.onOpenPreeditEditor != null) ({
                    openPreeditEditor()
                }) else null,
            )
            }
            }
            }
            // 量候选栏的顶边：不占地方的标记，位置就是上面各栏加起来的高度。
            Spacer(Modifier.onPlaced { aboveCandidateBarPx.intValue = it.positionInParent().y.roundToInt() })
            // Opening a tool must not insert a row and shrink the underlying handwriting canvas.
            if (!state.handwritingExpanded || candidatePageExpanded) renderCandidateBar(page)

            val renderExpandedCandidates: @Composable () -> Unit = {
                val cs = candidateState.value
                // 候选展开页：候选栏的在位展开态（顶部即真实候选栏，实时跟随编码/删除变化）。
                // 数据源为服务层的跨页全量候选（expandedCandidates），行分组惰性渲染
                // （LazyColumn 只画可见行），可上下滑动 + 翻页键滚动一屏，不驱动 rime 翻页。
                val allExpanded = candidateState.value.expandedCandidates
                // 左栏符号复刻当前键盘左栏：九键/笔画各自的可自定义 side_symbols
                // （configVersion 参与重组，保证设置改动后刷新）；其余布局用默认快捷符号
                val configVersion by KeysConfigHelper.configVersion.collectAsStateWithLifecycle()
                val customRailSymbols = remember(configVersion, keyboardState) {
                    when (keyboardState) {
                        is KeyboardLayoutState.T9Pinyin -> KeysConfigHelper.getT9SideSymbols()
                        is KeyboardLayoutState.Stroke -> KeysConfigHelper.getStrokeSideSymbols()
                        else -> null
                    }
                }
                val isT9Layout = keyboardState is KeyboardLayoutState.T9Pinyin
                val syllableRevision = candidateState.value.engineRevision
                val canChooseSyllable = !isT9Layout && !state.isAsciiMode &&
                    state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
                    state.inputProfile.capabilities.editablePinyin && candidateState.value.isComposing
                val merged14 = state.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14
                var syllableChoices by remember(state.inputSessionId, state.currentSchemaId, merged14) {
                    mutableStateOf<com.kingzcheung.xime.rime.PinyinSyllableChoices?>(null)
                }
                var syllableRequest by remember { mutableIntStateOf(0) }
                LaunchedEffect(canChooseSyllable, syllableRevision, state.inputSessionId, state.currentSchemaId, merged14) {
                    val request = ++syllableRequest
                    if (!canChooseSyllable) syllableChoices = null
                    else callbacks.onRequestPinyinSyllables?.invoke(syllableRevision) { choices ->
                        if (request == syllableRequest && candidateState.value.engineRevision == syllableRevision)
                            syllableChoices = choices
                    }
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { if (request == syllableRequest) syllableRequest++ }
                }
                val railPinyinOptions =
                    if (isT9Layout) t9Controller.firstOptions.map { if (it.near) "≈" + it.pinyin else it.pinyin }
                    else syllableChoices?.labels.orEmpty()
                val railSelectedPinyinIndex =
                    if (isT9Layout && t9Controller.leftPanelState ==
                        T9InputController.LeftPanelState.SELECTION
                    ) t9Controller.firstOptions.indexOf(t9Controller.selectedOption)
                    else if (!isT9Layout) syllableChoices?.selectedIndex ?: -1 else -1
                // 仅过滤并保留引擎索引；换行在候选区测得实际可用宽度后进行。
                val glossExpanded = !state.isAsciiMode && state.inputProfile.language == InputLanguage.CHINESE
                val expandedGlossRevision = CandidateGlossary.revision
                val currentEntries = remember(allExpanded, singleCharFilter, visibleBarCandidates, glossExpanded, expandedGlossRevision) {
                    ExpandedCandidatePager.filterIndices(allExpanded, singleCharFilter).map { gi ->
                        val comment = allExpanded[gi].comment
                        CandidateEntry(allExpanded[gi].text,
                            if (glossExpanded) CandidateGlossary.annotate(allExpanded[gi].text, comment) else comment, gi)
                    }.let { entries ->
                        // Exclude only the exact rendered prefix; preserve native selection indices.
                        remainingCandidates(entries, visibleBarCandidates)
                    }
                }
                var lastExpandedFrame by remember(candidatePageOpening, state.inputSessionId, singleCharFilter) {
                    mutableStateOf(0L to emptyList<CandidateEntry>())
                }
                val expandedReady = cs.expandedCandidatesLoaded && visibleBarUsesExpanded &&
                    visibleBarCandidateRevision == cs.engineRevision
                SideEffect {
                    if (expandedReady) lastExpandedFrame = cs.engineRevision to currentEntries
                }
                // Keep the previous frame until both sides have measured the new revision.
                val expandedEntries = if (!expandedReady && cs.candidates.isNotEmpty()) lastExpandedFrame.second else currentEntries
                val displayedExpandedRevision = if (!expandedReady && cs.candidates.isNotEmpty()) lastExpandedFrame.first else cs.engineRevision
                val candidateQwerty = remember(configVersion, keyboardState, state.inputProfile,
                    inputPreferences.splitKeyboardEnabled, underlyingPage) {
                    val ascii = keyboardState is KeyboardLayoutState.English
                    val usesQwerty = underlyingPage == KeyboardPage.Main(MainType.FULL) &&
                        (ascii || keyboardState is KeyboardLayoutState.Chinese &&
                            state.inputProfile.layout.kind in setOf(
                                com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC,
                                com.kingzcheung.xime.settings.LayoutKind.MERGED))
                    if (!usesQwerty) null else {
                        val custom = KeysConfigHelper.letterLayout(state.currentSchemaId, ascii)
                        candidateQwertyGeometry(custom?.typingRows() ?: KeysConfigHelper.getKeyRows(state.currentSchemaId, ascii),
                            fourteenKey = !ascii && state.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14,
                            custom = custom != null,
                            split = inputPreferences.splitKeyboardEnabled && supportsSplitKeyboard(state.currentSchemaId, ascii))
                    }
                }
                val barAssociationCount = visibleBarAssociations.size
                CandidatePage(
                    state = CandidatePageState(
                        candidates = expandedEntries,
                        engineRevision = displayedExpandedRevision,
                        associationCandidates = cs.associationCandidates.drop(barAssociationCount),
                        backgroundColor = keyboardBgColor,
                        textColor = candidateTextColor,
                        keyBackgroundColor = keyBgColor,
                        bottomPaddingDp = renderedBottomPaddingDp,
                        singleCharFilter = singleCharFilter,
                        railSymbols = if (canChooseSyllable && merged14) emptyList() else customRailSymbols?.takeIf { it.isNotEmpty() },
                        railPinyinOptions = railPinyinOptions,
                        choosingMergedLetter = syllableChoices?.letterGroup != null,
                        railSelectedPinyinIndex = railSelectedPinyinIndex,
                        railAccentColor = accentColor,
                        enterKeyText = state.enterKeyText,
                        matchT9Geometry = isT9Layout,
                        qwertyGeometry = candidateQwerty,
                        floating = state.isFloatingMode,
                    ),
                    callbacks = CandidatePageCallbacks(
                        onCandidateSelect = { entry ->
                            if (!expandedReady || !candidateState.value.expandedCandidatesLoaded) return@CandidatePageCallbacks
                            if (!callbacks.isCandidateSnapshotCurrent(cs)) return@CandidatePageCallbacks
                            // entry.globalIndex 即跨页全局索引（引擎侧 select_candidate）
                            callbacks.onGlobalCandidateSelect?.invoke(entry.globalIndex)
                        },
                        onCandidateLongPress = { entry ->
                            if (!expandedReady || !candidateState.value.expandedCandidatesLoaded) return@CandidatePageCallbacks
                            // 长按删除自造词：与候选栏共用确认弹窗
                            val word = cs.expandedCandidates
                                .getOrNull(entry.globalIndex)?.text
                            if (!word.isNullOrEmpty()) {
                                onHapticFeedback?.invoke()
                                val parts = sentenceParts(callbacks, cs, word, first = entry.globalIndex == 0)
                                deletePending = DeletePendingWord(word, pin = pinChoice(callbacks, cs, word), parts = parts) {
                                    if (!callbacks.isCandidateSnapshotCurrent(cs)) return@DeletePendingWord
                                    callbacks.onGlobalCandidateDelete?.invoke(entry.globalIndex)
                                }
                            }
                        },
                        onToggleSingleCharFilter = {
                            onHapticFeedback?.invoke()
                            viewModel.toggleSingleCharFilter()
                            if (viewModel.singleCharFilter.value) callbacks.onSingleCharFilterOn?.invoke()
                        }.takeIf { cs.isComposing || cs.candidates.isNotEmpty() },
                        onAssociationSelect = select@{ index ->
                            val sourceIndex = index + barAssociationCount
                            val word = cs.associationCandidates.getOrNull(sourceIndex) ?: return@select
                            val current = candidateState.value
                            if (cs.pendingEnglishText != current.pendingEnglishText ||
                                current.associationCandidates.getOrNull(sourceIndex) != word) return@select
                            callbacks.onAssociationSelect?.invoke(sourceIndex)
                            viewModel.setCandidatePageExpanded(false)
                        },
                        onRailPinyinSelect = { index ->
                            onHapticFeedback?.invoke()
                            if (isT9Layout) {
                                // 与九键键盘左栏同语义：切换音节选项，引擎重组后
                                // 服务层重拉全量候选，展开页内容随之刷新
                                t9Controller.firstOptions.getOrNull(index)?.let {
                                    t9Controller.onChoiceSelected(it)
                                }
                            } else {
                                val snapshot = syllableChoices
                                if (snapshot != null && snapshot.engineRevision == candidateState.value.engineRevision) {
                                    val request = syllableRequest
                                    callbacks.onSelectPinyinSyllable?.invoke(snapshot, index) { updated ->
                                        if (request == syllableRequest) syllableChoices = updated
                                    }
                                }
                            }
                        },
                        onCommitText = { text ->
                            onHapticFeedback?.invoke()
                            if (customRailSymbols != null) {
                                // 九键/笔画左栏符号：与键盘本体左栏同路径（onKeyPress，
                                // 由 rime punctuator 决定顶屏/上屏行为）
                                callbacks.onKeyPress(text, false)
                            } else {
                                callbacks.onCommitText?.invoke(text)
                            }
                        },
                        onDelete = {
                            if (isT9Layout) {
                                // 与九键键盘本体退格键（T9KeyboardLayout.handleDelete）
                                // 完全同路径：T9 撤销模型（删未分配数字/撤销左栏选择/
                                // 撤销上屏），buffer→rime 经 FlushRimeInput 同步；
                                // 引擎不消费时才转服务层普通删除。此前直接走
                                // onKeyPress("delete") 缺 flush，T9 buffer 已回退但
                                // rime composition 不刷新，表现为连按多次才删掉
                                t9Controller.onDeleted { result ->
                                    when (result) {
                                        T9InputController.DeleteResult.UNDO_COMMIT ->
                                            t9Controller.clearRimeAndResend()
                                        T9InputController.DeleteResult.NOT_CONSUMED ->
                                            callbacks.onKeyPress("delete", false)
                                        else -> {}
                                    }
                                }
                            } else {
                                // 与九键左栏同一规则：选过拼音时，删除键先撤销最后选的那个拼音，没有可撤销的才删字。
                                val snapshot = syllableChoices
                                val undo = callbacks.onUndoPinyinSyllable
                                if (snapshot != null && snapshot.chosen > 0 && undo != null &&
                                    snapshot.engineRevision == candidateState.value.engineRevision) {
                                    val request = syllableRequest
                                    undo(snapshot, { callbacks.onKeyPress("delete", false) }) { updated ->
                                        if (request == syllableRequest) syllableChoices = updated
                                    }
                                } else {
                                    callbacks.onKeyPress("delete", false)
                                }
                            }
                        },
                        onClear = { callbacks.onKeyPress("clear_all", false) },
                        onEnter = {
                            callbacks.onKeyPress("enter", false)
                        },
                        onKeyPressDown = callbacks.onKeyPressDown,
                        onKeyRelease = callbacks.onKeyRelease,
                        onClearComposition = { callbacks.onKeyPress("clear_composition", false) },
                        onUndoClear = { callbacks.onKeyPress("undo_clear", false) },
                    ),
                    pageScrollEvents = viewModel.expandedPageScrollEvents,
                    onHapticFeedback = onHapticFeedback,
                    modifier = panelMaterial.fillMaxSize()
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().hiddenDuringCursorControl(cursorKeepsBar).testTag("keyboard-underlay")) {
            val clipboardPanel = LocalClipboardPanelExpansion.current
            val stableUnderlayState = if (clipboardPanel != null) {
                val normal = state.copy(keyboardHeightDp = clipboardPanel.collapsedHeight)
                remember(normal) { normal }
            } else state
            // 换布局（26/14/9、全键/手写/语音）或进出数字、常用符号页时新键盘快速淡入；不吞连点。
            // 中英切换不算换布局：直接换字，不淡入（深色主题下淡入看起来像黑了一下）。
            // 符号页收起（直接换页，没有揭幕）回到键盘时，键盘也同样淡入。
            val symbolPageShown = (page as? KeyboardPage.Overlay)?.route is OverlayRoute.Symbol
            val underlaySwapKey = when (underlyingPage) {
                is KeyboardPage.Main -> listOf(underlyingPage.type, state.inputProfile.layout, symbolPageShown)
                is KeyboardPage.Panel -> listOf(underlyingPage.type, symbolPageShown)
                else -> null
            }
            Column(Modifier.clipboardUnderlaySize(clipboardPanel, state.keyboardHeightDp).fillMaxSize()
                .cySwapFadeIn(underlaySwapKey, instantKey = listOf(state.isAsciiMode, state.inputProfile.language))
                .excludeRevealedKeyboardPanels(panelsSeeKeyboardBackground, candidateReveal, overlayReveal).then(
                if (candidatePageExpanded || overlayVisible) Modifier.clearAndSetSemantics {} else Modifier
            // 全屏手写开着工具面板时书写区仍露在面板上方，照旧不动它。
            ).blockCoveredKeyboardTouches((candidatePageExpanded || overlayVisible) && !state.handwritingExpanded)) {
            val isMainKeyboard = underlyingPage is KeyboardPage.Main
            if (isMainKeyboard) {
                val mainType = (underlyingPage as KeyboardPage.Main).type
                when (mainType) {
                    MainType.FULL -> {
                        val suppressCursorMove = remember { mutableStateOf(false) }
                        val cursorMod = keyboardSwipeCursor(callbacks.onCursorMove, inputPreferences, suppressCursorMove)

                        val fullScreenOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "shift" -> viewModel.toggleShift()
                                "shift_single" -> viewModel.singleTapShift()
                                "shift_caps" -> viewModel.doubleTapShift()
                                // 拼音留在候选栏，点符号时连同字母原样上屏（和九键一致）。
                                "symbol", "mode_change", "mode_change_symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "number", "mode_change_number" -> {
                                    callbacks.onCommitTypedBeforeModeChange?.invoke()
                                    viewModel.enterPanel(PanelType.NUMBER)
                                }
                                // Keep legacy configuration commands compatible; default keys use the direct symbol route.
                                "mode_change_common_symbol" -> {
                                    callbacks.onCommitTypedBeforeModeChange?.invoke()
                                    viewModel.enterPanel(PanelType.COMMON_SYMBOL)
                                }
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> {
                                    // 连续触摸可能早于下一次重组，提交时读取真实 Shift 状态。
                                    val shift = viewModel.shiftMode.value
                                    callbacks.onKeyPress(shift.applyToKey(key), shift.isShifted)
                                    if (shift == com.kingzcheung.xime.viewmodel.ShiftMode.HELD ||
                                        !com.kingzcheung.xime.service.JapaneseTyping.usesKanaCase(state.currentSchemaId, state.isAsciiMode)) {
                                        viewModel.onCharacterTyped()
                                    }
                                }
                            }
                        }
                        val numberOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.returnToTextKeyboard()
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val symbolOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.returnToTextKeyboard()
                                "?123" -> {
                                    callbacks.onCommitTypedBeforeModeChange?.invoke()
                                    viewModel.enterPanel(PanelType.NUMBER)
                                    callbacks.onKeyPress("clear_composition", false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val commonSymbolOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.returnToTextKeyboard()
                                "number" -> {
                                    viewModel.enterPanel(PanelType.NUMBER)
                                }
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val strokeOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToFull, state.isAsciiMode
                                ))
                                "number" -> viewModel.enterPanel(PanelType.NUMBER)
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val t9OnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.setKeyboardState(
                                    initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                                )
                                "number" -> {
                                    callbacks.onCommitTypedBeforeModeChange?.invoke()
                                    viewModel.enterPanel(PanelType.NUMBER)
                                }
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                "ime_switch" -> {
                                    callbacks.onCommitTypedBeforeModeChange?.invoke()
                                    callbacks.onKeyPress(key, false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val currentOnKeyPress = when (keyboardState) {
                            is KeyboardLayoutState.Chinese,
                            is KeyboardLayoutState.English -> fullScreenOnKeyPress
                            is KeyboardLayoutState.Number -> numberOnKeyPress
                            is KeyboardLayoutState.CommonSymbol -> commonSymbolOnKeyPress
                            is KeyboardLayoutState.Stroke -> strokeOnKeyPress
                            is KeyboardLayoutState.T9Pinyin -> t9OnKeyPress
                            is KeyboardLayoutState.Symbol -> symbolOnKeyPress
                        }
                        CompositionLocalProvider(
                            LocalSuppressCursorMove provides suppressCursorMove,
                        ) {
                            KeyboardLayoutScreen(
                                keyboardState = keyboardState,
                                uiState = stableUnderlayState,
                                candidateState = candidateState,
                                viewModel = viewModel,
                                callbacks = if (previewLetterLayout) remember { KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}) }
                                    else callbacks,
                                onKeyPress = currentOnKeyPress,
                                modifier = Modifier.weight(1f).then(cursorMod),
                                t9Controller = t9Controller,
                            )
                            BottomLiftSpacer(renderedBottomPaddingDp, bottomLiftCursorOn, callbacks.onCursorMove)
                        }
                    }

                    MainType.HANDWRITING -> {
                        HandwritingKeyboardLayout(
                            floating = state.isFloatingMode,
                            language = state.inputProfile.language,
                            onUnsupportedExit = { viewModel.showOverlay(OverlayRoute.SchemaList) },
                            unsupportedExitLabel = "选择输入方案",
                            expanded = state.handwritingExpanded,
                            expandedControlsWidthDp = state.handwritingControlsWidthDp,
                            expandedControlsOffsetX = state.handwritingControlsOffsetX,
                            expandedCandidateBar = { renderCandidateBar(underlyingPage) },
                            panelBackgroundColor = keyboardBgColor,
                            sessionKey = state.inputSessionId,
                            bottomPaddingDp = 0,
                            onKeyPress = { key ->
                                when (key) {
                                    "expand" -> callbacks.onHandwritingExpand?.invoke(true)
                                    "collapse" -> callbacks.onHandwritingExpand?.invoke(false)
                                    "delete" -> {
                                        if (handwritingPending.size > 0) {
                                            handwritingPending.deleteLast()
                                            refreshHandwritingCandidates()
                                        } else callbacks.onKeyPress("delete", false)
                                    }
                                    "clear_all", "clear_composition" -> {
                                        if (handwritingPending.size > 0) clearHandwriting()
                                        else callbacks.onKeyPress(key, false)
                                    }
                                    // 板上没笔迹时的删除键撤回：放回上次清空的文字；还有没选的手写候选时不动，免得插到候选前面。
                                    "undo_clear" -> if (handwritingPending.size == 0) callbacks.onKeyPress("undo_clear", false)
                                    "symbol" -> { clearHandwriting(); viewModel.showOverlay(OverlayRoute.Symbol) }
                                    "number" -> { clearHandwriting(); viewModel.enterPanel(PanelType.NUMBER) }
                                    "ime_switch" -> {
                                        // 与其他键盘同一个语言键：按设置在语言间来回切（以前写死切到英文，切过去就回不来）
                                        clearHandwriting()
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    // 有候选时空格只确认候选，不再多打一个空格；没有候选才打空格。
                                    // 英文的空格本来就是词与词的分隔：确认候选后照样打一个空格（否则整句英文一个空格都没有）
                                    "space" -> if (handwritingPending.size > 0) {
                                        confirmHandwriting()
                                        if (state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.ENGLISH) callbacks.onCommitText?.invoke(" ")
                                    } else callbacks.onCommitText?.invoke(" ")
                                    "enter" -> { confirmHandwriting(); callbacks.onKeyPress("enter", false) }
                                    else -> { confirmHandwriting(); callbacks.onCommitText?.invoke(key) }
                                }
                            },
                            onNewCharacter = { handwritingPending.newInk() },
                            onRecognition = { segments ->
                                if (segments.any { it.replacesPending }) handwritingPending.clear()
                                handwritingPending.append(segments)
                                refreshHandwritingCandidates()
                            },
                            // 边写边识别：每写完一笔候选栏就换成当前笔迹的结果，点它直接上屏并清掉笔迹
                            onPreview = { segments ->
                                handwritingPending.preview(segments)
                                refreshHandwritingCandidates()
                            },
                            onButtonFeedback = { key ->
                                callbacks.onKeyPressDown?.invoke(key)
                            },
                            clearSignal = handwritingClearSignal,
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            specialKeyTextColor = specialKeyTextColor,
                            modifier = Modifier.weight(1f),
                        )
                        BottomLiftSpacer(renderedBottomPaddingDp, bottomLiftCursorOn, callbacks.onCursorMove)
                    }

                    MainType.STROKE -> {
                        // Stroke is handled via keyboardState within FULL for now
                    }

                    MainType.VOICE -> {
                        VoiceKeyboardLayout(
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            keyboardBackgroundColor = keyboardBgColor,
                            modifier = Modifier.weight(1f),
                            isDarkTheme = state.isDarkTheme,
                            themeId = state.themeId,
                            bottomActive = state.voiceBottomActive,
                            leftActive = state.voiceLeftActive,
                            rightActive = state.voiceRightActive,
                            pluginName = state.voicePluginName,
                            recognitionState = state.voiceRecognitionState,
                            recognizedText = state.voiceRecognizedText,
                            amplitude = voiceAmplitudeState.value,
                            spectrum = voiceSpectrumState.value
                        )
                    }
                }
            }

            val isPanelKeyboard = underlyingPage is KeyboardPage.Panel
            if (isPanelKeyboard) {
                val panelType = (underlyingPage as KeyboardPage.Panel).type
                val japaneseNumberPage = !state.isAsciiMode &&
                    state.inputProfile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.KANA_KEYPAD
                val numberSpacing = kbKey.spacingFor(numberKeyboardSpacingSection(state.inputProfile, state.isAsciiMode))
                val panelSuppressCursorMove = remember { mutableStateOf(false) }
                val panelCursorMod = keyboardSwipeCursor(callbacks.onCursorMove, inputPreferences, panelSuppressCursorMove)
                CompositionLocalProvider(LocalSuppressCursorMove provides panelSuppressCursorMove) {
                when (panelType) {
                    PanelType.NUMBER -> NumberKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> viewModel.exitPanel()
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        keySpacingX = numberSpacing.first?.dp,
                        keySpacingY = numberSpacing.second?.dp,
                        isJapaneseKana = japaneseNumberPage,
                        enterKeyText = state.enterKeyText,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).fillMaxWidth().then(panelCursorMod)
                    )

                    PanelType.COMMON_SYMBOL -> CommonSymbolKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> {
                                    viewModel.exitPanel()
                                }
                                "number" -> {
                                    viewModel.enterPanel(PanelType.NUMBER)
                                }
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        isAsciiMode = state.isAsciiMode,
                        enterKeyText = state.enterKeyText,
                        initialAsciiMode = viewModel.asciiStateMachine.targetFor(
                            AsciiKeyboardContext.SYMBOL_PANEL, state.isAsciiMode
                        ) ?: state.isAsciiMode,
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        keySpacingX = kbKey.spacingFor("symbol").first?.dp,
                        keySpacingY = kbKey.spacingFor("symbol").second?.dp,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).fillMaxWidth().then(panelCursorMod)
                    )

                }
                }
                BottomLiftSpacer(renderedBottomPaddingDp, bottomLiftCursorOn, callbacks.onCursorMove)
            }
            } // Full candidate panel reveals over the complete keyboard body.
            KeyboardPanelReveal(
                candidatePageExpanded,
                Modifier.matchParentSize()
                    .excludeRevealedKeyboardPanels(panelsSeeKeyboardBackground, overlayReveal)
                    .then(if (overlayVisible) Modifier.clearAndSetSemantics {} else Modifier),
                revealState = candidateReveal,
            ) {
                CompositionLocalProvider(LocalKeyCornerRadius provides kbKey.cornerRadius.dp,
                    LocalCandidateBarScale provides candidateBarScaleState.floatValue) {
                    // The closing curtain retains its content. A quick reopen must start a new list.
                    key(candidatePageOpening, state.inputSessionId) {
                        renderExpandedCandidates()
                    }
                }
            }
            // Expanded handwriting has its own bottom candidate bar. Tool navigation floats
            // above its canvas, so opening and closing a tool leave its geometry unchanged.
            if (state.handwritingExpanded && !candidatePageExpanded && overlayVisible) {
                retainedOverlay?.let { toolbarPage ->
                    // 面板的工具栏放在平时键盘的顶边（面板回到平时大小，上面留着全屏手写区）。
                    val toolbarSlot = handwritingOverlaySlot
                    Box((if (toolbarSlot == null) Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        else Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = (toolbarSlot.heightDp - candidateBarHeightDp(candidateBarScaleState.floatValue)).coerceAtLeast(0f).dp)
                            .absoluteOffset(x = toolbarSlot.offsetXDp.dp).width(toolbarSlot.widthDp.dp))
                        .keyboardPanelBackground(keyboardBgColor)
                        .then(if (!overlayVisibility.targetState) Modifier.clearAndSetSemantics {} else Modifier)) {
                        renderCandidateBar(toolbarPage)
                        if (!overlayVisibility.targetState) {
                            Box(Modifier.matchParentSize().clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) {})
                        }
                    }
                }
            }
            }

            val configuration = LocalConfiguration.current
            val isLandscapeBottom = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        if (cursorControlActive) CursorControlOverlay(keyboardBgColor, keyTextColor, Modifier.matchParentSize())

        state.voiceNotice?.let { notice ->
            Box(Modifier.matchParentSize().then(Modifier.zIndex(21f)), contentAlignment = Alignment.TopCenter) {
                Text(notice, color = keyTextColor, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.padding(8.dp).background(keyboardBgColor.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp).testTag("voice-notice"))
            }
        }

        state.rejectedCommitText?.let { rejected ->
            Box(Modifier.matchParentSize().then(Modifier.zIndex(20f))) {
                RejectedCommitOverlay(rejected, keyboardBgColor, keyTextColor,
                    callbacks.onRetryRejectedCommit, callbacks.onCancelRejectedCommit)
            }
        }

        // A short startup (the IME service recreated, the engine still resident) must not flash
        // "正在加载词库" and a progress bar on every keyboard open. The plain panel still covers the
        // keys at once (input stays gated by InputReadiness); the words and bar follow only when
        // loading really takes a while, or right away for an error.
        var deployNoticeVisible by remember { mutableStateOf(false) }
        LaunchedEffect(state.isDeploying) {
            deployNoticeVisible = false
            if (state.isDeploying) {
                kotlinx.coroutines.delay(DEPLOY_NOTICE_DELAY_MS)
                deployNoticeVisible = true
            }
        }
        if (state.isDeploying) {
            val isError = state.deploymentMessage.contains("超时") || state.deploymentMessage.contains("失败")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 不透明的键盘底色：玻璃下面板几乎透明，挡不住下面的按键和文字。
                    .background(keyboardBgColor.copy(alpha = 0.97f))
                    .testTag("deploy-overlay")
                    .clickable {},
                contentAlignment = Alignment.Center
            ) {
                if (deployNoticeVisible || isError) Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "正在加载词库",
                        color = keyTextColor,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    if (!isError) {
                        Spacer(modifier = Modifier.height(10.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.width(180.dp).testTag("deploy-progress"),
                            color = keyTextColor, trackColor = keyTextColor.copy(alpha = 0.18f))
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(
                        text = state.deploymentMessage.ifEmpty { "正在初始化..." },
                        color = keyTextColor.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                    if (isError) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "正在自动处理，未就绪前无法输入",
                            color = keyTextColor.copy(alpha = 0.5f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "首次使用或更新后需要一点时间，完成后自动恢复键盘；期间的按键不会补输入",
                            color = keyTextColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { callbacks.onSwitchKeyboard?.invoke() }) {
                            Text("切换输入法", color = keyTextColor)
                        }
                        TextButton(onClick = { callbacks.onHideKeyboard?.invoke() }) {
                            Text("收起", color = keyTextColor)
                        }
                    }
                }
            }
        }

        // 长按候选忘掉学习记录：键盘视图内确认覆盖层（同窗口不夺焦点，
        // 避免焦点型弹窗导致 IME 被系统收起）；确认后按来源分发——
        // 候选栏走当前页索引，展开页走全局索引。
        deletePending?.let { pending ->
            ForgetLearningConfirm(pending.word, pending.message, Modifier.matchParentSize(),
                onCancel = { onHapticFeedback?.invoke(); deletePending = null },
                onConfirm = { onHapticFeedback?.invoke(); deletePending = null; pending.onConfirm() },
                pinned = pending.pin?.pinned,
                onPin = { onHapticFeedback?.invoke(); deletePending = null; pending.pin?.apply?.invoke() },
                parts = pending.parts?.words,
                onPart = { part ->
                    onHapticFeedback?.invoke(); deletePending = null
                    pending.parts?.reselect?.invoke(part)
                    // 重选这个词：收起展开页，候选栏就是这个词的其它写法。
                    viewModel.setCandidatePageExpanded(false)
                })
        }

        // A route change replaces only the panel content, never restarts the reveal host.
            KeyboardPanelReveal(
                visible = page is KeyboardPage.Overlay,
                modifier = (handwritingOverlaySlot?.let { slot ->
                    Modifier.align(Alignment.BottomCenter).absoluteOffset(x = slot.offsetXDp.dp).width(slot.widthDp.dp)
                        .height((slot.heightDp - candidateBarHeightDp(candidateBarScaleState.floatValue)).coerceAtLeast(0f).dp)
                } ?: Modifier.fillMaxSize().belowCandidateBar(aboveCandidateBarPx,
                    candidateBarHeightDp(candidateBarScaleState.floatValue).dp))
                    .testTag("keyboard-overlay"),
                visibility = overlayVisibility,
                revealState = overlayReveal,
            ) {
            // 面板里换页（菜单进剪贴板、表情和符号互换等）新页快速淡入；符号页打开没有揭幕，打开本身也淡入。
            Box(Modifier.fillMaxSize().cySwapFadeIn(retainedOverlay?.route?.let { it::class },
                fadeOnFirst = symbolPageSwap)) {
            when (val p = retainedOverlay) {
                is KeyboardPage.Overlay -> when (p.route) {
                    is OverlayRoute.Menu -> MenuBar(
                        state = MenuBarState(
                            isVisible = true,
                            isDarkTheme = state.isDarkTheme,
                            darkMode = state.darkMode,
                            backgroundColor = keyboardBgColor,
                            keyBgColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            isFloatingMode = state.isFloatingMode,
                            schemaSwitches = state.schemaSwitches,
                            isAsciiMode = state.isAsciiMode,
                        ),
                        actions = menuQuickActions,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight(),
                    )
                    is OverlayRoute.Ocr -> com.kingzcheung.xime.ocr.OcrCameraPanel(
                        active = (page as? KeyboardPage.Overlay)?.route == OverlayRoute.Ocr,
                        background = keyboardBgColor, textColor = keyTextColor, accent = accentColor,
                        onClose = { viewModel.closeOverlay(); com.kingzcheung.xime.ocr.DocumentOcrActivity.finishHost() },
                        onConfirmed = { callbacks.onReviewOcrText?.invoke(it) },
                        modifier = Modifier.fillMaxSize().padding(bottom = renderedBottomPaddingDp.dp),
                    )
                    is OverlayRoute.SchemaList -> SchemaListView(
                        availableSchemas = state.schemas,
                        schemas = com.kingzcheung.xime.settings.InputModes.inCurrentLanguage(state.schemas,
                            com.kingzcheung.xime.settings.InputModes.selectedId(state.currentSchemaId, state.isAsciiMode)),
                        currentSchemaId = com.kingzcheung.xime.settings.InputModes.selectedId(state.currentSchemaId, state.isAsciiMode),
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        keyTextColor = keyTextColor,
                        keyBgColor = keyBgColor,
                        onSelectSchema = { schemaId ->
                            callbacks.onSwitchSchema?.invoke(schemaId)
                            viewModel.closeOverlay()
                        },
                        onSelectLayout = { schemaId ->
                            (callbacks.onSwitchSchemaForLayout ?: callbacks.onSwitchSchema)?.invoke(schemaId)
                            viewModel.closeOverlay()
                        },
                        modifier = panelMaterial.fillMaxSize().padding(bottom = renderedBottomPaddingDp.dp)
                    )
                    is OverlayRoute.Clipboard -> ClipboardView(
                        clipboardItems = state.clipboardItems,
                        quickSendItems = state.quickSendItems,
                        selectedTab = p.route.tab,
                        backgroundColor = keyboardBgColor,
                        keyTextColor = keyTextColor,
                        keyBgColor = keyBgColor,
                        viewModel = viewModel,
                        onSelectItem = { text ->
                            callbacks.onClipboardRecordCommitted?.invoke()
                            callbacks.onClipboardSelect?.invoke(text)
                            // The board's "close after paste" switch (also in settings); off keeps it open.
                            if (com.kingzcheung.xime.ui.menubar.clipboardClosesAfterPaste(keyboardContext)) viewModel.leaveClipboard()
                        },
                        onSplitWords = { text, _ -> viewModel.pushOverlay(OverlayRoute.SplitWords(text)) },
                        onBack = { viewModel.leaveClipboard() },
                        onClipboardTabChange = { viewModel.pushOverlay(OverlayRoute.Clipboard(it)) },
                        bottomPaddingDp = renderedBottomPaddingDp,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight(),
                        onQuickSendAddClick = {
                            viewModel.closeOverlay()
                            callbacks.onShowQuickSendForm?.invoke()
                        },
                        onQuickSendEditItem = { id, text, code ->
                            viewModel.closeOverlay()
                            callbacks.onQuickSendEditItem?.invoke(id, text, code)
                        },
                        onPullRemote = callbacks.onClipboardPullRemote,
                        pullRemoteAvailable = state.clipboardSyncEnabled,
                        onImageSelect = { image ->
                            callbacks.onClipboardRecordCommitted?.invoke()
                            callbacks.onClipboardImageSelect?.invoke(image)
                        },
                        onImageShare = callbacks.onClipboardImageShare,
                        onSystemImagePaste = callbacks.onClipboardImageSystemPaste,
                        imagesExpanded = state.clipboardImagesExpanded,
                        onExpandImages = callbacks.onExpandClipboardImages,
                        onCollapseImages = callbacks.onCollapseClipboardImages,
                        onRecognizeDocument = { viewModel.showOverlay(OverlayRoute.Ocr) },
                        onOpenSearch = callbacks.onClipboardSearchOpen,
                        onTranslateText = callbacks.onClipboardTranslate?.takeIf { callbacks.clipboardTranslateAvailable() },
                    )
                    is OverlayRoute.ToolbarCustomize -> ToolbarCustomizeView(
                        toolbarButtons = state.toolbarButtons,
                        menuButtons = state.menuButtons,
                        // 排列页只列当前键盘工具栏／菜单里真会出现的入口，与菜单一致。
                        isShown = { quickAction(it) != null },
                        pluginButtons = state.toolbarPluginButtons,
                        keyTextColor = keyTextColor,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        keyBgColor = keyBgColor,
                        onUpdateQuickActions = callbacks.onUpdateQuickActions,
                        onDismiss = { viewModel.closeOverlay() },
                        bottomPaddingDp = renderedBottomPaddingDp,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight().then(if (onPreviewArrangePositioned == null)
                            Modifier else Modifier.onGloballyPositioned {
                                val r = it.boundsInWindow()
                                onPreviewArrangePositioned(android.graphics.Rect(r.left.toInt(), r.top.toInt(),
                                    kotlin.math.ceil(r.right).toInt(), kotlin.math.ceil(r.bottom).toInt()))
                            })
                    )
                    is OverlayRoute.Edit -> {
                        val editAction: (String) -> Unit = { action ->
                            if (action != "select_reset") callbacks.onKeyPressDown?.invoke(action)
                            when (action) {
                                "delete" -> callbacks.onKeyPress("delete", false)
                                "enter" -> callbacks.onKeyPress("enter", false)
                                else -> callbacks.onToolbarEditingAction?.invoke(action)
                            }
                        }
                        EditKeyboardLayout(
                            onAction = editAction,
                            showBackKey = false,
                            onBack = { toolbarFeedback(); viewModel.closeOverlay() },
                            backgroundColor = keyboardBgColor,
                            textColor = keyTextColor,
                            accentColor = accentColor,
                            keyBgColor = keyBgColor,
                            bottomPaddingDp = renderedBottomPaddingDp,
                            keyCornerRadius = kbKey.cornerRadius.dp,
                            shadowEnabled = kbShadow.enabled,
                            shadowElevation = kbShadow.elevation.dp,
                            shadowShapeRadius = kbShadow.shapeRadius.dp,
                            modifier = panelMaterial.fillMaxWidth().fillMaxHeight(),
                            enterKeyText = state.enterKeyText,
                        )
                    }
                    is OverlayRoute.Emoji -> EmojiKeyboardLayout(
                        onDeleteFace = callbacks.onDeleteKaomoji,
                        pickedKaomoji = pickedKaomoji,
                        onEmojiSelect = { emoji ->
                            onHapticFeedback?.invoke()
                            if (emoji == "delete") {
                                callbacks.onKeyPress("delete", false)
                            } else {
                            // 顔文字和表情插件的成品文本须保留原字符，不受标点全半角影响。
                            (callbacks.onCommitExactText ?: callbacks.onCommitText)?.invoke(emoji)
                            }
                        },
                        onImageEmojiSelect = callbacks.onCommitImage,
                        onBack = { viewModel.closeOverlay() },
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        bottomPaddingDp = renderedBottomPaddingDp,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight(),
                        onHapticFeedback = onHapticFeedback,
                    )
                    is OverlayRoute.Symbol -> SymbolKeyboardLayout(
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        onSelectExact = { symbol ->
                            (callbacks.onCommitExactText ?: callbacks.onCommitText)?.invoke(symbol)
                        },
                        onSelect = { symbol ->
                            // 删除键的清空、撤回同其他键盘（11-05「部分页面的删除键功能缺失」）。
                            if (symbol == "delete" || symbol == "clear_all" || symbol == "undo_clear") {
                                onHapticFeedback?.invoke()
                                callbacks.onKeyPress(symbol, false)
                            } else {
                                callbacks.onCommitText?.invoke(symbol)
                            }
                        },
                        onBack = { viewModel.returnToTextKeyboard() },
                        onNumber = {
                            callbacks.onCommitTypedBeforeModeChange?.invoke()
                            viewModel.enterPanel(PanelType.NUMBER)
                        },
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        keyBgColor = keyBgColor,
                        bottomPaddingDp = renderedBottomPaddingDp,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight(),
                        onHapticFeedback = onHapticFeedback,
                    )
                    is OverlayRoute.SplitWords -> SplitWordsView(
                        text = p.route.text,
                        backgroundColor = keyboardBgColor,
                        viewModel = viewModel,
                        onBack = { viewModel.popOverlay() },
                        onNavigateToQuickSend = { viewModel.pushOverlay(OverlayRoute.Clipboard(1)) },
                        onConfirmText = { text -> callbacks.onCommitText?.invoke(text) },
                        bottomPaddingDp = renderedBottomPaddingDp,
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.ToolPanel -> InfoPanel(
                        title = state.toolPanelTitle,
                        nodes = state.toolPanelUiNodes ?: emptyList(),
                        items = state.toolPanelItems,
                        isLoading = state.toolPanelLoading,
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        itemBgColor = keyBgColor,
                        bottomPaddingDp = renderedBottomPaddingDp,
                        onClose = { callbacks.onToolPanelClose?.invoke() },
                        onAction = { actionId -> callbacks.onToolPanelAction?.invoke(actionId) },
                        onItemClick = { item -> callbacks.onToolPanelItemClick?.invoke(item) },
                        modifier = panelMaterial.fillMaxWidth().fillMaxHeight()
                    )
                }
                else -> {}
            }
            }
        }
        // 画在面板之后：从菜单或打开着的面板上点「无痕」时，确认卡也在最上面。
        if (incognitoConfirm) {
            KeyboardConfirmCard(
                title = "开启无痕模式？",
                message = "开启后打字不学新词、不调选词顺序，新复制的内容不会记进剪贴板；已有的剪贴板记录照常能用。再点一次「无痕」就关闭。",
                confirmLabel = "开启",
                destructive = false,
                tag = "incognito-confirm",
                modifier = Modifier.matchParentSize(),
                onCancel = { onHapticFeedback?.invoke(); incognitoConfirm = false },
                onConfirm = {
                    onHapticFeedback?.invoke()
                    incognitoConfirm = false
                    SettingsPreferences.setIncognito(quickContext, true)
                })
        }
    }
    } // FloatingKeyboardContainer: resize overlay must stay outside the floating card.
    resizeOverlay?.let { controls ->
        val splitActive = inputPreferences.splitKeyboardEnabled &&
            supportsSplitKeyboard(state.currentSchemaId, state.isAsciiMode)
        val splitLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
        val splitContext = LocalContext.current
        val splitPitchDp = rememberCalibratedKeyPitchDp()
        val splitGapSetting = splitGapPreview.value
            ?: if (splitLandscape) inputPreferences.splitGapLandscape else inputPreferences.splitGapPortrait
        // 只订阅预览矩形的宽度：纯移动时位置变、宽度不变，不让整个 KeyboardView 重算。
        val resizePreviewWidthPx by remember(sessionKey) { derivedStateOf { resizePreviewRect.value.width } }
        val splitGapFraction = SplitKeyboardGeometry.resolvedGap(splitGapSetting,
            resizePreviewWidthPx / resizeControlDensity.density,
            SplitKeyboardGeometry.gutterDp(state.isFloatingMode), splitPitchDp)
        KeyboardResizeControlsHost(
            previewRect = resizePreviewRect,
            initialRect = initialPreviewRect,
            previewPaddingDp = previewPaddingDp,
            fixedHeightRange = fixedRange,
            fixedBottomInsetDp = fixedBottomInsetDp,
            bounds = if (state.isFloatingMode) floatingViewportBounds(hostWidthPx, hostHeightPx, minBottomPx)
                else ResizeRect(0f, 0f, hostWidthPx,
                    (hostHeightPx - fixedBottomInsetDp * resizeControlDensity.density).coerceAtLeast(1f)),
            initialBottomPaddingDp = initialPadding,
            bottomKeyClearance = floatingBottomClearance,
            density = resizeControlDensity,
            aspectLimits = KeyboardAspectLimits.forLayout(state.inputProfile.layout.kind, splitActive, splitGapFraction),
            squareSnap = KeyboardSquareSnap.forLayout(state.inputProfile.layout.kind, splitActive, splitGapFraction,
                inputPreferences.wideKeys, numberRow = inputPreferences.numberRow),
            splitGapFraction = splitGapSetting,
            splitPitchDp = splitPitchDp,
            onSplitGapChange = { splitGapPreview.value = it },
            onSplitGapCommit = {
                splitGapPreview.value?.let { SettingsPreferences.setSplitGapFraction(splitContext, splitLandscape, it) }
            },
            rotation = if (state.isFloatingMode) resizeRotation else null,
            nonSplitRect = nonSplitPreviewRect,
            controls = controls,
        )
    }
}
}
}

@Composable
private fun KeyboardResizeControlsHost(
    previewRect: androidx.compose.runtime.MutableState<ResizeRect>,
    initialRect: ResizeRect,
    previewPaddingDp: androidx.compose.runtime.MutableIntState,
    fixedHeightRange: IntRange,
    fixedBottomInsetDp: Int,
    bounds: ResizeRect,
    initialBottomPaddingDp: Int,
    bottomKeyClearance: State<Float>,
    density: androidx.compose.ui.unit.Density,
    aspectLimits: KeyboardAspectLimits,
    squareSnap: KeyboardSquareSnap?,
    splitGapFraction: Float?,
    splitPitchDp: Float?,
    onSplitGapChange: (Float) -> Unit,
    onSplitGapCommit: () -> Unit,
    rotation: androidx.compose.runtime.MutableFloatState?,
    nonSplitRect: ResizeRect?,
    controls: @Composable () -> Unit,
) {
    // The controls and measured keyboard share one live rectangle.
    val session = KeyboardResizePreviewState(
        rect = previewRect.value,
        initialRect = initialRect,
        onRectChange = { previewRect.value = it },
        bounds = bounds,
        fixedHeightRange = fixedHeightRange,
        fixedBottomInsetDp = fixedBottomInsetDp,
        bottomPaddingDp = previewPaddingDp.intValue,
        floatingBottomClearanceDp = bottomKeyClearance.value,
        initialBottomPaddingDp = initialBottomPaddingDp,
        onBottomPaddingChange = { previewPaddingDp.intValue = it },
        aspectLimits = aspectLimits,
        squareSnap = squareSnap,
        splitGapFraction = splitGapFraction,
        splitPitchDp = splitPitchDp,
        onSplitGapChange = onSplitGapChange,
        onSplitGapCommit = onSplitGapCommit,
        rotation = rotation,
        nonSplitRect = nonSplitRect,
    )
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalKeyboardResizePreviewState provides session,
    ) {
        Box(Modifier.fillMaxSize()) { controls() }
    }
}

/**
 * 长按候选的卡片：忘掉学习记录；引擎候选还能固定（[pinned] 非空：false 显示「固定」，true 显示「取消固定」）。
 */
@Composable
internal fun ForgetLearningConfirm(word: String, message: String?, modifier: Modifier,
    onCancel: () -> Unit, onConfirm: () -> Unit, pinned: Boolean? = null, onPin: () -> Unit = {},
    parts: List<String>? = null, onPart: (Int) -> Unit = {}) {
    val forget = "忘掉「$word」的学习记录？词库里本来有的词会回到原来的位置，自己打出来的新词会消失。"
    KeyboardConfirmCard(
        title = if (pinned == null) "忘掉学习" else "「$word」",
        message = message ?: when (pinned) {
            null -> forget
            false -> "固定：这个编码下排第一，固定多个按先后排。\n忘掉：删掉学习记录，词库里本来有的词回到原来的位置，自己打出来的新词会消失。"
            true -> "取消固定：回到原来的位置。\n忘掉：删掉学习记录，词库里本来有的词回到原来的位置，自己打出来的新词会消失。"
        },
        confirmLabel = "忘掉",
        destructive = true,
        tag = "forget-learning-confirm",
        modifier = modifier, onCancel = onCancel, onConfirm = onConfirm,
        extraLabel = when (pinned) { null -> null; false -> "固定"; true -> "取消固定" },
        onExtra = onPin, parts = parts, onPart = onPart)
}

/**
 * 键盘内的确认卡（忘掉学习、开启无痕）。和剪贴板删除确认同一套卡片颜色：按键色卡片、主题字色，
 * 不用 Material 底（深色 iOS 上曾是白卡）。点卡片外面等于取消。
 */
@Composable
internal fun KeyboardConfirmCard(title: String, message: String, confirmLabel: String, destructive: Boolean,
    tag: String, modifier: Modifier, onCancel: () -> Unit, onConfirm: () -> Unit,
    extraLabel: String? = null, onExtra: () -> Unit = {},
    /** 长按首选整句时拆出的词；点一个只重选它。 */
    parts: List<String>? = null, onPart: (Int) -> Unit = {}) {
    val cardColors = keyboardCardColors()
    Box(
        modifier = modifier
            .background(cardColors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel
            )
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(containerColor = cardColors.card)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cardColors.text
                )
                if (!parts.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点一个词重选",
                        style = MaterialTheme.typography.bodySmall,
                        color = cardColors.text.copy(alpha = .7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    SentencePartChips(parts, cardColors.text, tag, onPart)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cardColors.text.copy(alpha = .7f)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = cardColors.text.copy(alpha = .7f))
                    }
                    if (extraLabel != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onExtra, modifier = Modifier.testTag("$tag-extra")) {
                            Text(extraLabel, color = cardColors.text)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) {
                        Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else cardColors.text)
                    }
                }
            }
        }
    }
}

/** 长按删除待确认项：词文本 + 用户确认后执行的删除动作（候选栏/展开页共用）。 */
private data class DeletePendingWord(
    val word: String,
    /** 弹窗说明；为空时用词库学习的说明。 */
    val message: String? = null,
    /** 引擎候选的固定按钮；null＝不显示（手写候选、没在组词）。 */
    val pin: PinChoice? = null,
    /** 长按首选整句时拆出的词；null＝不是整句（不显示）。 */
    val parts: SentenceParts? = null,
    val onConfirm: () -> Unit,
)

/** 长按首选整句：拆出的词，以及点了第几个词后去重选。 */
private class SentenceParts(val words: List<String>, val reselect: (Int) -> Unit)

/** 首选是引擎组出来的整句（两个词以上）时拆成词；其它候选、拿不到（引擎正忙）都不拆。 */
private fun sentenceParts(callbacks: KeyboardCallbacks, cs: com.kingzcheung.xime.service.CandidateState,
    word: String, first: Boolean): SentenceParts? {
    val reselect = callbacks.onReselectSentenceWord ?: return null
    if (!first || !cs.isComposing || cs.candidates.firstOrNull() != word) return null
    val words = callbacks.sentenceWords(word)?.takeIf { it.size >= 2 } ?: return null
    return SentenceParts(words) { target ->
        if (callbacks.isCandidateSnapshotCurrent(cs)) reselect(words, target)
    }
}

/** 整句拆出的词，一行排不下就换行；每个词一个按钮。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SentencePartChips(parts: List<String>, textColor: Color, tag: String, onPart: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        parts.forEachIndexed { index, part ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(textColor.copy(alpha = .1f))
                    .clickable { onPart(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("$tag-part-$index"),
                contentAlignment = Alignment.Center
            ) {
                Text(part, style = MaterialTheme.typography.titleMedium, color = textColor)
            }
        }
    }
}

/** 长按卡片的固定按钮：现在是否已固定，以及按下后切换。 */
private class PinChoice(val pinned: Boolean, val apply: () -> Unit)

/** 引擎候选长按时查一次固定状态；拿不到（没在组词、引擎正忙）就不显示固定按钮。 */
private fun pinChoice(callbacks: KeyboardCallbacks, cs: com.kingzcheung.xime.service.CandidateState, word: String): PinChoice? {
    val onPin = callbacks.onCandidatePin ?: return null
    if (!cs.isComposing) return null
    val pinned = callbacks.candidatePinState(word) ?: return null
    return PinChoice(pinned) {
        if (callbacks.isCandidateSnapshotCurrent(cs)) onPin(word, !pinned)
    }
}

/**
 * The clipboard above the search field, showing what the field matches as it is typed: committed
 * text, plus the word still being composed (its pinyin initials; on the 9-key keypad, its keys).
 * The composition is read here, so each key recomposes only this board, not the whole keyboard.
 */
@Composable
private fun ClipboardSearchResults(
    clipboard: com.kingzcheung.xime.clipboard.ClipboardManager,
    candidateState: State<CandidateState>,
    t9: Boolean,
    profile: com.kingzcheung.xime.settings.InputProfile,
    items: List<com.kingzcheung.xime.clipboard.ClipboardItem>,
    callbacks: KeyboardCallbacks,
    onRemoveText: (List<Long>) -> Unit,
    modifier: Modifier,
    showControls: Boolean = true,
) {
    val field by clipboard.boardQuery.collectAsStateWithLifecycle()
    val composing = candidateState.value.let { if (it.isComposing) it.shownPreedit(profile) else "" }
    val query = remember(field, composing, t9) { com.kingzcheung.xime.clipboard.ClipboardLiveQuery(field, composing, t9) }
    // The space is there from the first frame; the results rise into it with the panel motion.
    // Only the layer moves, so nothing is measured or recomposed while it runs.
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, keyboardPanelMotion(true)) }
    Box(modifier.clipToBounds()) {
    com.kingzcheung.xime.ui.menubar.ConnectedClipboardBoard(
        items, initialImages = false, expanded = false,
        onBack = {}, onQuickSend = {},
        onSelectText = { text -> callbacks.onClipboardSearchSelect?.invoke(text) },
        onRemoveText = onRemoveText, onAddQuick = {}, onSplit = { _, _ -> },
        onImageSelect = { image -> callbacks.onClipboardSearchImageSelect?.invoke(image) },
        onImageShare = { _, _ -> }, onSystemPaste = {}, onPullRemote = null,
        keepQueryOnDispose = true, liveQuery = query, showControls = showControls,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationY = (1f - reveal.value) * size.height
            alpha = reveal.value
        },
    )
    }
}

/**
 * 「键盘滑动」移光标：在键盘上横着滑过 60dp 后按格移动光标。字母键盘（含九键、笔画）和数字页、常用符号页共用这一套；
 * 以前只有字母键盘接了，切到数字页、常用符号页就滑不动（用户反馈 10-31「左右快捷横移在部分界面不可用」）。
 * [suppressCursorMove] 由页面里的语言键、退格左滑等置位，这一下手势不再当成移光标。
 */
@Composable
private fun keyboardSwipeCursor(onCursorMove: ((Int) -> Unit)?, inputPreferences: KeyboardInputPreferences,
    suppressCursorMove: androidx.compose.runtime.MutableState<Boolean>): Modifier {
    val currentOnCursorMove = rememberUpdatedState(onCursorMove)
    val cursorUnitPx = rememberCursorUnitPx()
    val cursorRootView = LocalView.current.rootView
    if (onCursorMove == null || inputPreferences.cursorGesture != CursorGestureMode.KEYBOARD) return Modifier
    return Modifier.pointerInput(inputPreferences.cursorStepDp, inputPreferences.cursorSwipeLines, cursorUnitPx) {
        val fallbackStepPx = inputPreferences.cursorStepDp * cursorUnitPx
        val activationThresholdPx = 60.dp.toPx()
        awaitEachGesture {
            suppressCursorMove.value = false
            val down = awaitFirstDown(requireUnconsumed = false)
            var isCursorGesture = false
            val cursorSteps = CursorStepAccumulator(fallbackStepPx)
            var lastX = down.position.x

            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y

                if (!change.pressed) {
                    if (isCursorGesture) {
                        event.changes.forEach { it.consume() }
                        SpaceAfterCursorGuard.mark(change.uptimeMillis)
                    }
                    break
                }
                if (suppressCursorMove.value || change.isConsumed) break
                if (abs(dx) > abs(dy) * 4f) {

                    if (!isCursorGesture && abs(dx) > activationThresholdPx) {
                        isCursorGesture = true
                        lastX = change.position.x
                        CursorLineMetrics.request()
                    }

                    if (isCursorGesture) {
                        event.changes.forEach { it.consume() }
                        cursorSteps.stepPx = lineCursorStepPx(cursorRootView.width / 2f,
                            inputPreferences.cursorSwipeLines, CursorLineMetrics.charsPerLine, fallbackStepPx, cursorUnitPx)
                        val steps = cursorSteps.move(change.position.x - lastX)
                        lastX = change.position.x
                        if (steps != 0) currentOnCursorMove.value?.invoke(steps)
                    }
                }
            } while (true)
        }
    }
}
