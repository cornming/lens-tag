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

    @Test
    fun `photoTransform at zoom 1 with no pan matches plain fitTransform`() {
        // 拍照模式一開啟就是這個狀態，應該跟單純的 FIT 顯示完全一樣
        val fit = fitTransform(480, 640, 1080f, 2280f)
        val photo = photoTransform(480, 640, 1080f, 2280f, zoom = 1f, panX = 0f, panY = 0f)
        val box = Box(100f, 100f, 200f, 300f)
        val a = fit.apply(box)
        val b = photo.apply(box)
        assertEquals(a.left, b.left, DELTA)
        assertEquals(a.top, b.top, DELTA)
        assertEquals(a.right, b.right, DELTA)
        assertEquals(a.bottom, b.bottom, DELTA)
    }

    @Test
    fun `zooming keeps the view centre anchored in place`() {
        // 以畫面中心為基準縮放：正中心那一點放大後還是在正中心
        val viewW = 1000f
        val viewH = 2000f
        val transform1 = photoTransform(500, 1000, viewW, viewH, zoom = 1f, panX = 0f, panY = 0f)
        val transform3 = photoTransform(500, 1000, viewW, viewH, zoom = 3f, panX = 0f, panY = 0f)

        // 找出在 zoom=1 時剛好落在畫面中心的那個照片座標點，用一個退化的框表示
        val centreDot = transform1.invert(Box(viewW / 2f, viewH / 2f, viewW / 2f, viewH / 2f))
        val afterZoom = transform3.apply(centreDot)

        assertEquals(viewW / 2f, afterZoom.left, DELTA)
        assertEquals(viewH / 2f, afterZoom.top, DELTA)
    }

    @Test
    fun `panning shifts everything by exactly the pan amount`() {
        val noPan = photoTransform(480, 640, 1080f, 2280f, zoom = 2f, panX = 0f, panY = 0f)
        val panned = photoTransform(480, 640, 1080f, 2280f, zoom = 2f, panX = 50f, panY = -30f)
        val box = Box(10f, 20f, 30f, 40f)
        assertEquals(noPan.apply(box).left + 50f, panned.apply(box).left, DELTA)
        assertEquals(noPan.apply(box).top - 30f, panned.apply(box).top, DELTA)
    }

    @Test
    fun `invert still round-trips once zoom and pan are applied`() {
        // 縮放後圈選仍然要能正確換算回照片座標，不然放大後圈的範圍會裁錯地方
        val transform = photoTransform(480, 640, 1080f, 2280f, zoom = 2.5f, panX = 120f, panY = -80f)
        val original = Box(left = 60f, top = 90f, right = 240f, bottom = 300f)
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
