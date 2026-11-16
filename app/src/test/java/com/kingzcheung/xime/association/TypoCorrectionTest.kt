package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypoCorrectionTest {
    private val letters = "abcdefghijklmnopqrstuvwxyz"

    @Test fun `neighbours follow the phone qwerty`() {
        assertTrue(TypoCorrection.near('q', 'w'))
        assertTrue(TypoCorrection.near('s', 'z'))
        assertTrue(TypoCorrection.near('g', 'b'))
        assertTrue(TypoCorrection.near('p', 'å'))
        assertTrue(TypoCorrection.near('l', 'æ'))
        assertFalse(TypoCorrection.near('q', 'p'))
        assertFalse(TypoCorrection.near('a', 'm'))
        assertFalse(TypoCorrection.near('a', 'a'))
    }

    @Test fun `edits rate swaps, neighbour slips and doubled letters as the smallest change`() {
        val edits = TypoCorrection.edits("teh", letters)
        assertEquals(0, edits["the"])          // 打反
        assertEquals(0, edits["tej"])          // h → j 相邻
        assertEquals(1, edits["te"])           // 多打一个
        assertEquals(1, edits["tech"])         // 漏打一个
        assertEquals(2, edits["tea"])          // h → a 不相邻
        assertFalse("teh" in edits)
        // 双字母少打、多打一个。
        assertEquals(0, TypoCorrection.edits("helo", letters)["hello"])
        assertEquals(0, TypoCorrection.edits("helllo", letters)["hello"])
    }

    private val ranks = mapOf("the" to 0, "they" to 30, "tea" to 900, "hello" to 400, "help" to 300)
    private fun rank(word: String) = ranks[word]?.let { word to it }

    @Test fun `common words with the smallest change come first`() {
        // the（打反、最常用）排在 tea（按错远处的键）前面。
        assertEquals(listOf("the", "tea"), TypoCorrection.corrections("teh", letters, completes = false, rank = ::rank))
        // helo：按到旁边的键（help）和双字母少打一个（hello）都是最小改动，按词频排。
        assertEquals(listOf("help", "hello"), TypoCorrection.corrections("helo", letters, completes = false, rank = ::rank))
        assertEquals(listOf("help"), TypoCorrection.corrections("hlep", letters, completes = false, rank = ::rank))
        assertEquals(listOf("the"), TypoCorrection.corrections("teh", letters, completes = false, limit = 1, rank = ::rank))
    }

    @Test fun `no corrections for unfinished, short or rare words`() {
        // 还能补全成别的词（打到一半）：不纠正。
        assertTrue(TypoCorrection.corrections("hlep", letters, completes = true, rank = ::rank).isEmpty())
        // 两个字母太短。
        assertTrue(TypoCorrection.corrections("te", letters, completes = false, rank = ::rank).isEmpty())
        // 带数字、撇号的不是单词。
        assertTrue(TypoCorrection.corrections("te4h", letters, completes = false, rank = ::rank).isEmpty())
        // 冷门词不拿来纠正。
        assertEquals(listOf("the"), TypoCorrection.corrections("teh", letters, completes = false, maxRank = 500, rank = ::rank))
    }
}
