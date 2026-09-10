package com.cornming.lenstag.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cornming.lenstag.camera.DetectedBox
import com.cornming.lenstag.camera.ObjectAnalyzer
import com.cornming.lenstag.recognize.ObjectRecognizer
import kotlinx.coroutines.launch

/**
 * 相機畫面：CameraX 預覽 + ML Kit 物件偵測/追蹤 + Compose Canvas 疊加框。
 *
 * 目前狀態（第一版骨架，尚未完成的部分見下面兩個 TODO）：
 * - 偵測、追蹤、畫框：完整可動
 * - 依追蹤 ID 觸發 Gemini Nano 辨識：邏輯骨架已接上，但「裁切 Bitmap」和
 *   「分析影格座標 → 螢幕座標換算」這兩塊還沒做，先留 TODO 掛勾
 * - 自訂顯示名稱／翻譯的本地儲存：還沒開始，下一步再加
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { ObjectRecognizer() }

    var boxes by remember { mutableStateOf<List<DetectedBox>>(emptyList()) }
    // 追蹤 ID -> 辨識出的文字標籤（session 快取；還沒接本地儲存，重開 App 會清空）
    val labelCache = remember { mutableStateMapOf<Int, String>() }
    // 正在辨識中的追蹤 ID，避免同一個物件短時間內重複觸發
    val pendingIds = remember { mutableStateMapOf<Int, Boolean>() }

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

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(ctx),
                        ObjectAnalyzer { detected ->
                            boxes = detected

                            detected.forEach { box ->
                                val id = box.trackingId
                                val alreadyKnown = labelCache.containsKey(id)
                                val alreadyPending = pendingIds.containsKey(id)

                                if (!alreadyKnown && !alreadyPending) {
                                    pendingIds[id] = true
                                    scope.launch {
                                        if (recognizer.ensureReady()) {
                                            // TODO: 這裡要從目前影格依 box.boundingBox 裁切出
                                            // 該物件的 Bitmap，再丟給 recognizer.recognize()。
                                            // 裁切邏輯建議接在 ObjectAnalyzer 那層（原始 ImageProxy
                                            // 還在），裁切完再透過 callback 把 Bitmap 一起帶出來。
                                        }
                                        pendingIds.remove(id)
                                    }
                                }
                            }
                        },
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

        // TODO: 這裡的框目前是直接用 ML Kit 回傳的影像座標畫，還沒對齊
        // PreviewView 實際顯示的縮放/裁切，框的位置在大多數機型上會有偏移，
        // 需要另外做 analysis 影像座標 -> 螢幕座標的轉換。
        Canvas(modifier = Modifier.fillMaxSize()) {
            boxes.forEach { box ->
                val rect = box.boundingBox
                drawRect(
                    color = Color.Green,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width(), rect.height()),
                    style = Stroke(width = 4f),
                )
            }
        }
    }
}
