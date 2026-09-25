package com.cornming.lenstag.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用假時鐘驗證限流，不用真的等一分鐘。 */
class RateLimiterTest {

    private var fakeNow = 0L
    private fun limiter(maxCalls: Int, windowMs: Long) =
        RateLimiter(maxCalls, windowMs) { fakeNow }

    @Test
    fun `allows calls up to the limit then refuses`() {
        val limiter = limiter(maxCalls = 3, windowMs = 60_000L)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `a refused call does not use up quota`() {
        // 被拒絕的呼叫不能記進去，不然額度會越拒越少、永遠恢復不了
        val limiter = limiter(maxCalls = 1, windowMs = 1_000L)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        fakeNow = 1_000L
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `changing the limit takes effect immediately without resetting the count`() {
        // 使用者拖滑桿調上限時不重建限流器，不然計數歸零等於改設定就能繞過上限
        val limiter = limiter(maxCalls = 5, windowMs = 60_000L)
        repeat(3) { assertTrue(limiter.tryAcquire()) }

        limiter.maxCalls = 3 // 調低到剛好等於已用掉的次數
        assertFalse(limiter.tryAcquire())

        limiter.maxCalls = 4 // 再調高一格，馬上多出一次額度
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `quota frees up as old calls slide out of the window`() {
        val limiter = limiter(maxCalls = 2, windowMs = 1_000L)
        fakeNow = 0L
        assertTrue(limiter.tryAcquire())
        fakeNow = 500L
        assertTrue(limiter.tryAcquire())
        fakeNow = 999L
        assertFalse(limiter.tryAcquire()) // 兩筆都還在視窗內
        fakeNow = 1_000L
        assertTrue(limiter.tryAcquire()) // 第一筆（t=0）滑出去了，空出一格
        assertFalse(limiter.tryAcquire()) // t=500 那筆還在
    }
}

class CostProtectionTest {

    @Test
    fun `free on-device recognition always auto-recognizes in live mode`() {
        // 成本保護只跟付費的 Azure 有關，手機內建 AI 不受開關影響
        val withProtection = RecognizerSettings(RecognizerKind.ON_DEVICE, AzureSettings(liveCostProtection = true))
        val withoutProtection = RecognizerSettings(RecognizerKind.ON_DEVICE, AzureSettings(liveCostProtection = false))
        assertTrue(withProtection.shouldAutoRecognizeLive())
        assertTrue(withoutProtection.shouldAutoRecognizeLive())
    }

    @Test
    fun `cost protection is on by default, so Azure never auto-fires out of the box`() {
        // 預設值很重要：新使用者設好 Azure 之後，不該在不知情的情況下開始自動燒錢
        assertTrue(AzureSettings().liveCostProtection)
        assertFalse(RecognizerSettings(RecognizerKind.AZURE).shouldAutoRecognizeLive())
    }

    @Test
    fun `turning cost protection off lets Azure auto-recognize in live mode`() {
        val settings = RecognizerSettings(RecognizerKind.AZURE, AzureSettings(liveCostProtection = false))
        assertTrue(settings.shouldAutoRecognizeLive())
    }

    @Test
    fun `stored call limits are clamped to the adjustable range`() {
        assertEquals(DEFAULT_MAX_CALLS_PER_MINUTE, AzureSettings().maxCallsPerMinute)
        assertEquals(MIN_MAX_CALLS_PER_MINUTE, clampMaxCalls(0)) // 0 等於完全不能用
        assertEquals(MIN_MAX_CALLS_PER_MINUTE, clampMaxCalls(-5))
        assertEquals(MAX_MAX_CALLS_PER_MINUTE, clampMaxCalls(99_999))
        assertEquals(60, clampMaxCalls(60))
    }
}
