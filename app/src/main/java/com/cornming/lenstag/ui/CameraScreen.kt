package com.cornming.lenstag.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size as AndroidSize
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cornming.lenstag.camera.AnalysisSettings
import com.cornming.lenstag.camera.DetectionResult
import com.cornming.lenstag.camera.FrameSink
import com.cornming.lenstag.camera.ObjectAnalyzer
import com.cornming.lenstag.capture.StillImageDetector
import com.cornming.lenstag.capture.cropBitmap
import com.cornming.lenstag.capture.rotatedBy
import com.cornming.lenstag.data.CustomName
import com.cornming.lenstag.data.CustomNamesStore
import com.cornming.lenstag.data.MarkedWord
import com.cornming.lenstag.data.MarkedWordsStore
import com.cornming.lenstag.data.normalizeKey
import com.cornming.lenstag.geometry.Box as GeomBox
import com.cornming.lenstag.geometry.PreviewTransform
import com.cornming.lenstag.geometry.previewTransform
import com.cornming.lenstag.recognize.AzureFoundryRecognizer
import com.cornming.lenstag.recognize.OnDeviceRecognizer
import com.cornming.lenstag.recognize.RateLimiter
import com.cornming.lenstag.recognize.RecognitionTask
import com.cornming.lenstag.recognize.Recognizer
import com.cornming.lenstag.recognize.RecognizerKind
import com.cornming.lenstag.recognize.RecognizerSettings
import com.cornming.lenstag.recognize.RecognizerSettingsStore
import com.cornming.lenstag.recognize.recognizeIfReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import java.net.URLEncoder

/** 框在畫面上保留的寬限期：物件短暫被遮住或偵測跳掉時，標籤不要立刻閃掉。 */
private const val BOX_GRACE_PERIOD_MS = 500L

/** 標籤文字跟框之間、以及跟螢幕邊緣之間留的間距 */
private const val LABEL_PADDING_PX = 8f

/** VR 模式下，準星停留在同一個框裡多久算「短停留」（唸發音，等同一般模式短按）。 */
private const val DWELL_SHORT_MS = 1500L

/** 在短停留之後，繼續停留到這個累積時間算「長停留」（開命名/標記對話框，等同一般模式長按）。 */
private const val DWELL_LONG_MS = 3000L

/** 一般（即時）模式／VR cardboard 模式／拍照後的靜止辨識模式。 */
private enum class ViewMode { NORMAL, VR_CARDBOARD, PHOTO }

/** 畫面上一個框的當前狀態（含最後一次看到的時間，用來做寬限期）。 */
private data class TrackedBox(
    val box: GeomBox,
    val lastSeenAt: Long,
)

