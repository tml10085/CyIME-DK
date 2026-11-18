package com.kingzcheung.xime.association

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.kingzcheung.xime.service.PredictionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EnglishAssociationStartupTest {
    @Test fun suggestionsPreserveTypedCaseAndKeepTheSameRanking() = runBlocking {
        val app = context { bundledTrie().inputStream() }
        suspend fun words(prefix: String) = AssociationService.getAssociations(app, prefix, true, 6)
        val lower = words("he")
        assertTrue(lower.any { it.length > 2 })
        assertEquals(lower.map { it.replaceFirstChar(Char::uppercaseChar) }, words("He"))
        assertEquals(lower.map { it.uppercase() }, words("HE"))
        assertEquals(lower.map { "hE" + it.drop(2) }, words("hE"))
        assertEquals(words("h").map { it.replaceFirstChar(Char::uppercaseChar) }, words("H"))
    }
    @Test fun typedSpellingWithoutApostropheOffersTheContraction() = runBlocking {
        val trie = bundledTrie()
        val table = listOf(
            File("src/main/assets/english_contractions.txt"),
            File("app/src/main/assets/english_contractions.txt")
        ).first { it.isFile }.readBytes()
        val assets = mock<AssetManager>()
        whenever(assets.list("")).thenReturn(arrayOf("english_trie.bin"))
        whenever(assets.open("english_trie.bin")).thenAnswer { trie.inputStream() }
        whenever(assets.open("english_contractions.txt")).thenAnswer { table.inputStream() }
        val app = mock<Context>().also { whenever(it.assets).thenReturn(assets) }
        suspend fun words(prefix: String) = AssociationService.getAssociations(app, prefix, true, 20)
        assertEquals("I'm", words("im").first())
        assertEquals("what's", words("whats").first())
        assertEquals("Don't", words("Dont").first())
        // its 本身是词：候选栏第一个仍是打的 its，缩写排第二，补全里不再重复 its。
        assertEquals("it's", words("its").first())
        assertFalse("its" in words("its"))
        assertEquals("help", words("hel").first())
    }

    @Test fun misspelledWordsOfferTheCommonWord() = runBlocking {
        val app = context { bundledTrie().inputStream() }
        suspend fun words(prefix: String) = AssociationService.getAssociations(app, prefix, true, 6)
        assertEquals("the", words("teh").first())
        assertEquals("The", words("Teh").first())
        assertEquals("THE", words("TEH").first())
        assertEquals("their", words("thier").first())
        assertEquals("because", words("becuase").first())
        assertTrue("receive" in words("recieve"))
        assertTrue("hello" in words("helo"))
        // 还没打完的词照旧先出补全，不夹 he、her。
        assertEquals("help", words("hel").first())
        assertFalse("he" in words("hel"))
    }

    @Before fun resetBefore() = AssociationService.release()
    @After fun resetAfter() = AssociationService.release()

    private fun bundledTrie(): ByteArray = listOf(
        File("src/main/assets/english_trie.bin"),
        File("app/src/main/assets/english_trie.bin")
    ).first { it.isFile }.readBytes()

    private fun bigWords(): ByteArray = listOf(
        File("src/main/assets/cyime-words-en-v1.txt"),
        File("app/src/main/assets/cyime-words-en-v1.txt")
    ).first { it.isFile }.readBytes()

    @Test fun bigWordListCompletesRareWordsAfterTheCommonOnes() = runBlocking {
        val app = context { bundledTrie().inputStream() }
        suspend fun words(prefix: String) = AssociationService.getAssociations(app, prefix, true, 6)
        assertTrue("stoichiometry" in words("stoich"))
        assertTrue("oxalic" in words("oxali"))
        // 常用词表的补全仍排在前面。
        assertEquals("help", words("hel").first())
        // 只有大写写法的词整词打出时排第一。
        assertEquals("pH", words("ph").first())
        assertEquals("DNA", words("dna").first())
    }

    private fun context(open: () -> InputStream): Context {
        val assets = mock<AssetManager>()
        whenever(assets.list("")).thenReturn(arrayOf("english_trie.bin"))
        whenever(assets.open("english_trie.bin")).thenAnswer { open() }
        whenever(assets.open("cyime-words-en-v1.txt")).thenAnswer { bigWords().inputStream() }
        return mock<Context>().also { whenever(it.assets).thenReturn(assets) }
    }

    @Test fun firstEnglishRequestLoadsBundledWordsWithoutPriorWarmUp() = runBlocking {
        val caller = Thread.currentThread()
        val bytes = bundledTrie()
        val context = context {
            assertNotSame("Asset loading must not block the calling thread", caller, Thread.currentThread())
            bytes.inputStream()
        }

        val words = AssociationService.getAssociations(context, "hel", isAsciiMode = true)

        assertEquals("help", words.first())
        assertTrue("Built-in word source should provide hello", "hello" in words)
    }

    @Test fun keyboardStartupDoesNotOpenEnglishDictionaryUntilTheFirstCompletion() = runBlocking {
        val bytes = bundledTrie()
        val opens = AtomicInteger()
        val app = context { opens.incrementAndGet(); bytes.inputStream() }
        whenever(app.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)).thenReturn(mock<SharedPreferences>())
        val job = Job()
        try {
            PredictionManager(app, CoroutineScope(job), {}).initialize()
            job.children.forEach { it.join() }
            assertFalse(AssociationService.isTrieInitialized())
            assertEquals(0, opens.get())
            assertEquals("help", AssociationService.getAssociations(app, "hel", true).first())
            assertEquals(1, opens.get())
        } finally {
            job.cancel()
            com.kingzcheung.xime.plugin.ExtensionManager.release()
        }
    }

    @Test fun simultaneousFirstWordsShareASingleDictionaryLoad() = runBlocking {
        val bytes = bundledTrie()
        val opens = AtomicInteger()
        val enteredLoad = CountDownLatch(1)
        val finishLoad = CountDownLatch(1)
        val enteredRequests = CountDownLatch(8)
        val context = context {
            opens.incrementAndGet()
            enteredLoad.countDown()
            check(finishLoad.await(5, TimeUnit.SECONDS)) { "Timed out waiting to finish test asset load" }
            bytes.inputStream()
        }
        val first = async(Dispatchers.Default) { AssociationService.getAssociations(context, "hel", true) }
        try {
            assertTrue("First request should begin loading", enteredLoad.await(5, TimeUnit.SECONDS))
            val requests = List(8) {
                async(Dispatchers.Default) {
                    enteredRequests.countDown()
                    AssociationService.getAssociations(context, "hel", isAsciiMode = true)
                }
            }
            assertTrue("First-word requests should overlap loading", enteredRequests.await(5, TimeUnit.SECONDS))
            finishLoad.countDown()
            assertEquals("help", first.await().first())
            requests.awaitAll().forEach { words ->
                assertEquals("First keystroke must not disappear during warm-up", "help", words.first())
                assertTrue("hello" in words)
            }
            assertEquals("Concurrent requests should load the dictionary once", 1, opens.get())
        } finally {
            finishLoad.countDown()
        }
    }

    @Test fun failedInitialLoadCanRecoverOnTheNextEnglishRequest() = runBlocking {
        val unavailable = context { throw IOException("Temporarily unavailable assets") }
        assertTrue(AssociationService.getAssociations(unavailable, "hel", isAsciiMode = true).isEmpty())
        assertFalse(AssociationService.isInitialized())

        val bytes = bundledTrie()
        val available = context { bytes.inputStream() }
        val words = AssociationService.getAssociations(available, "hel", isAsciiMode = true)

        assertEquals("help", words.first())
        assertTrue("hello" in words)
        assertTrue(AssociationService.isTrieInitialized())
    }
}
