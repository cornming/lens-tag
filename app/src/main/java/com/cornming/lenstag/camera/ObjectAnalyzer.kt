package com.cornming.lenstag.camera

import android.graphics.RectF
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/** 一個被 ML Kit 偵測到、且有追蹤 ID 的物件框。 */
data class DetectedBox(
    val trackingId: Int,
    val boundingBox: RectF,
)

/**
 * 持續跑 ML Kit Object Detection & Tracking（STREAM_MODE），
 * 每一影格輸出目前偵測到的物件框＋追蹤 ID。
 *
 * 這一層刻意不做分類——粗略分類器只有 5 個大類，不夠細（見專案討論記錄），
 * 真正「這是什麼東西」交給 recognize 套件裡的 ObjectRecognizer（Gemini Nano）處理。
 */
class ObjectAnalyzer(
    private val onDetected: (List<DetectedBox>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build(),
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { objects ->
                val boxes = objects.mapNotNull { obj ->
                    obj.trackingId?.let { id ->
                        DetectedBox(trackingId = id, boundingBox = RectF(obj.boundingBox))
                    }
                }
                onDetected(boxes)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
