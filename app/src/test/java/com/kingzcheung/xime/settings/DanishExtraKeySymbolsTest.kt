package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 丹麦语 29 键的 å æ ø：26 格之外没有可沿用的角标，存在英文那一套里，默认空着，在「丹麦语29键」页改。
 * 中文那一套和别的 26 格之外的字母（ñ）不受影响。
 */
class DanishExtraKeySymbolsTest {
    private val yaml = listOf(java.io.File("src/main/assets/xime.yaml"), java.io.File("app/src/main/assets/xime.yaml"))
        .first { it.isFile }.readText()

    @Test fun `editor rows match the danish keyboard`() {
        val danish = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty_danish"))
        assertEquals(danish.map { it.joinToString("") }, QwertySwipeSymbols.danishRows)
        val extra = QwertySwipeSymbols.danishRows.joinToString("").map(Char::toString) - QwertySwipeSymbols.keys.map(Char::toString).toSet()
        assertEquals(extra, QwertySwipeSymbols.extraKeys)
    }

    @Test fun `extra keys default to empty corners in the english set`() {
        for (key in QwertySwipeSymbols.extraKeys) {
            val gesture = requireNotNull(QwertySwipeSymbols.apply(key, null, emptyMap(), null, english = true))
            assertEquals("", gesture.swipeUp!!.value)
            assertEquals(GestureAction.NONE, gesture.swipeUp!!.action)
            assertEquals("", gesture.swipeDown!!.value)
        }
    }

    @Test fun `saved extra key symbols are typed`() {
        val gesture = requireNotNull(QwertySwipeSymbols.apply("å", null, mapOf("up.å" to "+", "down.å" to "-"), null, english = true))
        assertEquals("+", gesture.swipeUp!!.value)
        assertEquals(GestureAction.COMMIT, gesture.swipeUp!!.action)
        assertEquals("-", gesture.swipeDown!!.value)
    }

    @Test fun `chinese set and other extra letters keep the keyboard config`() {
        val original = KeyGestureConfig()
        assertSame(original, QwertySwipeSymbols.apply("å", original, mapOf("up.å" to "+"), null, english = false))
        assertSame(original, QwertySwipeSymbols.apply("ñ", original, mapOf("up.ñ" to "+"), null, english = true))
        assertNull(QwertySwipeSymbols.apply("ñ", null, emptyMap(), null, english = true))
    }
}
