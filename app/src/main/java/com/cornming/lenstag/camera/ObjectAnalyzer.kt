package com.cornming.lenstag.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlin.math.abs

/** 一個被 ML Kit 偵測到、且有追蹤 ID 的物件框。座標是「已旋轉後」的分析影像座標。 */
data class DetectedBox(
    val trackingId: Int,
    val boundingBox: RectF,
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
 * 持續跑 ML Kit Object Detection & Tracking（STREAM_MODE），
 * 每一影格輸出目前偵測到的物件框＋追蹤 ID。
 *
 * 設計原則（借自 Tesla Occupancy Network 的「幾何優先於分類」）：
 * 這一層只負責「那裡有沒有東西、在哪裡」，完全不管「那是什麼」。
 * 偵測不需要知道類別，所以看到訓練資料沒有的東西也照樣框得出來；
 * 至於「這是什麼」，交給 recognize 套件的 Gemini Nano 事後補上。
 *
 * 另外借用「靜止 vs 移動」的概念：連續幾影格位移都很小的框才算 stable，
 * 只有 stable 的物件才觸發辨識——此時裁切出來的圖最清晰，也順便節流 AICore quota。
 */
class ObjectAnalyzer(
    private val onDetected: (DetectionResult) -> Unit,
    private val onStableObject: (trackingId: Int, cropped: Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build(),
    )

    /** 追蹤 ID -> 上一次的框位置，用來算位移 */
    private val lastBoxes = mutableMapOf<Int, RectF>()

    /** 追蹤 ID -> 已經連續穩定幾影格 */
    private val stableFrames = mutableMapOf<Int, Int>()

    /** 已經送出去辨識過的追蹤 ID，不重複送 */
    private val alreadyEmitted = mutableSetOf<Int>()

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
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
                        DetectedBox(trackingId = id, boundingBox = RectF(obj.boundingBox))
                    }
                }

                onDetected(
                    DetectionResult(
                        boxes = boxes,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                    ),
                )

                val stableIds = updateStability(boxes)
                if (stableIds.isNotEmpty()) {
                    emitCrops(imageProxy, rotation, boxes, stableIds)
                }

                pruneVanished(boxes.map { it.trackingId }.toSet())
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** 更新每個追蹤 ID 的穩定度，回傳這一影格「剛好變成穩定、且還沒送出去辨識」的 ID。 */
    private fun updateStability(boxes: List<DetectedBox>): List<Int> {
        val newlyStable = mutableListOf<Int>()

        boxes.forEach { box ->
            val id = box.trackingId
            val previous = lastBoxes[id]
            lastBoxes[id] = RectF(box.boundingBox)

            if (previous == null) {
                stableFrames[id] = 0
                return@forEach
            }

            // 用框中心的位移量當穩定度指標，門檻取框寬度的一個比例，
            // 這樣近距離的大物件跟遠距離的小物件會用差不多寬鬆的標準
            val dx = abs(box.boundingBox.centerX() - previous.centerX())
            val dy = abs(box.boundingBox.centerY() - previous.centerY())
            val threshold = box.boundingBox.width() * MOVEMENT_THRESHOLD_RATIO

            if (dx < threshold && dy < threshold) {
                val frames = (stableFrames[id] ?: 0) + 1
                stableFrames[id] = frames
                if (frames == REQUIRED_STABLE_FRAMES && id !in alreadyEmitted) {
                    alreadyEmitted.add(id)
                    newlyStable.add(id)
                }
            } else {
                // 物件又動起來了，穩定度歸零重算
                stableFrames[id] = 0
            }
        }

        return newlyStable
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

    /** 追蹤 ID 消失時清掉狀態，避免這幾個 map 無限成長。 */
    private fun pruneVanished(currentIds: Set<Int>) {
        val gone = lastBoxes.keys - currentIds
        gone.forEach { id ->
            lastBoxes.remove(id)
            stableFrames.remove(id)
            // alreadyEmitted 刻意不清：同一個 ID 短時間內重新出現時不該重跑辨識。
            // ML Kit 的追蹤 ID 是遞增的，長時間下來不會撞號。
        }
    }

    private companion object {
        /** 框中心位移小於「框寬 × 這個比例」就算沒動 */
        const val MOVEMENT_THRESHOLD_RATIO = 0.02f

        /** 要連續穩定這麼多影格才觸發辨識 */
        const val REQUIRED_STABLE_FRAMES = 5
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** 依框裁切，並把座標夾在圖片範圍內，避免框超出邊界時 crash。 */
private fun cropSafely(source: Bitmap, box: RectF): Bitmap? {
    val rect = Rect(
        box.left.toInt().coerceIn(0, source.width - 1),
        box.top.toInt().coerceIn(0, source.height - 1),
        box.right.toInt().coerceIn(1, source.width),
        box.bottom.toInt().coerceIn(1, source.height),
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
}
