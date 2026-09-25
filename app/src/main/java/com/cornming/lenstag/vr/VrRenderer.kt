package com.cornming.lenstag.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "VrRenderer"

/** 鏡片參數（已換算成像素）。 */
data class VrLensParams(val lensSeparationPx: Float, val k1: Float, val k2: Float)

/**
 * OpenGL 版的 VR 畫面。
 *
 * 相機預覽直接送進 GPU（CameraX Preview → SurfaceTexture → 外部貼圖），
 * 畫面更新跟相機一樣快，不再是舊版那樣用每秒 6～7 張的分析影格——
 * 在 VR 裡轉頭時畫面延遲又卡，是暈眩的典型原因。
 *
 * 每一格分兩步：
 * 1. 把「一隻眼睛看到的畫面」（相機＋框＋準星）畫進一張離屏貼圖
 * 2. 把這張貼圖畫到左右兩眼，各自以鏡片中心做變形校正
 * 手機只有一顆後鏡頭，兩眼內容本來就一樣，所以第一步只做一次、貼兩次。
 *
 * 執行緒：GL 呼叫都在 GLSurfaceView 的 GL 執行緒；CameraX 的 SurfaceProvider
 * 在主執行緒。兩邊共用的欄位都標 @Volatile。
 *
 * 注意：這整段只驗證過「編譯得過」。OpenGL 的錯誤多半是執行時才看得到
 * （黑畫面、上下顛倒），CI 抓不到。所以啟動失敗會透過 onError 通知外面
 * 自動退回相容模式，設定裡也可以手動切回去。
 */
