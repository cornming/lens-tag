package com.cornming.lenstag.ui

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
     * @param primary 原文名稱（繁體中文）
     * @param secondary 翻譯名稱，沒有翻譯（或辨識時沒要求）就是 null
     * @param custom true 表示這是使用者自己輸入的，不是模型辨識的
     */
    data class Named(
        val primary: String,
        val secondary: String? = null,
        val custom: Boolean = false,
    ) : LabelState
}

/** 把辨識結果轉成畫面上的標籤狀態。 */
fun RecognitionResult.toLabelState(): LabelState = when (this) {
    is RecognitionResult.Success -> LabelState.Named(label.primary, label.secondary)
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
