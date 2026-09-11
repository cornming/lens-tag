package com.cornming.lenstag.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateChecker"
private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/cornming/lens-tag/releases/latest"

/** 檢查到的新版本資訊。 */
data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?,
)

/**
 * 從 Release 的 tag_name（格式 "build-<CI run number>"，見
 * .github/workflows/release.yml）取出數字版號；格式不對就回傳 null。
 * 抽成獨立函式方便直接寫單元測試（見 app/src/test/.../UpdateCheckerTest.kt），
 * 不用真的發一次 HTTP 請求才能驗證這段邏輯對不對。
 */
internal fun parseBuildNumber(tagName: String): Int? =
    tagName.substringAfterLast("-").toIntOrNull()

/**
 * 開啟 App 時檢查 GitHub Releases 有沒有更新版本。
 *
 * 版本比對用的是 Release 的 tag_name（格式 "build-<CI run number>"）裡的數字，
 * 跟目前安裝版本的 versionCode 比大小——因為 CI 本來就是拿 run number 當
 * versionCode（見 .github/workflows/release.yml），兩邊天生對得起來，
 * 不用另外維護一份版本對照表。
 *
 * 呼叫 GitHub API 用的是公開、不需要認證的 releases/latest 端點，一般個人
 * 使用量遠低於每小時 60 次的未認證限制，不會有 rate limit 問題。
 */
class UpdateChecker(private val currentVersionCode: Int) {

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name") // 例如 "build-12"
            val latestCode = parseBuildNumber(tagName) ?: return@withContext null

            if (latestCode <= currentVersionCode) return@withContext null

            var apkUrl: String? = null
            json.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }

            UpdateInfo(
                versionName = json.optString("name", tagName),
                releaseUrl = json.optString("html_url"),
                apkDownloadUrl = apkUrl,
            )
        } catch (e: Exception) {
            // 檢查更新失敗不該影響主要功能（例如沒網路），安靜忽略就好
            Log.w(TAG, "檢查更新失敗", e)
            null
        }
    }
}