class VrRenderer(
    private val mainExecutor: Executor,
    private val density: Float,
    private val fontScale: Float,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** 有新相機畫面時請 GLSurfaceView 重畫。 */
    @Volatile var requestRender: (() -> Unit)? = null

    /** GL 內容被系統重建時（很少見），通知外面跟 CameraX 重新要一次畫面。在主執行緒呼叫。 */
    @Volatile var onSurfaceTextureRecreated: (() -> Unit)? = null

    /** 初始化失敗（例如這支手機的 GPU 不支援某個語法）。在主執行緒呼叫。 */
    @Volatile var onError: ((String) -> Unit)? = null

    /** 要疊在畫面上的東西；主執行緒寫、GL 執行緒讀。 */
    @Volatile var overlayState: VrOverlayState? = null

    /** 鏡片參數；主執行緒寫、GL 執行緒讀。 */
    @Volatile var lens: VrLensParams = VrLensParams(0f, 0f, 0f)

    // CameraX 給的畫面資訊（主執行緒寫、GL 執行緒讀）
    @Volatile private var bufferWidth = 0
    @Volatile private var bufferHeight = 0
    @Volatile private var rotationDegrees = 0
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var failed = false

    // 只在主執行緒用
    private var glReady = false
    private var pendingRequest: SurfaceRequest? = null

    // 只在 GL 執行緒用
    private var cameraProgram = 0
    private var overlayProgram = 0
    private var distortProgram = 0
    private var oesTexture = 0
    private var overlayTexture = 0
    private var eyeTexture = 0
    private var eyeFramebuffer = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var eyeWidth = 0
    private var eyeHeight = 0
    private var overlayBitmap: Bitmap? = null
    private var lastOverlay: VrOverlayState? = null
    private var loggedUnexpectedRotation = false
    private val texMatrix = FloatArray(16)
    private val quad = FullScreenQuad()

    /** 交給 CameraX Preview 用。CameraX 在主執行緒呼叫。 */
    val surfaceProvider = Preview.SurfaceProvider { request ->
        if (glReady) {
            fulfill(request)
        } else {
            // GL 還沒準備好，先把請求存著，等 onSurfaceCreated 完成再給
            pendingRequest?.willNotProvideSurface()
            pendingRequest = request
        }
    }

    private fun fulfill(request: SurfaceRequest) {
        val st = surfaceTexture
        if (st == null || failed) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        st.setDefaultBufferSize(size.width, size.height)
        bufferWidth = size.width
        bufferHeight = size.height
        request.setTransformationInfoListener(mainExecutor) { info ->
            rotationDegrees = info.rotationDegrees
        }
        val surface = Surface(st)
        request.provideSurface(surface, mainExecutor) { surface.release() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            cameraProgram = GlUtil.createProgram(VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
            overlayProgram = GlUtil.createProgram(VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
            distortProgram = GlUtil.createProgram(VERTEX_SHADER, DISTORT_FRAGMENT_SHADER)
            oesTexture = GlUtil.createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            overlayTexture = GlUtil.createTexture(GLES20.GL_TEXTURE_2D)
        } catch (e: Exception) {
            Log.e(TAG, "GL 初始化失敗", e)
            failed = true
            val message = e.message ?: "未知錯誤"
            mainExecutor.execute { onError?.invoke(message) }
            return
        }

        // 新的 GL 內容：之前的離屏貼圖、疊加層都要重建
        eyeTexture = 0
        eyeFramebuffer = 0
        lastOverlay = null

        val recreated = surfaceTexture != null
        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(oesTexture).also { it.setOnFrameAvailableListener(this) }

        mainExecutor.execute {
            glReady = true
            pendingRequest?.let { fulfill(it) }
            pendingRequest = null
            // GL 內容被重建過：之前交給 CameraX 的 Surface 已經失效，要重新要一次
            if (recreated) onSurfaceTextureRecreated?.invoke()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (failed) return
        screenWidth = width
        screenHeight = height
        eyeWidth = width / 2
        eyeHeight = height
        if (eyeWidth <= 0 || eyeHeight <= 0) return

        // 離屏貼圖：一隻眼睛的畫面先畫到這裡，之後再貼到左右兩眼
        if (eyeFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(eyeFramebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(eyeTexture), 0)
        }
        eyeTexture = GlUtil.createTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, eyeWidth, eyeHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        eyeFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, eyeTexture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "離屏畫布建立失敗：$status")
            failed = true
            mainExecutor.execute { onError?.invoke("離屏畫布建立失敗（$status）") }
            return
        }

        // 疊加層的 Bitmap 跟眼睛畫面同尺寸；先上傳一張透明的，避免取到未初始化的貼圖
        overlayBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(eyeWidth, eyeHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        overlayBitmap = bitmap
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        lastOverlay = null
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            // SurfaceTexture 已經被釋放（例如正在離開 VR），這一格跳過
            Log.w(TAG, "updateTexImage 失敗", e)
            return
        }
        st.getTransformMatrix(texMatrix)
        if (eyeWidth <= 0 || eyeHeight <= 0 || eyeFramebuffer == 0) return

        uploadOverlayIfChanged()

        // 第一步：一隻眼睛的畫面（相機＋疊加層）畫進離屏貼圖
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFramebuffer)
        GLES20.glViewport(0, 0, eyeWidth, eyeHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawCamera()
        // Android 的 Bitmap 是預乘 alpha，混色要用 ONE / ONE_MINUS_SRC_ALPHA
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawOverlay()
        GLES20.glDisable(GLES20.GL_BLEND)

        // 第二步：同一張畫面貼到左右兩眼，各自以鏡片中心做變形校正
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val params = lens
        // 還沒設定鏡片間距時，先讓鏡片中心落在左右半螢幕的正中央
        val separation = if (params.lensSeparationPx > 0f) params.lensSeparationPx else screenWidth / 2f
        for (eye in 0..1) {
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, eyeHeight)
            val centerU = lensCenterU(
                isLeftEye = eye == 0,
                screenWidthPx = screenWidth.toFloat(),
                lensSeparationPx = separation,
            )
            drawDistorted(centerU, params)
        }
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "uCamera"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"), 1, false, texMatrix, 0,
        )

        // VR 鎖橫向，相機緩衝區只會需要轉 0 或 180 度（90/270 只有直向才會出現）。
        // 轉 90/270 的取樣方向沒辦法在沒有實機的情況下確認對錯，所以不去猜，只記錄下來。
        val rotation = rotationDegrees
        val quarterTurn = rotation == 90 || rotation == 270
        if (quarterTurn && !loggedUnexpectedRotation) {
            loggedUnexpectedRotation = true
            Log.w(TAG, "VR 模式出現非預期的旋轉角度 $rotation，畫面方向可能不對")
        }
        val imageWidth = if (quarterTurn) bufferHeight else bufferWidth
        val imageHeight = if (quarterTurn) bufferWidth else bufferHeight
        val (cropX, cropY) = fillCenterCropScale(imageWidth, imageHeight, eyeWidth, eyeHeight)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(cameraProgram, "uCropScale"), cropX, cropY)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(cameraProgram, "uFlip"),
            if (rotation == 180) 1f else 0f,
        )
        quad.draw(cameraProgram)
    }

    private fun drawOverlay() {
        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uOverlay"), 0)
        quad.draw(overlayProgram)
    }

    private fun drawDistorted(lensCenterU: Float, params: VrLensParams) {
        GLES20.glUseProgram(distortProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(distortProgram, "uEye"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(distortProgram, "uLensCenter"), lensCenterU, 0.5f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(distortProgram, "uAspect"),
            eyeWidth.toFloat() / eyeHeight,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distortProgram, "uK1"), params.k1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distortProgram, "uK2"), params.k2)
        quad.draw(distortProgram)
    }

    /**
     * 疊加層有變才重畫、重新上傳（框只在分析影格更新時才會變，大約每秒 6～7 次，
     * 不需要每一格相機畫面都重畫一張全尺寸 Bitmap）。
     */
    private fun uploadOverlayIfChanged() {
        val state = overlayState
        if (state == lastOverlay) return
        val bitmap = overlayBitmap ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        if (state != null) VrOverlayPainter.paint(Canvas(bitmap), state, density, fontScale)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        lastOverlay = state
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        requestRender?.invoke()
    }

    /** 離開 VR 時呼叫，釋放交給 CameraX 的畫面來源。 */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
