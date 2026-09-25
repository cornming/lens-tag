package com.cornming.lenstag.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cornming.lenstag.vr.VrRenderer

/**
 * 把 OpenGL 的 VR 畫面包成 Compose 元件。
 *
 * 觸控用一層透明的 Compose 元件疊在 GLSurfaceView 上面接：直接在
 * AndroidView 上掛觸控偵測，事件會先被底下的 Android View 攔走，
 * 行為比較難預期。
 */
@Composable
fun GlVrView(
    renderer: VrRenderer,
    onSizeChanged: (IntSize) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // pointerInput(Unit) 只會建立一次，透過這兩個讀，永遠拿到最新的處理函式
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    // GLSurfaceView 規定要跟著 Activity 的 onPause/onResume 暫停、恢復
    DisposableEffect(lifecycleOwner, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 離開 VR 時釋放交給 CameraX 的畫面來源
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onSizeChanged),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    // 盡量在 App 暫停時保留 GL 內容；保不住的話 renderer 會通知重新接相機
                    preserveEGLContextOnPause = true
                    // 疊在一般相機預覽（也是 SurfaceView）上面，避免兩個畫面搶上下層
                    setZOrderMediaOverlay(true)
                    setRenderer(renderer)
                    // 有新相機畫面才重畫，不空轉
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    renderer.requestRender = { requestRender() }
                    glView = this
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onLongPress = { currentOnLongPress() },
                    )
                },
        )
    }
}
