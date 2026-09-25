package com.cornming.lenstag.vr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.cornming.lenstag.geometry.Box
import com.cornming.lenstag.geometry.previewTransform

/** 疊在 VR 畫面上的一個框：位置（分析影像座標）、標籤文字、顏色（ARGB）。 */
data class VrOverlayItem(val box: Box, val text: String, val color: Int)

/**
 * 某一刻 VR 畫面上要疊的所有東西。主執行緒產生、GL 執行緒讀取，
 * 所以做成不可變的 data class，兩邊不會同時改到同一份資料。
 */
data class VrOverlayState(
    val items: List<VrOverlayItem>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val gazeProgress: Float,
    val gazeLongPhase: Boolean,
    val message: String?,
    val hint: String,
)

/**
 * 把框、標籤、準星、提示文字畫到一張透明的 Bitmap 上，之後當成貼圖疊在
 * 相機畫面上。
 *
 * 為什麼不直接用 Compose 畫在 GL 畫面上面：變形校正是在 GPU 上對「整張
 * 眼睛畫面」做的，框和文字必須跟相機影像一起經過同一次變形，透過鏡片看才
 * 對得齊。如果框畫在 GL 外面，相機影像被壓成桶形、框卻沒有，越靠邊緣偏越多。
 */
internal object VrOverlayPainter {

    fun paint(canvas: Canvas, state: VrOverlayState, density: Float, fontScale: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val background = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * density * fontScale
        }

        if (state.sourceWidth > 0 && state.sourceHeight > 0) {
            // 跟相機畫面的裁切用同一個 FILL_CENTER 規則，框才對得上
            val transform = previewTransform(state.sourceWidth, state.sourceHeight, w, h)
            state.items.forEach { item ->
                val r = transform.apply(item.box)
                stroke.color = item.color
                canvas.drawRect(r.left, r.top, r.right, r.bottom, stroke)
                drawLabel(canvas, item.text, r.left, r.top, labelText, background, w, h)
            }
        }

        // 準星：畫面正中央，之後會對準鏡片中心
        val cx = w / 2f
        val cy = h / 2f
        val radius = 10f * density
        stroke.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, stroke)
        if (state.gazeProgress > 0f) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (state.gazeLongPhase) 0xFFFFA000.toInt() else 0xFF4CAF50.toInt()
            }
            canvas.drawCircle(cx, cy, radius * state.gazeProgress, fill)
        }

        // 提示文字別放太靠邊：邊緣經過變形校正會被壓扁、甚至切到畫面外
        state.message?.let {
            drawCentered(canvas, it, h * 0.22f, 18f * density * fontScale, background, w)
        }
        drawCentered(canvas, state.hint, h * 0.78f, 14f * density * fontScale, background, w)
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        boxLeft: Float,
        boxTop: Float,
        paint: Paint,
        background: Paint,
        w: Float,
        h: Float,
    ) {
        val textW = paint.measureText(text)
        val metrics = paint.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val pad = 8f
        // 預設畫在框的正上方，放不下就畫在框內；水平方向夾在畫面內
        val top = if (boxTop - textH - pad >= 0f) boxTop - textH - pad else boxTop + pad
        val x = boxLeft.coerceIn(pad, maxOf(pad, w - textW - pad))
        val y = top.coerceIn(0f, maxOf(0f, h - textH))
        canvas.drawRect(x - 4f, y - 2f, x + textW + 4f, y + textH + 2f, background)
        canvas.drawText(text, x, y - metrics.ascent, paint)
    }

    private fun drawCentered(
        canvas: Canvas,
        text: String,
        centerY: Float,
        textSizePx: Float,
        background: Paint,
        w: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
        }
        val textW = paint.measureText(text)
        val metrics = paint.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val x = ((w - textW) / 2f).coerceAtLeast(0f)
        val y = centerY - textH / 2f
        canvas.drawRect(x - 8f, y - 4f, x + textW + 8f, y + textH + 4f, background)
        canvas.drawText(text, x, y - metrics.ascent, paint)
    }
}
