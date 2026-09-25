package com.cornming.lenstag.recognize

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException

private const val TAG = "AzureFoundryRecognizer"

/** 送去 Azure 前先把圖片縮到這個邊長以內，省流量也省 token，辨識品質影響很小。 */
private const val MAX_UPLOAD_EDGE = 768


/** 測試連線的結果：哪個模型、成功或失敗在哪。 */
data class ConnectionTestResult(val model: String, val result: RecognitionResult)

/**
 * 打 Azure AI Foundry 的 chat completions 端點做辨識。
 *
 * 相對於裝置端的 Gemini Nano，這條路線的取捨是：
 * - 好處：不挑機型、模型大得多所以辨識準確度高、可以依任務複雜度換模型
 * - 代價：要有網路、要自己的 Azure 資源、每次呼叫都有成本
 *
 * 請求格式用的是 OpenAI 相容的 chat completions，圖片以 base64 data URL 放在
 * message content 裡；認證用 Azure 慣例的 api-key header。端點 URL 由使用者
 * 完整填寫（含 api-version），這樣 Azure OpenAI 形式和 Foundry Models 形式
 * 的端點都能用。
 */
class AzureFoundryRecognizer(
    private val settings: AzureSettings,
    private val rateLimiter: RateLimiter = RateLimiter(settings.maxCallsPerMinute, 60_000L),
) : Recognizer {

    override val displayName = "Azure AI Foundry"

    override suspend fun ensureReady(): FailureReason? =
        if (settings.isUsable()) null else FailureReason.NOT_CONFIGURED

    override suspend fun recognize(
        objectBitmap: Bitmap,
        secondaryLanguage: String?,
        task: RecognitionTask,
    ): RecognitionResult {
        if (!settings.isUsable()) return RecognitionResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!rateLimiter.tryAcquire()) {
            return RecognitionResult.Failure(
                FailureReason.RATE_LIMITED,
                "已達每分鐘 ${rateLimiter.maxCalls} 次的呼叫上限（可以在「辨識」設定裡調高）",
            )
        }
        return postChatCompletion(
            settings = settings,
            body = buildRequestBody(
                model = settings.modelFor(task),
                prompt = buildPrompt(secondaryLanguage),
                imageDataUrl = objectBitmap.toDataUrl(),
            ),
        )
    }

    /**
     * 用一張很小的純色圖測試每個有填的模型，逐一回報結果。
     *
     * 刻意送圖而不是純文字：純文字請求只能驗證端點、金鑰、模型名稱，但驗證不到
     * 「這個模型看不看得懂圖」。如果部署的是不支援影像的模型，純文字測試會
     * 顯示成功，實際辨識卻全部失敗——那種測試只會給錯誤的安心感。
     *
     * 測試連線是使用者主動、低頻的動作，不經過限流。
     */
    suspend fun testConnection(): List<ConnectionTestResult> {
        if (!settings.isUsable()) {
            return listOf(
                ConnectionTestResult(
                    model = settings.simpleModel.ifBlank { "（未填）" },
                    result = RecognitionResult.Failure(
                        FailureReason.NOT_CONFIGURED,
                        "端點網址、API Key、一般模型三個欄位都要填",
                    ),
                ),
            )
        }

        val probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.toDataUrl()

        val models = listOf(settings.simpleModel, settings.complexModel)
            .filter { it.isNotBlank() }
            .distinct()

        return models.map { model ->
            ConnectionTestResult(
                model = model,
                result = postChatCompletion(
                    settings = settings,
                    body = buildRequestBody(
                        model = model,
                        prompt = "這張圖片是什麼顏色？只回答顏色名稱。",
                        imageDataUrl = probe,
                    ),
                ),
            )
        }
    }
}

/** 送出請求，把 HTTP 層和網路層的各種失敗都轉成具體的 FailureReason。 */
private suspend fun postChatCompletion(
    settings: AzureSettings,
    body: String,
): RecognitionResult = withContext(Dispatchers.IO) {
    try {
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
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            val message = extractErrorMessage(errorBody)
            Log.e(TAG, "Azure 回傳 $code：${message ?: errorBody?.take(300)}")
            return@withContext RecognitionResult.Failure(
                reason = reasonForHttpStatus(code),
                detail = "HTTP $code" + (message?.let { "：$it" } ?: ""),
            )
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        parseLabelResponse(extractContent(response))
            ?.let { RecognitionResult.Success(it) }
            ?: RecognitionResult.Failure(FailureReason.NO_ANSWER, "模型有回應，但內容是空的")
    } catch (e: MalformedURLException) {
        // 注意要排在 IOException 前面：MalformedURLException 是它的子類別
        RecognitionResult.Failure(FailureReason.NOT_FOUND, "端點網址格式不對：${e.message}")
    } catch (e: UnknownHostException) {
        RecognitionResult.Failure(
            FailureReason.NETWORK,
            "找不到主機 ${e.message}（網址打錯，或手機沒有網路）",
        )
    } catch (e: IOException) {
        RecognitionResult.Failure(FailureReason.NETWORK, e.message)
    } catch (e: Exception) {
        Log.e(TAG, "呼叫 Azure 失敗", e)
        RecognitionResult.Failure(FailureReason.UNKNOWN, e.message)
    }
}

/**
 * HTTP 狀態碼對應到使用者看得懂的失敗原因。抽成純函式方便測試。
 * 401/403 金鑰、404 端點或部署名稱、429 Azure 端的配額限制。
 */
internal fun reasonForHttpStatus(code: Int): FailureReason = when (code) {
    401, 403 -> FailureReason.AUTH
    404 -> FailureReason.NOT_FOUND
    429 -> FailureReason.RATE_LIMITED
    400, 422 -> FailureReason.BAD_REQUEST
    in 500..599 -> FailureReason.SERVER
    else -> FailureReason.UNKNOWN
}

/**
 * 組 OpenAI 相容的 chat completions 請求內容。
 *
 * 刻意「不」帶 temperature 和 max_tokens：推理型模型（例如 GPT-5 系列）在
 * chat completions 不支援這兩個參數，帶了會直接回 400。就算改用它們要求的
 * max_completion_tokens，推理模型會先把 token 花在內部推理上，上限設小了
 * 可能整個額度被推理吃光、最後回傳空字串。prompt 已經限制只回物體名稱，
 * 回應本來就很短；成本面則有 RateLimiter 擋著。
 */
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

/** Azure 的錯誤回應格式是 {"error":{"message":"..."}}，取出那段訊息給人看。 */
internal fun extractErrorMessage(errorBody: String?): String? {
    if (errorBody.isNullOrBlank()) return null
    return try {
        JSONObject(errorBody)
            .optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
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
