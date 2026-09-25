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

    @Test
    fun `with the camera orientation baked in, compensate by the display rotation`() {
        // 實機截圖發現 VR 畫面轉了 90 度的那個錯：相機直接接 SurfaceTexture 時，
        // 貼圖矩陣已經先轉成「直向的正向」，要補的是螢幕旋轉，不是 rotationDegrees
        assertEquals(1, cameraQuarterTurnsCcw(hasCameraTransform = true, displayRotationDegrees = 90, rotationDegrees = 0))
        assertEquals(3, cameraQuarterTurnsCcw(hasCameraTransform = true, displayRotationDegrees = 270, rotationDegrees = 180))
        assertEquals(0, cameraQuarterTurnsCcw(hasCameraTransform = true, displayRotationDegrees = 0, rotationDegrees = 90))
    }

    @Test
    fun `without the camera orientation, rotate clockwise by rotationDegrees`() {
        assertEquals(0, cameraQuarterTurnsCcw(hasCameraTransform = false, displayRotationDegrees = 90, rotationDegrees = 0))
        assertEquals(2, cameraQuarterTurnsCcw(hasCameraTransform = false, displayRotationDegrees = 270, rotationDegrees = 180))
        // 順時針 90 = 逆時針 270
        assertEquals(3, cameraQuarterTurnsCcw(hasCameraTransform = false, displayRotationDegrees = 0, rotationDegrees = 90))
        assertEquals(1, cameraQuarterTurnsCcw(hasCameraTransform = false, displayRotationDegrees = 0, rotationDegrees = 270))
    }

    @Test
    fun `landscape with baked camera orientation puts the sky back at the top`() {
        // 把物理推導寫成測試：手機橫拿（螢幕轉 90 度）時，貼圖矩陣給的是「直向的
        // 正向」畫面，天空（世界的上方）落在這張畫面的「右邊」。補正之後，
        // 輸出畫面的「頂端正中央」必須去取原本畫面「右邊正中央」的顏色——
        // 也就是天空回到上面。如果有人把旋轉方向改反，這裡會取到左邊（地上）。
        val turns = cameraQuarterTurnsCcw(hasCameraTransform = true, displayRotationDegrees = 90, rotationDegrees = 0)
        val (u, v) = rotateSampleCcw(u = 0.5f, v = 1.0f, turns = turns) // 輸出的頂端中央（v 往上）
        assertEquals(1.0f, u, DELTA) // 原本畫面的右邊
        assertEquals(0.5f, v, DELTA)
    }

    @Test
    fun `quarter turns compose back to where they started`() {
        // 轉四次 90 度要回到原點；也順便確認每一種轉法都是真正的旋轉、不是鏡像
        val start = 0.2f to 0.7f
        var p = start
        repeat(4) { p = rotateSampleCcw(p.first, p.second, 1) }
        assertEquals(start.first, p.first, DELTA)
        assertEquals(start.second, p.second, DELTA)

        val twice = rotateSampleCcw(rotateSampleCcw(0.2f, 0.7f, 1).first, rotateSampleCcw(0.2f, 0.7f, 1).second, 1)
        val half = rotateSampleCcw(0.2f, 0.7f, 2)
        assertEquals(half.first, twice.first, DELTA)
        assertEquals(half.second, twice.second, DELTA)
    }

    @Test
    fun `surface rotation constants convert to degrees`() {
        assertEquals(0, surfaceRotationToDegrees(0))
        assertEquals(90, surfaceRotationToDegrees(1))
        assertEquals(180, surfaceRotationToDegrees(2))
        assertEquals(270, surfaceRotationToDegrees(3))
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
