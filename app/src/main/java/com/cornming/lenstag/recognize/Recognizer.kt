package com.cornming.lenstag.recognize

import android.graphics.Bitmap

/** 一次辨識結果：原文名稱（繁體中文）+ 翻譯名稱（可能沒有）。 */
data class RecognizedLabel(
    val primary: String,
    val secondary: String?,
)

/**
 * 辨識任務的複雜度，用來決定要派哪個模型去跑。
 *
 * 這個區分是有實際依據的，不是為了分而分：
 * - SIMPLE：ML Kit 已經框出一個明確的單一物件，裁切出來的圖就是那個東西，
 *   「這是什麼」通常一眼可辨。小模型就夠，換來的是更快的回應和更低的成本。
 * - COMPLEX：使用者在拍照模式自己圈出來的範圍。會自己動手圈，通常正是因為
 *   自動偵測沒框出來——可能是局部細節、文字、或多個東西疊在一起的場景，
 *   需要更強的視覺理解。這時候值得用比較大的模型。
 */
enum class RecognitionTask { SIMPLE, COMPLEX }

/**
 * 辨識失敗的原因，附一個畫面上顯示用的短標籤。
 *
 * 之前不管哪種失敗都回 null，畫面上全部是同一個灰色問號——裝置不支援、
 * Azure 金鑰填錯、模型名稱打錯、沒網路，完全分不出來。尤其 Azure 要手動填
 * 四個欄位，填錯的機率不低，沒有這些區分根本無從除錯。
 */
enum class FailureReason(val shortLabel: String) {
    NOT_SUPPORTED("裝置不支援"),
    MODEL_DOWNLOADING("模型下載中"),
    NOT_CONFIGURED("未設定"),
    RATE_LIMITED("已達上限"),
    AUTH("金鑰錯誤"),
    NOT_FOUND("端點或模型錯誤"),
    BAD_REQUEST("請求被拒"),
    NETWORK("網路錯誤"),
    SERVER("伺服器錯誤"),
    NO_ANSWER("無法辨識"),
    UNKNOWN("未知錯誤"),
}

/** 一次辨識的結果：成功就帶標籤，失敗就帶原因（detail 是給人看的原始錯誤訊息）。 */
sealed interface RecognitionResult {
    data class Success(val label: RecognizedLabel) : RecognitionResult
    data class Failure(val reason: FailureReason, val detail: String? = null) : RecognitionResult
}

/**
 * 辨識器的共同介面。目前有兩種實作：
 * - OnDeviceRecognizer：手機內建的 Gemini Nano，離線、免費、但受限於機型
 * - AzureFoundryRecognizer：打 Azure AI Foundry 端點，準確度高、不挑機型，
 *   但需要網路和自己的 Azure 資源
 */
interface Recognizer {
    /**
     * 確認這個辨識器現在可不可以用。可以用回 null；不能用回原因。
     * 必要時會啟動初始化工作（例如在背景觸發模型下載），但不會卡住等它完成。
     */
    suspend fun ensureReady(): FailureReason?

    /**
     * 辨識一張裁切好的物件圖片。
     * @param secondaryLanguage 要翻譯成什麼語言；null 或空字串表示只要原文
     * @param task 任務複雜度，實作可以據此選擇不同的模型
     */
    suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognitionResult

    /** 顯示在設定畫面上的名稱。 */
    val displayName: String
}

/** 先確認可用、再辨識。呼叫端統一走這個，不用每處都自己寫一次檢查。 */
suspend fun Recognizer.recognizeIfReady(
    objectBitmap: Bitmap,
    secondaryLanguage: String?,
    task: RecognitionTask,
): RecognitionResult {
    ensureReady()?.let { return RecognitionResult.Failure(it) }
    return recognize(objectBitmap, secondaryLanguage, task)
}

/**
 * 把模型回傳的文字拆成原文和翻譯。
 *
 * 約定的格式是「原文|翻譯」，但模型不一定每次都乖乖照格式回（尤其是小模型），
 * 所以這裡做得寬容一點：拆不出第二段就只當作原文，整段空的才回 null。
 * 抽成獨立函式方便直接寫測試，不用真的打一次模型才能驗證解析邏輯。
 */
internal fun parseLabelResponse(raw: String?): RecognizedLabel? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.trim().split("|").map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> RecognizedLabel(primary = parts[0], secondary = parts[1])
        parts.size == 1 -> RecognizedLabel(primary = parts[0], secondary = null)
        else -> null
    }
}

/** 產生要送給模型的提示詞，兩種辨識器共用，確保回覆格式一致。 */
internal fun buildPrompt(secondaryLanguage: String?): String =
    if (secondaryLanguage.isNullOrBlank()) {
        "這張圖片中央的物體是什麼？只回答物體名稱本身，不要句子，不要標點符號。"
    } else {
        "這張圖片中央的物體是什麼？用「繁體中文名稱|${secondaryLanguage}名稱」的格式回答，" +
            "例如「門|door」，只回答這個格式，不要其他文字。"
    }
