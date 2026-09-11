package com.cornming.lenstag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkedWordsCodecTest {

    @Test
    fun `round trip preserves words with and without a translation`() {
        val words = listOf(
            MarkedWord(primary = "門", secondary = "door"),
            MarkedWord(primary = "桌子", secondary = null),
        )
        assertEquals(words, decodeWords(encodeWords(words)))
    }

    @Test
    fun `decoding null input yields an empty list`() {
        assertEquals(emptyList<MarkedWord>(), decodeWords(null))
    }

    @Test
    fun `decoding malformed json does not throw and yields an empty list`() {
        assertEquals(emptyList<MarkedWord>(), decodeWords("not json at all"))
    }

    @Test
    fun `toggle adds a word that is not present yet`() {
        val result = toggleWord(emptyList(), MarkedWord("門", "door"))
        assertEquals(listOf(MarkedWord("門", "door")), result)
    }

    @Test
    fun `toggle removes a word that is already present, matching by primary only`() {
        // 就算翻譯文字不同，只要 primary 一樣就當作同一個字，toggle 掉
        val existing = listOf(MarkedWord("門", "door"))
        val result = toggleWord(existing, MarkedWord("門", "gate"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toggle ignores a blank primary`() {
        val result = toggleWord(emptyList(), MarkedWord("", "door"))
        assertTrue(result.isEmpty())
    }
}
