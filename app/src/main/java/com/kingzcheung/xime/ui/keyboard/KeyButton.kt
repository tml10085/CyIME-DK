package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.util.CharInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment

/** 按键视觉缩进（padding），用于消除 spacedBy 死区。
 *  pointerInput 在 padding 之前，触摸区=全尺寸；
 *  shadow/clip/background 在 padding 之后，视觉区=缩进后。
 *  所有布局默认同一比例；自定义 spacing_x/y 可显式覆盖。 */
val LocalKeyVisualPadding = staticCompositionLocalOf {
    PaddingValues(2.dp)
}

/** 按键圆角半径，由各布局在根层通过 CompositionLocalProvider 提供。
 *  独立于 shadow.shape_radius，为统一配置化而设。 */
val LocalKeyCornerRadius = staticCompositionLocalOf { 8.dp }

/** 按键内容随按键实际高度放大；手机尺寸下保持原字号。 */
internal fun adaptiveKeyContentScale(
    keyHeightDp: Float,
    referenceHeightDp: Float = 56f,
): Float {
    if (!keyHeightDp.isFinite() || keyHeightDp <= 0f) return 1f
    return (keyHeightDp / referenceHeightDp).coerceIn(1f, 1.5f)
}

/** 滑动提示在大按键上比主字符增长稍快，避免视觉上仍然偏小。 */
internal fun adaptiveHintScale(contentScale: Float): Float =
    (1f + (contentScale - 1f) * 1.5f).coerceIn(1f, 1.7f)

/** 气泡跟随提示放大，但略微收敛，避免在平板上显得过重。 */
internal fun adaptiveBubbleScale(contentScale: Float): Float =
    adaptiveHintScale(contentScale).coerceAtMost(1.5f)

/** 主字符放大时同步拉开上下提示，手机尺寸下保持原来的 14dp 间距。 */
internal fun adaptiveHintOffsetDp(contentScale: Float): Float =
    (14f + (contentScale - 1f) * 25f).coerceIn(14f, 24f)

data class SwipeState(
    val isSwiping: Boolean = false,
    val swipeText: String? = null,
    val isSwipeDown: Boolean = false,
    val charInfos: List<CharInfo> = emptyList(),
    val isPressed: Boolean = false,
    val pressedText: String? = null,
    val isDanger: Boolean = false,
    // 长按弹出选择
    val isLongPress: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val selectedLongPressIndex: Int = 0,
    val longPressDrawableIds: List<Int> = emptyList(),
    val keyFlick: KeyFlickPreview? = null,
)

private val shadowColorCache = HashMap<Color, Color>()

internal fun crispShadowColor(backgroundColor: Color): Color {
    return shadowColorCache.getOrPut(backgroundColor) {
        val r = backgroundColor.red
        val g = backgroundColor.green
        val b = backgroundColor.blue
        val maxChroma = maxOf(r, g, b) - minOf(r, g, b)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (maxChroma > 0.05f) {
            Color(r * 0.95f, g * 0.95f, b * 0.95f, backgroundColor.alpha)
        } else if (luminance > 0.5f) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    swipeText: String? = null,
    swipeDownText: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState) -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    /** 长按回调（含震动反馈），点按仍走 [onClick] */
    onLongClick: (() -> Unit)? = null,
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 手势由外层接管时（符号键按住当 Ctrl），按下的样子由外层给。 */
    externalPressed: Boolean = false,
) {
    var ownPressed by remember { mutableStateOf(false) }
    val isPressed = ownPressed || externalPressed
    var flickPreview by remember { mutableStateOf<KeyFlickPreview?>(null) }
    val density = LocalDensity.current
    val view = LocalView.current
    val keyFontFamily = AppFonts.keyFontFamily
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val currentActions by rememberUpdatedState(KeyGestureActions(
        text = text,
        onTap = onClick,
        onPress = { ownPressed = true; onPress?.invoke() },
        onRelease = { ownPressed = false; onRelease?.invoke() },
        onPreview = { flickPreview = it.keyFlick; onSwipeStateChange?.invoke(it) },
        inlineVerticalPreview = true,
        upText = swipeText,
        downText = swipeDownText,
        onUp = onSwipe,
        onDown = onSwipeDown,
        onLongPress = onLongClick,
        onLongPressFeedback = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) },
    ))

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
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
        BoxWithConstraints(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .pointerInput(Unit) { detectExclusiveKeyGestures { currentActions } }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadowModifier)
            .clip(keyClipShape)
            .background(frostedKeyColor(
                backgroundColor, textColor, frostedGlass,
                legacyStateColor = if (isPressed) darkenColor(backgroundColor, 0.2f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor,
                pressed = isPressed, highlighted = isHighlighted,
                opacityScale = glassFunctionKeyScale(backgroundColor),
                functionDepth = glassFunctionKeyDepth(backgroundColor),
            )).keyHeldHighlight(isPressed)),
        contentAlignment = Alignment.Center
    ) {
        val contentScale = keyContentScale(maxWidth.value, maxHeight.value)
        val hintScale = adaptiveHintScale(contentScale)
        val hintSize = 10f * hintScale
        val hintOffset = KeyboardKeyMetrics.hintOffsetDp(maxHeight.value, hintSize, density.fontScale, contentScale).dp
        val labelSize = keyLabelSizeSp(text,
            fontSize?.takeUnless { it == androidx.compose.ui.unit.TextUnit.Unspecified }?.value
                ?: KeyboardKeyMetrics.LabelSize.value,
            maxWidth.value, maxHeight.value, density.fontScale,
            LocalKeyboardInputPreferences.current.keyTextScale)
        val keyHeight = maxHeight
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - (flickPreview?.progress ?: 0f)
        }, contentAlignment = Alignment.Center) {
            Text(
                text = punctuationKeyLabel(text),
                modifier = Modifier.fillMaxWidth().offset(y = if (!swipeText.isNullOrEmpty() && keyHeight >= 40.dp) 2.dp else 0.dp),
                color = textColor,
                fontSize = labelSize.sp,
                lineHeight = (labelSize * 1.2f).sp,
                fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontFamily = keyFontFamily
            )

            if (flickPreview?.fromTop != true && !swipeText.isNullOrEmpty() && swipeText != badgeText) {
                val displayText = if (swipeText.length <= 4) swipeText else swipeText.take(4)
                Text(
                    text = punctuationKeyLabel(displayText),
                    color = keyHintColor(textColor, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                    fontSize = hintSize.sp,
                    lineHeight = (hintSize * 1.2f).sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
                    fontFamily = keyLabelFontFamily
                )
            }

            if (badgeText != null && !(flickPreview?.fromTop == true && flickPreview?.text == badgeText)) {
                Text(
                    text = badgeText,
                    color = keyHintColor(textColor, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                    fontSize = (10f * hintScale).sp,
                    lineHeight = (12f * hintScale).sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp),
                    fontFamily = keyLabelFontFamily
                )
            }
        }
        flickPreview?.let { KeyFlickLabel(it, textColor, maxWidth.value, maxHeight.value, hintSize, labelSize) }
    }
}

