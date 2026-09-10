package com.cornming.lenstag.ui

import android.graphics.RectF
import android.util.Size as AndroidSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cornming.lenstag.camera.DetectionResult
import com.cornming.lenstag.camera.ObjectAnalyzer
import com.cornming.lenstag.recognize.ObjectRecognizer
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** 框在畫面上保留的寬限期：物件短暫被遮住或偵測跳掉時，標籤不要立刻閃掉。 */
private const val BOX_GRACE_PERIOD_MS = 500L

/** 標籤文字跟框之間、以及跟螢幕邊緣之間留的間距 */
private const val LABEL_PADDING_PX = 8f

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
 *
 * 雙語支援：辨識時同時跟 Gemini Nano 要原文（繁中）和翻譯名稱，
 * 畫面上可以切換只看原文／只看翻譯／雙語對照，方便學習用途。
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { ObjectRecognizer() }
    val textMeasurer = rememberTextMeasurer()
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    // 追蹤 ID -> 框位置與最後出現時間（含寬限期內、當前影格已經看不到的框）
    val trackedBoxes = remember { mutableStateMapOf<Int, TrackedBox>() }
    // 追蹤 ID -> 標籤狀態
    val labels = remember { mutableStateMapOf<Int, LabelState>() }
    // 來源分析影像的尺寸，用來把偵測座標換算成螢幕座標
    var sourceSize by remember { mutableStateOf(0 to 0) }
    // 使用者點了哪個框要手動命名
    var renamingId by remember { mutableStateOf<Int?>(null) }

    // 顯示模式（原文／翻譯／雙語）與翻譯目標語言。
    // 注意：改變這兩個設定只影響「之後新辨識」的物件，已經辨識過的物件
    // 不會自動重新查詢——這是刻意簡化，避免每次調設定都重打 Gemini Nano。
    var displayMode by remember { mutableStateOf(DisplayMode.BOTH) }
    var secondaryLanguage by remember { mutableStateOf("English") }
    var editingLanguage by remember { mutableStateOf(false) }

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
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    // 分析解析度故意設低（640x480 等級即可），不需要跟預覽一樣高解析度，
                    // 這樣每一次 ML Kit 推論的運算量小很多，也是降低發熱的一環。
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
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
                                    val languageAtRequestTime = secondaryLanguage
                                    scope.launch {
                                        val recognized = if (recognizer.ensureReady()) {
                                            recognizer.recognize(cropped, languageAtRequestTime)
                                        } else {
                                            null
                                        }
                                        // 辨識失敗或裝置不支援就退回 Unknown，
                                        // 框還在、使用者仍然可以點擊手動命名
                                        labels[id] = recognized
                                            ?.let { LabelState.Named(it.primary, it.secondary) }
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
                    detectTapGestures(
                        onTap = onTap@{ tap ->
                            val (sw, sh) = sourceSize
                            if (sw == 0 || sh == 0) return@onTap
                            val transform = previewTransform(sw, sh, size.width.toFloat(), size.height.toFloat())
                            val hitId = trackedBoxes.entries
                                .map { it.key to transform.apply(it.value.box) }
                                .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                .minByOrNull { (_, r) -> r.width() * r.height() }
                                ?.first
                            hitId?.let { id ->
                                (labels[id] as? LabelState.Named)?.let { named ->
                                    speaker.speak(named, displayMode, secondaryLanguage)
                                }
                            }
                        },
                        onLongPress = onLongPress@{ tap ->
                            val (sw, sh) = sourceSize
                            if (sw == 0 || sh == 0) return@onLongPress
                            val transform = previewTransform(sw, sh, size.width.toFloat(), size.height.toFloat())
                            // 找出點到的框（優先選中較小的框，比較符合直覺）
                            renamingId = trackedBoxes.entries
                                .map { it.key to transform.apply(it.value.box) }
                                .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                .minByOrNull { (_, r) -> r.width() * r.height() }
                                ?.first
                        },
                    )
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

                val text = state.displayText(displayMode)
                val layout = textMeasurer.measure(text, TextStyle(fontSize = 16.sp))
                val textW = layout.size.width.toFloat()
                val textH = layout.size.height.toFloat()

                // 預設畫在框的正上方；上面放不下就改畫在框裡面的上緣。
                // 水平方向永遠夾在螢幕寬度內，避免物件靠邊、或雙語文字較寬時被擠出畫面。
                val preferredY = rect.top - textH - LABEL_PADDING_PX
                val textY = if (preferredY >= 0f) preferredY else rect.top + LABEL_PADDING_PX
                val maxX = max(0f, this.size.width - textW - LABEL_PADDING_PX)
                val textX = rect.left.coerceIn(LABEL_PADDING_PX, max(LABEL_PADDING_PX, maxX))
                val clampedY = min(max(0f, textY), max(0f, this.size.height - textH))

                // 文字底下墊一塊半透明底色，不然彩色框線上疊白字常常看不清楚
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
        }

        // 上方控制列：切換顯示模式／翻譯語言
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { displayMode = displayMode.next() }) {
                Text("顯示：${displayMode.label}")
            }
            Button(onClick = { editingLanguage = true }) {
                Text("翻譯：$secondaryLanguage")
            }
        }

        renamingId?.let { id ->
            val current = labels[id] as? LabelState.Named
            RenameDialog(
                initialPrimary = current?.primary.orEmpty(),
                initialSecondary = current?.secondary.orEmpty(),
                onDismiss = { renamingId = null },
                onConfirm = { primary, secondary ->
                    if (primary.isNotBlank()) {
                        labels[id] = LabelState.Named(
                            primary = primary,
                            secondary = secondary.ifBlank { null },
                            custom = true,
                        )
                    }
                    renamingId = null
                },
            )
        }

        if (editingLanguage) {
            LanguageDialog(
                initial = secondaryLanguage,
                onDismiss = { editingLanguage = false },
                onConfirm = { lang ->
                    secondaryLanguage = lang.ifBlank { "English" }
                    editingLanguage = false
                },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initialPrimary: String,
    initialSecondary: String,
    onDismiss: () -> Unit,
    onConfirm: (primary: String, secondary: String) -> Unit,
) {
    var primary by remember { mutableStateOf(initialPrimary) }
    var secondary by remember { mutableStateOf(initialSecondary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("這是什麼？") },
        text = {
            Box {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = primary,
                        onValueChange = { primary = it },
                        label = { Text("顯示名稱（原文）") },
                    )
                    OutlinedTextField(
                        value = secondary,
                        onValueChange = { secondary = it },
                        label = { Text("翻譯名稱（選填）") },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(primary, secondary) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LanguageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻譯成什麼語言？") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("直接輸入語言名稱即可，例如 English、日文、韓文、西班牙文", fontSize = 12.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("目標語言") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 分析影像座標 -> 螢幕座標的換算。
 *
 * PreviewView 預設是 FILL_CENTER：影像等比例放大到填滿畫面，超出的部分被裁掉。
 * 所以縮放倍率取兩軸的較大值，再置中偏移。sourceWidth/sourceHeight 必須已經是
 * 「旋轉後」的尺寸（ObjectAnalyzer 已經處理過），不然這裡會整個算錯。
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
