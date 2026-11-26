package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 跨键交接（用户 11-26 触点日志：左手按住 A 还没抬，右手点的 O 先抬先出字，xiao 成了 xioa、zhao 成了 xhia）。
 * 每个键松手才出字、各跟各的手指，先按下的键可能后出字。键盘上任何一根手指新按下时（字母、空格、删除都算），
 * 先把在它之前按下、还按着的字母键按点按结算掉，出字顺序就跟按下顺序一致；结算过的键随后松手不再出字。
 * 能不能结算由登记的键自己判断（没挪过触摸阈值、没到长按），挪过的照旧等松手。
 * 只在主线程用：指针事件都在主线程分发。
 */
internal class KeyRollover {
    private class Held(val pointer: PointerId, val settle: () -> Unit)
    private val held = ArrayList<Held>()

    /** 字母键按下时登记，返回值交给 [release]。[settle] 自己判断这一下还能不能当点按结算。 */
    fun hold(pointer: PointerId, settle: () -> Unit): Any = Held(pointer, settle).also { held += it }

    fun release(token: Any) {
        held.remove(token)
    }

    /** 手指 [pointer] 新按下：别的手指还按着的字母键按按下先后结算，结算过（或不能结算）的不再登记。 */
    fun pressed(pointer: PointerId) {
        if (held.isEmpty()) return
        val earlier = held.filter { it.pointer != pointer }
        if (earlier.isEmpty()) return
        held.removeAll(earlier)
        earlier.forEach { it.settle() }
    }
}

internal val LocalKeyRollover = staticCompositionLocalOf<KeyRollover?> { null }

/** 键盘层只看不消费：在按键自己处理这次按下之前（Initial 阶段）先结算前面还按着的字母键。 */
internal fun Modifier.keyRollover(rollover: KeyRollover?): Modifier =
    if (rollover == null) this else pointerInput(rollover) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                    if (it.changedToDownIgnoreConsumed()) rollover.pressed(it.id)
                }
            }
        }
    }
