package com.cornming.lenstag.data

import android.content.Context
import org.json.JSONObject

/** 使用者對某種東西自訂的顯示名稱。 */
data class CustomName(val primary: String, val secondary: String?)

/**
 * 查表用的鑰匙正規化：去掉前後空白、內部空白、常見標點，英文轉小寫。
 *
 * 鑰匙是「模型辨識出來的文字」，而模型的措辭不保證每次一致。正規化能吸收
 * 「門」vs「門。」vs「 門 」、「Door」vs「door」這種表面差異；但吸收不了
 * 「門」vs「木門」這種用詞差異——那是這種做法天生的極限。
 */
internal fun normalizeKey(raw: String): String =
    raw.trim()
        .lowercase()
        .filterNot { it.isWhitespace() || it in PUNCTUATION }

private const val PUNCTUATION = "。，、．.,!?！？「」『』\"'：:；;（）()"

internal fun encodeCustomNames(names: Map<String, CustomName>): String {
    val root = JSONObject()
    names.forEach { (key, name) ->
        root.put(
            key,
            JSONObject().apply {
                put("primary", name.primary)
                put("secondary", name.secondary ?: "")
            },
        )
    }
    return root.toString()
}

internal fun decodeCustomNames(raw: String?): Map<String, CustomName> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val root = JSONObject(raw)
        root.keys().asSequence().associateWith { key ->
            val obj = root.getJSONObject(key)
            CustomName(
                primary = obj.getString("primary"),
                secondary = obj.optString("secondary", "").takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * 「模型辨識成 X」→「顯示成使用者取的名字」的對照表。這就是最初需求裡
 * 「下次看到門，就顯示我自訂的名字」的那一塊。
 *
 * 啟動時整份讀進記憶體，之後查表不碰磁碟（辨識結果回來時要馬上查），
 * 寫入時同步存回 SharedPreferences。
 *
 * 跟 MarkedWordsStore（單字本）刻意分開：兩者概念不同。單字本是「我想學
 * 這個字」的學習清單；這裡是「看到 X 時顯示成 Y」的顯示規則。硬併成一份
 * 反而會讓兩個功能的語意都變模糊。
 */
class CustomNamesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val names: MutableMap<String, CustomName> =
        decodeCustomNames(prefs.getString(KEY_NAMES, null)).toMutableMap()

    fun lookup(recognizedAs: String): CustomName? = names[normalizeKey(recognizedAs)]

    fun put(recognizedAs: String, name: CustomName) {
        val key = normalizeKey(recognizedAs)
        if (key.isEmpty()) return
        names[key] = name
        persist()
    }

    fun remove(recognizedAs: String) {
        if (names.remove(normalizeKey(recognizedAs)) != null) persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_NAMES, encodeCustomNames(names)).apply()
    }

    private companion object {
        const val PREFS_NAME = "custom_names"
        const val KEY_NAMES = "names"
    }
}
