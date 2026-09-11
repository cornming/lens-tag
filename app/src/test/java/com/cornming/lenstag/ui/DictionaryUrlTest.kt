package com.cornming.lenstag.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryUrlTest {

    @Test
    fun `builds a google search query around the word`() {
        val url = dictionaryUrl("door")
        assertEquals("https://www.google.com/search?q=define+door", url)
    }

    @Test
    fun `non-ascii words are percent-encoded without throwing`() {
        val url = dictionaryUrl("門")
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertFalse(url.contains(" "))
    }
}
