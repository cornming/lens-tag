package com.cornming.lenstag.geometry

import kotlin.math.abs

/**
 * 判斷一批追蹤中的物件框「有沒有停下來」。純數學邏輯，不依賴任何 Android
 * 元件，方便直接用 JUnit 驗證，不用透過模擬器或真的跑一次物件偵測。
 *
 * 用框中心的位移量當穩定度指標，門檻取框寬度的一個比例，這樣近距離的大
 * 物件跟遠距離的小物件會用差不多寬鬆的標準——這是借自 Tesla Occupancy
 * Network「區分靜止/移動物件」的簡化版，見專案討論記錄。
 */
class StabilityTracker(
    private val movementThresholdRatio: Float = 0.02f,
    private val requiredStableFrames: Int = 5,
) {
    private val lastBoxes = mutableMapOf<Int, Box>()
    private val stableFrames = mutableMapOf<Int, Int>()
    private val alreadyEmitted = mutableSetOf<Int>()

    /** 餵一批這一影格偵測到的框（追蹤 ID -> 框），回傳「剛好變成穩定、且還沒回報過」的 ID。 */
    fun update(boxes: Map<Int, Box>): List<Int> {
        val newlyStable = mutableListOf<Int>()

        boxes.forEach { (id, box) ->
            val previous = lastBoxes[id]
            lastBoxes[id] = box

            if (previous == null) {
                stableFrames[id] = 0
                return@forEach
            }

            val dx = abs(box.centerX - previous.centerX)
            val dy = abs(box.centerY - previous.centerY)
            val threshold = box.width * movementThresholdRatio

            if (dx < threshold && dy < threshold) {
                val frames = (stableFrames[id] ?: 0) + 1
                stableFrames[id] = frames
                if (frames == requiredStableFrames && id !in alreadyEmitted) {
                    alreadyEmitted.add(id)
                    newlyStable.add(id)
                }
            } else {
                // 物件又動起來了，穩定度歸零重算
                stableFrames[id] = 0
            }
        }

        return newlyStable
    }

    /** 追蹤 ID 消失時清掉狀態，避免這幾個 map 無限成長。alreadyEmitted 刻意不清。 */
    fun prune(currentIds: Set<Int>) {
        val gone = lastBoxes.keys - currentIds
        gone.forEach { id ->
            lastBoxes.remove(id)
            stableFrames.remove(id)
        }
    }
}
