package com.cornming.lenstag.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cornming.lenstag.geometry.Box
import com.cornming.lenstag.geometry.StabilityTracker
import com.cornming.lenstag.geometry.toBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/** 一個被 ML Kit 偵測到、且有追蹤 ID 的物件框。座標是「已旋轉後」的分析影像座標。 */
data class DetectedBox(
    val trackingId: Int,
    val boundingBox: Box,
)

/**
 * 一次分析的結果。帶上來源影像的尺寸，好讓 UI 能把分析影像座標換算成螢幕座標。
 */
data class DetectionResult(
    val boxes: List<DetectedBox>,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * 兩次 ML Kit 偵測之間至少要間隔多久（毫秒），可以動態調整（從畫面上的設定
 * 對話框改），不用重建整個 CameraX pipeline。數字越小越靈敏也越耗電發熱。
 */
class AnalysisSettings(initialIntervalMs: Long = 150L) {
    @Volatile
    var intervalMs: Long = initialIntervalMs
}

/**
 * VR 模式才需要看到完整影格（一般模式只需要框的座標，不需要真的拿整張圖）。
 * 用一個可以動態開關的容器傳進 ObjectAnalyzer，這樣平常模式完全不會多做
 * Bitmap 轉換這筆額外開銷，只有切到 VR 模式才會付出這個成本。
 */
class FrameSink {
    @Volatile
    var onFrame: ((Bitmap) -> Unit)? = null
}

/**
 * 持續跑 ML Kit Object Detection & Tracking（STREAM_MODE），
 * 每一影格輸出目前偵測到的物件框＋追蹤 ID。
 *
 * 設計原則（借自 Tesla Occupancy Network 的「幾何優先於分類」）：
 * 這一層只負責「那裡有沒有東西、在哪裡」，完全不管「那是什麼」。
 * 偵測不需要知道類別，所以看到訓練資料沒有的東西也照樣框得出來；
 * 至於「這是什麼」，交給 recognize 套件的 Gemini Nano 事後補上。
 *
 * 穩定度判斷（靜止 vs 移動）已經抽到 geometry.StabilityTracker，這裡只負責
 * 把 ML Kit / CameraX 的型別轉成純 Kotlin 的 Box 再丟給它——這樣穩定度的
 * 數學可以獨立寫單元測試，不用整個 CameraX pipeline 一起跑才能驗證。
 */
class ObjectAnalyzer(
    private val onDetected: (DetectionResult) -> Unit,
    private val onStableObject: (trackingId: Int, cropped: Bitmap) -> Unit,
    private val frameSink: FrameSink,
    private val analysisSettings: AnalysisSettings,
) : ImageAnalysis.Analyzer {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build(),
    )

    private val stability = StabilityTracker()

    /** 上一次真正跑 ML Kit 的時間，用來節流分析頻率 */
    private var lastAnalyzedAtMs = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // 相機預覽本身不受影響，只是「拿去跑 ML Kit 偵測」的頻率降到這個間隔一次。
        // 手機發燙主要是因為持續在相機原生 fps（常見 30fps）全速跑模型推論；
        // 這種用途的物件根本不需要每影格都判斷一次，跳過的影格直接關閉、不處理。
        // 間隔數字讀 analysisSettings.intervalMs，使用者可以在畫面上動態調整。
        if (now - lastAnalyzedAtMs < analysisSettings.intervalMs) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        // ML Kit 回傳的 boundingBox 是「旋轉後」的座標系，但 imageProxy 的
        // width/height 是原始 buffer（未旋轉）的尺寸。手機直立時 rotation 通常是
        // 90 度，寬高剛好顛倒——這裡不交換的話，UI 換算出來的框位置就會整個歪掉。
        val quarterTurned = rotation == 90 || rotation == 270
        val sourceWidth = if (quarterTurned) imageProxy.height else imageProxy.width
        val sourceHeight = if (quarterTurned) imageProxy.width else imageProxy.height

        detector.process(image)
            .addOnSuccessListener { objects ->
                val boxes = objects.mapNotNull { obj ->
                    obj.trackingId?.let { id ->
                        DetectedBox(trackingId = id, boundingBox = obj.boundingBox.toBox())
                    }
                }

                onDetected(
                    DetectionResult(
                        boxes = boxes,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                    ),
                )

                val stableIds = stability.update(boxes.associate { it.trackingId to it.boundingBox })
                if (stableIds.isNotEmpty()) {
                    emitCrops(imageProxy, rotation, boxes, stableIds)
                }

                // 只有 VR 模式會設定這個 callback，一般模式維持零成本
                frameSink.onFrame?.let { callback ->
                    try {
                        callback(imageProxy.toBitmap().rotated(rotation))
                    } catch (e: Exception) {
                        // 轉檔失敗就跳過這一影格，VR 畫面停格一下沒關係，下一輪會補上
                    }
                }

                stability.prune(boxes.map { it.trackingId }.toSet())
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** 把穩定物件從當前影格裁切出來，交給呼叫端去辨識。 */
    @ExperimentalGetImage
    private fun emitCrops(
        imageProxy: ImageProxy,
        rotation: Int,
        boxes: List<DetectedBox>,
        stableIds: List<Int>,
    ) {
        val fullBitmap = try {
            imageProxy.toBitmap().rotated(rotation)
        } catch (e: Exception) {
            return
        }

        stableIds.forEach { id ->
            val box = boxes.firstOrNull { it.trackingId == id } ?: return@forEach
            cropSafely(fullBitmap, box.boundingBox)?.let { cropped ->
                onStableObject(id, cropped)
            }
        }
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** 依框裁切，並把座標夾在圖片範圍內，避免框超出邊界時 crash。 */
private fun cropSafely(source: Bitmap, box: Box): Bitmap? {
    val rect = Rect(
        box.left.toInt().coerceIn(0, source.width - 1),
        box.top.toInt().coerceIn(0, source.height - 1),
        box.right.toInt().coerceIn(1, source.width),
        box.bottom.toInt().coerceIn(1, source.height),
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
}
