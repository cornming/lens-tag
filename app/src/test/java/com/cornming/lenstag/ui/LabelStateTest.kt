package com.cornming.lenstag.ui

import com.cornming.lenstag.data.CustomName
import com.cornming.lenstag.recognize.FailureReason
import com.cornming.lenstag.recognize.RecognitionResult
import com.cornming.lenstag.recognize.RecognizedLabel
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
    fun `a failed label shows its specific reason, not a bare question mark`() {
        // 這是步驟 2 的重點：失敗原因要看得出來，不能跟「還沒辨識」長得一樣
        val failed = LabelState.Failed(FailureReason.AUTH, "HTTP 401")
        DisplayMode.entries.forEach { mode ->
            assertEquals("⚠ 金鑰錯誤", failed.displayText(mode))
        }
    }

    @Test
    fun `a successful result becomes a named label that remembers what the model called it`() {
        // recognizedAs 是自訂名稱對照表的鑰匙，之後改名時靠它知道要更新哪一筆
        val result = RecognitionResult.Success(RecognizedLabel("門", "door"))
        assertEquals(
            LabelState.Named("門", "door", custom = false, recognizedAs = "門"),
            result.toLabelState(),
        )
    }

    @Test
    fun `a custom name replaces the model's wording when one exists`() {
        // 最初需求的核心：模型說「門」，使用者之前把「門」改名成「大門」，
        // 那就要顯示「大門」，而不是模型的說法
        val result = RecognitionResult.Success(RecognizedLabel("門", "door"))
        val label = result.toLabelState { key ->
            if (key == "門") CustomName("大門", "front door") else null
        }
        assertEquals(
            LabelState.Named("大門", "front door", custom = true, recognizedAs = "門"),
            label,
        )
    }

    @Test
    fun `without a matching custom name the model's wording is kept`() {
        val result = RecognitionResult.Success(RecognizedLabel("椅子", "chair"))
        val label = result.toLabelState { key ->
            if (key == "門") CustomName("大門", "front door") else null
        }
        assertEquals(
            LabelState.Named("椅子", "chair", custom = false, recognizedAs = "椅子"),
            label,
        )
    }

    @Test
    fun `failures are never replaced by a custom name`() {
        val result = RecognitionResult.Failure(FailureReason.NETWORK, "timeout")
        val label = result.toLabelState { CustomName("不該出現", null) }
        assertEquals(LabelState.Failed(FailureReason.NETWORK, "timeout"), label)
    }

    @Test
    fun `a failed result keeps both its reason and its detail`() {
        val result = RecognitionResult.Failure(FailureReason.NOT_FOUND, "HTTP 404：deployment not found")
        assertEquals(
            LabelState.Failed(FailureReason.NOT_FOUND, "HTTP 404：deployment not found"),
            result.toLabelState(),
        )
    }

    @Test
    fun `next cycles through all three modes and wraps back to the start`() {
        assertEquals(DisplayMode.SECONDARY, DisplayMode.PRIMARY.next())
        assertEquals(DisplayMode.BOTH, DisplayMode.SECONDARY.next())
        assertEquals(DisplayMode.PRIMARY, DisplayMode.BOTH.next())
    }
}
