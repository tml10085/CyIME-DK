package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRowsTest {
    private val all = InputLanguage.supported.map { it.id }

    @Test
    fun justTurnedOffLanguageAppearsOnce() {
        // 关掉西班牙语后的那一帧：拖动顺序还没更新，仍含 es。
        val staleOrder = listOf("zh", "en", "es", "de")
        val enabled = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.GERMAN, InputLanguage.UNSPECIFIED)
        val rows = languageRows(staleOrder, enabled)
        assertEquals(rows.distinct(), rows)
        assertEquals(all.toSet(), rows.toSet())
        assertEquals(listOf("zh", "en", "de"), rows.take(3))
    }

    @Test
    fun everyLanguageTurnedOffOneByOneStaysUnique() {
        val order = listOf("zh", "ja", "en", "da", "es", "de", "fr", "it", "ru", "ko").filter { it in all }
        order.filter { it != "en" }.forEach { off ->
            val enabled = InputLanguage.supported.filter { it.id != off }.toSet()
            val rows = languageRows(order, enabled)
            assertEquals(rows.distinct(), rows)
            assertEquals(off, rows.last())
        }
    }

    @Test
    fun unusedLetterLanguagesFoldUnderMoreLanguages() {
        val enabled = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.FRENCH, InputLanguage.UNSPECIFIED)
        val rows = languageRows(listOf("zh", "en", "fr"), enabled)
        val folded = foldedLanguages(rows, enabled)
        // 开着的法语、中文、日语（没开也列出）不收；没开的其他字母语言收进去。
        assertEquals(false, "fr" in folded)
        assertEquals(false, "ja" in folded)
        assertEquals(true, "es" in folded && "ru" in folded)
        val closed = languageListRows(rows, enabled, moreOpen = false)
        assertEquals(MORE_LANGUAGES_ROW, closed.last())
        assertEquals(rows.filterNot { it in folded }, closed.dropLast(1))
        val open = languageListRows(rows, enabled, moreOpen = true)
        assertEquals(closed + folded, open)
        assertEquals(open.distinct(), open)
    }

    @Test
    fun noMoreLanguagesRowWhenEveryLanguageIsOn() {
        val enabled = InputLanguage.supported.toSet()
        val rows = languageRows(InputLanguage.supported.map { it.id }, enabled)
        assertEquals(rows, languageListRows(rows, enabled, moreOpen = false))
    }

    @Test
    fun keepsDragOrderOfEnabledLanguages() {
        val enabled = setOf(InputLanguage.JAPANESE, InputLanguage.CHINESE, InputLanguage.ENGLISH)
        val rows = languageRows(listOf("ja", "en", "zh"), enabled)
        assertEquals(listOf("ja", "en", "zh"), rows.take(3))
    }
}
