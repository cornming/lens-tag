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

    /** 已經有名稱了。custom = true 表示這是使用者自己命名的，不是模型辨識的 */
    data class Named(val text: String, val custom: Boolean = false) : LabelState
}