/**
 * 相機畫面：CameraX 預覽 + ML Kit 物件偵測/追蹤 + Compose Canvas 疊加框與標籤。
 *
 * 三個借自 Tesla Vision 的設計決定：
 * 1. 框先於名稱——偵測到就畫框，辨識不出來就顯示「?」，可點擊手動命名
 * 2. 框穩定不動才觸發辨識（在 ObjectAnalyzer 裡實作）
 * 3. 框消失後保留一段寬限期，避免遮擋造成標籤閃爍
 *
 * 雙語支援：辨識時同時跟 Gemini Nano 要原文（繁中）和翻譯名稱，
 * 畫面上可以切換只看原文／只看翻譯／雙語對照，方便學習用途。
 *
 * VR cardboard 模式：畫面鎖橫向、分成左右兩半（塞進 cardboard viewer 用）。
 * 手機放進眼鏡後摸不到螢幕，所以互動靠畫面中央固定的準星（代表「頭現在
 * 指向哪裡」）：
 * - 凝視短停留：選取（已命名就唸出來，還沒辨識就送辨識）
 * - 繼續停留到長停留：標記／取消標記單字（有語音確認）
 * - 眼鏡側邊的按鈕（按下去會碰觸螢幕）：立刻選取，不用等停留
 * - 手機拿出來後長按螢幕或按返回鍵：退出 VR
 * 控制列和對話框在 VR 裡都不顯示——它們會橫跨兩眼，戴著眼鏡看不清楚也按不到。
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // 辨識方式（手機內建 AI / Azure AI Foundry）。設定一改就換一個 Recognizer 實作，
    // 呼叫端的程式碼完全不用動——這是把辨識抽成 Recognizer 介面的用意。
    val settingsStore = remember { RecognizerSettingsStore(context) }
    var recognizerSettings by remember { mutableStateOf(settingsStore.load()) }
    var editingRecognizer by remember { mutableStateOf(false) }
    // 限流器放在畫面層保存，不跟著 recognizer 重建——不然每改一次設定計數就歸零，
    // 等於改一下設定就能繞過上限
    val azureRateLimiter = remember {
        RateLimiter(maxCalls = recognizerSettings.azure.maxCallsPerMinute, windowMs = 60_000L)
    }
    // 設定裡的上限一改就同步到同一個限流器上（只改上限、不歸零計數）
    SideEffect { azureRateLimiter.maxCalls = recognizerSettings.azure.maxCallsPerMinute }
    val recognizer: Recognizer = remember(recognizerSettings) {
        when (recognizerSettings.kind) {
            RecognizerKind.ON_DEVICE -> OnDeviceRecognizer()
            RecognizerKind.AZURE -> AzureFoundryRecognizer(recognizerSettings.azure, azureRateLimiter)
        }
    }
    // 即時模式的分析器是在相機初始化時建立的（AndroidView 的 factory 只跑一次），
    // 如果直接用上面的 recognizer，它會永遠抓著「初始化當下」那一個——切換辨識
    // 方式之後即時模式還在用舊的。透過 rememberUpdatedState 讀，永遠拿到最新的。
    val currentRecognizer by rememberUpdatedState(recognizer)
    val textMeasurer = rememberTextMeasurer()
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    // 追蹤 ID -> 框位置與最後出現時間（含寬限期內、當前影格已經看不到的框）
    val trackedBoxes = remember { mutableStateMapOf<Int, TrackedBox>() }
    // 追蹤 ID -> 標籤狀態
    val labels = remember { mutableStateMapOf<Int, LabelState>() }
    // 來源分析影像的尺寸，用來把偵測座標換算成螢幕座標
    var sourceSize by remember { mutableStateOf(0 to 0) }
    // 使用者點了哪個框要手動命名
    var renamingId by remember { mutableStateOf<Int?>(null) }

    // 顯示模式（原文／翻譯／雙語）與翻譯目標語言。
    // 注意：改變這兩個設定只影響「之後新辨識」的物件，已經辨識過的物件
    // 不會自動重新查詢——這是刻意簡化，避免每次調設定都重打 Gemini Nano。
    var displayMode by rememberSaveable { mutableStateOf(DisplayMode.BOTH) }
    var secondaryLanguage by rememberSaveable { mutableStateOf("English") }
    var editingLanguage by remember { mutableStateOf(false) }

    // 畫面更新頻率（兩次 ML Kit 偵測間至少間隔多久）。analysisSettings 是傳給
    // ObjectAnalyzer 的可變容器，updateIntervalMs 是給 UI 顯示/互動用的 Compose
    // state，兩者用 LaunchedEffect 同步，這樣調整設定不需要重建整個相機 pipeline。
    val analysisSettings = remember { AnalysisSettings() }
    var updateIntervalMs by rememberSaveable { mutableStateOf(150L) }
    var editingFrequency by remember { mutableStateOf(false) }
    LaunchedEffect(updateIntervalMs) {
        analysisSettings.intervalMs = updateIntervalMs
    }

    // 標記過的單字，存在本機（SharedPreferences），跨 session 都在。
    val markedWordsStore = remember { MarkedWordsStore(context) }
    // 「模型辨識成 X → 顯示成我取的名字」的對照表，跨 App 重開都在
    val customNamesStore = remember { CustomNamesStore(context) }
    var markedWords by remember { mutableStateOf(markedWordsStore.getAll()) }
    var showingMarkedWords by remember { mutableStateOf(false) }

    fun toggleMark(primary: String, secondary: String?) {
        markedWordsStore.toggle(MarkedWord(primary, secondary))
        markedWords = markedWordsStore.getAll()
    }

    // 一般 / VR cardboard / 拍照模式。frameSink 只有 VR 模式才會有 callback，
    // 平常模式 ObjectAnalyzer 完全不會多做整影格 Bitmap 轉換那筆開銷。
    // rememberSaveable：畫面重建（轉向、系統回收）後還記得在哪個模式。
    // 之前用 remember，進 VR 要把手機轉橫，一轉畫面重建就跳回一般模式了
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.NORMAL) }
    val frameSink = remember { FrameSink() }

    // 拍照模式：拍下來的照片、上面的可辨識區域、以及拍完後偵測中的狀態。
    // 用 ImageCapture 另外拍一張高解析度照片，而不是凍結即時分析用的那張
    // 640x480——拍照模式的重點就是可以慢慢圈、圈小塊區域也還看得清楚，
    // 解析度不能將就。
    val imageCapture = remember { mutableStateOf<ImageCapture?>(null) }
    val stillDetector = remember { StillImageDetector() }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoRegions by remember { mutableStateOf<List<PhotoRegion>>(emptyList()) }
    var nextRegionId by remember { mutableStateOf(0) }
    var photoDetecting by remember { mutableStateOf(false) }
    var renamingPhotoRegionId by remember { mutableStateOf<Int?>(null) }

    /** 對拍照模式裡的某一塊區域跑辨識。 */
    fun recognizePhotoRegion(regionId: Int) {
        val photo = photoBitmap ?: return
        val region = photoRegions.firstOrNull { it.id == regionId } ?: return
        if (region.label is LabelState.Named) return // 已經有名字就不重跑
        val cropped = cropBitmap(photo, region.box) ?: return

        photoRegions = photoRegions.map {
            if (it.id == regionId) it.copy(label = LabelState.Recognizing) else it
        }
        val languageAtRequestTime = secondaryLanguage
        // 使用者自己圈的範圍算複雜任務：會動手圈通常正是因為自動偵測沒框到，
        // 可能是局部細節或文字，值得派比較強的模型
        val task = if (region.manual) RecognitionTask.COMPLEX else RecognitionTask.SIMPLE
        scope.launch {
            val result = currentRecognizer.recognizeIfReady(cropped, languageAtRequestTime, task)
            photoRegions = photoRegions.map {
                if (it.id == regionId) it.copy(label = result.toLabelState(customNamesStore::lookup)) else it
            }
        }
    }

    fun takePhoto() {
        val capture = imageCapture.value ?: return
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toBitmap().rotatedBy(image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "拍照後轉檔失敗", e)
                        null
                    } finally {
                        image.close()
                    }
                    if (bitmap == null) return

                    photoBitmap = bitmap
                    photoRegions = emptyList()
                    viewMode = ViewMode.PHOTO
                    photoDetecting = true

                    // 對整張照片重跑一次偵測（SINGLE_IMAGE_MODE），框的座標系
                    // 就跟照片本身一致，不用處理即時分析解析度的換算誤差
                    scope.launch {
                        val boxes = stillDetector.detect(bitmap)
                        photoRegions = boxes.mapIndexed { i, b ->
                            PhotoRegion(id = i, box = b, label = LabelState.Unknown)
                        }
                        nextRegionId = boxes.size
                        photoDetecting = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraScreen", "拍照失敗", exception)
                }
            },
        )
    }

    fun exitPhotoMode() {
        viewMode = ViewMode.NORMAL
        photoBitmap = null
        photoRegions = emptyList()
        renamingPhotoRegionId = null
    }

    // 照片（Bitmap）存不進去重建前的狀態，如果模式是從重建中還原回拍照模式、
    // 照片卻不在了，就退回一般模式，不要卡在一個空白的拍照畫面
    LaunchedEffect(viewMode, photoBitmap) {
        if (viewMode == ViewMode.PHOTO && photoBitmap == null) viewMode = ViewMode.NORMAL
    }

    // 螢幕方向：VR 鎖橫向（放進眼鏡本來就是橫的），其他模式鎖直向。
    // 之前沒處理，轉橫向時 Android 會重建畫面，不只 VR 會跳掉，一般模式轉一下
    // 手機，顯示模式、翻譯語言這些設定也會全部回到預設值。
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(viewMode) {
        activity?.requestedOrientation = if (viewMode == ViewMode.VR_CARDBOARD) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // 相機畫面開著就保持螢幕常亮。VR 模式靠凝視操作、完全不碰螢幕，系統的自動
    // 關閉螢幕計時器照跑的話，戴到一半畫面就黑了
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    // 返回鍵／返回手勢：在 VR 或拍照模式時先回到一般模式，而不是直接離開 App
    BackHandler(enabled = viewMode != ViewMode.NORMAL) {
        if (viewMode == ViewMode.PHOTO) exitPhotoMode() else viewMode = ViewMode.NORMAL
    }

    var latestFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var vrContainerSize by remember { mutableStateOf(IntSize.Zero) }
    // 準星停留進度，0f~1f，只在 VR 模式下有意義；gazePastShort 代表已經過了
    // 短停留門檻、正在往長停留（開對話框）累積，準星顏色靠這個切換
    var gazeProgress by remember { mutableStateOf(0f) }
    var gazePastShort by remember { mutableStateOf(false) }

    LaunchedEffect(viewMode) {
        frameSink.onFrame = if (viewMode == ViewMode.VR_CARDBOARD) {
            { bitmap -> latestFrame = bitmap.asImageBitmap() }
        } else {
            null
        }
    }

    /**
     * 使用者在命名對話框按下確定。回傳這個框新的標籤；沒有實際改動就回 null。
     *
     * 如果這個框是模型辨識出來的（有 recognizedAs），就把「模型說 X → 顯示成
     * 這個名字」存成規則——之後任何被辨識成 X 的都會套用，而且畫面上現在其他
     * 被辨識成 X 的框也會立刻跟著換。從沒被模型辨識過的框只改這一個，不存規則。
     *
     * 沒改任何字就按確定是 no-op：很多時候長按只是為了標記單字或查字典，順手
     * 按了確定。如果這樣也存規則，會把當下的翻譯「凍結」進去——之後換成日文
     * 翻譯，這個字還會卡在英文。
     */
    fun applyRename(current: LabelState?, primary: String, secondary: String?): LabelState.Named? {
        val named = current as? LabelState.Named
        if (named != null && named.primary == primary && named.secondary == secondary) return null

        val recognizedAs = named?.recognizedAs
        val renamed = LabelState.Named(
            primary = primary,
            secondary = secondary,
            custom = true,
            recognizedAs = recognizedAs,
        )
        if (recognizedAs == null) return renamed

        customNamesStore.put(recognizedAs, CustomName(primary, secondary))

        // 畫面上其他同樣被辨識成這個東西的框，立刻換成新名字
        val key = normalizeKey(recognizedAs)
        fun sameThing(label: LabelState?) =
            label is LabelState.Named &&
                label.recognizedAs != null &&
                normalizeKey(label.recognizedAs) == key
        labels.keys.toList().forEach { id ->
            if (sameThing(labels[id])) labels[id] = renamed
        }
        photoRegions = photoRegions.map { if (sameThing(it.label)) it.copy(label = renamed) else it }
        return renamed
    }

    // 追蹤 ID -> 物件「穩定下來那一刻」裁切出來的圖。Azure 模式下不自動辨識，
    // 改成點框才辨識——但點擊當下手上沒有影像可以裁，所以先把穩定時那張清晰的
    // 裁切圖存起來，點的時候直接拿來用。框消失時一起清掉，不會無限累積。
    val liveCrops = remember { mutableMapOf<Int, Bitmap>() }

    /** 把即時模式某個追蹤 ID 的裁切圖送去辨識。自動辨識、點擊、VR 凝視都走這裡。 */
    fun recognizeLive(id: Int) {
        if (labels[id] is LabelState.Named || labels[id] == LabelState.Recognizing) return
        val cropped = liveCrops[id] ?: return // 物件還沒穩定過，沒有清晰的圖可以送

        labels[id] = LabelState.Recognizing
        val languageAtRequestTime = secondaryLanguage
        scope.launch {
            // 自動偵測框出來的單一物件，屬於簡單任務。失敗的話框會顯示具體原因，
            // 點一下可以重試，長按仍然可以手動命名
            val result = currentRecognizer.recognizeIfReady(
                cropped,
                languageAtRequestTime,
                RecognitionTask.SIMPLE,
            )
            labels[id] = result.toLabelState(customNamesStore::lookup)
        }
    }

    // VR 模式下準星目前對著哪個框。凝視停留和眼鏡按鈕（點螢幕）都用它
    var vrGazedId by remember { mutableStateOf<Int?>(null) }
    // VR 模式下短暫顯示在兩眼畫面裡的提示（內容＋出現時間，時間讓同一句話也能重新計時）
    var vrMessage by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(vrMessage) {
        if (vrMessage != null) {
            delay(2_000)
            vrMessage = null
        }
    }

    /** VR 的「選取」：已命名就唸出來，還沒辨識就送辨識。凝視短停留和眼鏡按鈕共用。 */
    fun vrSelect(id: Int) {
        when (val label = labels[id]) {
            is LabelState.Named -> speaker.speak(label, displayMode, secondaryLanguage)
            else -> recognizeLive(id)
        }
    }

    /**
     * VR 的長停留：標記／取消標記這個單字，用語音和畫面提示確認。
     *
     * 一般模式的長按是開命名對話框，但那在 VR 裡行不通：對話框整個螢幕置中，
     * 戴著眼鏡時每隻眼睛只看得到一半，而且要打字、要點按鈕。長按功能裡唯一
     * 不需要打字的就是「標記」，所以 VR 裡長停留只做這件事；要改名或查字典，
     * 之後在一般模式從單字本處理。
     */
    fun vrToggleMark(id: Int) {
        val named = labels[id] as? LabelState.Named
        if (named == null) {
            vrMessage = "還沒辨識出名稱，無法標記" to System.currentTimeMillis()
            speaker.announce("還沒辨識出名稱")
            return
        }
        val wasMarked = markedWords.any { it.primary == named.primary }
        toggleMark(named.primary, named.secondary)
        val text = if (wasMarked) "已移出單字本" else "已加入單字本"
        vrMessage = (if (wasMarked) "☆ " else "★ ") + "$text：${named.primary}" to System.currentTimeMillis()
        speaker.announce(text)
    }

    // VR 模式的「凝視＋停留」偵測：準星固定在每一半畫面的正中央，分兩段：
    // 停留到 DWELL_SHORT_MS 選取（唸出發音或送辨識，等同一般模式短按）；
    // 視線沒移開、繼續停留到 DWELL_LONG_MS 則標記／取消標記單字。
    // 眼鏡側邊的按鈕（按下去會點到螢幕）則是「立刻選取」，不用等停留。
    LaunchedEffect(viewMode) {
        if (viewMode != ViewMode.VR_CARDBOARD) {
            gazeProgress = 0f
            gazePastShort = false
            vrGazedId = null
            return@LaunchedEffect
        }
        var gazedId: Int? = null
        var gazeStartMs = 0L
        var firedShort = false
        var firedLong = false
        while (true) {
            delay(100)
            val (sw, sh) = sourceSize
            val containerW = vrContainerSize.width
            val containerH = vrContainerSize.height
            if (sw == 0 || sh == 0 || containerW == 0 || containerH == 0) continue

            val paneWidth = containerW / 2f
            val paneHeight = containerH.toFloat()
            val transform = previewTransform(sw, sh, paneWidth, paneHeight)
            val centerX = paneWidth / 2f
            val centerY = paneHeight / 2f

            val hitId = trackedBoxes.entries
                .map { it.key to transform.apply(it.value.box) }
                .filter { (_, r) -> r.contains(centerX, centerY) }
                .minByOrNull { (_, r) -> r.width * r.height }
                ?.first

            vrGazedId = hitId
            if (hitId != gazedId) {
                gazedId = hitId
                gazeStartMs = System.currentTimeMillis()
                firedShort = false
                firedLong = false
                gazeProgress = 0f
                gazePastShort = false
            } else if (hitId != null) {
                val elapsed = System.currentTimeMillis() - gazeStartMs

                if (!firedShort) {
                    gazeProgress = (elapsed.toFloat() / DWELL_SHORT_MS).coerceIn(0f, 1f)
                    if (elapsed >= DWELL_SHORT_MS) {
                        firedShort = true
                        gazePastShort = true
                        gazeProgress = 0f
                        vrSelect(hitId)
                    }
                } else if (!firedLong) {
                    val longSpan = DWELL_LONG_MS - DWELL_SHORT_MS
                    gazeProgress = ((elapsed - DWELL_SHORT_MS).toFloat() / longSpan).coerceIn(0f, 1f)
                    if (elapsed >= DWELL_LONG_MS) {
                        firedLong = true
                        gazeProgress = 0f
                        vrToggleMark(hitId)
                    }
                }
            } else {
                gazeProgress = 0f
                gazePastShort = false
            }
        }
    }

    fun onDetected(result: DetectionResult) {
        val now = System.currentTimeMillis()
        sourceSize = result.sourceWidth to result.sourceHeight

        result.boxes.forEach { box ->
            trackedBoxes[box.trackingId] = TrackedBox(box.boundingBox, now)
            labels.putIfAbsent(box.trackingId, LabelState.Unknown)
        }

        // 超過寬限期還沒再出現的框才真正移除
        val expired = trackedBoxes.filterValues { now - it.lastSeenAt > BOX_GRACE_PERIOD_MS }.keys
        expired.forEach { id ->
            trackedBoxes.remove(id)
            labels.remove(id)
            liveCrops.remove(id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    // 分析解析度故意設低（640x480 等級即可），不需要跟預覽一樣高解析度，
                    // 這樣每一次 ML Kit 推論的運算量小很多，也是降低發熱的一環。
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    // 拍照模式用的 use case。Preview + ImageAnalysis + ImageCapture
                    // 這個三件組是 CameraX 官方支援的標準組合。
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture.value = capture

                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(ctx),
                        ObjectAnalyzer(
                            onDetected = ::onDetected,
                            onStableObject = { id, cropped ->
                                // 只有「穩定不動」的物件會走到這裡，所以不必再自己節流。
                                // 一律先存下裁切圖；要不要自動送辨識看辨識方式——
                                // 付費的 Azure 不自動送，等使用者點框才送。
                                liveCrops[id] = cropped
                                if (recognizerSettings.shouldAutoRecognizeLive()) {
                                    recognizeLive(id)
                                }
                            },
                            frameSink = frameSink,
                            analysisSettings = analysisSettings,
                        ),
                    )

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        capture,
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        val currentPhoto = photoBitmap
        if (viewMode == ViewMode.PHOTO && currentPhoto != null) {
            PhotoModeScreen(
                photo = currentPhoto,
                regions = photoRegions,
                displayMode = displayMode,
                textMeasurer = textMeasurer,
                onRegionTapped = { region ->
                    // 還沒辨識過就先辨識；已經有名字了就唸出來（跟即時模式一致）
                    when (val label = region.label) {
                        is LabelState.Named -> speaker.speak(label, displayMode, secondaryLanguage)
                        else -> recognizePhotoRegion(region.id)
                    }
                },
                onRegionLongPressed = { region -> renamingPhotoRegionId = region.id },
                onManualRegion = { box ->
                    // 使用者自己圈的範圍：立刻加進清單並馬上辨識，不用再點一次
                    val id = nextRegionId
                    nextRegionId = id + 1
                    photoRegions = photoRegions + PhotoRegion(
                        id = id,
                        box = box,
                        label = LabelState.Unknown,
                        manual = true,
                    )
                    recognizePhotoRegion(id)
                },
            )
        } else if (viewMode != ViewMode.VR_CARDBOARD) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = onTap@{ tap ->
                                val (sw, sh) = sourceSize
                                if (sw == 0 || sh == 0) return@onTap
                                val transform = previewTransform(sw, sh, size.width.toFloat(), size.height.toFloat())
                                val hitId = trackedBoxes.entries
                                    .map { it.key to transform.apply(it.value.box) }
                                    .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                    .minByOrNull { (_, r) -> r.width * r.height }
                                    ?.first
                                hitId?.let { id ->
                                    // 已命名就唸出來；還沒辨識就送辨識（Azure 模式
                                    // 不自動辨識，這裡就是觸發的地方）
                                    when (val label = labels[id]) {
                                        is LabelState.Named ->
                                            speaker.speak(label, displayMode, secondaryLanguage)
                                        else -> recognizeLive(id)
                                    }
                                }
                            },
                            onLongPress = onLongPress@{ tap ->
                                val (sw, sh) = sourceSize
                                if (sw == 0 || sh == 0) return@onLongPress
                                val transform = previewTransform(sw, sh, size.width.toFloat(), size.height.toFloat())
                                // 找出點到的框（優先選中較小的框，比較符合直覺）
                                renamingId = trackedBoxes.entries
                                    .map { it.key to transform.apply(it.value.box) }
                                    .filter { (_, r) -> r.contains(tap.x, tap.y) }
                                    .minByOrNull { (_, r) -> r.width * r.height }
                                    ?.first
                            },
                        )
                    },
            ) {
                val (sw, sh) = sourceSize
                if (sw == 0 || sh == 0) return@Canvas
                val transform = previewTransform(sw, sh, size.width, size.height)
                drawDetections(trackedBoxes, labels, displayMode, transform, textMeasurer)
            }
        } else {
            // VR cardboard：左右各畫一次同樣的內容（用最新一張分析影格的 Bitmap，
            // 不是即時 PreviewView，畫面更新頻率跟 ObjectAnalyzer 的節流間隔一樣，
            // 大約每秒 6-7 張，不是流暢的 30fps 視訊，但這個模式本來就是輔助用途）。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { vrContainerSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            // 很多 cardboard 眼鏡側邊有按鈕，按下去會碰觸螢幕：
                            // 當作「立刻選取準星對著的東西」，不用等停留
                            onTap = { vrGazedId?.let { vrSelect(it) } },
                            // 手機拿出眼鏡後長按螢幕退出 VR（返回鍵也可以）
                            onLongPress = { viewMode = ViewMode.NORMAL },
                        )
                    },
            ) {
                repeat(2) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        latestFrame?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val (sw, sh) = sourceSize
                            if (sw != 0 && sh != 0) {
                                val transform = previewTransform(sw, sh, size.width, size.height)
                                drawDetections(trackedBoxes, labels, displayMode, transform, textMeasurer)
                            }

                            // 中央準星：空心圈代表待命，實心綠圈按停留進度長大，
                            // 長滿代表這次已經觸發選取。
                            val center = Offset(size.width / 2f, size.height / 2f)
                            drawCircle(
                                color = Color.White,
                                radius = 16f,
                                center = center,
                                style = Stroke(width = 2f),
                            )
                            if (gazeProgress > 0f) {
                                drawCircle(
                                    color = if (gazePastShort) Color(0xFFFFA000) else Color(0xFF4CAF50),
                                    radius = 16f * gazeProgress,
                                    center = center,
                                )
                            }

                            // 提示文字畫在「每隻眼睛各自的畫面裡」，戴著眼鏡才讀得到；
                            // 不能用一般的 Compose 元件橫跨整個螢幕
                            vrMessage?.let { (text, _) ->
                                drawVrText(textMeasurer, text, size.height * 0.15f, size.width, 18.sp)
                            }
                            drawVrText(
                                textMeasurer,
                                "長按螢幕或按返回鍵退出 VR",
                                size.height * 0.9f,
                                size.width,
                                12.sp,
                            )
                        }
                    }
                }
            }
        }

        // 控制列。拍照模式放在畫面下方（像相機 App 那樣，拇指好按，也不會擋住
        // 照片上緣的框）；即時／VR 模式維持在上方，因為下方是雙手持握的位置，
        // 即時模式常常要一邊舉著手機一邊點。
        // statusBarsPadding / navigationBarsPadding 是因為 targetSdk 36 預設
        // edge-to-edge，沒有的話按鈕會被系統列蓋住一部分，點起來不準。
        // 橫向捲動則是避免按鈕在窄螢幕上擠不下。
        // VR 模式不顯示控制列：它會橫跨左右兩眼，戴著眼鏡什麼都看不清楚，
        // 而且在眼鏡裡也按不到。退出 VR 用長按螢幕或返回鍵。
        if (viewMode != ViewMode.VR_CARDBOARD) Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(if (viewMode == ViewMode.PHOTO) Alignment.BottomCenter else Alignment.TopCenter)
                .then(
                    if (viewMode == ViewMode.PHOTO) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier.statusBarsPadding()
                    },
                )
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { displayMode = displayMode.next() }) {
                Text("顯示：${displayMode.label}")
            }
            Button(onClick = { editingLanguage = true }) {
                Text("翻譯：$secondaryLanguage")
            }
            Button(onClick = { showingMarkedWords = true }) {
                Text("單字本 (${markedWords.size})")
            }
            Button(onClick = { editingRecognizer = true }) {
                Text("辨識：${recognizerSettings.kind.label}")
            }
            if (viewMode == ViewMode.PHOTO) {
                Button(onClick = { takePhoto() }) {
                    Text("重拍")
                }
                Button(onClick = { exitPhotoMode() }) {
                    Text("回到即時")
                }
            } else {
                Button(onClick = { takePhoto() }) {
                    Text("拍照辨識")
                }
                Button(onClick = { editingFrequency = true }) {
                    Text("更新頻率")
                }
                Button(
                    onClick = {
                        viewMode = if (viewMode == ViewMode.NORMAL) {
                            ViewMode.VR_CARDBOARD
                        } else {
                            ViewMode.NORMAL
                        }
                    },
                ) {
                    Text(if (viewMode == ViewMode.NORMAL) "VR 模式" else "退出 VR")
                }
            }
        }

        // 拍照模式的操作提示放在上方（控制列已經佔住下方了）
        if (viewMode == ViewMode.PHOTO) {
            Text(
                text = if (photoDetecting) {
                    "偵測中…"
                } else {
                    "雙指縮放／移動；點框辨識，已辨識的點一下會唸出來；長按可命名標記；單指拖曳可圈出任意範圍辨識"
                },
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp),
            )
        }

        renamingId?.let { id ->
            val current = labels[id] as? LabelState.Named
            RenameDialog(
                failure = labels[id] as? LabelState.Failed,
                initialPrimary = current?.primary.orEmpty(),
                initialSecondary = current?.secondary.orEmpty(),
                markedPrimaries = remember(markedWords) { markedWords.map { it.primary }.toSet() },
                onDismiss = { renamingId = null },
                onConfirm = { primary, secondary ->
                    if (primary.isNotBlank()) {
                        applyRename(labels[id], primary, secondary.ifBlank { null })
                            ?.let { labels[id] = it }
                    }
                    renamingId = null
                },
                onToggleMark = { primary, secondary ->
                    toggleMark(primary, secondary.ifBlank { null })
                },
                onOpenDictionary = { word ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dictionaryUrl(word))))
                },
            )
        }

        renamingPhotoRegionId?.let { id ->
            val region = photoRegions.firstOrNull { it.id == id }
            val current = region?.label as? LabelState.Named
            RenameDialog(
                failure = region?.label as? LabelState.Failed,
                initialPrimary = current?.primary.orEmpty(),
                initialSecondary = current?.secondary.orEmpty(),
                markedPrimaries = remember(markedWords) { markedWords.map { it.primary }.toSet() },
                onDismiss = { renamingPhotoRegionId = null },
                onConfirm = { primary, secondary ->
                    if (primary.isNotBlank()) {
                        applyRename(region?.label, primary, secondary.ifBlank { null })
                            ?.let { renamed ->
                                photoRegions = photoRegions.map {
                                    if (it.id == id) it.copy(label = renamed) else it
                                }
                            }
                    }
                    renamingPhotoRegionId = null
                },
                onToggleMark = { primary, secondary ->
                    toggleMark(primary, secondary.ifBlank { null })
                },
                onOpenDictionary = { word ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dictionaryUrl(word))))
                },
            )
        }

        if (editingLanguage) {
            LanguageDialog(
                initial = secondaryLanguage,
                onDismiss = { editingLanguage = false },
                onConfirm = { lang ->
                    secondaryLanguage = lang.ifBlank { "English" }
                    editingLanguage = false
                },
            )
        }

        if (showingMarkedWords) {
            MarkedWordsDialog(
                words = markedWords,
                onDismiss = { showingMarkedWords = false },
                onRemove = { primary ->
                    markedWordsStore.remove(primary)
                    markedWords = markedWordsStore.getAll()
                },
            )
        }

        if (editingRecognizer) {
            RecognizerSettingsDialog(
                initial = recognizerSettings,
                onDismiss = { editingRecognizer = false },
                onConfirm = { updated ->
                    settingsStore.save(updated)
                    recognizerSettings = updated
                    editingRecognizer = false
                },
            )
        }

        if (editingFrequency) {
            UpdateFrequencyDialog(
                initialIntervalMs = updateIntervalMs,
                onDismiss = { editingFrequency = false },
                onConfirm = { ms ->
                    updateIntervalMs = ms
                    editingFrequency = false
                },
            )
        }
    }
}

