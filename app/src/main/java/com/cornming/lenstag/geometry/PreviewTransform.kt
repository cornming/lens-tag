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

    /**
     * apply 的反向操作：螢幕座標 -> 來源影像座標。
     * 拍照模式讓使用者用手指圈一塊區域時，圈出來的是螢幕座標，但要拿去裁切
     * 原始照片，就得換算回照片自己的座標系。
     */
    fun invert(box: Box) = Box(
        left = (box.left - offsetX) / scale,
        top = (box.top - offsetY) / scale,
        right = (box.right - offsetX) / scale,
        bottom = (box.bottom - offsetY) / scale,
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

/**
 * FIT_CENTER 版本：整張影像完整顯示在畫面內（等比例縮到放得下為止），
 * 四周可能留黑邊。拍照模式用這個而不是 previewTransform——拍下來的照片
 * 應該完整看到，不該像即時預覽那樣為了填滿畫面把邊緣裁掉，不然使用者
 * 想圈的東西可能剛好在被裁掉的那一塊。
 */
fun fitTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
): PreviewTransform {
    val scale = minOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    return PreviewTransform(
        scale = scale,
        offsetX = (viewWidth - sourceWidth * scale) / 2f,
        offsetY = (viewHeight - sourceHeight * scale) / 2f,
    )
}

/**
 * 拍照模式用的轉換：先 FIT_CENTER 讓整張照片可見，再疊上使用者的縮放與拖曳。
 *
 * 縮放以畫面中心為基準點（雙指捏合時視覺上最自然），公式推導：
 *   fit 之後：  s = p * fitScale + fitOffset
 *   以中心縮放：f = (s - c) * zoom + c + pan
 *   展開合併：  f = p * (fitScale * zoom) + (fitOffset * zoom - c * zoom + c + pan)
 * 所以最終還是一個單純的 scale + offset，可以直接包成 PreviewTransform，
 * 框的繪製和圈選的反向換算就都自動跟著縮放走，不用各自處理。
 */
fun photoTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
): PreviewTransform {
    val fitScale = minOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    val fitOffsetX = (viewWidth - sourceWidth * fitScale) / 2f
    val fitOffsetY = (viewHeight - sourceHeight * fitScale) / 2f
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    return PreviewTransform(
        scale = fitScale * zoom,
        offsetX = fitOffsetX * zoom - centerX * zoom + centerX + panX,
        offsetY = fitOffsetY * zoom - centerY * zoom + centerY + panY,
    )
}
