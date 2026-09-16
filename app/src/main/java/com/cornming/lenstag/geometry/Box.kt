package com.cornming.lenstag.geometry

import android.graphics.Rect

/**
 * 一個矩形框，用純 Kotlin 型別表示，刻意不用 android.graphics.RectF。
 *
 * 這樣座標轉換／穩定度判斷的數學可以直接用純 JVM 單元測試驗證（見
 * app/src/test），不需要 Robolectric 或模擬器，CI 跑起來快，也不會受
 * Android 元件版本影響——這個專案已經因為版本相容問題踩過幾次建置失敗的
 * 坑，這裡刻意選風險最低的做法。
 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /**
     * 正規化成 left <= right、top <= bottom。
     * 使用者用手指圈選時可以往任何方向拖（例如從右下拖到左上），直接拿起點
     * 終點組成的 Box 會是「負的」，寬高變負數，後面裁切就會出錯。
     */
    fun normalized(): Box = Box(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )

    /** 把座標夾在 0..maxWidth / 0..maxHeight 範圍內，避免圈到影像外面。 */
    fun clampedTo(maxWidth: Int, maxHeight: Int): Box = Box(
        left = left.coerceIn(0f, maxWidth.toFloat()),
        top = top.coerceIn(0f, maxHeight.toFloat()),
        right = right.coerceIn(0f, maxWidth.toFloat()),
        bottom = bottom.coerceIn(0f, maxHeight.toFloat()),
    )
}

/** ML Kit／CameraX 回傳的都是 android.graphics.Rect，這裡轉成我們自己的 Box。 */
fun Rect.toBox(): Box = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
