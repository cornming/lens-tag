package com.cornming.lenstag.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `parses the build number out of a normal tag`() {
        assertEquals(12, parseBuildNumber("build-12"))
    }

    @Test
    fun `returns null when there is no trailing number`() {
        assertNull(parseBuildNumber("build-"))
        assertNull(parseBuildNumber("release"))
    }

    @Test
    fun `only the segment after the last hyphen is used`() {
        // 萬一以後 tag 命名多加一段前綴，還是要抓最後一段的數字
        assertEquals(7, parseBuildNumber("lens-tag-build-7"))
    }
}
