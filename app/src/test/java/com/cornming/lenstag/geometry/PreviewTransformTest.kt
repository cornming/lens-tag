package com.cornming.lenstag.geometry

import org.junit.Assert.assertEquals
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

    private companion object {
        const val DELTA = 0.01f
    }
}
