package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.glide.EnglishContractions
import com.kingzcheung.xime.settings.LetterLanguage
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 英文与丹麦语（等字母语言）的下一词联想（Bigram Next-Word Prediction）。
 * 在用户按空格结束一个单词、或点选补全候选上屏后，根据刚完成的词给出高频后续词。
 */
internal object WordBigramPredictor {
    private const val TAG = "WordBigramPredictor"
    private const val ENGLISH_BIGRAMS_ASSET = "english_bigrams.txt"
    private const val ENGLISH_CONTRACTIONS_ASSET = "english_contractions.txt"
    private const val DANISH_BIGRAMS_ASSET = "danish_bigrams.txt"
    internal const val USER_BIGRAMS_FILE = "letter_user_bigrams.tsv"
    private const val MAX_NEXT_PER_WORD = 12
    private const val MAX_USER_NEXT_PER_WORD = 8
    private const val MAX_USER_KEYS = 512

    private val mutex = Mutex()
    private val userFileMutex = Any()
    private val ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WordBigramIO").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile private var englishBigrams: Map<String, String>? = null
    @Volatile private var englishContractions: EnglishContractions? = null
    @Volatile private var danishBigrams: Map<String, String>? = null
    @Volatile private var lastSaveJob: Job? = null
    private val userLoaded = AtomicBoolean(false)

    /** 用户连续输入的双词记忆：key 为 "$langKey:$prevLower"，value 为最近紧跟其后的词列表。 */
    private val userBigrams = ConcurrentHashMap<String, MutableList<String>>()

    private val englishFallback = listOf("the", "to", "and", "of", "in", "is", "for", "that", "with", "on")
    private val danishFallback = listOf("og", "i", "på", "til", "med", "for", "at", "er", "ikke", "det")

    /** 英文高频口语搭配微调（补充字幕语料中排位靠后的常见词，如 I -> am）。 */
    private val englishCuratedBoosts = mapOf(
        "i" to listOf("am", "have", "will", "don't", "can", "think", "know", "want", "was", "would"),
        "im" to listOf("not", "going", "sorry", "in", "on", "here", "just", "sure", "ready", "happy"),
        "you" to listOf("are", "can", "have", "want", "know", "need", "will", "do", "don't", "should"),
        "we" to listOf("are", "have", "can", "will", "need", "should", "were", "do", "don't", "want"),
        "they" to listOf("are", "have", "were", "can", "will", "don't", "want", "need", "do"),
        "he" to listOf("is", "was", "has", "said", "can", "will", "would", "didn't"),
        "she" to listOf("is", "was", "has", "said", "can", "will", "would", "didn't"),
        "it" to listOf("is", "was", "will", "can", "would", "has", "looks", "works"),
        "thank" to listOf("you", "goodness", "god"),
        "thanks" to listOf("for", "to", "again", "a"),
        "good" to listOf("morning", "night", "luck", "idea", "afternoon", "evening", "job", "to", "for"),
        "how" to listOf("are", "much", "many", "to", "do", "can", "long", "about", "did"),
        "what" to listOf("is", "are", "do", "about", "happened", "if", "time", "can"),
        "where" to listOf("are", "is", "do", "can", "did", "to"),
        "when" to listOf("you", "I", "we", "is", "are", "it", "did"),
        "why" to listOf("are", "is", "do", "did", "not", "don't", "would"),
    )

    private val exactProfaneEnglishWords = hashSetOf(
        "arse", "arsehole", "arseholes", "ass", "asses", "asshole", "assholes",
        "asswipe", "asswipes", "bastard", "bastards", "bitch", "bitches", "bitching",
        "bitchy", "blowjob", "blowjobs", "boner", "boob", "boobs", "bullshit",
        "chink", "chinks", "clit", "cock", "cocks", "cockhead", "cockheads",
        "cocksucker", "cocksuckers", "cocksucking", "crap", "cum", "cumming", "cunt",
        "cunts", "dick", "dicks", "dickhead", "dickheads", "dildo", "dildos",
        "douche", "douchebag", "douchebags", "dumbass", "dumbasses", "fag", "fags",
        "faggot", "faggots", "fatass", "fuck", "fucked", "fucker", "fuckers",
        "fucking", "fucks", "fuckup", "fuckups", "handjob", "handjobs", "jackass",
        "jackasses", "jerkoff", "jerkoffs", "jizz", "kike", "kikes", "motherfucker",
        "motherfuckers", "motherfucking", "nigga", "niggas", "nigger", "niggers",
        "piss", "pissed", "pisses", "pissing", "porn", "prick", "pricks", "pussies",
        "pussy", "retard", "retarded", "retards", "shit", "shits", "shitted",
        "shitting", "shitty", "slut", "sluts", "smartass", "spic", "spics", "tits",
        "titties", "twat", "twats", "wank", "wanker", "wankers", "wetback",
        "wetbacks", "whore", "whores", "whoring",
    )

