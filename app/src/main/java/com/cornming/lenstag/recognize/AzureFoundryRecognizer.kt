package com.cornming.lenstag.recognize

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AzureFoundryRecognizer"

/** 送去 Azure 前先把圖片縮到這個邊長以內，省流量也省 token，辨識品質影響很小。 */
private const val MAX_UPLOAD_EDGE = 768

/** 每分鐘最多幾次 Azure 呼叫。安全網，正常手動操作碰不到這個上限。 */
private const val MAX_CALLS_PER_MINUTE = 20

/**
 * 打 Azure AI Foundry 的 chat completions 端點做辨識。
 *
 * 相對於裝置端的 Gemini Nano，這條路線的取捨是：
 * - 好處：不挑機型（沒有 AICore 的手機也能用）、模型大得多所以辨識準確度高、
 *   而且可以依任務複雜度換不同模型
 * - 代價：要有網路、要自己的 Azure 資源、每次呼叫都有成本
 *
 * 請求格式用的是 OpenAI 相容的 chat completions，圖片以 base64 data URL 放在
 * message content 裡；認證用 Azure 慣例的 api-key header。端點 URL 由使用者
 * 完整填寫（含 api-version），這樣 Azure OpenAI 形式和 Foundry Models 形式
 * 的端點都能用，不用我猜他的資源是哪一種。
 */
class AzureFoundryRecognizer(
    private val settings: AzureSettings,
    private val rateLimiter: RateLimiter = RateLimiter(MAX_CALLS_PER_MINUTE, 60_000L),
) : Recognizer {

    override val displayName = "Azure AI Foundry"

    override suspend fun ensureReady(): Boolean = settings.isUsable()

    override suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognizedLabel? = withContext(Dispatchers.IO) {
        if (!settings.isUsable()) return@withContext null
        if (!rateLimiter.tryAcquire()) {
            Log.w(TAG, "已達每分鐘 $MAX_CALLS_PER_MINUTE 次的呼叫上限，這次先不送")
            return@withContext null
        }

        val model = settings.modelFor(task)
        return@withContext try {
            val body = buildRequestBody(
                model = model,
                prompt = buildPrompt(secondaryLanguage),
                imageDataUrl = objectBitmap.toDataUrl(),
            )

            val connection = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("api-key", settings.apiKey)
                // 也帶 Authorization，有些 Foundry 端點吃的是 Bearer 而不是 api-key
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                connectTimeout = 10_000
                readTimeout = 30_000
            }

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Azure 回傳 $code：${error?.take(500)}")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseLabelResponse(extractContent(response))
        } catch (e: Exception) {
            Log.e(TAG, "呼叫 Azure 失敗", e)
            null
        }
    }
}

/** 組 OpenAI 相容的 chat completions 請求內容。抽出來方便寫測試。 */
internal fun buildRequestBody(model: String, prompt: String, imageDataUrl: String): String {
    val textPart = JSONObject().apply {
        put("type", "text")
        put("text", prompt)
    }
    val imagePart = JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply { put("url", imageDataUrl) })
    }
    val userMessage = JSONObject().apply {
        put("role", "user")
        put("content", JSONArray().put(textPart).put(imagePart))
    }
    return JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().put(userMessage))
        put("max_tokens", 32)
        put("temperature", 0.1)
    }.toString()
}

/** 從 chat completions 回應裡取出模型講的那段文字；格式不對就回 null。 */
internal fun extractContent(responseBody: String): String? = try {
    JSONObject(responseBody)
        .optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** 縮圖 + 壓成 JPEG + base64，組成 data URL。 */
private fun Bitmap.toDataUrl(): String {
    val scaled = downscaled(MAX_UPLOAD_EDGE)
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$encoded"
}

private fun Bitmap.downscaled(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}
