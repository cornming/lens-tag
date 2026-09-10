package com.cornming.lenstag.ui

/**
 * 一個被偵測到的物件目前的標籤狀態。
 *
 * 「Unknown 是合法狀態」是這個 App 的核心設計決定（借自 Tesla 的
 * ontology cracks 觀點）：偵測到物件跟認得出物件是兩件事，框永遠先畫出來，
 * 辨識不出來、或這台裝置根本不支援 Gemini Nano，都只是標籤停在 Unknown，
 * 使用者仍然可以點框手動命名。功能不會整個掛掉。
 */
sealed interface LabelState {
    /** 偵測到了，但還沒辨識（可能剛出現、還在動、或辨識失敗） */
    data object Unknown : LabelState

    /** 正在丟給 Gemini Nano 辨識中 */
    data object Recognizing : LabelState

    /**
     * 已經有名稱了。
     * @param primary 原文名稱（繁體中文）
     * @param secondary 翻譯名稱，沒有翻譯（或辨識時沒要求）就是 null
     * @param custom true 表示這是使用者自己輸入的，不是 Gemini Nano 辨識的
     */
    data class Named(
        val primary: String,
        val secondary: String? = null,
        val custom: Boolean = false,
    ) : LabelState
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
    is LabelState.Named -> when (mode) {
        DisplayMode.PRIMARY -> primary
        DisplayMode.SECONDARY -> secondary ?: primary
        DisplayMode.BOTH -> if (secondary != null) "$primary $secondary" else primary
    }
}
