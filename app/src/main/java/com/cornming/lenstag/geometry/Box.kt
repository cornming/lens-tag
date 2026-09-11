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
}

/** ML Kit／CameraX 回傳的都是 android.graphics.Rect，這裡轉成我們自己的 Box。 */
fun Rect.toBox(): Box = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
