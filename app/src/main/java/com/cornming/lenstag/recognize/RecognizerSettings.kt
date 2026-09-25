package com.cornming.lenstag.recognize

import android.content.Context

/** 要用哪一種辨識方式。 */
enum class RecognizerKind(val label: String) {
    ON_DEVICE("手機內建 AI"),
    AZURE("Azure AI Foundry"),
}

/** 每分鐘呼叫上限的預設值與可調範圍。 */
const val DEFAULT_MAX_CALLS_PER_MINUTE = 20
const val MIN_MAX_CALLS_PER_MINUTE = 10
const val MAX_MAX_CALLS_PER_MINUTE = 120

/**
 * Azure AI Foundry 的連線設定。
 *
 * endpoint 由使用者完整填寫（含 api-version），因為 Azure 有兩種端點形式：
 * - Azure OpenAI：https://<資源>.openai.azure.com/openai/deployments/<部署>/chat/completions?api-version=...
 * - Foundry Models：https://<資源>.services.ai.azure.com/models/chat/completions?api-version=...
 * 讓使用者貼完整網址，兩種都能用，不用我去猜他開的是哪一種資源。
 *
 * simpleModel / complexModel 對應 RecognitionTask 的兩種複雜度。注意：如果用的是
 * Azure OpenAI 形式的端點，模型是綁在網址裡的部署名稱，body 帶的 model 會被忽略，
 * 兩個欄位就沒有分流效果——要真的分流，得用 Foundry Models 形式的端點。
 */
data class AzureSettings(
    val endpoint: String = "",
    val apiKey: String = "",
    val simpleModel: String = "",
    val complexModel: String = "",
    /**
     * 成本保護：開著的時候，即時模式不自動送 Azure，要點框才送。
     * 即時模式每出現一個新的追蹤 ID 就會觸發一次辨識——手機內建 AI 免費無所謂，
     * 但 Azure 每次都要錢，拿著手機走一圈追蹤 ID 不斷產生，可能幾十次呼叫就出去了。
     * 預設開著；想要即時模式也自動用 Azure 辨識（體驗比較流暢）就關掉。
     */
    val liveCostProtection: Boolean = true,
    /**
     * 每分鐘最多幾次 Azure 呼叫。刻意跟上面的開關分開、而且永遠生效：它防的不是
     * 正常使用，是程式邏輯出錯導致狂打 API，這種情況關掉成本保護的人一樣會碰到。
     * 但關掉成本保護後正常使用也可能超過 20 次，所以要能調高。
     */
    val maxCallsPerMinute: Int = DEFAULT_MAX_CALLS_PER_MINUTE,
) {
    fun isUsable(): Boolean =
        endpoint.isNotBlank() && apiKey.isNotBlank() && simpleModel.isNotBlank()

    /**
     * 依任務複雜度選模型。複雜任務沒填就退回用簡單任務那個，
     * 這樣使用者只想填一個模型也能正常運作。
     */
    fun modelFor(task: RecognitionTask): String = when (task) {
        RecognitionTask.SIMPLE -> simpleModel
        RecognitionTask.COMPLEX -> complexModel.ifBlank { simpleModel }
    }
}

/** 辨識相關的所有設定。 */
data class RecognizerSettings(
    val kind: RecognizerKind = RecognizerKind.ON_DEVICE,
    val azure: AzureSettings = AzureSettings(),
) {
    /**
     * 即時模式下，物件一穩定就自動送去辨識嗎？
     * 手機內建 AI 免費，永遠自動；Azure 看成本保護開關。
     */
    fun shouldAutoRecognizeLive(): Boolean = when (kind) {
        RecognizerKind.ON_DEVICE -> true
        RecognizerKind.AZURE -> !azure.liveCostProtection
    }
}

/**
 * 設定的本機儲存。
 *
 * 注意：API key 是明文存在 SharedPreferences 裡。對這個 debug 用途的 App 來說
 * 可接受（其他 App 讀不到別人的 SharedPreferences），但如果之後要正式發布、
 * 或手機有 root，就該換成 EncryptedSharedPreferences。README 有記這件事。
 */
class RecognizerSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RecognizerSettings {
        val kind = runCatching {
            RecognizerKind.valueOf(prefs.getString(KEY_KIND, null) ?: RecognizerKind.ON_DEVICE.name)
        }.getOrDefault(RecognizerKind.ON_DEVICE)

        return RecognizerSettings(
            kind = kind,
            azure = AzureSettings(
                endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
                simpleModel = prefs.getString(KEY_SIMPLE_MODEL, "").orEmpty(),
                complexModel = prefs.getString(KEY_COMPLEX_MODEL, "").orEmpty(),
                // 舊版本沒存過這兩個欄位，讀不到就用預設值（保護開著、上限 20）
                liveCostProtection = prefs.getBoolean(KEY_COST_PROTECTION, true),
                maxCallsPerMinute = clampMaxCalls(
                    prefs.getInt(KEY_MAX_CALLS, DEFAULT_MAX_CALLS_PER_MINUTE),
                ),
            ),
        )
    }

    fun save(settings: RecognizerSettings) {
        prefs.edit()
            .putString(KEY_KIND, settings.kind.name)
            .putString(KEY_ENDPOINT, settings.azure.endpoint)
            .putString(KEY_API_KEY, settings.azure.apiKey)
            .putString(KEY_SIMPLE_MODEL, settings.azure.simpleModel)
            .putString(KEY_COMPLEX_MODEL, settings.azure.complexModel)
            .putBoolean(KEY_COST_PROTECTION, settings.azure.liveCostProtection)
            .putInt(KEY_MAX_CALLS, clampMaxCalls(settings.azure.maxCallsPerMinute))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "recognizer_settings"
        const val KEY_KIND = "kind"
        const val KEY_ENDPOINT = "azure_endpoint"
        const val KEY_API_KEY = "azure_api_key"
        const val KEY_SIMPLE_MODEL = "azure_simple_model"
        const val KEY_COMPLEX_MODEL = "azure_complex_model"
        const val KEY_COST_PROTECTION = "azure_live_cost_protection"
        const val KEY_MAX_CALLS = "azure_max_calls_per_minute"
    }
}

/** 把上限夾在可調範圍內，避免存到 0（等於完全不能用）或離譜的大數字。 */
internal fun clampMaxCalls(value: Int): Int =
    value.coerceIn(MIN_MAX_CALLS_PER_MINUTE, MAX_MAX_CALLS_PER_MINUTE)
