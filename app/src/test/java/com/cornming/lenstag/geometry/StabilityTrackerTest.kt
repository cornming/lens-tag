package com.cornming.lenstag.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「靜止才觸發辨識」那個機制的數學驗證，純 JVM 測試。 */
class StabilityTrackerTest {

    private val stillBox = Box(0f, 0f, 100f, 50f)

    @Test
    fun `reports stable only after enough consecutive still frames`() {
        val tracker = StabilityTracker(movementThresholdRatio = 0.1f, requiredStableFrames = 3)

        assertEquals(emptyList<Int>(), tracker.update(mapOf(1 to stillBox))) // 第一次看到，還沒有基準
        assertEquals(emptyList<Int>(), tracker.update(mapOf(1 to stillBox))) // 穩定 1 影格
        assertEquals(emptyList<Int>(), tracker.update(mapOf(1 to stillBox))) // 穩定 2 影格
        assertEquals(listOf(1), tracker.update(mapOf(1 to stillBox))) // 穩定 3 影格，達門檻
        assertEquals(emptyList<Int>(), tracker.update(mapOf(1 to stillBox))) // 已經回報過，不重複
    }

    @Test
    fun `movement beyond threshold resets the stability count`() {
        val tracker = StabilityTracker(movementThresholdRatio = 0.1f, requiredStableFrames = 3)
        // 框寬 100，門檻是 100 * 0.1 = 10；中心點位移 50 遠超過門檻
        val movedBox = Box(50f, 0f, 150f, 50f)

        tracker.update(mapOf(1 to stillBox))
        tracker.update(mapOf(1 to stillBox)) // 穩定 1 影格
        tracker.update(mapOf(1 to movedBox)) // 位移過大，歸零重算
        tracker.update(mapOf(1 to movedBox)) // 穩定 1 影格（相對新位置）
        tracker.update(mapOf(1 to movedBox)) // 穩定 2 影格
        assertEquals(listOf(1), tracker.update(mapOf(1 to movedBox))) // 穩定 3 影格，達門檻
    }

    @Test
    fun `a tracking id is never reported twice, even after reappearing`() {
        val tracker = StabilityTracker(movementThresholdRatio = 0.1f, requiredStableFrames = 3)

        tracker.update(mapOf(1 to stillBox))
        tracker.update(mapOf(1 to stillBox))
        tracker.update(mapOf(1 to stillBox))
        assertEquals(listOf(1), tracker.update(mapOf(1 to stillBox)))

        // 物件短暫消失（框沒有繼續出現在偵測結果裡），呼叫 prune
        tracker.prune(emptySet())

        // 同一個追蹤 ID 重新出現，就算又穩定了一輪，也不該再回報一次
        tracker.update(mapOf(1 to stillBox))
        tracker.update(mapOf(1 to stillBox))
        tracker.update(mapOf(1 to stillBox))
        assertEquals(emptyList<Int>(), tracker.update(mapOf(1 to stillBox)))
    }

    @Test
    fun `different tracking ids are tracked independently`() {
        val tracker = StabilityTracker(movementThresholdRatio = 0.1f, requiredStableFrames = 2)
        val otherBox = Box(0f, 100f, 100f, 150f)

        tracker.update(mapOf(1 to stillBox, 2 to otherBox)) // 第一次看到，建立基準
        tracker.update(mapOf(1 to stillBox, 2 to otherBox)) // 穩定 1 影格
        val result = tracker.update(mapOf(1 to stillBox, 2 to otherBox)) // 穩定 2 影格，達門檻

        assertEquals(setOf(1, 2), result.toSet())
    }
}
