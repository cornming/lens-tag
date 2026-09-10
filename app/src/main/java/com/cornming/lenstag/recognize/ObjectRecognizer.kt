package com.cornming.lenstag.recognize

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

private const val TAG = "ObjectRecognizer"

/**
 * 用 ML Kit GenAI Prompt API（裝置端 Gemini Nano）做開放詞彙的物件辨識——
 * 也就是專案討論裡選定的「路線二」。
 *
 * 注意：這個 API 目前只在有 AICore 支援的機型（Tensor / 部分 Snapdragon /
 * 部分 Dimensity）能用，而且不支援解鎖過 bootloader 的裝置，呼叫前一定要
 * 先用 ensureReady() 確認狀態，不能假設每台機器都能跑。
 *
 * import 路徑（com.google.mlkit.genai.common / .prompt）是依官方文件推斷、
 * 尚未實際編譯驗證過，如果 Android Studio 抓不到，用自動 import 訂正即可。
 */
class ObjectRecognizer {

    private val generativeModel = Generation.getClient()

    /** 確認 Gemini Nano 在這台裝置上的狀態，必要時觸發下載。回傳 true 表示可以呼叫 recognize()。 */
    suspend fun ensureReady(): Boolean {
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

    /**
     * 把裁切好的單一物件圖片丟給 Gemini Nano，回傳簡短的物件名稱文字（例如「門」）。
     * 失敗或辨識不出來回傳 null。
     *
     * 呼叫端（CameraScreen）負責依追蹤 ID 做快取，同一個物件不要重複呼叫這個函式。
     */
    suspend fun recognize(objectBitmap: Bitmap): String? {
        return try {
            val response = generativeModel.generateContent(
                generateContentRequest(
                    ImagePart(objectBitmap),
                    TextPart("這張圖片中央的物體是什麼？只回答物體名稱本身，不要句子，不要標點符號。"),
                ) {
                    temperature = 0.1f
                    maxOutputTokens = 16
                },
            )
            response.candidates.firstOrNull()?.text?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "辨識失敗", e)
            null
        }
    }
}