/** 在單眼畫面裡水平置中畫一行帶半透明底的文字（VR 提示用）。 */
private fun DrawScope.drawVrText(
    textMeasurer: TextMeasurer,
    text: String,
    centerY: Float,
    paneWidth: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    val layout = textMeasurer.measure(text, TextStyle(fontSize = fontSize))
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val x = ((paneWidth - w) / 2f).coerceAtLeast(0f)
    val y = centerY - h / 2f
    drawRect(
        color = Color.Black.copy(alpha = 0.6f),
        topLeft = Offset(x - 8f, y - 4f),
        size = Size(w + 16f, h + 8f),
    )
    drawText(textLayoutResult = layout, topLeft = Offset(x, y), color = Color.White)
}

/** 畫所有框＋標籤文字；一般模式和 VR 模式的兩個分割畫面都呼叫這個共用邏輯。 */
private fun DrawScope.drawDetections(
    trackedBoxes: Map<Int, TrackedBox>,
    labels: Map<Int, LabelState>,
    displayMode: DisplayMode,
    transform: PreviewTransform,
    textMeasurer: TextMeasurer,
) {
    trackedBoxes.forEach { (id, tracked) ->
        val rect = transform.apply(tracked.box)
        val state = labels[id] ?: LabelState.Unknown

        val color = when (state) {
            is LabelState.Named -> Color(0xFF4CAF50)
            LabelState.Recognizing -> Color(0xFFFFC107)
            is LabelState.Failed -> Color(0xFFF44336)
            LabelState.Unknown -> Color(0xFF9E9E9E)
        }

        drawRect(
            color = color,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 4f),
        )

        val text = state.displayText(displayMode)
        val layout = textMeasurer.measure(text, TextStyle(fontSize = 16.sp))
        val textW = layout.size.width.toFloat()
        val textH = layout.size.height.toFloat()

        // 預設畫在框的正上方；上面放不下就改畫在框裡面的上緣。
        // 水平方向永遠夾在螢幕寬度內，避免物件靠邊、或雙語文字較寬時被擠出畫面。
        val preferredY = rect.top - textH - LABEL_PADDING_PX
        val textY = if (preferredY >= 0f) preferredY else rect.top + LABEL_PADDING_PX
        val maxX = max(0f, size.width - textW - LABEL_PADDING_PX)
        val textX = rect.left.coerceIn(LABEL_PADDING_PX, max(LABEL_PADDING_PX, maxX))
        val clampedY = min(max(0f, textY), max(0f, size.height - textH))

        // 文字底下墊一塊半透明底色，不然彩色框線上疊白字常常看不清楚
        drawRect(
            color = Color.Black.copy(alpha = 0.55f),
            topLeft = Offset(textX - 4f, clampedY - 2f),
            size = Size(textW + 8f, textH + 4f),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(textX, clampedY),
            color = Color.White,
        )
    }
}

