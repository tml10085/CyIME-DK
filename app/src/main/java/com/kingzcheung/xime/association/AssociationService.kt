package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AssociationService {
    private const val TAG = "AssociationService"
    private const val CONTRACTIONS_FILE = "english_contractions.txt"
    private const val ENGLISH_LETTERS = "abcdefghijklmnopqrstuvwxyz"
    /** 10 万词的英文词表（tools/wordlists/build_words.py en），补常用词表里没有的词：stoichiometry、pH、DNA。 */
    private const val WORDS_FILE = "cyime-words-en-v1.txt"
    
    @Volatile private var trieEngine: TrieAssociationEngine? = null
    /** 英文补全用的缩写表（im → I'm）；读不到就不给缩写，补全照常。 */
    @Volatile private var contractions: com.kingzcheung.xime.glide.EnglishContractions? = null
    /** 英文大词表；读不到就只用常用词表。 */
    @Volatile private var lexicon: WordLexicon? = null
    @Volatile private var isInitialized = false
    private val initializationMutex = Mutex()
    
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            return@withContext true
        }
        
        initializationMutex.withLock {
            if (isInitialized) return@withLock true

            try {
                val engine = TrieAssociationEngine.getInstance()
                val trieInit = engine.initialize(context)
                if (trieInit) trieEngine = engine
                if (trieInit) contractions = runCatching {
                    context.assets.open(CONTRACTIONS_FILE).bufferedReader()
                        .useLines { com.kingzcheung.xime.glide.EnglishContractions(it.toList().asSequence()) }
                }.getOrNull()
                if (trieInit) lexicon = runCatching {
                    context.assets.open(WORDS_FILE).bufferedReader().useLines { lines ->
                        WordLexicon(lines.map(String::trim)
                            .filter { it.isNotEmpty() && !WordBigramPredictor.isProfaneEnglishWord(it) }.toList())
                    }
                }.onFailure { Log.w(TAG, "English word list could not load", it) }.getOrNull()
                isInitialized = trieInit
                Log.i(TAG, "Association service initialized: trie=$trieInit")
                trieInit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize association service", e)
                false
            }
        }
    }
    
    suspend fun getAssociations(
        context: Context,
        inputText: String,
        isAsciiMode: Boolean,
        topK: Int = 5
    ): List<String> = withContext(Dispatchers.Default) {
        if (inputText.isEmpty()) {
            return@withContext emptyList()
        }
        
        try {
            if (isAsciiMode) {
                getEnglishAssociations(context, inputText, topK)
            } else {
                getChineseAssociations(context, inputText, topK).map { it.text }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get associations", e)
            emptyList()
        }
    }
    
    private suspend fun getEnglishAssociations(
        context: Context,
        prefix: String,
        topK: Int
    ): List<String> {
        // Load the English dictionary on its first request, shared by concurrent keystrokes.
        if (topK <= 0) return emptyList()
        val scored = if (initialize(context)) trieEngine?.predict(prefix, topK).orEmpty() else emptyList()
        val imported = withContext(Dispatchers.IO) {
            try {
                com.kingzcheung.xime.settings.CellDictionaryManager.store(context).englishCompletions(prefix, topK)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Cannot read imported English words", e)
                emptyList()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid imported dictionary state", e)
                emptyList()
            }
        }
        // 常用词表排前，大词表补后面：打 stoi 也能出 stoichiometry。第二个字母起有大写的词整词打出时排第一（ph → pH、dna → DNA）。
        // 打的不是词时给只差一处的常用词：teh → the、helo → hello（[TypoCorrection]）；常用词表补全出了词时不纠正。
        val correct = scored.isEmpty() && imported.isEmpty()
        val more = lexicon?.complete(prefix, topK, correct).orEmpty()
        val bundled = when {
            !correct || more.isNotEmpty() -> (more.filter { it.equals(prefix, ignoreCase = true) && it.drop(1).any(Char::isUpperCase) } + scored.map { it.text } + more)
                .distinctBy { it.lowercase() }.take(topK)
            else -> trieEngine?.let { engine ->
                TypoCorrection.corrections(prefix.lowercase(), ENGLISH_LETTERS, completes = false, rank = engine::rankOf)
                    .map { WordLexicon.cased(it, prefix) }
            }.orEmpty()
        }
        val words = if (imported.isEmpty()) bundled else
            (bundled.take(1) + imported + bundled.drop(1)).distinctBy { it.lowercase() }.take(topK)
        val table = contractions ?: return words
        return EnglishContractionCompletion.apply(prefix, words, topK) { word ->
            table.display(word).takeIf { it != word } ?: table.offer(word)
        }
    }
    
    private suspend fun getChineseAssociations(
        context: Context,
        inputText: String,
        topK: Int
    ): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        try {
            if (!AssociationManager.isInitialized()) {
                val initSuccess = withContext(Dispatchers.IO) {
                    AssociationManager.initialize(context)
                }
                if (!initSuccess) {
                    Log.e(TAG, "AssociationManager initialization failed")
                    return@withContext emptyList()
                }
            }
            
            val candidates = AssociationManager.predict(inputText, topK)
            
            candidates
        } catch (e: Exception) {
            Log.e(TAG, "Chinese associations failed", e)
            emptyList()
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun isTrieInitialized(): Boolean = trieEngine?.isInitialized() ?: false
    
    fun release() {
        trieEngine?.release()
        trieEngine = null
        contractions = null
        lexicon = null
        isInitialized = false
    }
}
