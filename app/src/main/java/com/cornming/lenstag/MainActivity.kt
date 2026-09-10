package com.cornming.lenstag

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cornming.lenstag.ui.CameraScreen
import com.cornming.lenstag.update.ApkInstaller
import com.cornming.lenstag.update.UpdateChecker
import com.cornming.lenstag.update.UpdateInfo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LensTagApp()
                }
            }
        }
    }
}

@Composable
private fun LensTagApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 開啟 App 時順便靜靜檢查一次有沒有新版本；失敗（例如沒網路）就當作沒有更新，
    // 不影響主要的相機功能。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        updateInfo = UpdateChecker(BuildConfig.VERSION_CODE).checkForUpdate()
    }

    // App 內下載＋安裝更新，不用使用者自己開瀏覽器找檔案
    val installer = remember { ApkInstaller(context) }
    DisposableEffect(Unit) {
        installer.register()
        onDispose { installer.unregister() }
    }

    // 下載中的進度（0f~1f；null 表示還不知道總大小，畫面上顯示不確定的跑動進度條）
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    // Android 8+ 第一次安裝來源沒授權時，要先跳系統設定頁讓使用者允許；
    // 記住當下要裝的 APK 網址，回來後如果授權成功就直接接著下載。
    var pendingApkUrl by remember { mutableStateOf<String?>(null) }

    fun beginDownload(apkUrl: String) {
        updateInfo = null
        downloading = true
        downloadProgress = null
        val id = installer.download(apkUrl)
        scope.launch {
            installer.observeProgress(id) { progress -> downloadProgress = progress }
            downloading = false
        }
    }

    val installSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val url = pendingApkUrl
        pendingApkUrl = null
        if (url != null && context.packageManager.canRequestPackageInstalls()) {
            beginDownload(url)
        }
    }

    fun startUpdate(apkUrl: String) {
        if (context.packageManager.canRequestPackageInstalls()) {
            beginDownload(apkUrl)
        } else {
            pendingApkUrl = apkUrl
            installSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            CameraScreen()
        } else {
            Text(
                text = stringResource(id = R.string.camera_permission_rationale),
                modifier = Modifier.padding(24.dp),
            )
        }

        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("有新版本可以更新") },
                text = { Text("目前版本：${info.versionName}") },
                confirmButton = {
                    TextButton(onClick = {
                        val apkUrl = info.apkDownloadUrl
                        if (apkUrl != null) {
                            startUpdate(apkUrl)
                        } else {
                            // 理論上 Release 一定會附 APK，這裡只是保險退路
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                            updateInfo = null
                        }
                    }) { Text("下載並安裝") }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) { Text("稍後") }
                },
            )
        }

        if (downloading) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("正在下載更新…")
                val progress = downloadProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(progress * 100).toInt()}%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
