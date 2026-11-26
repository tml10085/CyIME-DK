package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 一次按下只触发一个输入分支；长按动作到时执行，滑动与菜单松手确认。 */
internal data class KeyGestureActions(
    val text: String,
    val onTap: () -> Unit,
    val onPress: () -> Unit,
    val onRelease: () -> Unit,
    val onPreview: (SwipeState) -> Unit,
    val upText: String? = null,
    val downText: String? = null,
    val onUp: ((String) -> Unit)? = null,
    val onDown: ((String) -> Unit)? = null,
    val leftText: String? = null,
    val onLeft: (() -> Unit)? = null,
    val rightText: String? = null,
    val onRight: (() -> Unit)? = null,
    val horizontalThresholdDp: Float = 50f,
    val verticalThresholdDp: Float = 50f,
    val reserveHorizontalSwipe: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val longPressDrawableIds: List<Int> = emptyList(),
    val keyWidth: Float = 1f,
    val onLongPress: (() -> Unit)? = null,
    val onLongPressSelect: ((String) -> Unit)? = null,
    val onLongPressFeedback: () -> Unit = {},
    val longPressTimeoutMillis: Long? = null,
    /**
     * [onLongPress] 到点立刻输入的那个字（「长按输入」的角标、九键数字）：同一刻气泡换成它，按住一直显示，松手消失。
     * 不受按压气泡开关限制，和长按菜单一样是选择反馈。
     */
    val longPressBubbleText: String? = null,
    val blockUpSwipe: Boolean = false,
    val blockDownSwipe: Boolean = false,
    val inlineVerticalPreview: Boolean = false,
    val inlineHorizontalPreview: Boolean = false,
    val upPreviewText: String? = upText,
    val downPreviewText: String? = downText,
    val upPreviewFromTop: Boolean = true,
    val downPreviewFromTop: Boolean = false,
    /**
     * Four-way flick keys (nine-key flick letters, Japanese kana): the direction follows the finger's
     * current dominant axis, so the finger may re-aim without returning to the centre, and the release
     * point decides. Released within the threshold is a tap; past it in a direction without a choice
     * enters nothing.
     */
    val freeDirection: Boolean = false,
    /** 26 键单字母键：按住时登记，别的手指按下就提前按点按结算，见 [KeyRollover]。 */
    val rollover: KeyRollover? = null,
)

internal fun KeyGestureActions.withLetterSwipes(
    letter: String, bindings: Map<String, String>, execute: (String) -> Unit,
): KeyGestureActions {
    val left = bindings["$letter.left"]
    val right = bindings["$letter.right"]
    val up = bindings["$letter.up"]
    val down = bindings["$letter.down"]
    if (left == null && right == null && up == null && down == null) return this
    fun label(value: String?) = com.kingzcheung.xime.settings.LetterSwipeBindings.label(value)
    return copy(leftText = label(left) ?: leftText, rightText = label(right) ?: rightText,
        upText = label(up) ?: upText, downText = label(down) ?: downText,
        upPreviewText = label(up) ?: upPreviewText, downPreviewText = label(down) ?: downPreviewText,
        onLeft = left?.let { { execute(it) } } ?: onLeft,
        onRight = right?.let { { execute(it) } } ?: onRight,
        onUp = up?.let { action -> { _: String -> execute(action) } } ?: onUp,
        onDown = down?.let { action -> { _: String -> execute(action) } } ?: onDown,
        blockUpSwipe = up == null && blockUpSwipe, blockDownSwipe = down == null && blockDownSwipe,
        inlineHorizontalPreview = inlineHorizontalPreview || left != null || right != null,
        reserveHorizontalSwipe = reserveHorizontalSwipe || left != null || right != null)
}

/**
 * 「按键符号」给单字母键设的上下滑动作，键面角标改写动作名（如 Z X C V 预设的撤销、剪切、复制、粘贴）。
 * 条件和 SwipeableKeyButton 套用 [withLetterSwipes] 时一样；返回（上方角标, 下方角标），没设的方向为 null。
 */
