package com.cornming.lenstag.vr

/**
 * VR 渲染用到的幾何計算，全部是純函式，可以直接寫單元測試。
 *
 * GPU 上跑的 shader 沒辦法測，但 shader 用的是跟這裡「同一套數學」——
 * 把數學在這裡寫一份、用測試釘住，至少能確保公式本身是對的。
 */

/**
 * FILL_CENTER：影像等比放大到填滿視窗、超出的部分裁掉。
 * 回傳影像在水平、垂直方向上「看得到的比例」（其中一個一定是 1）。
 *
 * 必須跟 geometry.previewTransform（框的座標換算）用同一個規則，
 * 不然相機畫面裁切的範圍跟框的位置會對不起來。
 */
internal fun fillCenterCropScale(imageW: Int, imageH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
    if (imageW <= 0 || imageH <= 0 || viewW <= 0 || viewH <= 0) return 1f to 1f
    val scale = maxOf(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
    return (viewW / (imageW * scale)) to (viewH / (imageH * scale))
}

/** 公釐換算成螢幕像素（1 吋 = 25.4 公釐）。 */
internal fun mmToPx(mm: Float, dpi: Float): Float = mm * dpi / 25.4f

/**
 * 鏡片中心落在該眼畫面（左半或右半螢幕）的哪個水平位置，0～1。
 *
 * cardboard 兩片鏡片的中心距離通常比「左右半螢幕的中心距離」近，所以鏡片
 * 中心會往螢幕中線偏：左眼偏右、右眼偏左。畫面要對準鏡片中心，不然兩眼
 * 看到的影像對不起來，看久會累、甚至重影。
 */
internal fun lensCenterU(isLeftEye: Boolean, screenWidthPx: Float, lensSeparationPx: Float): Float {
    val ratio = lensSeparationPx / screenWidthPx
    return if (isLeftEye) 1f - ratio else ratio
}

/**
 * 變形校正：輸出畫面上 (u, v) 這一點，要去「眼睛畫面」的哪個位置取色。
 *
 * cardboard 的鏡片會讓畫面邊緣放大得比中間多（枕形變形），所以顯示前先把
 * 畫面往反方向壓（桶形變形），兩者抵消後透過鏡片看起來才是直的。做法是
 * 離鏡片中心越遠的點，去越外面的地方取色：取色半徑 = r × (1 + k1·r² + k2·r⁴)。
 *
 * 距離以「半個畫面高度」為單位，並依寬高比修正，讓變形是圓形而不是橢圓形。
 * 眼睛畫面的中心（0.5, 0.5）對準鏡片中心。回傳值超出 0～1 表示該點沒有
 * 影像（shader 會畫成黑色）。
 */
internal fun distortSourceUv(
    u: Float,
    v: Float,
    lensU: Float,
    lensV: Float,
    aspect: Float,
    k1: Float,
    k2: Float,
): Pair<Float, Float> {
    val sx = aspect * 2f
    val sy = 2f
    val dx = (u - lensU) * sx
    val dy = (v - lensV) * sy
    val r2 = dx * dx + dy * dy
    val f = 1f + k1 * r2 + k2 * r2 * r2
    return (dx * f / sx + 0.5f) to (dy * f / sy + 0.5f)
}

/**
 * 相機畫面顯示時要「逆時針」轉幾個 90 度才會是正的。
 *
 * 這裡曾經寫錯過，造成實機上 VR 畫面整個轉了 90 度：相機畫面直接送進
 * SurfaceTexture 時，相機會把「感光元件的方向」寫進去，SurfaceTexture 的
 * 貼圖矩陣已經先把畫面轉成「手機自然方向（直向）的正向」了
 * （CameraX 的 hasCameraTransform() 為 true）。這時候要補的是「螢幕目前轉了
 * 多少」，而不是 CameraX 給的 rotationDegrees——後者是給「沒被相機轉過的
 * 原始畫面」用的。官方文件 SurfaceRequest.TransformationInfo 有寫這個分別。
 *
 * 方向推導（以後置鏡頭、感光元件方向 90、螢幕轉 90 度的橫向為例）：
 * - 感光元件方向 90 的定義：原始畫面要順時針轉 90 度才會在直向時是正的，
 *   所以貼圖矩陣給出的畫面 = 原始畫面順時針轉 90 度
 * - 橫向時 CameraX 算出原始畫面的 rotationDegrees 是 0，也就是原始畫面在
 *   橫向本來就是正的
 * - 所以貼圖矩陣給的畫面要再「逆時針」轉 90 度轉回來 → 逆時針轉「螢幕旋轉角度」
 *
 * 沒有相機方向時（例如畫面中間經過其他處理），就照 rotationDegrees 順時針轉，
 * 換算成逆時針就是 360 - rotationDegrees。
 */
internal fun cameraQuarterTurnsCcw(
    hasCameraTransform: Boolean,
    displayRotationDegrees: Int,
    rotationDegrees: Int,
): Int = if (hasCameraTransform) {
    (((displayRotationDegrees / 90) % 4) + 4) % 4
} else {
    (((360 - rotationDegrees) % 360) / 90 + 4) % 4
}

/**
 * 要讓顯示出來的畫面「逆時針轉 turns 個 90 度」，輸出畫面上 (u, v) 這一點
 * 要去原本貼圖的哪裡取色。uv 的原點在左下、v 往上（OpenGL 慣例）。
 * 跟 GlUtil 裡相機 shader 的 rotateCcw 同一套，改一邊要一起改另一邊。
 */
internal fun rotateSampleCcw(u: Float, v: Float, turns: Int): Pair<Float, Float> =
    when (((turns % 4) + 4) % 4) {
        0 -> u to v
        1 -> v to (1f - u)
        2 -> (1f - u) to (1f - v)
        else -> (1f - v) to u
    }

/** Surface.ROTATION_0～ROTATION_270（常數值 0～3）換算成角度。 */
internal fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
    1 -> 90
    2 -> 180
    3 -> 270
    else -> 0
}
