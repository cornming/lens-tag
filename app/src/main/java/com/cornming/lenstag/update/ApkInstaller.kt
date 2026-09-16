package com.cornming.lenstag.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

private const val TAG = "ApkInstaller"
private const val APK_FILE_NAME = "lens-tag-update.apk"

/** 下載結束的狀態，讓呼叫端知道要不要顯示錯誤訊息。 */
enum class DownloadOutcome { SUCCESS, FAILED }

/**
 * 用 DownloadManager 把新版 APK 下載到 App 專屬的外部儲存空間，下載完成後
 * 透過 FileProvider 產生 content:// URI，直接跳系統安裝畫面——
 * 不用使用者自己開瀏覽器、去下載資料夾找檔案、手動點開安裝。
 *
 * 安裝畫面有兩條觸發路徑，互相備援：
 * 1. observeProgress 輪詢到 STATUS_SUCCESSFUL 時直接觸發（主要路徑，可靠）
 * 2. DownloadManager 的完成廣播（備援，例如 App 短暫切到背景時）
 * 兩條都會經過 promptInstall 的重複觸發保護，不會跳兩次安裝畫面。
 */
class ApkInstaller(private val context: Context) {

    private var pendingDownloadId: Long = -1L

    /** 這一輪下載是否已經跳過安裝畫面了，避免輪詢和廣播重複觸發 */
    private var installPrompted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == pendingDownloadId) {
                promptInstall()
            }
        }
    }

    fun register() {
        // 必須是 EXPORTED：DownloadManager 的完成通知是「系統」送出的廣播，
        // 對這個 App 來說算外部來源。之前用 RECEIVER_NOT_EXPORTED 收不到，
        // 所以下載完根本不會自動跳安裝畫面。
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // 本來就還沒註冊過，忽略即可
        }
    }

    fun download(apkUrl: String): Long {
        targetFile().let { if (it.exists()) it.delete() } // 避免上次沒裝完的舊檔案造成衝突
        installPrompted = false

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("lens-tag 更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)

        pendingDownloadId = manager.enqueue(request)
        return pendingDownloadId
    }

    /**
     * 輪詢下載進度直到完成或失敗為止，成功時直接跳安裝畫面。
     * onProgress 收到 0f~1f；total 大小還不知道時收到 null（顯示成不確定的跑動進度條即可）。
     * 回傳這次下載的結果，讓呼叫端可以在失敗時顯示訊息。
     */
    suspend fun observeProgress(downloadId: Long, onProgress: (Float?) -> Unit): DownloadOutcome {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            var outcome: DownloadOutcome? = null
            cursor.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    onProgress(if (total > 0) downloaded.toFloat() / total else null)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> outcome = DownloadOutcome.SUCCESS
                        DownloadManager.STATUS_FAILED -> outcome = DownloadOutcome.FAILED
                    }
                } else {
                    // 查不到這筆下載了（可能被系統清掉），當成失敗處理
                    outcome = DownloadOutcome.FAILED
                }
            }
            outcome?.let { result ->
                if (result == DownloadOutcome.SUCCESS) promptInstall()
                return result
            }
            delay(300)
        }
    }

    private fun promptInstall() {
        if (installPrompted) return

        val file = targetFile()
        if (!file.exists()) {
            Log.w(TAG, "下載完成但檔案不存在：${file.path}")
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        installPrompted = true
        context.startActivity(installIntent)
    }

    private fun targetFile() =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
}
