package com.cornming.lenstag.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Box 的正規化與夾範圍——拍照模式讓使用者用手指圈選時會直接用到：
 * 圈選可以往任何方向拖，也可能圈到照片外面去。
 */
class BoxTest {

    @Test
    fun `normalized fixes a box dragged from bottom-right to top-left`() {
        val backwards = Box(left = 300f, top = 400f, right = 100f, bottom = 200f)
        val fixed = backwards.normalized()
        assertEquals(100f, fixed.left, DELTA)
        assertEquals(200f, fixed.top, DELTA)
        assertEquals(300f, fixed.right, DELTA)
        assertEquals(400f, fixed.bottom, DELTA)
    }

    @Test
    fun `normalized gives a positive width and height`() {
        val backwards = Box(left = 300f, top = 400f, right = 100f, bottom = 200f)
        val fixed = backwards.normalized()
        assertEquals(200f, fixed.width, DELTA)
        assertEquals(200f, fixed.height, DELTA)
    }

    @Test
    fun `normalized leaves an already-correct box untouched`() {
        val fine = Box(10f, 20f, 30f, 40f)
        assertEquals(fine, fine.normalized())
    }

    @Test
    fun `clampedTo pulls a box that overflows the image back inside`() {
        val overflowing = Box(left = -50f, top = -20f, right = 999f, bottom = 888f)
        val clamped = overflowing.clampedTo(maxWidth = 640, maxHeight = 480)
        assertEquals(0f, clamped.left, DELTA)
        assertEquals(0f, clamped.top, DELTA)
        assertEquals(640f, clamped.right, DELTA)
        assertEquals(480f, clamped.bottom, DELTA)
    }

    @Test
    fun `contains is inclusive of the edges`() {
        val box = Box(10f, 10f, 20f, 20f)
        assertEquals(true, box.contains(10f, 10f))
        assertEquals(true, box.contains(15f, 15f))
        assertEquals(true, box.contains(20f, 20f))
        assertEquals(false, box.contains(9.9f, 15f))
        assertEquals(false, box.contains(15f, 20.1f))
    }

    private companion object {
        const val DELTA = 0.01f
    }
}