@androidx.compose.runtime.Composable
internal fun letterSwipeCornerLabels(key: String): Pair<String?, String?> {
    val preferences = LocalKeyboardInputPreferences.current
    val actions = LocalKeyboardInputActions.current
    if (key.length != 1 || actions.onLetterSwipeAction == null || letterSwipeAvailable(LocalFourteenKeyLayout.current,
            LocalCustomLayout.current?.mergedGroups?.isNotEmpty() == true)) return null to null
    val bindings = if (actions.englishLetters) preferences.englishLetterSwipes else preferences.chineseLetterSwipes
    return com.kingzcheung.xime.settings.LetterSwipeBindings.cornerLabels(bindings, key, preferences.reverseSymbolSwipe)
}

/**
 * [reserveDragForGlide]：这个键上的拖动留给滑行（九键滑行、26 键英文/中文滑行），键自己不做上下滑、拂动。
 * 拖动后滑行没接管（没有消费这根手指）时，抬手仍按点按结算（见 [glideDriftIsTap]）。
 */
internal suspend fun PointerInputScope.detectExclusiveKeyGestures(
    reserveDragForGlide: Boolean = false,
    /** 拖动留给的是 26 键字母滑行（不是九键）：没成滑行时按字母滑行的接管距离判点按，见 [glideDriftIsTap]。 */
    letterGlide: Boolean = false,
    currentActions: () -> KeyGestureActions,
) = coroutineScope {
    val gestureScope = this
    val horizontalCancelThreshold = 60.dp.toPx()
    // 快速连打同一个键（小鹤 uu）时两只拇指会交叠：前一下还没抬，后一下已按在同一键上。
    // 后按下的手指结束前一下（按它当时的位置结算），再接着算自己的一下；不等所有手指抬起，否则这一下被吞。
    var handover: PointerInputChange? = null
    awaitPointerEventScope { while (true) {
        val down = handover ?: awaitNewDown()
        handover = null
        val actions = currentActions()
        val swipeThreshold = actions.verticalThresholdDp.dp.toPx()
        val horizontalThreshold = actions.horizontalThresholdDp.dp.toPx()
        down.consume()
        actions.onPress()
        actions.onPreview(SwipeState(isPressed = true, pressedText = actions.text))
        var longPressed = false
        var moved = false
        var crossedSwipeThreshold = false
        // Once a direction owns the drag, sideways drift cannot undo its preview.
        // Returning along that axis to the key centre releases the direction.
        var verticalDrag: Boolean? = null
        // A free flick keeps the touch once it has left the centre, even after coming back.
        var flickClaimed = false
        var selectedIndex = 0
        val items = actions.longPressItems
        val longPressJob = if (items.isNotEmpty() || actions.onLongPress != null) gestureScope.launch {
            delay(actions.longPressTimeoutMillis ?: if (items.isNotEmpty()) 400L else viewConfiguration.longPressTimeoutMillis)
            longPressed = true
            actions.onLongPressFeedback()
            if (items.isNotEmpty()) {
                actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                    longPressItems = items, selectedLongPressIndex = selectedIndex,
                    longPressDrawableIds = actions.longPressDrawableIds))
            } else {
                actions.onLongPress?.invoke()
                actions.longPressBubbleText?.takeIf { it.isNotEmpty() }?.let { shown ->
                    actions.onPreview(SwipeState(isPressed = true, isLongPress = true, longPressItems = listOf(shown)))
                }
            }
        } else null
        // 别的手指按下时已按点按结算（见 [KeyRollover]）：松手不再出字，也不再滑出角标。
        var settled = false
        val rolloverToken = actions.rollover?.takeIf { !actions.freeDirection }?.hold(down.id) {
            if (!settled && !moved && !longPressed && verticalDrag == null && !flickClaimed && !crossedSwipeThreshold) {
                settled = true
                longPressJob?.cancel()
                actions.onTap()
            }
        }
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!longPressed && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                    moved = true
                    longPressJob?.cancel()
                    // 留给滑行的键以前一过触摸阈值就放手，可滑行要走一段才接管（九键 DigitStroke.claimed、
                    // 26 键 GlideTracker.claimed）：快打时手指一抖、没到滑行又不算点按，这一下就吞了（用户 11-10）。
                    // 现在接着跟这根手指：滑行接管时会消费事件，下面照常放手；没接管就在抬手时按点按算。
                }
                val slop = viewConfiguration.touchSlop
                // Back at the key's own centre also means the centre choice, for presses that began
                // off-centre; a fixed tolerance, so wide tablet keys still flick sideways.
                val atKeyCentre = actions.freeDirection && moved &&
                    abs(change.position.x - size.width / 2f) <= slop && abs(change.position.y - size.height / 2f) <= slop
                if (reserveDragForGlide) {
                    // 留给滑行的拖动不认方向：不上下滑、不拂动，只等滑行接管或抬手。
                } else if (actions.freeDirection) {
                    if (!longPressed) verticalDrag = when {
                        maxOf(abs(dx), abs(dy)) <= slop || atKeyCentre -> null
                        abs(dy) >= abs(dx) -> true
                        else -> false
                    }
                    if (verticalDrag != null) flickClaimed = true
                } else if ((verticalDrag == true && abs(dy) <= slop) || (verticalDrag == false && abs(dx) <= slop))
                    verticalDrag = null
                if (verticalDrag == null && !longPressed && !actions.freeDirection && !reserveDragForGlide) {
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.1f &&
                        (actions.onUp != null || actions.onDown != null || actions.blockUpSwipe || actions.blockDownSwipe)) verticalDrag = true
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.1f &&
                        (actions.onLeft != null || actions.onRight != null)) verticalDrag = false
                }
                val up = verticalDrag == true && dy < -swipeThreshold && actions.onUp != null
                val downSwipe = verticalDrag == true && dy > swipeThreshold && actions.onDown != null
                val left = verticalDrag == false && dx < -horizontalThreshold && actions.onLeft != null
                val right = verticalDrag == false && dx > horizontalThreshold && actions.onRight != null
                val blockedUp = verticalDrag == true && actions.blockUpSwipe && dy < -swipeThreshold
                val blockedDown = verticalDrag == true && actions.blockDownSwipe && dy > swipeThreshold
                // Free flicks are decided where the finger lifts, so leaving the centre is not sticky.
                val outsideCentre = !reserveDragForGlide && actions.freeDirection && !atKeyCentre && maxOf(abs(dx), abs(dy)) > swipeThreshold
                crossedSwipeThreshold = if (actions.freeDirection) outsideCentre
                    else crossedSwipeThreshold || up || downSwipe || left || right || blockedUp || blockedDown
                if (longPressed && items.isNotEmpty()) {
                    val itemWidth = (actions.keyWidth / items.size).coerceAtLeast(1f)
                    selectedIndex = ((dx / itemWidth) + if (items.size > 1) 0.5f else 0f)
                        .toInt().coerceIn(items.indices)
                    actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                        longPressItems = items, selectedLongPressIndex = selectedIndex,
                        longPressDrawableIds = actions.longPressDrawableIds))
                } else if (!longPressed && !settled) {
                    val hint = if (up) actions.upText else if (downSwipe) actions.downText else if (left) actions.leftText else if (right) actions.rightText else null
                    actions.onPreview(SwipeState(isSwiping = hint != null, swipeText = hint,
                        isSwipeDown = downSwipe, isPressed = !moved, pressedText = actions.text,
                        keyFlick = (if (actions.inlineVerticalPreview && verticalDrag == true) keyFlickPreview(
                            0f, dy, viewConfiguration.touchSlop, swipeThreshold,
                            actions.upPreviewText.takeIf { actions.onUp != null && !actions.blockUpSwipe },
                            actions.downPreviewText.takeIf { actions.onDown != null && !actions.blockDownSwipe },
                            actions.upPreviewFromTop, actions.downPreviewFromTop,
                        ) else null) ?: if (actions.inlineHorizontalPreview && verticalDrag == false) horizontalKeyFlickPreview(
                            dx, 0f, viewConfiguration.touchSlop, horizontalThreshold,
                            actions.leftText.takeIf { actions.onLeft != null },
                            actions.rightText.takeIf { actions.onRight != null },
                        ) else null))
                }
                // 长按菜单正在选时不换手，保持原来的选择。
                val nextDown = if (longPressed) null
                    else event.changes.firstOrNull { it.id != down.id && it.changedToDown() && !it.isConsumed }
                if (!change.pressed || nextDown != null) {
                    change.consume()
                    handover = nextDown
                    if (!settled) when {
                        longPressed -> items.getOrNull(selectedIndex)?.let { actions.onLongPressSelect?.invoke(it) }
                        up -> actions.onUp?.invoke(actions.upText.orEmpty())
                        downSwipe -> actions.onDown?.invoke(actions.downText.orEmpty())
                        left -> actions.onLeft?.invoke()
                        right -> actions.onRight?.invoke()
                        reserveDragForGlide && moved ->
                            if (glideDriftIsTap(down.position, change.position, size.width.toFloat(), size.height.toFloat(),
                                    viewConfiguration.touchSlop, letterGlide)) actions.onTap()
                        !crossedSwipeThreshold && (actions.freeDirection || abs(dx) < horizontalCancelThreshold) -> actions.onTap()
                    }
                    break
                }
                // Reserve paired-letter drags before the whole-keyboard cursor's
                // activation distance, even when the user sets a longer threshold.
                if (longPressed || crossedSwipeThreshold || verticalDrag != null || flickClaimed || actions.reserveHorizontalSwipe && !reserveDragForGlide &&
                    abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.1f) change.consume()
                else {
                    // 父层光标/滚动手势可能在 Main 后半段消费；取消后不得补出普通字符。
                    val finalChange = awaitPointerEvent(PointerEventPass.Final)
                        .changes.firstOrNull { it.id == down.id }
                    if (finalChange == null || finalChange.isConsumed) break
                }
            }
        } finally {
            rolloverToken?.let { actions.rollover?.release(it) }
            longPressJob?.cancel()
            actions.onRelease()
            actions.onPreview(SwipeState())
        }
    } }
}

