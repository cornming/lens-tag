package com.cornming.lenstag.recognize

/**
 * 滑動視窗限流：windowMs 內最多允許 maxCalls 次呼叫。
 *
 * 用途是 Azure 呼叫的安全網——正常使用下（即時模式改成點擊才辨識、拍照模式
 * 本來就是手動）不太會碰到上限；它防的是程式邏輯出錯導致狂打 API 的情況，
 * 例如某個迴圈意外重複觸發辨識。這種錯誤在付費 API 上是真金白銀。
 *
 * 時間來源可注入，方便用假時鐘寫測試，不用真的等一分鐘。
 */
class RateLimiter(
    maxCalls: Int,
    private val windowMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 上限可以即時調整（使用者在設定裡拖滑桿），不需要重建限流器——
     * 重建的話計數會歸零，等於改一下設定就能繞過上限。
     */
    @Volatile
    var maxCalls: Int = maxCalls

    private val timestamps = ArrayDeque<Long>()

    /** 還有額度就記一筆並回 true；已經用完回 false，不記錄。 */
    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        while (timestamps.isNotEmpty() && t - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxCalls) return false
        timestamps.addLast(t)
        return true
    }
}
