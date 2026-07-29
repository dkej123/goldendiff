package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GoldenSourceSelectionTest {

    private val golden = File("goldens/Login.png")
    private val generated = File("output/Login_actual.png")
    private val working = GoldenSourceVariant(golden, ExtraComparisonItemStatus.MODIFIED)
    private val testOutput = GoldenSourceVariant(generated, ExtraComparisonItemStatus.MODIFIED)

    @Test
    fun `new dual-source golden defaults to test output`() {
        val item = MergedGoldenItem(golden, working, testOutput)

        assertEquals(BuiltInSource.GENERATED, preferredGoldenSource(item, remembered = null))
    }

    @Test
    fun `available remembered source wins over the default`() {
        val item = MergedGoldenItem(golden, working, testOutput)

        assertEquals(
            BuiltInSource.WORKING_COPY,
            preferredGoldenSource(item, remembered = BuiltInSource.WORKING_COPY),
        )
    }

    @Test
    fun `removed test output falls back to working copy`() {
        val refreshed = MergedGoldenItem(golden, working, testOutput = null)

        assertEquals(
            BuiltInSource.WORKING_COPY,
            preferredGoldenSource(refreshed, remembered = BuiltInSource.GENERATED),
        )
    }

    @Test
    fun `test-output-only golden selects its available variant`() {
        val item = MergedGoldenItem(golden, workingCopy = null, testOutput = testOutput)

        assertEquals(
            BuiltInSource.GENERATED,
            preferredGoldenSource(item, remembered = BuiltInSource.WORKING_COPY),
        )
    }

    @Test
    fun `source labels use explicit user-facing wording`() {
        assertEquals("Test output", sourceLabel(BuiltInSource.GENERATED))
        assertEquals("Working copy", sourceLabel(BuiltInSource.WORKING_COPY))
    }
}
