package com.cornming.lenstag.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelStateTest {

    @Test
    fun `unknown shows a question mark regardless of display mode`() {
        DisplayMode.entries.forEach { mode ->
            assertEquals("?", LabelState.Unknown.displayText(mode))
        }
    }

    @Test
    fun `recognizing shows an ellipsis regardless of display mode`() {
        DisplayMode.entries.forEach { mode ->
            assertEquals("…", LabelState.Recognizing.displayText(mode))
        }
    }

    @Test
    fun `named without a translation always falls back to the primary text`() {
        val state = LabelState.Named(primary = "門", secondary = null)
        assertEquals("門", state.displayText(DisplayMode.PRIMARY))
        assertEquals("門", state.displayText(DisplayMode.SECONDARY))
        assertEquals("門", state.displayText(DisplayMode.BOTH))
    }

    @Test
    fun `named with a translation respects each display mode`() {
        val state = LabelState.Named(primary = "門", secondary = "door")
        assertEquals("門", state.displayText(DisplayMode.PRIMARY))
        assertEquals("door", state.displayText(DisplayMode.SECONDARY))
        assertEquals("門 door", state.displayText(DisplayMode.BOTH))
    }

    @Test
    fun `next cycles through all three modes and wraps back to the start`() {
        assertEquals(DisplayMode.SECONDARY, DisplayMode.PRIMARY.next())
        assertEquals(DisplayMode.BOTH, DisplayMode.SECONDARY.next())
        assertEquals(DisplayMode.PRIMARY, DisplayMode.BOTH.next())
    }
}
