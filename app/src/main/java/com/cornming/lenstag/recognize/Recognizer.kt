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
 * 辨識器的共同介面。目前有兩種實作：
 * - OnDeviceRecognizer：手機內建的 Gemini Nano，離線、免費、但受限於機型
 * - AzureFoundryRecognizer：打 Azure AI Foundry 端點，準確度高、不挑機型，
 *   但需要網路和自己的 Azure 資源
 */
interface Recognizer {
    /**
     * 確認這個辨識器現在可不可以用（例如裝置支不支援、設定填了沒）。
     * 必要時會做初始化工作（例如觸發模型下載）。
     */
    suspend fun ensureReady(): Boolean

    /**
     * 辨識一張裁切好的物件圖片。
     * @param secondaryLanguage 要翻譯成什麼語言；null 或空字串表示只要原文
     * @param task 任務複雜度，實作可以據此選擇不同的模型
     */
    suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognizedLabel?

    /** 顯示在設定畫面上的名稱，以及目前不能用時要告訴使用者的原因。 */
    val displayName: String
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
