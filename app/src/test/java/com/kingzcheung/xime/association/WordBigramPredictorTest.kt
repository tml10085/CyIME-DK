package com.kingzcheung.xime.association

import android.content.Context
import android.content.res.AssetManager
import com.kingzcheung.xime.settings.LetterLanguage
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WordBigramPredictorTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Before fun resetBefore() = WordBigramPredictor.clearForTest()
    @After fun resetAfter() = WordBigramPredictor.clearForTest()

    private fun assetFile(name: String): ByteArray = listOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name")
    ).first { it.isFile }.readBytes()

    private fun mockContext(filesDir: File? = null): Context {
        val enBigrams = assetFile("english_bigrams.txt")
        val enContractions = assetFile("english_contractions.txt")
        val daBigrams = assetFile("danish_bigrams.txt")
        val assets = mock<AssetManager>()
        whenever(assets.open("english_bigrams.txt")).thenAnswer { enBigrams.inputStream() }
        whenever(assets.open("english_contractions.txt")).thenAnswer { enContractions.inputStream() }
        whenever(assets.open("danish_bigrams.txt")).thenAnswer { daBigrams.inputStream() }
        return mock<Context>().also {
            whenever(it.assets).thenReturn(assets)
            whenever(it.filesDir).thenReturn(filesDir)
        }
    }

    @Test fun `english next-word prediction returns natural continuations and contractions`() = runBlocking {
        val ctx = mockContext()
        val afterI = WordBigramPredictor.predictNextWords(ctx, "I", isAsciiMode = true, letterLanguage = null, limit = 10)
        assertEquals("am", afterI.first())
        assertTrue("have" in afterI)
        assertTrue("don't" in afterI)

        val afterThank = WordBigramPredictor.predictNextWords(ctx, "thank", isAsciiMode = true, letterLanguage = null, limit = 5)
        assertEquals("you", afterThank.first())

        val afterIm = WordBigramPredictor.predictNextWords(ctx, "I'm", isAsciiMode = true, letterLanguage = null, limit = 5)
        assertTrue("not" in afterIm)
        assertTrue("going" in afterIm)
    }

    @Test fun `english next-word prediction filters profanity without blocking innocent words`() = runBlocking {
        assertFalse(WordBigramPredictor.isProfaneEnglishWord("spice"))
        assertFalse(WordBigramPredictor.isProfaneEnglishWord("cocktail"))
        assertFalse(WordBigramPredictor.isProfaneEnglishWord("class"))
        assertTrue(WordBigramPredictor.isProfaneEnglishWord("asshole"))
        assertTrue(WordBigramPredictor.isProfaneEnglishWord("fuck"))
        assertTrue(WordBigramPredictor.isProfaneEnglishWord("fucking"))

        val ctx = mockContext()
        val blocked = setOf("asshole", "assholes", "fuck", "fucking", "fucked", "shit", "bitch", "cunt", "dick")
        for (probe in listOf("a", "an", "the", "you", "my")) {
            val preds = WordBigramPredictor.predictNextWords(ctx, probe, isAsciiMode = true, letterLanguage = null, limit = 20)
            assertTrue("Expected non-empty predictions for '$probe'", preds.isNotEmpty())
            for (word in preds) {
                assertFalse("Profane word '$word' leaked for '$probe'", word.lowercase() in blocked)
            }
        }
    }

    @Test fun `streaming english parser ranks top candidates and drops profanity`() {
        val raw = """
            a asshole 10
            a good 300
            a great 200
            a fucking 50
            a spice 250
            b book 400
            a nice 150
        """.trimIndent()
        val parsed = WordBigramPredictor.parseEnglishBigramsStream(BufferedReader(StringReader(raw)))
        assertEquals(listOf("great", "spice", "good", "nice"), parsed["a"]?.split(' '))
        assertEquals(listOf("book"), parsed["b"]?.split(' '))
    }

    @Test fun `danish next-word prediction returns natural continuations and pads sparse entries`() = runBlocking {
        val ctx = mockContext()
        val afterJeg = WordBigramPredictor.predictNextWords(ctx, "jeg", isAsciiMode = false, letterLanguage = LetterLanguage.DANISH, limit = 10)
        assertEquals("er", afterJeg.first())
        assertTrue("har" in afterJeg)
        assertTrue("kan" in afterJeg)

        val afterMange = WordBigramPredictor.predictNextWords(ctx, "mange", isAsciiMode = false, letterLanguage = LetterLanguage.DANISH, limit = 5)
        assertEquals("tak", afterMange.first())

        val afterI = WordBigramPredictor.predictNextWords(ctx, "i", isAsciiMode = false, letterLanguage = LetterLanguage.DANISH, limit = 10)
        assertTrue("dag" in afterI)
        assertTrue("Danmark" in afterI)
        assertTrue("København" in afterI)

        // Sparse Danish entries from corpus should keep specific word(s) first and pad with common connectives.
        val sparseLine = File("src/main/assets/danish_bigrams.txt")
            .takeIf { it.isFile } ?: File("app/src/main/assets/danish_bigrams.txt")
        val singleEntry = sparseLine.useLines { lines ->
            lines.map { it.trim().split(' ') }.first { it.size == 2 && it[0].length >= 2 }
        }
        val preds = WordBigramPredictor.predictNextWords(
            ctx,
            singleEntry[0],
            isAsciiMode = false,
            letterLanguage = LetterLanguage.DANISH,
            limit = 10,
        )
        assertEquals(singleEntry[1], preds.first())
        assertTrue("Expected sparse Danish entry to be padded", preds.size >= 6)
    }

    @Test fun `user learned word pairs persist across process restarts and skip profanity`() = runBlocking {
        val filesDir = tempFolder.newFolder("ime_files")
        val ctx = mockContext(filesDir)

        WordBigramPredictor.recordWordPair("hej", "skat", isDanish = true, context = ctx)
        WordBigramPredictor.recordWordPair("good", "asshole", isDanish = false, context = ctx)
        WordBigramPredictor.recordWordPair("good", "morning", isDanish = false, context = ctx)
        WordBigramPredictor.flushUserBigramsForTest()

        // Simulate app restart.
        WordBigramPredictor.clearForTest()

        val afterHej = WordBigramPredictor.predictNextWords(
            ctx, "Hej", isAsciiMode = false, letterLanguage = LetterLanguage.DANISH, limit = 5
        )
        assertEquals("skat", afterHej.first())

        val afterGood = WordBigramPredictor.predictNextWords(
            ctx, "good", isAsciiMode = true, letterLanguage = null, limit = 10
        )
        assertEquals("morning", afterGood.first())
        assertFalse("asshole" in afterGood)
    }
}
