package com.cornming.lenstag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cornming.lenstag.vr.MAX_DISTORTION
import com.cornming.lenstag.vr.MAX_LENS_SEPARATION_MM
import com.cornming.lenstag.vr.MIN_LENS_SEPARATION_MM
import com.cornming.lenstag.vr.VrEngine
import com.cornming.lenstag.vr.VrSettings

/**
 * 「設定」：畫面更新頻率＋VR 模式的調整。
 *
 * VR 的鏡片間距、變形校正沒有對所有眼鏡都對的數字，只能戴上去調，
 * 所以說明文字寫的是「怎麼調」而不是「該調多少」。
 */
@Composable
fun DisplaySettingsDialog(
    initialIntervalMs: Long,
    initialVr: VrSettings,
    onDismiss: () -> Unit,
    onConfirm: (intervalMs: Long, vr: VrSettings) -> Unit,
) {
    var interval by remember { mutableStateOf(initialIntervalMs.toFloat()) }
    var engine by remember { mutableStateOf(initialVr.engine) }
    var separation by remember { mutableStateOf(initialVr.lensSeparationMm) }
    var distortion by remember { mutableStateOf(initialVr.distortion) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("框的更新頻率", fontSize = 14.sp)
                val fps = 1000f / interval
                Text("間隔 ${interval.toInt()} 毫秒（約每秒 ${"%.1f".format(fps)} 次）", fontSize = 12.sp)
                Text(
                    "數字越小框跟得越緊，但比較耗電發熱；數字越大越省電。" +
                        "VR 流暢模式下相機畫面本身不受這個影響，只影響框。",
                    fontSize = 11.sp,
                )
                Slider(value = interval, onValueChange = { interval = it }, valueRange = 80f..1000f)

                HorizontalDivider()

                Text("VR 模式", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VrEngine.entries.forEach { option ->
                        FilterChip(
                            selected = engine == option,
                            onClick = { engine = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    when (engine) {
                        VrEngine.GL ->
                            "流暢：相機畫面直接送進 GPU，跟相機一樣順，有鏡片變形校正。" +
                                "如果畫面黑掉、上下顛倒或閃退，切到「相容」。"
                        VrEngine.LEGACY ->
                            "相容：舊版畫面，每秒約 6～7 張、沒有變形校正，但最穩定。"
                    },
                    fontSize = 11.sp,
                )

                if (engine == VrEngine.GL) {
                    Text("鏡片間距：${"%.1f".format(separation)} 公釐", fontSize = 13.sp)
                    Slider(
                        value = separation,
                        onValueChange = { separation = it },
                        valueRange = MIN_LENS_SEPARATION_MM..MAX_LENS_SEPARATION_MM,
                    )
                    Text(
                        "戴上眼鏡調到兩眼畫面自然合成一個、不會重影為止。每款眼鏡不一樣。",
                        fontSize = 11.sp,
                    )

                    Text("變形校正：${"%.2f".format(distortion)}", fontSize = 13.sp)
                    Slider(
                        value = distortion,
                        onValueChange = { distortion = it },
                        valueRange = 0f..MAX_DISTORTION,
                    )
                    Text(
                        "看靠近畫面邊緣的門框、窗框這種直線：如果線條往畫面中心凹進去" +
                            "（校正不夠）就調大；往外凸出（校正過頭）就調小，調到看起來是直的。" +
                            "0 表示不校正。",
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    interval.toLong(),
                    VrSettings(engine = engine, lensSeparationMm = separation, distortion = distortion),
                )
            }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