@Composable
fun SwipeableKeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    layoutMode: ButtonLayout = ButtonLayout.STANDARD,
    icon: Painter? = null,
    /** 主图标基准尺寸，随键帽缩放；细小的图标（如箭头）可调大。 */
    iconSizeDp: Float = KeyboardKeyMetrics.FunctionIconSize.value,
    swipeText: String? = null,
    swipeDownText: String? = null,
    /** 下滑文本显示在按键上（气泡为空，用于 display:key） */
    swipeDownKeyLabel: String? = null,
    /** 上滑文本显示在按键上（气泡则为空，用于 display:bubble） */
    swipeUpKeyLabel: String? = null,
    /** All corner-symbol keys use the same preference; dedicated icon actions are separate. */
    followSymbolSwipeDirection: Boolean = true,
    /** 角标固定上滑且长按也输入（「底排标点固定上滑」）；不跟全局「长按输入/滑动输入」走。 */
    keepSymbolSwipeOnLongPress: Boolean = false,
    /** Literal upper-hint input, independent of whether its visual hint is enabled. */
    symbolInputText: String? = null,
    /** 下角标直接上屏的那个符号（不受「下方角标提示」开关影响）；26 键滑行时放进长按弹出的两格，见 [withGlideCornerMenu]。 */
    symbolInputDownText: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    swipeLeftText: String? = null,
    /** 左滑预览用的图标（有主图标的键用，如 0 键「上一项」左箭头）；null 时显示 [swipeLeftText]。 */
    swipeLeftIcon: Painter? = null,
    /** 右滑动作（与左滑成对，如 0 键右滑「下一项」）；14 键字母对的右半字母优先。 */
    onSwipeRight: (() -> Unit)? = null,
    swipeRightText: String? = null,
    swipeRightIcon: Painter? = null,
    letterGroup: String? = null,
    onLetterSelection: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    longPressDrawableIds: List<Int>? = null,
    /** 拂动选字母：依次为左、上、右、下（缺的方向保留原手势），长按执行 [onFlickLongPress]。 */
    flickLetters: List<String>? = null,
    onFlickLetter: ((String) -> Unit)? = null,
    onFlickLongPress: (() -> Unit)? = null,
    flickThresholdDp: Float = 24f,
    /** Long-press delay for [longPressItems]; null keeps the default menu delay. */
    longPressTimeoutMillis: Long? = null,
    /** Transparent row-edge space belongs to this key's gestures, but not its keycap. */
    edgeContentPadding: PaddingValues = PaddingValues(0.dp),
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /**
     * 九键数字键按住 !@# 当 Ctrl 时代表的字母（用户 11-03，见 [com.kingzcheung.xime.keyboard.CtrlShortcuts.t9Letter]）：
     * 拖到这个键上或另一根手指点它发 Ctrl+这个字母，键面换成这个字母的用途。null 时按键自己的字母（26/14 键）。
     */
    ctrlShortcutLetter: Char? = null,
) {
    val customLayout = LocalCustomLayout.current
    val letterGeometry = LocalLetterGeometry.current
    val customAccent = LocalCustomAccent.current
    // 玻璃下键帽是按主题文字方向重新提亮/压暗的半透明材质，不是这里算出的底色；
    // 强调色文字要按玻璃键面（与普通文字同一方向的表面）取对比，否则会算成黑字压黑键。
    val glassLabelSurface = if (LocalKeyboardInputPreferences.current.frostedGlass.enabled)
        customLayoutGlassSurface(textColor) else null
    val resolvedColors = customLayoutKeyColors(customLayout, text, backgroundColor, textColor, customAccent, glassLabelSurface)
    val resolvedBackground = resolvedColors.first
    val resolvedText = resolvedColors.second
    // 九键滑行占了 2～9 键的滑动，上滑出数字、角标都滑不出来：这些键的角标改为长按输入，和 26 键滑行一样
    // （用户 11-11「开着滑行长按失效…只有九键不会跳转」：以前照存的「滑动输入」，长按什么都不出）。
    val t9GlideOwnsDrag = LocalT9GlideKeys.current != null && badgeText?.singleOrNull()?.let { it in '2'..'9' } == true
    val symbolInputMode = if (t9GlideOwnsDrag) SymbolInputMode.LONG_PRESS else LocalKeyboardInputPreferences.current.symbolInputMode
    val inputPreferences = LocalKeyboardInputPreferences.current
    val letterSwipeLayout = letterSwipeAvailable(LocalFourteenKeyLayout.current,
        customLayout?.mergedGroups?.isNotEmpty() == true)
    val letters = fourteenKeyLetters(letterGroup, letterSwipeLayout &&
        inputPreferences.fourteenLetterSwipe && onLetterSelection != null)
    var isPressed by remember { mutableStateOf(false) }
    var flickPreview by remember { mutableStateOf<KeyFlickPreview?>(null) }
    var longPressShown by remember { mutableStateOf(false) }
    // 方向圆盘只在手指拂出去或按住一会儿后出现；快速点按不弹（用户 11-03：只点不拂时轮盘让打字发卡）。
    var discRevealed by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect.Zero) }
    val touchOrigin = remember { arrayOf(Offset.Zero) }
    // 浮标按这一刻按键的位置画，见 [LiveBounds]。
    val liveBounds = remember { LiveBounds() }

    val view = LocalView.current
    val density = LocalDensity.current
    val flick = flickLetters?.takeIf { onFlickLetter != null && it.isNotEmpty() }
    // Keys whose flick directions are fixed letters (nine-key flick mode, fourteen-key pairs)
    // show the kana direction disc while held. Labels come from the live gesture mapping.
    // 14 键里落单的字母键（l、m，分体或 Shift 拖动时拆开的单字母）和双字母键用同一个方向盘，
    // 不再一个出方向盘、一个键面渐变（用户 10-21）。
    val fourteenSingle = LocalFourteenKeyLayout.current && inputPreferences.fourteenLetterSwipe &&
        text.length == 1 && text[0].lowercaseChar() in 'a'..'z'
    val discKey = inputPreferences.flickDirectionDisc && (flick != null || letters != null || fourteenSingle)
    fun keyPreview(state: SwipeState): KeyFlickPreview? {
        if (discKey && state.movedOffPress()) discRevealed = true
        return discFlickPreview(state.keyFlick, discKey)
    }
    // 按键浮标和方向盘是同一个东西（用户 11-09）：按下先出按键浮标，方向盘一出来就把浮标收掉，
    // 不两个叠着；长按到点换成角标气泡。
    fun forwardPreview(state: SwipeState) {
        val discOwnsPress = discRevealed && state.isPressed && !state.isLongPress && state.keyFlick == null
        onSwipeStateChange?.invoke(if (discOwnsPress) SwipeState() else state, liveBounds.current(buttonBounds, "key $text"))
    }
    // In flick mode the held key's own value (the digit) is a one-item long-press popup: it shows
    // what will be entered and commits only on release, like every other long-press choice.
    // 「长按输入」（关闭角标滑动）时和其他布局的角标一样：按满设定时长立刻输入，气泡显示它，松手消失（用户 11-08）。
    val flickLongPressLabel = (badgeText ?: swipeText ?: text).takeIf { onFlickLongPress != null }
    val flickLongPressNow = flickLongPressLabel != null && symbolInputMode == SymbolInputMode.LONG_PRESS
    fun flickAt(index: Int): String? = flick?.getOrNull(index)
    fun flickAction(index: Int): (() -> Unit)? = flickAt(index)?.let { letter -> { onFlickLetter?.invoke(letter); Unit } }
    // 按住符号键当 Ctrl 时，另一根手指点字母发 Ctrl+字母，不打字（用户 10-24）。
    val ctrlTargets = LocalShiftSlideTargets.current
    // 14 键快捷键键面出来前双字母键还没拆开：按点在左半还是右半决定是哪个字母，和拆开后的位置一样（用户 10-31）。
    val ctrlTapFraction = remember { floatArrayOf(0.5f) }
    val fourteenKeyLayout = LocalFourteenKeyLayout.current
    val tapOrCtrl: () -> Unit = {
        val letters = letterGroup ?: text
        val pick = ctrlShortcutLetter?.toString()
            ?: if (fourteenKeyLayout && letters.length == 2 && ctrlTargets?.ctrl == true)
            letters[if (ctrlTapFraction[0] < 0.5f) 0 else 1].toString() else letters
        if (ctrlTargets?.ctrlTap(pick) != true) onClick()
    }
    val letterGlideOwnsDrag = LocalLetterGlide.current && letterGroup?.singleOrNull()?.lowercaseChar()?.let { it in 'a'..'z' } == true
    val reserveDragForGlide = letterGlideOwnsDrag || t9GlideOwnsDrag
    // 九键滑行接管拖动的数字键往下滑打不出下角标：左下角不画（用户 11-11）。26 键字母滑行改成长按弹出上下两个角标
    // （[withGlideCornerMenu]），下角标打得到，照旧画（用户 11-11「关闭数字行滑行打不出 !@#」）。
    val downCornerLabel = swipeDownKeyLabel.takeUnless { reserveDragForGlide && !letterGlideOwnsDrag }
    val gestureActions = if (flick != null) KeyGestureActions(
        // 拂动：方向固定对应字母，不随角标方向反转；数字等改为长按。
        text = text,
        onTap = tapOrCtrl,
        onPress = { isPressed = true; discRevealed = false; onPress?.invoke() },
        onRelease = { isPressed = false; onRelease?.invoke() },
        onPreview = { flickPreview = keyPreview(it); longPressShown = it.isLongPress; forwardPreview(it) },
        inlineVerticalPreview = true,
        inlineHorizontalPreview = true,
        leftText = flickAt(0), onLeft = flickAction(0),
        upText = flickAt(1), onUp = flickAction(1)?.let { action -> { _: String -> action() } },
        rightText = flickAt(2), onRight = flickAction(2),
        downText = flickAt(3) ?: swipeDownText,
        downPreviewText = flickAt(3) ?: swipeDownKeyLabel?.takeIf { it.isNotEmpty() } ?: swipeDownText,
        onDown = flickAction(3)?.let { action -> { _: String -> action() } } ?: onSwipeDown,
        horizontalThresholdDp = flickThresholdDp,
        verticalThresholdDp = flickThresholdDp,
        reserveHorizontalSwipe = true,
        freeDirection = true,
        keyWidth = buttonBounds.width,
        longPressItems = if (flickLongPressNow) emptyList() else listOfNotNull(flickLongPressLabel),
        onLongPressSelect = if (flickLongPressNow) null else { _ -> onFlickLongPress?.invoke() },
        onLongPress = if (flickLongPressNow) { { onFlickLongPress?.invoke() } } else null,
        longPressBubbleText = flickLongPressLabel?.takeIf { flickLongPressNow },
        longPressTimeoutMillis = if (flickLongPressNow) inputPreferences.symbolLongPressMs else 300L,
        onLongPressFeedback = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) },
    ) else KeyGestureActions(
        text = text,
        onTap = tapOrCtrl,
        onPress = { isPressed = true; discRevealed = false; onPress?.invoke() },
        onRelease = { isPressed = false; onRelease?.invoke() },
        // 长按到点后（角标已输入或弹出菜单）方向盘收起，和拂动键一样只留长按气泡。
        onPreview = { flickPreview = keyPreview(it); longPressShown = it.isLongPress; forwardPreview(it) },
        inlineVerticalPreview = true,
        upText = swipeText,
        downText = swipeDownText,
        upPreviewText = swipeUpKeyLabel?.takeIf { it.isNotEmpty() } ?: swipeText,
        downPreviewText = swipeDownKeyLabel?.takeIf { it.isNotEmpty() } ?: swipeDownText,
        onUp = onSwipe,
        onDown = onSwipeDown,
        longPressItems = longPressItems.orEmpty(),
        onLeft = letters?.let { { onLetterSelection?.invoke(it.first); Unit } } ?: onSwipeLeft,
        // 「字母键显示大写」只改显示：方向提示跟键帽一样大写，送出的仍是 letters 原样。
        leftText = letters?.first?.let { if (inputPreferences.uppercaseKeyLabels) it.uppercase() else it } ?: swipeLeftText,
        onRight = letters?.let { { onLetterSelection?.invoke(it.second); Unit } } ?: onSwipeRight,
        rightText = letters?.second?.let { if (inputPreferences.uppercaseKeyLabels) it.uppercase() else it } ?: swipeRightText,
        horizontalThresholdDp = if (letters != null) normalizeLetterSwipeDistance(inputPreferences.fourteenLetterSwipeDp) else 50f,
        reserveHorizontalSwipe = letters != null,
        // With the four-way disc shown, 14-key pairs re-aim freely like kana and nine-key flicks;
        // the in-key mode keeps the 26-key axis lock.
        freeDirection = letters != null && inputPreferences.flickDirectionDisc,
        // 横滑动作（如九键 0 键左滑「上一项」）和上下滑一样在键内渐变，不再弹旧的滑动浮窗（用户 10-24）。
        inlineHorizontalPreview = true,
        longPressDrawableIds = longPressDrawableIds.orEmpty(),
        keyWidth = buttonBounds.width,
        onLongPressSelect = onLongPressSelect,
        longPressTimeoutMillis = longPressTimeoutMillis,
        onLongPressFeedback = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) },
    ).let { actions ->
        // 26 键滑行开着时字母键的滑动给了滑行：长按弹出上下两个角标，挪过去松手选（设置页锁在「长按输入」）。
        if (letterGlideOwnsDrag) actions.withGlideCornerMenu(symbolInputText, symbolInputDownText, inputPreferences.symbolLongPressMs)
        // 底排标点固定上滑（空格两侧标点、数字键盘点键）：上滑和长按都输入角标（用户 11-09：键在底边，长按更顺手）。
        else actions.withSymbolInput(if (keepSymbolSwipeOnLongPress) SymbolInputMode.LONG_PRESS else symbolInputMode,
            symbolInputText, keepSymbolSwipeOnLongPress, inputPreferences.symbolLongPressMs)
    }.let {
        if (followSymbolSwipeDirection) it.withSymbolSwipeDirection(inputPreferences.reverseSymbolSwipe) else it
    }
    val inputActions = LocalKeyboardInputActions.current
    val bindings = if (inputActions.englishLetters) inputPreferences.englishLetterSwipes else inputPreferences.chineseLetterSwipes
    // 跨键交接只给 26 键的单字母键（含 æøå）：九键拂动、14 键和自建合并键要到松手才知道是哪个字母，不参与。
    val keyRollover = LocalKeyRollover.current?.takeIf {
        flick == null && letters == null && !letterSwipeLayout && letterGroup?.singleOrNull()?.isLetter() == true
    }
    val currentActions by rememberUpdatedState((if (bindings.isEmpty() || letterSwipeLayout ||
        flick != null || letterGroup?.length != 1 || inputActions.onLetterSwipeAction == null) gestureActions
        else gestureActions.withLetterSwipes(letterGroup.lowercase(), bindings, inputActions.onLetterSwipeAction))
        .let { if (keyRollover != null) it.copy(rollover = keyRollover) else it })

    // 同一组文字只建一次：手指移动时圆盘不因标签“换了个对象”而整个重画。
    val discLabels = if (flick == null && letters == null && !fourteenSingle) null else gestureActions.let { actions ->
        val left = actions.leftText
        val up = actions.upPreviewText ?: actions.upText
        val right = actions.rightText
        val down = actions.downPreviewText ?: actions.downText
        remember(text, left, up, right, down) {
            buildMap {
                put(KanaFlickDirection.TAP, text)
                left?.let { put(KanaFlickDirection.LEFT, it) }
                up?.let { put(KanaFlickDirection.UP, it) }
                right?.let { put(KanaFlickDirection.RIGHT, it) }
                down?.let { put(KanaFlickDirection.DOWN, it) }
            }.filterValues { it.isNotEmpty() }
        }
    }
    val discDirection = flickPreview?.takeIf { it.progress >= 1f }?.let { preview ->
        when (preview.origin) {
            KeyFlickOrigin.LEFT -> KanaFlickDirection.LEFT
            KeyFlickOrigin.RIGHT -> KanaFlickDirection.RIGHT
            KeyFlickOrigin.TOP, KeyFlickOrigin.TOP_END ->
                if (discLabels?.get(KanaFlickDirection.DOWN) == preview.text &&
                    discLabels[KanaFlickDirection.UP] != preview.text) KanaFlickDirection.DOWN else KanaFlickDirection.UP
            KeyFlickOrigin.BOTTOM, KeyFlickOrigin.BOTTOM_START ->
                if (discLabels?.get(KanaFlickDirection.UP) == preview.text &&
                    discLabels[KanaFlickDirection.DOWN] != preview.text) KanaFlickDirection.UP else KanaFlickDirection.DOWN
        }
    } ?: KanaFlickDirection.TAP
    // 「长按输入」时按住就是要长按出角标：方向盘不再按住 180 毫秒就弹，免得按下去先冒出方向盘再换成角标气泡；
    // 拂出去时照样出方向盘（用户 11-09：按下时不出气泡，到点输入时才出，松手消失）。
    val holdCommitsSymbol = gestureActions.longPressBubbleText != null
    if (discKey && !holdCommitsSymbol) LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(FLICK_DISC_HOLD_MS)
            discRevealed = true
            if (!longPressShown) onSwipeStateChange?.invoke(SwipeState(), liveBounds.current(buttonBounds, "key $text"))
        }
    }
    val showDisc = inputPreferences.flickDirectionDisc && isPressed && discRevealed && !longPressShown &&
        discLabels != null && discLabels.size > 1
    val flickFade = rememberKeyFlickFade(flickPreview)

    val shiftTargets = LocalShiftSlideTargets.current
    val dragSpace = LocalKeyDragSpace.current
    var dragBounds by remember { mutableStateOf(Rect.Zero) }
    val shiftToken = remember { Any() }
    val maxShiftLetters = if (LocalFourteenKeyLayout.current) 2 else 1
    val shiftLetters = (letterGroup ?: text).takeIf { group ->
        group.length in 1..maxShiftLetters && group.all { it.lowercaseChar() in 'a'..'z' }
    }
    val currentLetterSelection by rememberUpdatedState(onLetterSelection)
    androidx.compose.runtime.DisposableEffect(shiftTargets, shiftToken) {
        onDispose { shiftTargets?.remove(shiftToken) }
    }
    androidx.compose.runtime.SideEffect {
        if (ctrlShortcutLetter != null) shiftTargets?.putCtrl(shiftToken, ctrlShortcutLetter, dragBounds)
        else if (shiftLetters != null) shiftTargets?.put(shiftToken, shiftLetters, dragBounds) { uppercase ->
            currentActions.onPress()
            try { currentLetterSelection?.invoke(uppercase) ?: currentActions.onTap() }
            finally { currentActions.onRelease() }
        } else shiftTargets?.remove(shiftToken)
    }
    val shiftHovered = (shiftLetters != null || ctrlShortcutLetter != null) && shiftTargets?.hovered?.bounds?.center?.let(dragBounds::contains) == true
    // 按住符号键当 Ctrl：单字母键换成快捷键键面（用户 10-24）；按满时限才换，渐变出来，松手立刻换回（用户 10-31）。
    // 九键数字键换成它代表的字母的用途，左上角写数字（用户 11-03）。
    val ctrlLetter = (ctrlShortcutLetter ?: shiftLetters?.singleOrNull())?.takeIf { shiftTargets?.ctrlFaces == true }
    // 从 0 起渐变：14 键这时才拆成单字母键，新键也要渐变出来，不能一出现就是快捷键键面。
    val ctrlFade = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(ctrlLetter != null) {
        if (ctrlLetter != null) ctrlFade.animateTo(1f, androidx.compose.animation.core.tween(CTRL_FACE_FADE_MS))
        else ctrlFade.snapTo(0f)
    }
    // 双拼键位提示：这个键代表的翘舌声母和韵母（只在开着开关的双拼 26 键有）。开着时所有字母键都不画上下角标
    // （没有提示的 a、e 也一样），滑动照常。
    val doublePinyinHints = LocalDoublePinyinKeyHints.current
    val doublePinyinHint = doublePinyinHints?.forKey(letterGroup ?: text)
    val dpHidesCorners = doublePinyinHints?.hidesCornerLabels(letterGroup ?: text) == true
    // 开着上方或下方角标提示时，角标不藏，和键位提示分层排开，由 DoublePinyinCornerFace 一起画（用户 11-01 去掉了
    // 「和角标一起显示」开关；以前打开过它的照旧分层）。紧凑键面不支持，照旧藏角标。
    val dpWithCorners = dpHidesCorners && layoutMode != ButtonLayout.COMPACT &&
        LocalKeyboardInputPreferences.current.let { it.doublePinyinKeyHintsWithCorners || it.swipeUpHints || it.swipeDownHints }

    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val hexagonal = LocalHoneycombKeys.current && (letterGroup ?: text).let { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, resolvedBackground, frostedGlass.enabled, hexagonal) {
        if (shadowEnabled && !frostedGlass.enabled && !hexagonal) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(resolvedBackground)
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
    val keyClipShape = remember(keyCornerRadius, hexagonal) { if (hexagonal) HexagonShape else RoundedCornerShape(keyCornerRadius) }
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val keyFontFamily = AppFonts.keyFontFamily

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .then(if (shiftLetters?.length == 2) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    ctrlTapFraction[0] = if (size.width > 0) down.position.x / size.width else 0.5f
                }
            } else Modifier)
            .then(if (com.kingzcheung.xime.stats.TouchLog.enabled) Modifier.onGloballyPositioned {
                touchOrigin[0] = it.boundsInRoot().topLeft
            }.pointerInput(text) {
                // 触点记录（调试，默认关）：只看不消费，见 TouchLog。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var last = down.position
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    val origin = touchOrigin[0]
                    com.kingzcheung.xime.stats.TouchLog.key(text, origin.x, origin.y, size.width.toFloat(), size.height.toFloat(),
                        origin.x + down.position.x, origin.y + down.position.y, origin.x + last.x, origin.y + last.y,
                        android.os.SystemClock.uptimeMillis() - down.uptimeMillis)
                }
            } else Modifier)
            .pointerInput(reserveDragForGlide, letterGlideOwnsDrag) {
                // 滑行没接管的拖动抬手仍算点按（用户 11-10：快打一句吞一半的键）。
                detectExclusiveKeyGestures(reserveDragForGlide, letterGlide = letterGlideOwnsDrag) { currentActions }
            }
            .padding(edgeContentPadding)
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
                liveBounds.coordinates = coordinates
                if (shiftTargets != null) dragBounds = if (dragSpace.card == null) buttonBounds else dragSpace.bounds(coordinates)
                // 滑行的键位与轨迹同在键盘卡片坐标里：悬浮键盘旋转后 boundsInRoot 是斜放键的外接框，滑行会落到别的键。
                letterGeometry?.place(text, if (dragSpace.card == null) buttonBounds else dragSpace.bounds(coordinates))
            }
            .padding(scaledKeyVisualPadding())
            .then(if (hexagonal) Modifier.honeycombCap() else Modifier)
            .keyGlow(Modifier.then(shadowModifier)
            .clip(keyClipShape)
            .background(frostedKeyColor(
                resolvedBackground, if (frostedGlass.enabled) textColor else resolvedText, frostedGlass,
                legacyStateColor = if (isPressed || shiftHovered) resolvedBackground.copy(alpha = 0.7f)
                else if (isHighlighted) resolvedBackground.copy(alpha = 0.8f)
                else resolvedBackground,
                pressed = isPressed || shiftHovered, highlighted = isHighlighted,
                opacityScale = glassFunctionKeyScale(resolvedBackground),
                functionDepth = glassFunctionKeyDepth(resolvedBackground),
            )).keyHeldHighlight(isPressed || shiftHovered))
            .then(if (hexagonal) Modifier.honeycombLabelArea() else Modifier),
        contentAlignment = if (layoutMode == ButtonLayout.COMPACT) Alignment.TopStart else Alignment.Center
    ) {
        val contentScale = keyContentScale(maxWidth.value, maxHeight.value)
        val defaultFontSize = KeyboardKeyMetrics.LabelSize.value
        val labelSize = keyLabelSizeSp(text,
            if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize.value else defaultFontSize,
            maxWidth.value, maxHeight.value, density.fontScale,
            LocalKeyboardInputPreferences.current.keyTextScale)
        val hintScale = adaptiveHintScale(contentScale)
        val effectiveSwipeFontSize = swipeHintFontSp(swipeFontSize.value, contentScale).sp
        // 实际键帽太小时只留主字符，灰色上下滑提示不再挤在键面上。
        val hintsVisible = showKeyHints(maxWidth.value, maxHeight.value, LocalDpPerMillimeter.current)
        val compactIconSize = keyIconSizeDp(maxWidth.value, maxHeight.value, 16f).dp
        val mainIconSize = keyIconSizeDp(maxWidth.value, maxHeight.value, iconSizeDp).dp
        val keyHeight = maxHeight
        // 快捷键键面画在里层 Box 里，那里拿不到 BoxWithConstraints 的 maxWidth，先取出来。
        val keyWidthDp = maxWidth.value
        // 和角标一起显示时键面实际写的上下角标（截短规则同下面单独画角标时）。
        val dpUpCorner = (swipeUpKeyLabel ?: swipeText)?.takeIf { it.isNotEmpty() && it != badgeText }
            ?.let { punctuationKeyLabel(if (it.length <= 4) it else it.take(4)) }
        val dpDownCorner = downCornerLabel?.takeIf { it.isNotEmpty() }
            ?.let { punctuationKeyLabel(if (it.length <= 4) it else it.take(4)) }
        // 放不下（键太小）时为 null：这个键退回只画键位提示、不画角标，不让两者重叠。
        val dpCornerLayout = if (dpWithCorners && hintsVisible) fitDoublePinyinWithCorners(
            doublePinyinHint?.initial, doublePinyinHint?.finals.orEmpty(), dpUpCorner, dpDownCorner,
            maxWidth.value, maxHeight.value, effectiveSwipeFontSize.value, labelSize, density.fontScale) else null
        val dpHint = doublePinyinHint?.takeIf { hintsVisible && dpCornerLayout == null }
        val dpFinals = dpHint?.let { fitDoublePinyinFinals(it.finals, maxWidth.value - 6f, maxHeight.value * 0.42f,
            effectiveSwipeFontSize.value, density.fontScale) }
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = (1f - if (ctrlLetter != null) ctrlFade.value else 0f) * (if (showDisc) 1f else 1f - flickFade.strongest)
        }, contentAlignment = if (layoutMode == ButtonLayout.COMPACT) Alignment.TopStart else Alignment.Center) {
            if (layoutMode == ButtonLayout.COMPACT) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (icon != null) {
                        Icon(
                            painter = icon,
                            contentDescription = text,
                            tint = resolvedText,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 2.dp, start = 4.dp)
                                .size(compactIconSize)
                        )
                    } else {
                        Text(
                            text = customLayoutLabel(customLayout, punctuationKeyLabel(text), glassLabelSurface ?: resolvedBackground, customAccent, textColor),
                            color = resolvedText,
                            fontSize = labelSize.sp,
                            fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            lineHeight = (labelSize * 1.2f).sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 2.dp, start = 4.dp),
                            fontFamily = keyFontFamily
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight()
                            .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        val swipeUpHint = swipeUpKeyLabel ?: swipeText
                        if (hintsVisible && !dpHidesCorners && flickPreview?.fromTop != true && !swipeUpHint.isNullOrEmpty()) {
                            val displayText = if (swipeUpHint.length <= 2) swipeUpHint else swipeUpHint.take(2)
                            Text(
                                text = punctuationKeyLabel(displayText),
                                color = keyHintColor(resolvedText, frostedGlass, 0.6f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                                fontSize = effectiveSwipeFontSize,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                lineHeight = 1.sp
                            )
                        }

                        val swipeDownHint = downCornerLabel
                        if (hintsVisible && !dpHidesCorners && flickPreview?.fromTop != false && !swipeDownHint.isNullOrEmpty()) {
                            val hasChinese = swipeDownHint.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' || it in '\uf900'..'\ufaff' }
                            val adjustedFontSize = if (hasChinese && effectiveSwipeFontSize > 6.sp) (effectiveSwipeFontSize.value * 0.85f).sp else effectiveSwipeFontSize
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.BottomStart
                            ) {
                                val displayText = if (swipeDownHint.length <= 12) swipeDownHint else swipeDownHint.take(12)
                                Text(
                                    text = punctuationKeyLabel(displayText),
                                    color = keyHintColor(resolvedText, frostedGlass, 0.7f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                                    fontSize = adjustedFontSize,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Start,
                                    maxLines = 3,
                                    lineHeight = adjustedFontSize,
                                    fontFamily = keyLabelFontFamily
                                )
                            }
                        }
                    }
                }
            } else {
                if (icon != null) {
                    Icon(
                        painter = icon,
                        contentDescription = text,
                        tint = resolvedText,
                        modifier = Modifier.size(mainIconSize)
                    )
                } else {
                    Text(
                        text = customLayoutLabel(customLayout, punctuationKeyLabel(text), glassLabelSurface ?: resolvedBackground, customAccent, textColor),
                        // 双拼键位提示的韵母在字母下面：字母上移半个提示高，两者一起居中。
                        modifier = Modifier.fillMaxWidth().offset(y = if (dpCornerLayout != null) dpCornerLayout.letterOffsetDp.dp
                            else if (dpHint != null) (-(dpFinals?.heightDp ?: 0f) / 2f).dp
                            else if (hintsVisible && !(swipeUpKeyLabel ?: swipeText).isNullOrEmpty() && keyHeight >= 40.dp) 2.dp else 0.dp)
                            // 和角标一起显示：字母框固定为 1.2 倍字号高、居中、超出裁掉，备用字体（全角分号等）不会伸进上下层。
                            .then(if (dpCornerLayout != null) Modifier
                                .height((dpCornerLayout.letterSp * 1.2f * density.fontScale).dp)
                                .clipToBounds().wrapContentHeight(unbounded = true) else Modifier),
                        color = resolvedText,
                        fontSize = (dpCornerLayout?.letterSp ?: labelSize).sp,
                        lineHeight = ((dpCornerLayout?.letterSp ?: labelSize) * 1.2f).sp,
                        fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        fontFamily = keyFontFamily
                    )
                }

                // 上滑提示与角标文字相同（如九键/笔画上滑输入键面数字）时不再重复渲染提示，
                // 角标已表达该信息；swipeText 状态保持非空，上滑触发与气泡不受影响。
                if (hintsVisible && !dpHidesCorners && flickPreview?.fromTop != true && !(swipeUpKeyLabel ?: swipeText).isNullOrEmpty() && (swipeUpKeyLabel ?: swipeText) != badgeText) {
                    val keyLabel = (swipeUpKeyLabel ?: swipeText)!!
                    val displayText = if (keyLabel.length <= 4) keyLabel else keyLabel.take(4)
                    Text(
                        text = punctuationKeyLabel(displayText),
                        color = keyHintColor(resolvedText, frostedGlass, 0.6f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                        fontSize = effectiveSwipeFontSize,
                        lineHeight = (effectiveSwipeFontSize.value * 1.2f).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
                        fontFamily = keyLabelFontFamily
                    )
                }

                if (hintsVisible && !dpHidesCorners && flickPreview?.fromTop != false && !downCornerLabel.isNullOrEmpty()) {
                    val displayText = if (downCornerLabel.length <= 4) downCornerLabel else downCornerLabel.take(4)
                    Text(
                        text = punctuationKeyLabel(displayText),
                        color = keyHintColor(resolvedText, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                        fontSize = effectiveSwipeFontSize,
                        lineHeight = (effectiveSwipeFontSize.value * 1.2f).sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 2.dp),
                        fontFamily = keyLabelFontFamily
                    )
                }

                if (badgeText != null && !(flickPreview?.fromTop == true && flickPreview?.text == badgeText)) {
                    Text(
                        text = badgeText,
                        color = keyHintColor(resolvedText, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                        fontSize = (10f * hintScale).sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        lineHeight = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                    )
                }
            }
            if (dpCornerLayout != null) DoublePinyinCornerFace(doublePinyinHint, dpCornerLayout,
                upCorner = dpUpCorner?.takeIf { flickPreview?.fromTop != true },
                downCorner = dpDownCorner?.takeIf { flickPreview?.fromTop != false },
                initialColor = keyHintColor(resolvedText, frostedGlass, 0.7f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                finalsColor = keyHintColor(resolvedText, frostedGlass, 0.6f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                upColor = keyHintColor(resolvedText, frostedGlass, 0.6f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                downColor = keyHintColor(resolvedText, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity), fontFamily = keyLabelFontFamily)
            if (dpHint != null) DoublePinyinKeyHintFace(dpHint, dpFinals, effectiveSwipeFontSize,
                initialColor = keyHintColor(resolvedText, frostedGlass, 0.7f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                finalsColor = keyHintColor(resolvedText, frostedGlass, 0.6f, LocalKeyboardInputPreferences.current.cornerHintOpacity), fontFamily = keyLabelFontFamily)
        }
        if (ctrlLetter != null) Box(Modifier.matchParentSize().graphicsLayer { alpha = ctrlFade.value }) {
            CtrlShortcutFace(ctrlLetter, resolvedText, frostedGlass, labelSize,
                effectiveSwipeFontSize, hintsVisible, keyWidthDp, keyFontFamily,
                cornerLabel = badgeText?.takeIf { ctrlShortcutLetter != null } ?: ctrlLetter.uppercase())
        }
        else if (showDisc && discLabels != null) {
            KanaDirectionIndicator(discDirection, MaterialTheme.colorScheme.primary.copy(alpha = .6f))
            DirectionDiscPreview(discLabels, discDirection, resolvedBackground, resolvedText, contentScale)
        } else KeyFlickLabels(flickFade, resolvedText, maxWidth.value, maxHeight.value, effectiveSwipeFontSize.value, labelSize,
            icons = buildMap {
                swipeLeftIcon?.let { put(KeyFlickOrigin.LEFT, it) }
                swipeRightIcon?.let { put(KeyFlickOrigin.RIGHT, it) }
            }, iconSizeDp = mainIconSize.value)
    }
}

@Composable
fun KeyboardRow(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    isShifted: Boolean,
    modifier: Modifier = Modifier,
    swipeKeys: List<String>? = null,
    swipeDownKeys: List<String>? = null,
    onSwipeKey: ((String) -> Unit)? = null,
    onSwipeDownKey: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            val swipeText = swipeKeys?.getOrNull(index)
            val swipeDownText = swipeDownKeys?.getOrNull(index)
            val rowOnClick = remember(key, onKeyPress) { { onKeyPress(key) } }
            val rowOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val rowOnRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            SwipeableKeyButton(
                text = if (isShifted) key.uppercase() else key,
                onClick = rowOnClick,
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeText,
                swipeDownText = swipeDownText,
                onSwipe = onSwipeKey,
                onSwipeDown = onSwipeDownKey,
                onSwipeStateChange = onSwipeStateChange,
                onPress = rowOnPress,
                onRelease = rowOnRelease
            )
        }
    }
}

@Composable
fun IconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = KeyboardKeyMetrics.FunctionIconSize,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    contentDescription: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val density = LocalDensity.current

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
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        currentOnPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                        currentOnRelease?.invoke()
                    },
                    onTap = {
                        currentOnClick()
                    }
                )
            }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadowModifier)
            .clip(keyClipShape)
            .background(frostedKeyColor(
                backgroundColor, iconColor, frostedGlass,
                legacyStateColor = if (isPressed) darkenColor(backgroundColor, 0.1f)
                else if (isHighlighted) darkenColor(backgroundColor, 0.2f)
                else backgroundColor,
                pressed = isPressed, highlighted = isHighlighted,
                opacityScale = glassFunctionKeyScale(backgroundColor),
                functionDepth = glassFunctionKeyDepth(backgroundColor),
            )), materialLevel = com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(keyIconSizeDp(maxWidth.value, maxHeight.value, iconSize.value).dp)
        )

        // 右上角小圆点指示 — 仅在 isHighlighted 时显示
        if (isHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(iconColor)
            )
        }
    }
}