    private val profaneStemRegex = Regex(
        "^(fuck|shit|bitch|cunt|asshole|asswipe|motherfuck|nigg|faggot|cocksuck|dickhead|douchebag|twat|whore|slut|wank).*"
    )

    internal fun isProfaneEnglishWord(word: String): Boolean {
        val cleaned = word.trim { !it.isLetterOrDigit() }
            .lowercase()
            .replace("'", "")
            .replace("’", "")
        if (cleaned.isEmpty()) return false
        return cleaned in exactProfaneEnglishWords || profaneStemRegex.matches(cleaned)
    }

    fun normalizeEnglishKey(word: String): String =
        word.trim().lowercase().replace("'", "").replace("’", "")

    fun normalizeDanishKey(word: String): String =
        word.trim().lowercase()

    fun recordWordPair(
        previousWord: String,
        nextWord: String,
        isDanish: Boolean,
        context: Context? = null,
    ) {
        if (context != null) ensureUserBigramsLoaded(context)
        val prev = if (isDanish) normalizeDanishKey(previousWord) else normalizeEnglishKey(previousWord)
        val cleanedNext = nextWord.trim()
        if (prev.isEmpty() || cleanedNext.isEmpty() || prev == cleanedNext.lowercase()) return
        if (!isDanish && (isProfaneEnglishWord(prev) || isProfaneEnglishWord(cleanedNext))) return
        val key = "${if (isDanish) "da" else "en"}:$prev"
        userBigrams.compute(key) { _, existing ->
            val list = existing ?: mutableListOf()
            synchronized(list) {
                list.removeAll { it.equals(cleanedNext, ignoreCase = true) }
                list.add(0, cleanedNext)
                if (list.size > MAX_USER_NEXT_PER_WORD) {
                    list.subList(MAX_USER_NEXT_PER_WORD, list.size).clear()
                }
            }
            list
        }
        trimUserBigramsIfNeeded()
        context?.filesDir?.let { scheduleSaveUserBigrams(it) }
    }

    suspend fun predictNextWords(
        context: Context,
        previousWord: String,
        isAsciiMode: Boolean,
        letterLanguage: LetterLanguage?,
        limit: Int = 20,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val trimmed = previousWord.trim()
        if (trimmed.isEmpty() || !trimmed.any { it.isLetter() }) return emptyList()
        ensureUserBigramsLoaded(context)
        return when {
            !isAsciiMode && letterLanguage == LetterLanguage.DANISH -> {
                val map = ensureDanishLoaded(context)
                val key = normalizeDanishKey(trimmed)
                val learned = userBigrams["da:$key"]?.let { synchronized(it) { it.toList() } }.orEmpty()
                val fromCorpus = map[key]?.split(' ').orEmpty()
                val base = if (fromCorpus.isNotEmpty() || key.length >= 2) {
                    fromCorpus + danishFallback
                } else {
                    emptyList()
                }
                (learned + base)
                    .distinctBy { it.lowercase() }
                    .filter { !it.equals(trimmed, ignoreCase = true) }
                    .take(limit)
            }
            isAsciiMode || letterLanguage == null -> {
                val (map, contractions) = ensureEnglishLoaded(context)
                val key = normalizeEnglishKey(trimmed)
                val learned = userBigrams["en:$key"]?.let { synchronized(it) { it.toList() } }.orEmpty()
                val boosted = englishCuratedBoosts[key].orEmpty()
                val fromCorpus = map[key]?.split(' ')
                    .orEmpty()
                    .map { w -> formatEnglishNextWord(w, contractions) }
                val base = if (boosted.isNotEmpty() || fromCorpus.isNotEmpty() || key.length >= 2) {
                    boosted + fromCorpus + englishFallback
                } else {
                    emptyList()
                }
                (learned + base)
                    .asSequence()
                    .filter { !isProfaneEnglishWord(it) }
                    .distinctBy { it.lowercase() }
                    .filter { !it.equals(trimmed, ignoreCase = true) }
                    .take(limit)
                    .toList()
            }
            else -> emptyList()
        }
    }

