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
    
    @Volatile private var trieEngine: TrieAssociationEngine? = null
    /** 英文补全用的缩写表（im → I'm）；读不到就不给缩写，补全照常。 */
    @Volatile private var contractions: com.kingzcheung.xime.glide.EnglishContractions? = null
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
        // 词表和导入词库都补全不出任何词时（打的也就不是词），给只差一处的常用词：teh → the、helo → hello（[TypoCorrection]）。
        val bundled = if (scored.isNotEmpty() || imported.isNotEmpty()) scored.map { it.text }
            else trieEngine?.let { engine ->
                TypoCorrection.corrections(prefix.lowercase(), ENGLISH_LETTERS, completes = false, rank = engine::rankOf)
                    .map { WordLexicon.cased(it, prefix) }
            }.orEmpty()
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
        isInitialized = false
    }
}