@Composable
fun SwipeableIconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = KeyboardKeyMetrics.FunctionIconSize,
    swipeText: String? = null,
    onSwipe: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    // 上滑/下滑/左滑增强
    swipeUpLabel: String? = null,
    swipeDownLabel: String? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    visualPadding: PaddingValues? = null,
    upFeedback: KeyFlickFeedback = KeyFlickFeedback.NORMAL,
    downFeedback: KeyFlickFeedback = KeyFlickFeedback.NORMAL,
    followSymbolSwipeDirection: Boolean = false,
    distanceDelete: Boolean = false,
    /** 删除键：按住连删按「长按删除速度」。会左右滑动删除的键都是删除键；手写的删除键不接滑动，单独标。 */
    deleteKey: Boolean = distanceDelete,
) {
    // Gesture coroutines survive recomposition; route to the current editor/input callbacks.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val startDeleteDrag by rememberUpdatedState(LocalDeleteDrag.current.takeIf { distanceDelete })
    val repeatIntervalMs by rememberUpdatedState(if (deleteKey) LocalKeyboardInputPreferences.current.deleteRepeatSpeed.intervalMs else 30L)
    // 删除键的清空、撤回按「清空滑动距离」，其他功能键照旧 80dp。
    val actionDistanceDp by rememberUpdatedState(if (deleteKey) LocalKeyboardInputPreferences.current.clearSwipeDistanceDp else 80f)
    val currentOnSwipeStateChange by rememberUpdatedState(onSwipeStateChange)
    val currentSwipeText by rememberUpdatedState(swipeText)
    val currentSwipeUpLabel by rememberUpdatedState(swipeUpLabel)
    val currentSwipeDownLabel by rememberUpdatedState(swipeDownLabel)
    // 删除键另有「删除键单独反向」，实际方向见 deleteSwipeReversed；其他键只跟「反转上下滑方向」。
    val swapVertical by rememberUpdatedState(followSymbolSwipeDirection &&
        !LocalKeyboardInputPreferences.current.let { if (deleteKey) it.deleteSwipeReversed else it.reverseSymbolSwipe })
    var isPressed by remember { mutableStateOf(false) }
    var flickPreview by remember { mutableStateOf<KeyFlickPreview?>(null) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val liveBounds = remember { LiveBounds() }
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val density = LocalDensity.current

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
            alpha = color.alpha
        )
    }
    
    // 预览从原提示所在一侧移入（上方 = 原上滑动作），颜色按该动作取，与手指方向无关。
    val feedback = flickPreview?.let { if (it.fromTop) upFeedback else downFeedback }
        ?: KeyFlickFeedback.NORMAL
    val baseFill = frostedKeyColor(backgroundColor, iconColor, frostedGlass,
        legacyStateColor = if (isPressed) darkenColor(backgroundColor, .2f)
            else if (isHighlighted) backgroundColor.copy(alpha = .8f) else backgroundColor,
        pressed = isPressed, highlighted = isHighlighted,
        opacityScale = glassFunctionKeyScale(backgroundColor),
        functionDepth = glassFunctionKeyDepth(backgroundColor))
    val feedbackColors = keyFlickFeedbackColors(baseFill, iconColor,
        com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.accent ?: androidx.compose.material3.MaterialTheme.colorScheme.primary,
        feedback, flickPreview?.progress ?: 0f, frostedGlass.enabled)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .semantics { onClick { currentOnClick(); true } }
            .pointerInput(Unit) {
                detectRepeatingKeyGestures {
                    RepeatingKeyActions(
                        onTap = { currentOnClick() },
                        onPress = { isPressed = true; currentOnPress?.invoke() },
                        onRelease = { isPressed = false; currentOnRelease?.invoke() },
                        onRepeat = currentOnLongClick?.let { { currentOnLongClick?.invoke(); Unit } },
                        onUp = (currentOnSwipeUp ?: currentOnSwipe)?.let {
                            { (currentOnSwipeUp ?: currentOnSwipe)?.invoke(); Unit }
                        },
                        onDown = currentOnSwipeDown?.let { { currentOnSwipeDown?.invoke(); Unit } },
                        onLeft = currentOnSwipeLeft?.let { { currentOnSwipeLeft?.invoke(); Unit } },
                        upLabel = currentSwipeUpLabel ?: currentSwipeText,
                        downLabel = currentSwipeDownLabel,
                        startDeleteDrag = startDeleteDrag,
                        repeatIntervalMs = repeatIntervalMs,
                        actionDistanceDp = actionDistanceDp,
                        onPreview = { state ->
                            flickPreview = state.keyFlick
                            currentOnSwipeStateChange?.invoke(state, liveBounds.current(buttonBounds, "function key"))
                        },
                    ).let { if (swapVertical) it.withSymbolSwipeDirection(reverse = false) else it }
                }
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
                liveBounds.coordinates = coordinates
            }
            .padding(visualPadding ?: scaledKeyVisualPadding())
            .then(if (honeycombEdge != null) Modifier.honeycombCap() else Modifier)
            .keyGlow(Modifier.then(if (honeycombEdge != null) Modifier else shadowModifier)
            .clip(keyClipShape)
            .background(feedbackColors.background)
            .keyHeldHighlight(isPressed && feedback == KeyFlickFeedback.NORMAL), materialLevel = com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED,
                glowVisibility = if (feedback == KeyFlickFeedback.NORMAL) 1f else 1f - (flickPreview?.progress ?: 0f))
            .then(if (honeycombEdge != null) Modifier.honeycombLabelArea(half = true) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val contentScale = keyContentScale(maxWidth.value, maxHeight.value)
        val hintSize = 10f * adaptiveHintScale(contentScale)
        val renderedIconSize = keyIconSizeDp(maxWidth.value, maxHeight.value, iconSize.value).dp
        val hintOffset = KeyboardKeyMetrics.hintOffsetDp(maxHeight.value, hintSize, density.fontScale, contentScale).dp
        val hintFits = KeyboardKeyMetrics.hintClearsIcon(hintOffset.value, hintSize, density.fontScale, renderedIconSize.value)
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - (flickPreview?.progress ?: 0f)
        }, contentAlignment = Alignment.Center) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = feedbackColors.foreground,
            modifier = Modifier.size(renderedIconSize)
        )
        
        // Like a character key's corner hint: the hint itself becomes the moving label.
        // A half-hexagon edge key has no corner left for it, and a short key has no room above
        // the icon; the swipe still works.
        // 「功能键不显示提示字」只藏这行字，滑动和滑动时的预览照旧（GitHub #60）。
        if (!swipeText.isNullOrEmpty() && flickPreview?.text != swipeText && honeycombEdge == null && hintFits &&
            !LocalKeyboardInputPreferences.current.hideFunctionKeyHints) {
            Text(
                text = punctuationKeyLabel(swipeText),
                color = keyHintColor(iconColor, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                fontSize = hintSize.sp,
                lineHeight = (hintSize * 1.2f).sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = -hintOffset),
                fontFamily = keyLabelFontFamily
            )
        }
        }
        flickPreview?.let {
            val labelSize = keyLabelSizeSp(it.text, KeyboardKeyMetrics.LabelSize.value,
                maxWidth.value, maxHeight.value, density.fontScale)
            KeyFlickLabel(it, feedbackColors.foreground, maxWidth.value, maxHeight.value, hintSize, labelSize)
        }
    }
}
