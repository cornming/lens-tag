package com.cornming.lenstag.vr

import android.content.Context

/** VR 畫面用哪一套引擎。 */
enum class VrEngine(val label: String) {
    /** OpenGL：相機預覽直接送進 GPU，畫面跟相機一樣流暢，有鏡片變形校正 */
    GL("流暢"),

    /** 舊版：用分析影格（每秒約 6～7 張），沒有變形校正。新引擎有問題時的退路 */
    LEGACY("相容"),
}

const val DEFAULT_LENS_SEPARATION_MM = 63f
const val MIN_LENS_SEPARATION_MM = 50f
const val MAX_LENS_SEPARATION_MM = 75f
const val DEFAULT_DISTORTION = 0.2f
const val MAX_DISTORTION = 0.5f

/**
 * VR 相關設定。
 *
 * 鏡片間距和變形強度的預設值只是「大概」：每一款 cardboard 眼鏡的鏡片都
 * 不一樣，沒辦法有一個對所有眼鏡都對的數字。要靠實際戴上去調——
 * 間距調到兩眼畫面自然合成一個、不會重影；變形調到門框、窗框這種直線
 * 看起來是直的。
 */
data class VrSettings(
    val engine: VrEngine = VrEngine.GL,
    val lensSeparationMm: Float = DEFAULT_LENS_SEPARATION_MM,
    val distortion: Float = DEFAULT_DISTORTION,
)

internal fun clampLensSeparation(mm: Float): Float =
    mm.coerceIn(MIN_LENS_SEPARATION_MM, MAX_LENS_SEPARATION_MM)

internal fun clampDistortion(value: Float): Float = value.coerceIn(0f, MAX_DISTORTION)

class VrSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): VrSettings = VrSettings(
        engine = runCatching {
            VrEngine.valueOf(prefs.getString(KEY_ENGINE, null) ?: VrEngine.GL.name)
        }.getOrDefault(VrEngine.GL),
        lensSeparationMm = clampLensSeparation(
            prefs.getFloat(KEY_LENS_SEPARATION, DEFAULT_LENS_SEPARATION_MM),
        ),
        distortion = clampDistortion(prefs.getFloat(KEY_DISTORTION, DEFAULT_DISTORTION)),
    )

    fun save(settings: VrSettings) {
        prefs.edit()
            .putString(KEY_ENGINE, settings.engine.name)
            .putFloat(KEY_LENS_SEPARATION, clampLensSeparation(settings.lensSeparationMm))
            .putFloat(KEY_DISTORTION, clampDistortion(settings.distortion))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "vr_settings"
        const val KEY_ENGINE = "engine"
        const val KEY_LENS_SEPARATION = "lens_separation_mm"
        const val KEY_DISTORTION = "distortion"
    }
}
