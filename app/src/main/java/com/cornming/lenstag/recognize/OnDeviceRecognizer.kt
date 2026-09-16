package com.cornming.lenstag.recognize

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

private const val TAG = "OnDeviceRecognizer"

/**
 * 用 ML Kit GenAI Prompt API（裝置端 Gemini Nano）做開放詞彙的物件辨識。
 *
 * 注意：這個 API 目前只在有 AICore 支援的機型（Tensor / 部分 Snapdragon /
 * 部分 Dimensity）能用，而且不支援解鎖過 bootloader 的裝置，呼叫前一定要
 * 先用 ensureReady() 確認狀態，不能假設每台機器都能跑。
 *
 * 裝置端只有一個模型，所以 RecognitionTask 對這個實作沒有作用——
 * 複雜度調度是 Azure 那條路線才有的能力。
 *
 * import 路徑（com.google.mlkit.genai.common / .prompt）是依官方文件推斷、
 * 尚未實際編譯驗證過，如果 Android Studio 抓不到，用自動 import 訂正即可。
 */
class OnDeviceRecognizer : Recognizer {

    override val displayName = "手機內建 AI（Gemini Nano）"

    private val generativeModel = Generation.getClient()

    override suspend fun ensureReady(): Boolean {
        return when (generativeModel.checkStatus()) {
            FeatureStatus.AVAILABLE -> true

            FeatureStatus.UNAVAILABLE -> {
                Log.w(TAG, "這台裝置不支援 Gemini Nano，或設定尚未同步")
                false
            }

            FeatureStatus.DOWNLOADING -> {
                Log.d(TAG, "Gemini Nano 下載中，這次先略過辨識")
                false
            }

            FeatureStatus.DOWNLOADABLE -> {
                var success = false
                generativeModel.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            Log.d(TAG, "開始下載 Gemini Nano")

                        is DownloadStatus.DownloadProgress ->
                            Log.d(TAG, "已下載 ${status.totalBytesDownloaded} bytes")

                        DownloadStatus.DownloadCompleted -> {
                            Log.d(TAG, "Gemini Nano 下載完成")
                            success = true
                        }

                        is DownloadStatus.DownloadFailed ->
                            Log.e(TAG, "下載失敗：${status.e.message}")
                    }
                }
                success
            }

            else -> false
        }
    }

    override suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognizedLabel? {
        return try {
            val response = generativeModel.generateContent(
                generateContentRequest(
                    ImagePart(objectBitmap),
                    TextPart(buildPrompt(secondaryLanguage)),
                ) {
                    temperature = 0.1f
                    maxOutputTokens = 32
                },
            )
            parseLabelResponse(response.candidates.firstOrNull()?.text)
        } catch (e: Exception) {
            Log.e(TAG, "辨識失敗", e)
            null
        }
    }
}
