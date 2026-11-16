package com.kingzcheung.xime.association

import kotlin.math.ln

/**
 * 英文直接输入、丹麦语等字母语言补全的拼错纠正：打的词不在词表里时，找只差一处的常用词
 * （相邻两个字母打反、按到旁边的键、漏一个字母、多一个字母、按错一个键），比如 teh → the、helo → hello、tka → tak。
 *
 * 纠正出的词只当候选，不自动替换，按「改动的种类 + 词频」排。只在打了 [MIN_LENGTH] 个字母以上、
 * 打的不是词表里的词、也补全不出任何词时才找（打到一半的 comp、hel 照旧只出补全）。
 */
internal object TypoCorrection {
    const val MIN_LENGTH = 3
    private const val MAX_LENGTH = 24
    /** 每次最多给几个纠正。 */
    const val LIMIT = 2

    /** 手机 QWERTY 键位（第二排右移半键、第三排右移一键半），丹麦语键盘右边多 å æ ø；按到旁边的键算小改动。 */
    private val rows = listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm")
    private val rowOffset = floatArrayOf(0f, 0.5f, 1.5f)
    private val positions: Map<Char, Pair<Float, Float>> = buildMap {
        rows.forEachIndexed { row, letters -> letters.forEachIndexed { col, c -> put(c, (col + rowOffset[row]) to row.toFloat()) } }
    }

    internal fun near(a: Char, b: Char): Boolean {
        if (a == b) return false
        val pa = positions[a] ?: return false
        val pb = positions[b] ?: return false
        val dx = pa.first - pb.first
        val dy = pa.second - pb.second
        return dx * dx + dy * dy <= 1.6f * 1.6f
    }

    /**
     * 改动的代价：0 = 打反、按到旁边的键、双字母少打或多打一个（helo → hello、helllo → hello）；
     * 1 = 漏一个、多一个字母；2 = 按错不相邻的键。
     */
    internal fun edits(typed: String, alphabet: String): Map<String, Int> {
        val out = HashMap<String, Int>(typed.length * (alphabet.length * 2 + 2))
        fun put(variant: String, cost: Int) {
            if (variant.isEmpty() || variant == typed) return
            val old = out[variant]
            if (old == null || cost < old) out[variant] = cost
        }
        for (i in typed.indices) {
            val doubled = (i > 0 && typed[i - 1] == typed[i]) || (i + 1 < typed.length && typed[i + 1] == typed[i])
            put(typed.removeRange(i, i + 1), if (doubled) 0 else 1)
            if (i + 1 < typed.length && typed[i] != typed[i + 1])
                put(typed.substring(0, i) + typed[i + 1] + typed[i] + typed.substring(i + 2), 0)
            for (c in alphabet) if (c != typed[i])
                put(typed.substring(0, i) + c + typed.substring(i + 1), if (near(typed[i], c)) 0 else 2)
        }
        for (i in 0..typed.length) for (c in alphabet) {
            val doubling = (i > 0 && typed[i - 1] == c) || (i < typed.length && typed[i] == c)
            put(typed.substring(0, i) + c + typed.substring(i), if (doubling) 0 else 1)
        }
        return out
    }

    /** 纠正的排序分，越小越前：常用词在前，改动大的往后（代价每加 1 相当于词频排名靠后约 2.5 倍）。 */
    internal fun score(rank: Int, cost: Int): Double = ln(rank + 10.0) + cost * 0.9

    /**
     * [typed]（已转小写、去重音）不是词时，从 [rank] 查得到的变体里挑最多 [limit] 个纠正，按 [score] 从好到差返回词表里的写法。
     * [rank] 返回 null 表示不是词，否则是这个词的写法和词频排名（0 最常用）；排名超过 [maxRank] 的冷门词不拿来纠正。
     *
     * [completes]：打的字母还能补全成别的词（多半是还没打完，hel → help、stra → Straße）。这时不纠正：
     * 打到一半时候选都以打的字母开头，不夹 he、Star 这类改了前缀的词。补全不出任何词时才找纠正。
     */
    fun corrections(typed: String, alphabet: String, completes: Boolean, limit: Int = LIMIT, maxRank: Int = Int.MAX_VALUE,
        rank: (String) -> Pair<String, Int>?): List<String> {
        if (completes || typed.length !in MIN_LENGTH..MAX_LENGTH || typed.any { !it.isLetter() } || limit <= 0) return emptyList()
        return edits(typed, alphabet).mapNotNull { (variant, cost) ->
            rank(variant)?.takeIf { it.second <= maxRank }?.let { (word, r) -> word to score(r, cost) }
        }.groupBy { it.first.lowercase() }.map { (_, same) -> same.minBy { it.second } }
            .sortedBy { it.second }.take(limit).map { it.first }
    }
}
