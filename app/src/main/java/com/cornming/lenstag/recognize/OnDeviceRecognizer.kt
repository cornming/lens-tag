package com.cornming.lenstag.recognize

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    // 模型下載在背景跑，不卡住辨識流程。之前的寫法是在 ensureReady 裡直接等整個
    // 下載完成（可能幾百 MB），這段期間畫面只有黃色「…」，看起來像卡住了。
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var downloadInFlight = false

    override suspend fun ensureReady(): FailureReason? {
        return when (generativeModel.checkStatus()) {
            FeatureStatus.AVAILABLE -> null

            FeatureStatus.UNAVAILABLE -> {
                Log.w(TAG, "這台裝置不支援 Gemini Nano，或設定尚未同步")
                FailureReason.NOT_SUPPORTED
            }

            FeatureStatus.DOWNLOADING -> FailureReason.MODEL_DOWNLOADING

            FeatureStatus.DOWNLOADABLE -> {
                startDownloadInBackground()
                FailureReason.MODEL_DOWNLOADING
            }

            else -> FailureReason.UNKNOWN
        }
    }

    private fun startDownloadInBackground() {
        if (downloadInFlight) return
        downloadInFlight = true
        downloadScope.launch {
            try {
                generativeModel.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            Log.d(TAG, "開始下載 Gemini Nano")

                        is DownloadStatus.DownloadProgress ->
                            Log.d(TAG, "已下載 ${status.totalBytesDownloaded} bytes")

                        DownloadStatus.DownloadCompleted ->
                            Log.d(TAG, "Gemini Nano 下載完成")

                        is DownloadStatus.DownloadFailed ->
                            Log.e(TAG, "下載失敗：${status.e.message}")
                    }
                }
            } finally {
                // 不管成功或失敗都放開，失敗的話下一次點擊還能再觸發一次下載
                downloadInFlight = false
            }
        }
    }

    override suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognitionResult {
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
                ?.let { RecognitionResult.Success(it) }
                ?: RecognitionResult.Failure(FailureReason.NO_ANSWER)
        } catch (e: Exception) {
            Log.e(TAG, "辨識失敗", e)
            RecognitionResult.Failure(FailureReason.UNKNOWN, e.message)
        }
    }
}
