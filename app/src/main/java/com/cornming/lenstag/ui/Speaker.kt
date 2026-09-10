package com.cornming.lenstag.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 常見語言名稱（使用者在「翻譯語言」輸入框打的字）對應到 TTS 用的 Locale。 */
private val LANGUAGE_LOCALES: Map<String, Locale> = mapOf(
    "english" to Locale.US,
    "英文" to Locale.US,
    "日文" to Locale.JAPAN,
    "japanese" to Locale.JAPAN,
    "韓文" to Locale.KOREA,
    "korean" to Locale.KOREA,
    "法文" to Locale.FRANCE,
    "french" to Locale.FRANCE,
    "德文" to Locale.GERMANY,
    "german" to Locale.GERMANY,
    "西班牙文" to Locale("es", "ES"),
    "spanish" to Locale("es", "ES"),
    "越南文" to Locale("vi", "VN"),
    "vietnamese" to Locale("vi", "VN"),
    "泰文" to Locale("th", "TH"),
    "thai" to Locale("th", "TH"),
)

/**
 * 把標籤文字唸出來。原文固定當繁體中文處理；翻譯文字依「翻譯語言」設定的
 * 字串猜對應語系，猜不到就用裝置預設語系（發音可能不準，但不會噴例外）。
 *
 * 注意：這裡用 tts.language 切語系後立刻呼叫 speak()，還沒有在真實裝置上
 * 驗證過雙語連續播放時語系是否每一句都正確套用——不同 TTS 引擎行為可能有
 * 差異，如果實測發現第二句還是用第一句的腔調唸，可能要改成偵測
 * onDone callback 再切語系呼叫下一句。
 */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
    }

    /** 依目前顯示模式唸出對應的文字（原文、翻譯、或兩個都唸）。 */
    fun speak(state: LabelState, mode: DisplayMode, secondaryLanguage: String) {
        val named = state as? LabelState.Named ?: return
        val engine = tts ?: return
        if (!ready) return

        when (mode) {
            DisplayMode.PRIMARY -> {
                engine.language = Locale.TAIWAN
                engine.speak(named.primary, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            DisplayMode.SECONDARY -> {
                val text = named.secondary ?: named.primary
                engine.language = resolveLocale(secondaryLanguage)
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            DisplayMode.BOTH -> {
                engine.language = Locale.TAIWAN
                engine.speak(named.primary, TextToSpeech.QUEUE_FLUSH, null, null)
                named.secondary?.let { secondary ->
                    engine.language = resolveLocale(secondaryLanguage)
                    engine.speak(secondary, TextToSpeech.QUEUE_ADD, null, null)
                }
            }
        }
    }

    private fun resolveLocale(languageHint: String): Locale =
        LANGUAGE_LOCALES[languageHint.trim().lowercase()] ?: Locale.getDefault()

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
