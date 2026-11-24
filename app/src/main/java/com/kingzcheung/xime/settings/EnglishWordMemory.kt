package com.kingzcheung.xime.settings

import java.io.File

/**
 * English words the user typed by hand in Chinese 26-key input (letters committed with Enter, e.g. Obsidian),
 * kept as typed so lua/cyime_en_memory.lua offers them again even when no dictionary has them.
 * One word per line in the Rime user directory, newest last; commits append, duplicates are compacted away.
 */
object EnglishWordMemory {
    const val FILE_NAME = "cyime_en_memory.txt"
    private const val MAX_WORDS = 2000
    private val WORD = Regex("[A-Za-z]{2,40}")

    /** A word with a capital, or of four letters and more; raw lowercase ab/abc is mostly a jianpin slip. */
    internal fun isLearnable(text: String): Boolean =
        WORD.matches(text) && (text.any { it.isUpperCase() } || text.length >= 4)

    @Synchronized
    fun remember(rimeDir: File, text: String) {
        if (!isLearnable(text)) return
        val file = File(rimeDir, FILE_NAME)
        runCatching {
            val lines = if (file.exists()) file.readLines() else emptyList()
            if (lines.lastOrNull() == text) return
            if (lines.size >= MAX_WORDS * 2) write(file, compact(lines + text)) else file.appendText("$text\n")
        }
    }

    /** Cached file contents, keyed by path, size and modification time. */
    @Volatile private var cache: Triple<String, Long, List<String>>? = null

    /**
     * English direct input: remembered words spelled with a capital after the first letter (HCl, NaOH, iPhone)
     * that start with [typed] (two letters and more), newest first. Plain lowercase words, which Chinese 26-key
     * also remembers from raw pinyin on Enter, are left out.
     */
    fun mixedCaseCompletions(rimeDir: File, typed: String, limit: Int = 2): List<String> {
        if (typed.length < 2 || limit <= 0) return emptyList()
        val file = File(rimeDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val key = file.length() * 31 + file.lastModified()
        val words = cache?.takeIf { it.first == file.path && it.second == key }?.third
            ?: runCatching { compact(file.readLines()).asReversed() }.getOrDefault(emptyList())
                .also { cache = Triple(file.path, key, it) }
        return words.asSequence()
            .filter { it.startsWith(typed, ignoreCase = true) && it.drop(1).any(Char::isUpperCase) }
            .take(limit).toList()
    }

    /** Long-press delete forgets the word in every spelling; false when it was never remembered. */
    @Synchronized
    fun forget(rimeDir: File, text: String): Boolean {
        val file = File(rimeDir, FILE_NAME)
        if (!WORD.matches(text) || !file.exists()) return false
        return runCatching {
            val lines = file.readLines()
            val kept = lines.filterNot { it.trim().equals(text, ignoreCase = true) }
            if (kept.size == lines.size) return false
            write(file, compact(kept))
            true
        }.getOrDefault(false)
    }

    /** Latest spelling of each word last, at most [MAX_WORDS]. */
    internal fun compact(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { WORD.matches(it) }.asReversed().distinct().take(MAX_WORDS).asReversed()

    private fun write(file: File, words: List<String>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(words.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
