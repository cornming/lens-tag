package com.cornming.lenstag.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.cornming.lenstag.geometry.Box as GeomBox
import com.cornming.lenstag.geometry.photoTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 圈選的框至少要這麼大（螢幕像素）才算數，避免手指輕輕一滑就產生一堆小框。 */
private const val MIN_DRAG_SIZE_PX = 40f

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

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
 * 拍照模式：畫面是靜止的一張照片，滿版顯示，可以雙指縮放/拖曳，
 * 上面疊著可辨識的框。
 *
 * 互動方式（照片是靜止的，所以可以慢慢操作，這也是這個模式存在的理由）：
 * - 雙指捏合縮放、雙指拖曳移動畫面
 * - 點框 -> 辨識；已辨識過的點一下會唸出來
 * - 長按框 -> 命名／標記／查字典
 * - 單指拖曳 -> 圈出任意範圍辨識（自動偵測漏掉、或想辨識局部細節時用）
 *
 * 單指拖曳拿來圈選，雙指才是縮放平移——這樣圈選不需要額外切換模式，
 * 但也不會跟縮放打架。
 *
 * 照片本身不用 Image composable 畫，而是跟框一起畫在同一個 Canvas 上，
 * 這樣照片和框保證用同一個座標轉換，縮放時絕對不會有框跟照片對不齊的問題。
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
    var zoom by remember(photo) { mutableStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }

    // 使用者正在拖曳中的圈選框（螢幕座標），放開手才會變成正式的 region
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    val image = remember(photo) { photo.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // 雙指：縮放與平移
                .pointerInput(photo) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pan = if (zoom <= MIN_ZOOM) {
                            // 縮回原始大小就歸位，避免照片被拖到畫面外找不回來
                            Offset.Zero
                        } else {
                            pan + panChange
                        }
                    }
                }
                // 單指拖曳：圈選範圍
                .pointerInput(photo) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                        },
                        onDrag = { change, _ -> dragCurrent = change.position },
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

                            val transform = photoTransform(
                                photo.width, photo.height,
                                size.width.toFloat(), size.height.toFloat(),
                                zoom, pan.x, pan.y,
                            )
                            // 圈出來的是螢幕座標，換算回照片座標系才能拿去裁切
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
                .pointerInput(photo, regions, zoom, pan) {
                    detectTapGestures(
                        onTap = { tap -> hitRegion(regions, photo, size.width.toFloat(), size.height.toFloat(), zoom, pan, tap)?.let(onRegionTapped) },
                        onLongPress = { tap -> hitRegion(regions, photo, size.width.toFloat(), size.height.toFloat(), zoom, pan, tap)?.let(onRegionLongPressed) },
                    )
                },
        ) {
            val transform = photoTransform(
                photo.width, photo.height,
                size.width, size.height,
                zoom, pan.x, pan.y,
            )

            // 照片跟框畫在同一個 Canvas、用同一個轉換，縮放時保證對得齊
            val photoRect = transform.apply(GeomBox(0f, 0f, photo.width.toFloat(), photo.height.toFloat()))
            drawImage(
                image = image,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    photoRect.left.toInt(),
                    photoRect.top.toInt(),
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    photoRect.width.toInt().coerceAtLeast(1),
                    photoRect.height.toInt().coerceAtLeast(1),
                ),
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

            // 正在圈選中的框，即時顯示，放開手才會變成正式的 region
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

/** 找出點擊位置底下的區域，優先選比較小的框（比較符合直覺）。 */
private fun hitRegion(
    regions: List<PhotoRegion>,
    photo: Bitmap,
    viewWidth: Float,
    viewHeight: Float,
    zoom: Float,
    pan: Offset,
    tap: Offset,
): PhotoRegion? {
    val transform = photoTransform(
        photo.width, photo.height,
        viewWidth, viewHeight,
        zoom, pan.x, pan.y,
    )
    return regions
        .map { it to transform.apply(it.box) }
        .filter { (_, r) -> r.contains(tap.x, tap.y) }
        .minByOrNull { (_, r) -> r.width * r.height }
        ?.first
}
