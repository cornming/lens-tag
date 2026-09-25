package com.cornming.lenstag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CustomNamesTest {

    @Test
    fun `normalization absorbs surface differences in how a model phrases the same word`() {
        // 模型同一個字有時多帶標點、空白、大小寫不同，這些都該對到同一筆
        assertEquals(normalizeKey("門"), normalizeKey(" 門 "))
        assertEquals(normalizeKey("門"), normalizeKey("門。"))
        assertEquals(normalizeKey("門"), normalizeKey("「門」"))
        assertEquals(normalizeKey("door"), normalizeKey("Door"))
        assertEquals(normalizeKey("door"), normalizeKey("door."))
    }

    @Test
    fun `normalization does not merge genuinely different words`() {
        // 這是查表做法天生的極限，測試把它寫明：「門」跟「木門」是兩筆，
        // 不會被當成同一個東西
        assertNotEquals(normalizeKey("門"), normalizeKey("木門"))
    }

    @Test
    fun `round trip keeps names with and without a translation`() {
        val names = mapOf(
            "門" to CustomName("大門", "front door"),
            "桌子" to CustomName("書桌", null),
        )
        assertEquals(names, decodeCustomNames(encodeCustomNames(names)))
    }

    @Test
    fun `decoding garbage or nothing yields an empty table instead of crashing`() {
        assertEquals(emptyMap<String, CustomName>(), decodeCustomNames(null))
        assertEquals(emptyMap<String, CustomName>(), decodeCustomNames(""))
        assertEquals(emptyMap<String, CustomName>(), decodeCustomNames("not json"))
    }
}
