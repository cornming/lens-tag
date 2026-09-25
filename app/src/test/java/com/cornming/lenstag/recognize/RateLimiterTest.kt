package com.cornming.lenstag.recognize

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

class RecognizerKindTest {

    @Test
    fun `free on-device recognition keeps auto-recognizing in live mode`() {
        assertTrue(RecognizerKind.ON_DEVICE.autoRecognizeLive)
    }

    @Test
    fun `paid Azure recognition never auto-fires in live mode`() {
        // 這條是成本保護的核心：即時模式每個新追蹤 ID 都會觸發，
        // Azure 自動辨識的話拿著手機走一圈就是幾十次付費呼叫
        assertFalse(RecognizerKind.AZURE.autoRecognizeLive)
    }
}