    internal fun formatEnglishNextWord(raw: String, contractions: EnglishContractions?): String {
        if (raw == "i") return "I"
        val displayed = contractions?.display(raw)
        if (displayed != null && displayed != raw) return displayed
        return raw
    }

    /**
     * 流式解析按首词分组的 `english_bigrams.txt`，每个首词仅用固定长度数组维护最优 12 个候选，
     * 并将结果压缩为空格分隔字符串 `Map<String, String>`，避免百万级 `Pair` 与数万个 `ArrayList` 堆分配。
     */
    internal fun parseEnglishBigramsStream(reader: BufferedReader): Map<String, String> {
        val result = HashMap<String, String>(16384)
        val topCosts = IntArray(MAX_NEXT_PER_WORD)
        val topWords = arrayOfNulls<String>(MAX_NEXT_PER_WORD)
        var currentW1: String? = null
        var topCount = 0

        fun insertCandidate(word: String, cost: Int) {
            for (i in 0 until topCount) {
                if (topWords[i] == word) {
                    if (cost < topCosts[i]) {
                        topCosts[i] = cost
                        var pos = i
                        while (pos > 0 && topCosts[pos] < topCosts[pos - 1]) {
                            val tc = topCosts[pos - 1]
                            val tw = topWords[pos - 1]
                            topCosts[pos - 1] = topCosts[pos]
                            topWords[pos - 1] = topWords[pos]
                            topCosts[pos] = tc
                            topWords[pos] = tw
                            pos--
                        }
                    }
                    return
                }
            }
            if (topCount == MAX_NEXT_PER_WORD && cost >= topCosts[topCount - 1]) return
            val insertLimit = minOf(topCount, MAX_NEXT_PER_WORD - 1)
            var idx = insertLimit
            while (idx > 0 && cost < topCosts[idx - 1]) {
                if (idx < MAX_NEXT_PER_WORD) {
                    topCosts[idx] = topCosts[idx - 1]
                    topWords[idx] = topWords[idx - 1]
                }
                idx--
            }
            topCosts[idx] = cost
            topWords[idx] = word
            if (topCount < MAX_NEXT_PER_WORD) topCount++
        }

        fun flushCurrent() {
            val w1 = currentW1 ?: return
            if (topCount <= 0) return
            val existing = result[w1]
            if (existing == null) {
                val sb = StringBuilder(topCount * 6)
                for (i in 0 until topCount) {
                    if (i > 0) sb.append(' ')
                    sb.append(topWords[i])
                }
                result[w1] = sb.toString()
            } else {
                // 仅在输入流未按 w1 完全连续分组时（如人工构造的小测试数据）兜底合并去重。
                val merged = LinkedHashSet<String>(MAX_NEXT_PER_WORD)
                existing.split(' ').forEach { if (it.isNotEmpty()) merged.add(it) }
                for (i in 0 until topCount) {
                    if (merged.size >= MAX_NEXT_PER_WORD) break
                    topWords[i]?.let { merged.add(it) }
                }
                result[w1] = merged.joinToString(" ")
            }
            topCount = 0
        }

        reader.forEachLine { line ->
            val firstSpace = line.indexOf(' ')
            if (firstSpace <= 0) return@forEachLine
            val secondSpace = line.indexOf(' ', firstSpace + 1)
            if (secondSpace <= firstSpace + 1) return@forEachLine
            val w1 = line.substring(0, firstSpace)
            val w2 = line.substring(firstSpace + 1, secondSpace)
            if (isProfaneEnglishWord(w2)) return@forEachLine
            val cost = line.substring(secondSpace + 1).trim().toIntOrNull() ?: return@forEachLine
            if (w1 != currentW1) {
                flushCurrent()
                currentW1 = w1
            }
            insertCandidate(w2, cost)
        }
        flushCurrent()
        return result
    }

    internal fun parseDanishBigramsStream(reader: BufferedReader): Map<String, String> {
        val result = HashMap<String, String>(8192)
        reader.forEachLine { line ->
            val trimmed = line.trim()
            val firstSpace = trimmed.indexOf(' ')
            if (firstSpace <= 0 || firstSpace >= trimmed.length - 1) return@forEachLine
            val w1 = trimmed.substring(0, firstSpace).lowercase()
            val rest = trimmed.substring(firstSpace + 1).trim()
            if (rest.isEmpty()) return@forEachLine
            val words = rest.split(' ').filter { it.isNotBlank() }.take(MAX_NEXT_PER_WORD)
            if (words.isNotEmpty()) {
                result[w1] = words.joinToString(" ")
            }
        }
        return result
    }

