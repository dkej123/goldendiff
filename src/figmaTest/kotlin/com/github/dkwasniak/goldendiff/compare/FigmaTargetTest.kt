package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FigmaTargetTest {

    @Test
    fun `parses design url with node id`() {
        val target = FigmaTarget.parse(
            "https://www.figma.com/design/XuoyLQizWOHjVdOdAlB7KR/App-2.0---DEV?node-id=37412-44087&m=dev",
        )

        assertEquals("XuoyLQizWOHjVdOdAlB7KR", target?.fileKey)
        assertEquals("37412:44087", target?.nodeId)
    }

    @Test
    fun `uses branch key for branch design url`() {
        val target = FigmaTarget.parse(
            "https://www.figma.com/design/fileKey/branch/branchKey/File?node-id=1-2",
        )

        assertEquals("branchKey", target?.fileKey)
        assertEquals("1:2", target?.nodeId)
    }

    @Test
    fun `rejects url without node id`() {
        assertNull(FigmaTarget.parse("https://www.figma.com/design/fileKey/File"))
    }
}
