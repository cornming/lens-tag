package com.cornming.lenstag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cornming.lenstag.recognize.AzureFoundryRecognizer
import com.cornming.lenstag.recognize.AzureSettings
import com.cornming.lenstag.recognize.ConnectionTestResult
import com.cornming.lenstag.recognize.RecognitionResult
import com.cornming.lenstag.recognize.RecognizerKind
import com.cornming.lenstag.recognize.RecognizerSettings
import kotlinx.coroutines.launch

/**
 * 辨識方式設定：選手機內建 AI 還是 Azure AI Foundry，選 Azure 的話填連線資訊。
 */
@Composable
fun RecognizerSettingsDialog(
    initial: RecognizerSettings,
    onDismiss: () -> Unit,
    onConfirm: (RecognizerSettings) -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var endpoint by remember { mutableStateOf(initial.azure.endpoint) }
    var apiKey by remember { mutableStateOf(initial.azure.apiKey) }
    var simpleModel by remember { mutableStateOf(initial.azure.simpleModel) }
    var complexModel by remember { mutableStateOf(initial.azure.complexModel) }

    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ConnectionTestResult>>(emptyList()) }

    fun currentAzure() = AzureSettings(
        endpoint = endpoint.trim(),
        apiKey = apiKey.trim(),
        simpleModel = simpleModel.trim(),
        complexModel = complexModel.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("辨識方式") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecognizerKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                        )
                    }
                }

                when (kind) {
                    RecognizerKind.ON_DEVICE -> {
                        Text(
                            "用手機內建的 Gemini Nano，離線可用、不花錢，但只有部分機型支援" +
                                "（需要 AICore，且不支援解鎖過 bootloader 的裝置）。",
                            fontSize = 12.sp,
                        )
                    }

                    RecognizerKind.AZURE -> {
                        Text(
                            "改打你自己的 Azure AI Foundry 端點，辨識準確度較高、不挑機型，" +
                                "但需要網路而且每次呼叫都有成本。",
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("端點網址（含 api-version）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text(
                            "從 Azure 入口網站複製完整網址，例如 .../models/chat/completions?api-version=...",
                            fontSize = 11.sp,
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = simpleModel,
                            onValueChange = { simpleModel = it },
                            label = { Text("一般辨識用的模型") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = complexModel,
                            onValueChange = { complexModel = it },
                            label = { Text("複雜辨識用的模型（選填）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text(
                            "自動框出來的物件用「一般」模型（快、便宜）；你自己圈的範圍用" +
                                "「複雜」模型——會自己動手圈通常代表自動偵測沒框到，可能是細節或" +
                                "文字，值得用大一點的模型。沒填就兩種都用一般那個。",
                            fontSize = 11.sp,
                        )
                        Text(
                            "注意：如果端點是 Azure OpenAI 形式（模型綁在網址的 deployment 裡），" +
                                "兩個模型欄位不會有分流效果，要用 Foundry Models 形式的端點才有。",
                            fontSize = 11.sp,
                        )

                        // 用「現在表單上填的值」測試，不是已儲存的值——
                        // 不然得先存一份錯誤的設定才能測
                        Button(
                            onClick = {
                                testing = true
                                testResults = emptyList()
                                scope.launch {
                                    testResults = AzureFoundryRecognizer(currentAzure()).testConnection()
                                    testing = false
                                }
                            },
                            enabled = !testing,
                        ) {
                            Text(if (testing) "測試中…" else "測試連線")
                        }
                        if (testing) {
                            CircularProgressIndicator()
                        }
                        testResults.forEach { result -> ConnectionTestRow(result) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(RecognizerSettings(kind = kind, azure = currentAzure()))
            }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 一個模型的測試結果：成功顯示綠色勾勾，失敗顯示原因與 Azure 回的完整錯誤訊息。 */
@Composable
private fun ConnectionTestRow(result: ConnectionTestResult) {
    when (val r = result.result) {
        is RecognitionResult.Success -> Text(
            text = "✓ ${result.model}：連線成功，模型看得懂圖片（回答：${r.label.primary}）",
            color = Color(0xFF4CAF50),
            fontSize = 12.sp,
        )
        is RecognitionResult.Failure -> Text(
            text = "✗ ${result.model}：${r.reason.shortLabel}" + (r.detail?.let { "\n$it" } ?: ""),
            color = Color(0xFFF44336),
            fontSize = 12.sp,
        )
    }
}
