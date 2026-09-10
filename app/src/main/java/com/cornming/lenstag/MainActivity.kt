package com.cornming.lenstag

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cornming.lenstag.ui.CameraScreen
import com.cornming.lenstag.update.UpdateChecker
import com.cornming.lenstag.update.UpdateInfo

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
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // 開啟 App 時順便靜靜檢查一次有沒有新版本；失敗（例如沒網路）就當作沒有更新，
    // 不影響主要的相機功能。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        updateInfo = UpdateChecker(BuildConfig.VERSION_CODE).checkForUpdate()
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
                        val url = info.apkDownloadUrl ?: info.releaseUrl
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        updateInfo = null
                    }) { Text("前往下載") }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) { Text("稍後") }
                },
            )
        }
    }
}
