package com.cornming.lenstag.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * VR 渲染的幾何數學。GPU 上的 shader 沒辦法測，但 shader 用的是跟這裡同一套
 * 公式，所以至少公式本身的正確性可以在這裡釘住。
 */
class VrMathTest {

    @Test
    fun `fill-center crop keeps everything when aspect ratios match`() {
        val (x, y) = fillCenterCropScale(1600, 1200, 800, 600)
        assertEquals(1f, x, DELTA)
        assertEquals(1f, y, DELTA)
    }

    @Test
    fun `fill-center crop trims the axis that overflows`() {
        // 4:3 的相機畫面填進「比較窄」的單眼畫面（例如 1170x1080），左右會被裁掉一點
        val (x, y) = fillCenterCropScale(1440, 1080, 1170, 1080)
        assertEquals(1f, y, DELTA)
        assertTrue(x < 1f)
        assertEquals(1170f / 1440f, x, DELTA)
    }

    @Test
    fun `fill-center crop never shows more than the whole image`() {
        val (x, y) = fillCenterCropScale(640, 480, 1170, 1080)
        assertTrue(x <= 1f + DELTA)
        assertTrue(y <= 1f + DELTA)
        assertTrue(abs(x - 1f) < DELTA || abs(y - 1f) < DELTA) // 其中一個方向一定完整
    }

    @Test
    fun `degenerate sizes fall back to no cropping instead of dividing by zero`() {
        assertEquals(1f to 1f, fillCenterCropScale(0, 0, 800, 600))
        assertEquals(1f to 1f, fillCenterCropScale(640, 480, 0, 0))
    }

    @Test
    fun `millimetres convert to pixels through dpi`() {
        assertEquals(400f, mmToPx(25.4f, 400f), DELTA) // 1 吋 = 25.4 公釐
        assertEquals(200f, mmToPx(12.7f, 400f), DELTA)
    }

    @Test
    fun `lens centres sit at the middle of each half when separation equals half the screen`() {
        assertEquals(0.5f, lensCenterU(isLeftEye = true, screenWidthPx = 2000f, lensSeparationPx = 1000f), DELTA)
        assertEquals(0.5f, lensCenterU(isLeftEye = false, screenWidthPx = 2000f, lensSeparationPx = 1000f), DELTA)
    }

    @Test
    fun `narrower lens separation pulls both centres toward the middle of the screen`() {
        // 左眼畫面的「螢幕中線那一側」是右邊（u 變大），右眼是左邊（u 變小）
        val left = lensCenterU(isLeftEye = true, screenWidthPx = 2000f, lensSeparationPx = 800f)
        val right = lensCenterU(isLeftEye = false, screenWidthPx = 2000f, lensSeparationPx = 800f)
        assertEquals(0.6f, left, DELTA)
        assertEquals(0.4f, right, DELTA)
    }

    @Test
    fun `the two lens centres are mirror images of each other`() {
        val left = lensCenterU(isLeftEye = true, screenWidthPx = 2340f, lensSeparationPx = 930f)
        val right = lensCenterU(isLeftEye = false, screenWidthPx = 2340f, lensSeparationPx = 930f)
        assertEquals(1f, left + right, DELTA)
    }

    @Test
    fun `the lens centre always samples the centre of the eye image`() {
        // 準星畫在眼睛畫面正中央，這一點必須對準鏡片中心，不管變形多強
        val (u, v) = distortSourceUv(0.6f, 0.5f, lensU = 0.6f, lensV = 0.5f, aspect = 1.08f, k1 = 0.4f, k2 = 0.1f)
        assertEquals(0.5f, u, DELTA)
        assertEquals(0.5f, v, DELTA)
    }

    @Test
    fun `with no correction the mapping is a plain shift toward the lens centre`() {
        val (u, v) = distortSourceUv(0.7f, 0.3f, lensU = 0.6f, lensV = 0.5f, aspect = 1.08f, k1 = 0f, k2 = 0f)
        assertEquals(0.7f - 0.6f + 0.5f, u, DELTA)
        assertEquals(0.3f - 0.5f + 0.5f, v, DELTA)
    }

    @Test
    fun `positive correction samples farther out, compressing the edges (barrel)`() {
        // 桶形校正的本質：離中心越遠，取樣位置被推得越外面，畫面邊緣因此被壓縮，
        // 抵消鏡片把邊緣放大的枕形變形
        val lensU = 0.5f
        val lensV = 0.5f
        val none = distortSourceUv(0.8f, 0.7f, lensU, lensV, aspect = 1f, k1 = 0f, k2 = 0f)
        val some = distortSourceUv(0.8f, 0.7f, lensU, lensV, aspect = 1f, k1 = 0.3f, k2 = 0f)
        val rNone = hypot(none.first - 0.5f, none.second - 0.5f)
        val rSome = hypot(some.first - 0.5f, some.second - 0.5f)
        assertTrue(rSome > rNone)
    }

    @Test
    fun `stronger correction pushes farther than weaker correction`() {
        val weak = distortSourceUv(0.9f, 0.5f, 0.5f, 0.5f, aspect = 1f, k1 = 0.1f, k2 = 0f)
        val strong = distortSourceUv(0.9f, 0.5f, 0.5f, 0.5f, aspect = 1f, k1 = 0.4f, k2 = 0f)
        assertTrue(strong.first > weak.first)
    }

    @Test
    fun `distortion is circular, not stretched, on a non-square eye view`() {
        // 距離有依寬高比修正：水平、垂直走「一樣的實際距離」，變形程度要一樣
        val aspect = 2f
        val horizontal = distortSourceUv(0.5f + 0.1f, 0.5f, 0.5f, 0.5f, aspect, k1 = 0.3f, k2 = 0f)
        val vertical = distortSourceUv(0.5f, 0.5f + 0.2f, 0.5f, 0.5f, aspect, k1 = 0.3f, k2 = 0f)
        // 水平 0.1 個畫面寬 = 0.2 個畫面高（寬是高的 2 倍），兩者實際距離相同
        val hShift = (horizontal.first - 0.5f) / 0.1f
        val vShift = (vertical.second - 0.5f) / 0.2f
        assertEquals(hShift, vShift, DELTA)
    }

    private companion object {
        const val DELTA = 0.001f
    }
}

class VrSettingsTest {

    @Test
    fun `defaults use the smooth engine with moderate lens values`() {
        val settings = VrSettings()
        assertEquals(VrEngine.GL, settings.engine)
        assertEquals(DEFAULT_LENS_SEPARATION_MM, settings.lensSeparationMm, 0.001f)
        assertEquals(DEFAULT_DISTORTION, settings.distortion, 0.001f)
    }

    @Test
    fun `stored values are clamped to the adjustable ranges`() {
        assertEquals(MIN_LENS_SEPARATION_MM, clampLensSeparation(0f), 0.001f)
        assertEquals(MAX_LENS_SEPARATION_MM, clampLensSeparation(999f), 0.001f)
        assertEquals(0f, clampDistortion(-1f), 0.001f)
        assertEquals(MAX_DISTORTION, clampDistortion(9f), 0.001f)
    }
}
