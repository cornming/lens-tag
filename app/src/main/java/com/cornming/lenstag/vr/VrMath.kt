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
