package com.cornming.lenstag.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.cornming.lenstag.geometry.Box as GeomBox
import com.cornming.lenstag.geometry.fitTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 圈選的框至少要這麼大（螢幕像素）才算數，避免手指輕輕一滑就產生一堆小框。 */
private const val MIN_DRAG_SIZE_PX = 40f

/**
 * 拍照模式下畫面上的一塊區域：可能是 ML Kit 自動偵測出來的，
 * 也可能是使用者自己用手指圈的。座標是「照片自己的座標系」。
 */
data class PhotoRegion(
    val id: Int,
    val box: GeomBox,
    val label: LabelState,
    /** true 表示這是使用者手動圈的，不是自動偵測的 */
    val manual: Boolean = false,
)

/**
 * 拍照模式：畫面是靜止的一張照片，上面疊著可辨識的框。
 *
 * 跟即時模式的差別（也是這個模式存在的理由）：畫面不會動，所以可以慢慢看、
 * 慢慢圈。兩種互動方式：
 * - 點一下自動偵測出來的框 -> 辨識那個物件
 * - 用手指拖曳圈出任意範圍 -> 辨識圈起來的東西（自動偵測漏掉、或想辨識
 *   某個局部細節時用）
 *
 * 照片用 FIT_CENTER 完整顯示（不像即時預覽那樣裁掉邊緣），因為使用者想圈
 * 的東西可能剛好在邊上；對應的座標換算用 geometry.fitTransform。
 */
@Composable
fun PhotoModeScreen(
    photo: Bitmap,
    regions: List<PhotoRegion>,
    displayMode: DisplayMode,
    textMeasurer: TextMeasurer,
    onRegionTapped: (PhotoRegion) -> Unit,
    onRegionLongPressed: (PhotoRegion) -> Unit,
    onManualRegion: (GeomBox) -> Unit,
) {
    // 使用者正在拖曳中的框（螢幕座標），放開手才會變成正式的 region
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                        },
                        onDrag = { change, _ ->
                            dragCurrent = change.position
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragCurrent
                            dragStart = null
                            dragCurrent = null
                            if (start == null || end == null) return@detectDragGestures

                            // 太小的框當成誤觸，忽略
                            if (abs(end.x - start.x) < MIN_DRAG_SIZE_PX ||
                                abs(end.y - start.y) < MIN_DRAG_SIZE_PX
                            ) {
                                return@detectDragGestures
                            }

                            val transform = fitTransform(
                                photo.width,
                                photo.height,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            // 圈出來的是螢幕座標，換算回照片自己的座標系才能拿去裁切
                            val inPhoto = transform
                                .invert(GeomBox(start.x, start.y, end.x, end.y).normalized())
                                .clampedTo(photo.width, photo.height)
                            onManualRegion(inPhoto)
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        },
                    )
                }
                .pointerInput(photo, regions) {
                    detectTapGestures(
                        onTap = { tap ->
                            val transform = fitTransform(
                                photo.width,
                                photo.height,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            val hit = regions
                                .map { it to transform.apply(it.box) }
                                .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                .minByOrNull { (_, r) -> r.width * r.height }
                                ?.first
                            hit?.let(onRegionTapped)
                        },
                        onLongPress = { tap ->
                            val transform = fitTransform(
                                photo.width,
                                photo.height,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            val hit = regions
                                .map { it to transform.apply(it.box) }
                                .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                .minByOrNull { (_, r) -> r.width * r.height }
                                ?.first
                            hit?.let(onRegionLongPressed)
                        },
                    )
                },
        ) {
            val transform = fitTransform(
                photo.width,
                photo.height,
                size.width,
                size.height,
            )

            regions.forEach { region ->
                val rect = transform.apply(region.box)
                val color = when (region.label) {
                    is LabelState.Named -> Color(0xFF4CAF50)
                    LabelState.Recognizing -> Color(0xFFFFC107)
                    // 手動圈的框用藍色，跟自動偵測的灰框區分開來
                    LabelState.Unknown -> if (region.manual) Color(0xFF2196F3) else Color(0xFF9E9E9E)
                }

                drawRect(
                    color = color,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 4f),
                )

                val text = region.label.displayText(displayMode)
                val layout = textMeasurer.measure(text, TextStyle(fontSize = 16.sp))
                val textW = layout.size.width.toFloat()
                val textH = layout.size.height.toFloat()

                val preferredY = rect.top - textH - 8f
                val textY = if (preferredY >= 0f) preferredY else rect.top + 8f
                val maxX = max(0f, size.width - textW - 8f)
                val textX = rect.left.coerceIn(8f, max(8f, maxX))
                val clampedY = min(max(0f, textY), max(0f, size.height - textH))

                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(textX - 4f, clampedY - 2f),
                    size = Size(textW + 8f, textH + 4f),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(textX, clampedY),
                    color = Color.White,
                )
            }

            // 正在拖曳中的框，用虛線感的細框即時顯示，放開手才會變成正式的 region
            val start = dragStart
            val current = dragCurrent
            if (start != null && current != null) {
                val live = GeomBox(start.x, start.y, current.x, current.y).normalized()
                drawRect(
                    color = Color(0xFF2196F3),
                    topLeft = Offset(live.left, live.top),
                    size = Size(live.width, live.height),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}
