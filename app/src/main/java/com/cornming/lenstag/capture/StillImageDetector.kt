package com.cornming.lenstag.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.cornming.lenstag.geometry.Box
import com.cornming.lenstag.geometry.toBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "StillImageDetector"

/**
 * 對一張已經拍好的靜態照片跑一次物件偵測。
 *
 * 跟即時模式的 ObjectAnalyzer 不同，這裡用 SINGLE_IMAGE_MODE：
 * 不需要跨影格追蹤（照片是靜止的），而且這個模式的偵測會比 STREAM_MODE
 * 更仔細——STREAM_MODE 為了跟上即時影格會犧牲一些準確度。
 *
 * 回傳的框座標是相對於傳進來的這張 Bitmap，跟照片本身同一個座標系，
 * 所以不會有「即時分析影像 vs 拍照解析度」換算不一致的問題。
 */
class StillImageDetector {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build(),
    )

    suspend fun detect(bitmap: Bitmap): List<Box> = suspendCancellableCoroutine { cont ->
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { objects ->
                cont.resume(objects.map { it.boundingBox.toBox() })
            }
            .addOnFailureListener { e ->
                // 偵測失敗不該讓拍照模式整個不能用——照片還在，使用者仍然可以
                // 自己圈範圍辨識，所以這裡回空清單而不是丟例外。
                Log.w(TAG, "靜態照片偵測失敗", e)
                cont.resume(emptyList())
            }
    }
}

/** 依框裁切照片，座標夾在圖片範圍內，避免圈超出邊界時 crash。 */
fun cropBitmap(source: Bitmap, box: Box): Bitmap? {
    val safe = box.normalized().clampedTo(source.width, source.height)
    val rect = Rect(
        safe.left.toInt(),
        safe.top.toInt(),
        safe.right.toInt(),
        safe.bottom.toInt(),
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
}

/** 把拍出來的照片轉正（ImageCapture 回傳的影像帶著旋轉角度，不轉的話會是躺著的）。 */
fun Bitmap.rotatedBy(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
