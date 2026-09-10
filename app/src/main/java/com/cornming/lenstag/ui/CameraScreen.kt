package com.cornming.lenstag.ui

import android.graphics.RectF
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cornming.lenstag.camera.DetectionResult
import com.cornming.lenstag.camera.ObjectAnalyzer
import com.cornming.lenstag.recognize.ObjectRecognizer
import kotlinx.coroutines.launch
import kotlin.math.max

/** 框在畫面上保留的寬限期：物件短暫被遮住或偵測跳掉時，標籤不要立刻閃掉。 */
private const val BOX_GRACE_PERIOD_MS = 500L

/** 畫面上一個框的當前狀態（含最後一次看到的時間，用來做寬限期）。 */
private data class TrackedBox(
    val box: RectF,
    val lastSeenAt: Long,
)

/**
 * 相機畫面：CameraX 預覽 + ML Kit 物件偵測/追蹤 + Compose Canvas 疊加框與標籤。
 *
 * 三個借自 Tesla Vision 的設計決定：
 * 1. 框先於名稱——偵測到就畫框，辨識不出來就顯示「?」，可點擊手動命名
 * 2. 框穩定不動才觸發辨識（在 ObjectAnalyzer 裡實作）
 * 3. 框消失後保留一段寬限期，避免遮擋造成標籤閃爍
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { ObjectRecognizer() }
    val textMeasurer = rememberTextMeasurer()

    // 追蹤 ID -> 框位置與最後出現時間（含寬限期內、當前影格已經看不到的框）
    val trackedBoxes = remember { mutableStateMapOf<Int, TrackedBox>() }
    // 追蹤 ID -> 標籤狀態
    val labels = remember { mutableStateMapOf<Int, LabelState>() }
    // 來源分析影像的尺寸，用來把偵測座標換算成螢幕座標
    var sourceSize by remember { mutableStateOf(0 to 0) }
    // 使用者點了哪個框要手動命名
    var renamingId by remember { mutableStateOf<Int?>(null) }

    fun onDetected(result: DetectionResult) {
        val now = System.currentTimeMillis()
        sourceSize = result.sourceWidth to result.sourceHeight

        result.boxes.forEach { box ->
            trackedBoxes[box.trackingId] = TrackedBox(box.boundingBox, now)
            labels.putIfAbsent(box.trackingId, LabelState.Unknown)
        }

        // 超過寬限期還沒再出現的框才真正移除
        val expired = trackedBoxes.filterValues { now - it.lastSeenAt > BOX_GRACE_PERIOD_MS }.keys
        expired.forEach { id ->
            trackedBoxes.remove(id)
            labels.remove(id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(ctx),
                        ObjectAnalyzer(
                            onDetected = ::onDetected,
                            onStableObject = { id, cropped ->
                                // 只有「穩定不動」的物件會走到這裡，所以不必再自己節流
                                if (labels[id] !is LabelState.Named) {
                                    labels[id] = LabelState.Recognizing
                                    scope.launch {
                                        val name = if (recognizer.ensureReady()) {
                                            recognizer.recognize(cropped)
                                        } else {
                                            null
                                        }
                                        // 辨識失敗或裝置不支援就退回 Unknown，
                                        // 框還在、使用者仍然可以點擊手動命名
                                        labels[id] = name?.takeIf { it.isNotBlank() }
                                            ?.let { LabelState.Named(it) }
                                            ?: LabelState.Unknown
                                    }
                                }
                            },
                        ),
                    )

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val (sw, sh) = sourceSize
                        if (sw == 0 || sh == 0) return@detectTapGestures
                        val transform = previewTransform(sw, sh, size.width.toFloat(), size.height.toFloat())
                        // 找出點到的框（由小到大排序，優先選中較小的框，比較符合直覺）
                        renamingId = trackedBoxes.entries
                            .map { it.key to transform.apply(it.value.box) }
                            .filter { (_, r) -> r.contains(tap.x, tap.y) }
                            .minByOrNull { (_, r) -> r.width() * r.height() }
                            ?.first
                    }
                },
        ) {
            val (sw, sh) = sourceSize
            if (sw == 0 || sh == 0) return@Canvas
            val transform = previewTransform(sw, sh, size.width, size.height)

            trackedBoxes.forEach { (id, tracked) ->
                val rect = transform.apply(tracked.box)
                val state = labels[id] ?: LabelState.Unknown

                val color = when (state) {
                    is LabelState.Named -> Color(0xFF4CAF50)
                    LabelState.Recognizing -> Color(0xFFFFC107)
                    LabelState.Unknown -> Color(0xFF9E9E9E)
                }

                drawRect(
                    color = color,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width(), rect.height()),
                    style = Stroke(width = 4f),
                )

                val text = when (state) {
                    is LabelState.Named -> state.text
                    LabelState.Recognizing -> "…"
                    LabelState.Unknown -> "?"
                }

                drawText(
                    textMeasurer = textMeasurer,
                    text = text,
                    topLeft = Offset(rect.left, max(0f, rect.top - 48f)),
                    style = TextStyle(color = color, fontSize = 18.sp),
                )
            }
        }

        renamingId?.let { id ->
            RenameDialog(
                initial = (labels[id] as? LabelState.Named)?.text.orEmpty(),
                onDismiss = { renamingId = null },
                onConfirm = { name ->
                    if (name.isNotBlank()) {
                        labels[id] = LabelState.Named(name, custom = true)
                    }
                    renamingId = null
                },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("這是什麼？") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("顯示名稱") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 分析影像座標 -> 螢幕座標的換算。
 *
 * PreviewView 預設是 FILL_CENTER：影像等比例放大到填滿畫面，超出的部分被裁掉。
 * 所以縮放倍率取兩軸的較大值，再置中偏移。
 */
private class PreviewTransform(
    private val scale: Float,
    private val offsetX: Float,
    private val offsetY: Float,
) {
    fun apply(box: RectF) = RectF(
        box.left * scale + offsetX,
        box.top * scale + offsetY,
        box.right * scale + offsetX,
        box.bottom * scale + offsetY,
    )
}

private fun previewTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
): PreviewTransform {
    val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
    return PreviewTransform(
        scale = scale,
        offsetX = (viewWidth - sourceWidth * scale) / 2f,
        offsetY = (viewHeight - sourceHeight * scale) / 2f,
    )
}
