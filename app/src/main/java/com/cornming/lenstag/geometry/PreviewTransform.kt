package com.cornming.lenstag.geometry

/**
 * 分析影像座標 -> 螢幕座標的換算。
 *
 * PreviewView 預設是 FILL_CENTER：影像等比例放大到填滿畫面，超出的部分被裁掉。
 * 所以縮放倍率取兩軸的較大值，再置中偏移。sourceWidth/sourceHeight 必須已經是
 * 「旋轉後」的尺寸（ObjectAnalyzer 已經處理過），不然這裡會整個算錯——框歪掉
 * 那個 bug 的根源就在這裡，所以這塊數學特別值得寫測試釘住（見
 * app/src/test/.../PreviewTransformTest.kt）。
 *
 * VR 模式呼叫這個函式時，viewWidth/viewHeight 傳的是「單一半邊」的寬高，
 * 不是整個螢幕，這樣兩邊的準星／框位置換算出來才會正確對齊各自那一半畫面。
 */
class PreviewTransform(
    private val scale: Float,
    private val offsetX: Float,
    private val offsetY: Float,
) {
    fun apply(box: Box) = Box(
        left = box.left * scale + offsetX,
        top = box.top * scale + offsetY,
        right = box.right * scale + offsetX,
        bottom = box.bottom * scale + offsetY,
    )
}

fun previewTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
): PreviewTransform {
    val scale = maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    return PreviewTransform(
        scale = scale,
        offsetX = (viewWidth - sourceWidth * scale) / 2f,
        offsetY = (viewHeight - sourceHeight * scale) / 2f,
    )
}
