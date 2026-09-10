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

/**
 * 用 DownloadManager 把新版 APK 下載到 App 專屬的外部儲存空間，下載完成後
 * 透過 FileProvider 產生 content:// URI，直接跳系統安裝畫面——
 * 不用使用者自己開瀏覽器、去下載資料夾找檔案、手動點開安裝。
 *
 * 呼叫端（MainActivity）要負責：
 * 1. 在 Composable 進入時呼叫 register()，離開時呼叫 unregister()
 * 2. download() 前先確認 packageManager.canRequestPackageInstalls()，
 *    沒有的話要先引導使用者去系統設定允許「安裝未知應用程式」
 */
class ApkInstaller(private val context: Context) {

    private var pendingDownloadId: Long = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == pendingDownloadId) {
                promptInstall()
            }
        }
    }

    fun register() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
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

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("lens-tag 更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)

        pendingDownloadId = manager.enqueue(request)
        return pendingDownloadId
    }

    /**
     * 輪詢下載進度直到完成或失敗為止（App 內進度條用；系統通知列本來就會顯示，
     * 這個是額外給想在畫面上直接看進度的情境）。
     * onProgress 收到 0f~1f；total 大小還不知道時收到 null（顯示成不確定的跑動進度條即可）。
     */
    suspend fun observeProgress(downloadId: Long, onProgress: (Float?) -> Unit) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            var shouldStop = false
            cursor.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    onProgress(if (total > 0) downloaded.toFloat() / total else null)

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        shouldStop = true
                    }
                } else {
                    // 查不到這筆下載了（可能被系統清掉），沒什麼好等的
                    shouldStop = true
                }
            }
            if (shouldStop) return
            delay(300)
        }
    }

    private fun promptInstall() {
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
        context.startActivity(installIntent)
    }

    private fun targetFile() =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
}
