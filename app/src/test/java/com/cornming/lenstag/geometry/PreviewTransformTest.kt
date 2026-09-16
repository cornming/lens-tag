package com.cornming.lenstag.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 座標轉換的數學驗證——「框歪掉」那個 bug 的根源就在這塊，值得釘住。
 * 純 JVM 測試，不用模擬器、不用 Robolectric。
 */
class PreviewTransformTest {

    @Test
    fun `matching aspect ratio scales without any offset`() {
        val transform = previewTransform(
            sourceWidth = 100,
            sourceHeight = 100,
            viewWidth = 200f,
            viewHeight = 200f,
        )
        val result = transform.apply(Box(0f, 0f, 100f, 100f))
        assertEquals(0f, result.left, DELTA)
        assertEquals(0f, result.top, DELTA)
        assertEquals(200f, result.right, DELTA)
        assertEquals(200f, result.bottom, DELTA)
    }

    @Test
    fun `FILL_CENTER crops the taller axis and centers with a negative offset`() {
        // source 100x200（直立長條，像手機拍出來的畫面），view 300x300（正方形）。
        // 寬度要放大 3 倍才能填滿畫面，高度因此溢出畫面，靠負的 offsetY 置中裁掉上下。
        val transform = previewTransform(
            sourceWidth = 100,
            sourceHeight = 200,
            viewWidth = 300f,
            viewHeight = 300f,
        )
        val result = transform.apply(Box(left = 10f, top = 20f, right = 30f, bottom = 40f))
        assertEquals(30f, result.left, DELTA)
        assertEquals(-90f, result.top, DELTA)
        assertEquals(90f, result.right, DELTA)
        assertEquals(-30f, result.bottom, DELTA)
    }

    @Test
    fun `aspect ratio of a box is preserved under the transform`() {
        // 轉換只是線性縮放＋平移，寬高比不該變——如果 x、y 的縮放係數不小心用反了，
        // 這個測試會抓到。
        val transform = previewTransform(
            sourceWidth = 480,
            sourceHeight = 640,
            viewWidth = 1080f,
            viewHeight = 2280f,
        )
        val box = Box(left = 100f, top = 100f, right = 200f, bottom = 300f)
        val result = transform.apply(box)
        assertEquals(box.width / box.height, result.width / result.height, DELTA)
    }

    @Test
    fun `fitTransform shows the whole image with letterboxing instead of cropping`() {
        // source 100x200（直立），view 300x300（正方形）。FIT 要整張看得見，
        // 所以縮放取較小的那個（300/200 = 1.5），左右留白。
        val transform = fitTransform(
            sourceWidth = 100,
            sourceHeight = 200,
            viewWidth = 300f,
            viewHeight = 300f,
        )
        val whole = transform.apply(Box(0f, 0f, 100f, 200f))
        assertEquals(75f, whole.left, DELTA) // (300 - 150) / 2
        assertEquals(0f, whole.top, DELTA)
        assertEquals(225f, whole.right, DELTA)
        assertEquals(300f, whole.bottom, DELTA)
    }

    @Test
    fun `fitTransform never pushes content outside the view`() {
        // 跟 previewTransform（FILL_CENTER，會裁掉溢出的部分）最關鍵的差別：
        // FIT 的結果一定完整落在畫面內，不會有負座標。
        val transform = fitTransform(480, 640, 1080f, 2280f)
        val whole = transform.apply(Box(0f, 0f, 480f, 640f))
        assertTrue(whole.left >= -DELTA)
        assertTrue(whole.top >= -DELTA)
        assertTrue(whole.right <= 1080f + DELTA)
        assertTrue(whole.bottom <= 2280f + DELTA)
    }

    @Test
    fun `invert undoes apply, so a circled screen area maps back to photo coordinates`() {
        // 拍照模式使用者圈選時走的路徑：圈出來的是螢幕座標，要換算回照片座標
        // 才能拿去裁切。apply 之後再 invert 應該回到原點。
        val transform = fitTransform(480, 640, 1080f, 2280f)
        val original = Box(left = 120f, top = 80f, right = 300f, bottom = 400f)
        val roundTripped = transform.invert(transform.apply(original))
        assertEquals(original.left, roundTripped.left, DELTA)
        assertEquals(original.top, roundTripped.top, DELTA)
        assertEquals(original.right, roundTripped.right, DELTA)
        assertEquals(original.bottom, roundTripped.bottom, DELTA)
    }

    private companion object {
        const val DELTA = 0.01f
    }
}