/**
 * 留给滑行的键上手指挪过了触摸阈值、滑行却没接管，抬手算不算点了这个键：
 * 抬手点还在这个键上（各边放宽一个触摸阈值），或者离按下点不到滑行接管要走的距离
 * （[DigitStroke.CLAIM_KEY_FRACTION] 个键的短边；快打时按在键边往隔壁抖一下也算这一键）。
 * 26 键字母滑行（[letterGlide]）接管要横竖合起来走满 [GlideTracker.MIN_SPAN_KEYS] 个键（宽、高各按键宽、键高算），
 * 比九键的 0.6 个短边远，竖着更远：以前两条线中间那一段（快打时手指一抖出键 0.6～0.75 个键）既不是滑行也不算点按，
 * 这一下就吞了（用户 11-11「打两个字第二个字被吞」）。现在没到字母滑行的接管距离都算点按。
 * 都不是（拖到左栏、功能键或拖远了又没成滑行）就不出字，和以前滑出键外取消一样。
 */
internal fun glideDriftIsTap(down: Offset, up: Offset, keyWidth: Float, keyHeight: Float, slop: Float,
                             letterGlide: Boolean = false): Boolean {
    val inside = up.x >= -slop && up.x <= keyWidth + slop && up.y >= -slop && up.y <= keyHeight + slop
    val drift = up - down
    val belowLetterClaim = letterGlide && keyWidth > 0f && keyHeight > 0f &&
        kotlin.math.hypot(drift.x / keyWidth, drift.y / keyHeight) < com.kingzcheung.xime.glide.GlideTracker.MIN_SPAN_KEYS
    return inside || belowLetterClaim || drift.getDistance() < minOf(keyWidth, keyHeight) * DigitStroke.CLAIM_KEY_FRACTION
}

/** 这个键上新按下的一根手指；已经结算过、还按着的手指不算。 */
private suspend fun AwaitPointerEventScope.awaitNewDown(): PointerInputChange {
    while (true) {
        awaitPointerEvent().changes.firstOrNull { it.changedToDown() && !it.isConsumed }?.let { return it }
    }
}
