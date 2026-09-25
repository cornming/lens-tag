package com.cornming.lenstag.ui

import com.cornming.lenstag.data.CustomName
import com.cornming.lenstag.recognize.FailureReason
import com.cornming.lenstag.recognize.RecognitionResult

/**
 * 一個被偵測到的物件目前的標籤狀態。
 *
 * 「Unknown 是合法狀態」是這個 App 的核心設計決定（借自 Tesla 的
 * ontology cracks 觀點）：偵測到物件跟認得出物件是兩件事，框永遠先畫出來，
 * 辨識不出來也只是標籤停在某個狀態，使用者仍然可以點框手動命名。
 * 功能不會整個掛掉。
 */
sealed interface LabelState {
    /** 偵測到了，但還沒辨識（剛出現、還在動、或 Azure 模式下等使用者點擊） */
    data object Unknown : LabelState

    /** 正在送去辨識中 */
    data object Recognizing : LabelState

    /**
     * 辨識失敗了，而且知道為什麼。跟 Unknown 分開，是因為「還沒試」跟
     * 「試過但失敗」對使用者的意義完全不同：前者點一下就會開始辨識，
     * 後者需要知道原因才知道該怎麼處理（去改設定？等模型下載？檢查網路？）。
     * 點一下 Failed 的框會重試。
     */
    data class Failed(val reason: FailureReason, val detail: String? = null) : LabelState

    /**
     * 已經有名稱了。
     * @param primary 顯示的原文名稱
     * @param secondary 顯示的翻譯名稱，沒有就是 null
     * @param custom true 表示顯示的是使用者自訂的名稱，不是模型原本的說法
     * @param recognizedAs 模型「原本」把它辨識成什麼，是自訂名稱對照表的鑰匙。
     *   畫面上顯示的是自訂名稱時，得靠這個才知道要更新哪一筆對照；
     *   從來沒被模型辨識過（例如辨識失敗後直接手動命名）就是 null，
     *   這種情況只改這一個框，不會存成規則
     */
    data class Named(
        val primary: String,
        val secondary: String? = null,
        val custom: Boolean = false,
        val recognizedAs: String? = null,
    ) : LabelState
}

/**
 * 把辨識結果轉成畫面上的標籤狀態，同時查自訂名稱對照表。
 *
 * 這就是「下次看到門，就顯示我取的名字」實際發生的地方：模型說這是「門」，
 * 如果使用者之前把「門」改名過，就顯示那個名字，而不是模型的說法。
 * lookup 以函式傳入，這樣這段邏輯可以直接測試，不需要真的 SharedPreferences。
 */
fun RecognitionResult.toLabelState(
    lookup: (recognizedAs: String) -> CustomName? = { null },
): LabelState = when (this) {
    is RecognitionResult.Success -> {
        val key = label.primary
        lookup(key)
            ?.let { custom ->
                LabelState.Named(
                    primary = custom.primary,
                    secondary = custom.secondary,
                    custom = true,
                    recognizedAs = key,
                )
            }
            ?: LabelState.Named(
                primary = label.primary,
                secondary = label.secondary,
                recognizedAs = key,
            )
    }
    is RecognitionResult.Failure -> LabelState.Failed(reason, detail)
}

/** 畫面上要顯示原文、翻譯、還是兩個都顯示。 */
enum class DisplayMode(val label: String) {
    PRIMARY("原文"),
    SECONDARY("翻譯"),
    BOTH("雙語");

    fun next(): DisplayMode = entries[(ordinal + 1) % entries.size]
}

/** 依目前的顯示模式，把一個 LabelState 轉成要畫在螢幕上的文字。 */
fun LabelState.displayText(mode: DisplayMode): String = when (this) {
    LabelState.Unknown -> "?"
    LabelState.Recognizing -> "…"
    is LabelState.Failed -> "⚠ ${reason.shortLabel}"
    is LabelState.Named -> when (mode) {
        DisplayMode.PRIMARY -> primary
        DisplayMode.SECONDARY -> secondary ?: primary
        DisplayMode.BOTH -> if (secondary != null) "$primary $secondary" else primary
    }
}