    private fun ensureUserBigramsLoaded(context: Context) {
        if (userLoaded.get()) return
        val filesDir = runCatching { context.filesDir }.getOrNull() ?: return
        synchronized(userFileMutex) {
            if (userLoaded.get()) return
            val file = File(filesDir, USER_BIGRAMS_FILE)
            if (file.isFile) {
                runCatching {
                    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val parts = line.split('\t')
                            if (parts.size < 3) return@forEach
                            val lang = parts[0]
                            val prev = parts[1]
                            if (lang != "en" && lang != "da") return@forEach
                            if (prev.isBlank()) return@forEach
                            val nextWords = parts.drop(2)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() && (lang != "en" || !isProfaneEnglishWord(it)) }
                                .take(MAX_USER_NEXT_PER_WORD)
                            if (nextWords.isNotEmpty()) {
                                userBigrams["$lang:$prev"] = nextWords.toMutableList()
                            }
                        }
                    }
                }.onFailure { Log.e(TAG, "Failed to load $USER_BIGRAMS_FILE", it) }
            }
            userLoaded.set(true)
        }
    }

    private fun trimUserBigramsIfNeeded() {
        while (userBigrams.size > MAX_USER_KEYS) {
            val firstKey = userBigrams.keys.firstOrNull() ?: break
            userBigrams.remove(firstKey)
        }
    }

    private fun scheduleSaveUserBigrams(filesDir: File) {
        val snapshot = userBigrams.entries.mapNotNull { (key, list) ->
            val sep = key.indexOf(':')
            if (sep <= 0) return@mapNotNull null
            val lang = key.substring(0, sep)
            val prev = key.substring(sep + 1)
            val words = synchronized(list) { list.toList() }
            if (words.isEmpty()) null else Triple(lang, prev, words)
        }
        lastSaveJob = ioScope.launch {
            synchronized(userFileMutex) {
                runCatching {
                    if (!filesDir.exists()) filesDir.mkdirs()
                    val target = File(filesDir, USER_BIGRAMS_FILE)
                    val tmp = File(filesDir, "$USER_BIGRAMS_FILE.tmp")
                    tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                        for ((lang, prev, words) in snapshot) {
                            writer.write(lang)
                            writer.write("\t")
                            writer.write(prev)
                            for (w in words) {
                                writer.write("\t")
                                writer.write(w)
                            }
                            writer.newLine()
                        }
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }.onFailure { Log.e(TAG, "Failed to save $USER_BIGRAMS_FILE", it) }
            }
        }
    }

    private suspend fun ensureEnglishLoaded(context: Context): Pair<Map<String, String>, EnglishContractions?> {
        englishBigrams?.let { return it to englishContractions }
        return mutex.withLock {
            englishBigrams?.let { return@withLock it to englishContractions }
            withContext(Dispatchers.IO) {
                val contractions = runCatching {
                    context.assets.open(ENGLISH_CONTRACTIONS_ASSET).bufferedReader().useLines { lines ->
                        EnglishContractions(lines.toList().asSequence())
                    }
                }.getOrNull()
                val bigrams = runCatching {
                    context.assets.open(ENGLISH_BIGRAMS_ASSET).bufferedReader().use { reader ->
                        parseEnglishBigramsStream(reader)
                    }
                }.onFailure { Log.e(TAG, "Failed to load $ENGLISH_BIGRAMS_ASSET", it) }.getOrDefault(emptyMap())
                englishContractions = contractions
                if (bigrams.isNotEmpty()) englishBigrams = bigrams
                bigrams to contractions
            }
        }
    }

    private suspend fun ensureDanishLoaded(context: Context): Map<String, String> {
        danishBigrams?.let { return it }
        return mutex.withLock {
            danishBigrams?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val bigrams = runCatching {
                    context.assets.open(DANISH_BIGRAMS_ASSET).bufferedReader().use { reader ->
                        parseDanishBigramsStream(reader)
                    }
                }.onFailure { Log.e(TAG, "Failed to load $DANISH_BIGRAMS_ASSET", it) }.getOrDefault(emptyMap())
                if (bigrams.isNotEmpty()) danishBigrams = bigrams
                bigrams
            }
        }
    }

    internal suspend fun flushUserBigramsForTest() {
        lastSaveJob?.join()
    }

    internal fun clearForTest() {
        englishBigrams = null
        englishContractions = null
        danishBigrams = null
        userLoaded.set(false)
        userBigrams.clear()
    }
}
