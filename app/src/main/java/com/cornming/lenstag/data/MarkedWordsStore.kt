package com.cornming.lenstag.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一個被標記起來的單字：原文＋翻譯（翻譯可能沒有）。 */
data class MarkedWord(val primary: String, val secondary: String?)

/**
 * 單字清單的 JSON 編碼/解碼，跟 SharedPreferences 讀寫分開放，方便直接對
 * 這段邏輯寫單元測試（見 app/src/test/.../MarkedWordsCodecTest.kt）——
 * 測試環境沒有真的 Android Context，但 org.json 這幾個類別本身只是純
 * Java，用 testImplementation("org.json:json:...") 提供一份真的實作
 * （不是 android.jar 裡那份會直接丟例外的 stub）就能測。
 */
internal fun encodeWords(words: List<MarkedWord>): String {
    val array = JSONArray()
    words.forEach { w ->
        array.put(
            JSONObject().apply {
                put("primary", w.primary)
                put("secondary", w.secondary ?: "")
            },
        )
    }
    return array.toString()
}

internal fun decodeWords(raw: String?): List<MarkedWord> {
    if (raw == null) return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            MarkedWord(
                primary = obj.getString("primary"),
                secondary = obj.optString("secondary", "").takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** 已經在清單裡就移除，不在就加入；純邏輯，不碰 SharedPreferences。 */
internal fun toggleWord(words: List<MarkedWord>, word: MarkedWord): List<MarkedWord> {
    if (word.primary.isBlank()) return words
    val index = words.indexOfFirst { it.primary == word.primary }
    return if (index >= 0) words.filterIndexed { i, _ -> i != index } else words + word
}

/**
 * 標記過的單字清單，存在 SharedPreferences 裡的一份 JSON 陣列。
 *
 * 特意不用 Room：這個功能只是存一份簡單清單，用 SharedPreferences＋JSON
 * 就夠了，不需要為此另外導入 Room／KSP annotation processor 增加建置風險
 * （這個專案已經因為版本相容問題踩過幾次建置失敗的坑，能少一個相依套件
 * 就少一個）。以原文（primary）當作單字的唯一識別，同一個原文只會存一筆。
 */
class MarkedWordsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<MarkedWord> = decodeWords(prefs.getString(KEY_WORDS, null))

    /** 已經標記過就取消標記，還沒標記過就加進去。 */
    fun toggle(word: MarkedWord) {
        save(toggleWord(getAll(), word))
    }

    fun remove(primary: String) {
        save(getAll().filterNot { it.primary == primary })
    }

    private fun save(words: List<MarkedWord>) {
        prefs.edit().putString(KEY_WORDS, encodeWords(words)).apply()
    }

    private companion object {
        const val PREFS_NAME = "marked_words"
        const val KEY_WORDS = "words"
    }
}