/**
 * 用 Google 搜尋查字義，語言不限，中英日韓文字都能正常編碼。
 * 用 java.net.URLEncoder 而不是 android.net.Uri.encode，這樣這個函式是純
 * JVM 邏輯，可以直接寫單元測試（見 app/src/test/.../DictionaryUrlTest.kt）。
 */
internal fun dictionaryUrl(word: String): String =
    "https://www.google.com/search?q=" + URLEncoder.encode("define $word", "UTF-8")

@Composable
private fun RenameDialog(
    initialPrimary: String,
    initialSecondary: String,
    markedPrimaries: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (primary: String, secondary: String) -> Unit,
    onToggleMark: (primary: String, secondary: String) -> Unit,
    onOpenDictionary: (word: String) -> Unit,
    failure: LabelState.Failed? = null,
) {
    var primary by remember { mutableStateOf(initialPrimary) }
    var secondary by remember { mutableStateOf(initialSecondary) }
    val isMarked = primary.isNotBlank() && primary in markedPrimaries

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("這是什麼？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 辨識失敗的話，框上只放得下短標籤，完整原因在這裡顯示
                failure?.let { f ->
                    Text(
                        text = "辨識失敗：${f.reason.shortLabel}" +
                            (f.detail?.let { "\n$it" } ?: ""),
                        color = Color(0xFFF44336),
                        fontSize = 12.sp,
                    )
                }
                OutlinedTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = { Text("顯示名稱（原文）") },
                )
                OutlinedTextField(
                    value = secondary,
                    onValueChange = { secondary = it },
                    label = { Text("翻譯名稱（選填）") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onToggleMark(primary, secondary) },
                        enabled = primary.isNotBlank(),
                    ) {
                        Text(if (isMarked) "★ 取消標記" else "☆ 標記這個單字")
                    }
                    TextButton(
                        onClick = {
                            val word = secondary.ifBlank { primary }
                            if (word.isNotBlank()) onOpenDictionary(word)
                        },
                        enabled = primary.isNotBlank() || secondary.isNotBlank(),
                    ) {
                        Text("查字典")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(primary, secondary) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LanguageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻譯成什麼語言？") },
        text = {
            Column {
                Text("直接輸入語言名稱即可，例如 English、日文、韓文、西班牙文", fontSize = 12.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("目標語言") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MarkedWordsDialog(
    words: List<MarkedWord>,
    onDismiss: () -> Unit,
    onRemove: (primary: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("標記過的單字") },
        text = {
            if (words.isEmpty()) {
                Text("還沒有標記任何單字。長按一個框，在跳出的對話框裡可以標記。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.forEach { word ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val display = if (!word.secondary.isNullOrBlank()) {
                                "${word.primary}  ${word.secondary}"
                            } else {
                                word.primary
                            }
                            Text(text = display)
                            TextButton(onClick = { onRemove(word.primary) }) { Text("移除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

@Composable
private fun UpdateFrequencyDialog(
    initialIntervalMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var value by remember { mutableStateOf(initialIntervalMs.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("畫面更新頻率") },
        text = {
            Column {
                val fps = 1000f / value
                Text("間隔 ${value.toInt()} 毫秒（約每秒 ${"%.1f".format(fps)} 次）", fontSize = 12.sp)
                Text(
                    "數字越小越靈敏，但比較耗電發熱；數字越大越省電，畫面更新會比較慢。",
                    fontSize = 12.sp,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 80f..1000f,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.toLong()) }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Compose 拿到的 context 不一定直接是 Activity（可能包了幾層），往回拆找到它。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
