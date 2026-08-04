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
    private fun working(status: ExtraComparisonItemStatus) = GoldenSourceVariant(golden, status)
    private fun testOutput(status: ExtraComparisonItemStatus) = GoldenSourceVariant(generated, status)

    @Test
    fun `modified test output selects generated`() {
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.UNCHANGED),
            testOutput(ExtraComparisonItemStatus.MODIFIED),
        )

        assertEquals(BuiltInSource.GENERATED, automaticGoldenSource(item))
    }

    @Test
    fun `new test output selects generated`() {
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.UNCHANGED),
            testOutput(ExtraComparisonItemStatus.NEW),
        )

        assertEquals(BuiltInSource.GENERATED, automaticGoldenSource(item))
    }

    @Test
    fun `modified working copy with unchanged test output selects working copy`() {
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.MODIFIED),
            testOutput(ExtraComparisonItemStatus.UNCHANGED),
        )

        assertEquals(BuiltInSource.WORKING_COPY, automaticGoldenSource(item))
    }

    @Test
    fun `new working copy without test output selects working copy`() {
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.NEW),
            testOutput = null,
        )

        assertEquals(BuiltInSource.WORKING_COPY, automaticGoldenSource(item))
    }

    @Test
    fun `unchanged variants select working copy`() {
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.UNCHANGED),
            testOutput(ExtraComparisonItemStatus.UNCHANGED),
        )

        assertEquals(BuiltInSource.WORKING_COPY, automaticGoldenSource(item))
    }

    @Test
    fun `missing preferred variant falls back to only available variant`() {
        val item = MergedGoldenItem(
            golden,
            workingCopy = null,
            testOutput(ExtraComparisonItemStatus.UNCHANGED),
        )

        assertEquals(BuiltInSource.GENERATED, automaticGoldenSource(item))
    }

    @Test
    fun `accepting failed test output switches automatically to working copy`() {
        val failed = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.UNCHANGED),
            testOutput(ExtraComparisonItemStatus.MODIFIED),
        )
        val accepted = failed.copy(
            workingCopy = working(ExtraComparisonItemStatus.MODIFIED),
            testOutput = null,
        )

        assertEquals(BuiltInSource.GENERATED, automaticGoldenSource(failed))
        assertEquals(BuiltInSource.WORKING_COPY, automaticGoldenSource(accepted))
    }

    @Test
    fun `source labels use explicit user-facing wording`() {
        assertEquals("Test output", sourceLabel(BuiltInSource.GENERATED))
        assertEquals("Working copy", sourceLabel(BuiltInSource.WORKING_COPY))
    }

    @Test
    fun `comparison uses the exact generated file already resolved by the scanner`() {
        val xcresultActual = File("CoreSimulator/tmp/LoginTests/Login.png")
        val item = MergedGoldenItem(
            golden,
            working(ExtraComparisonItemStatus.UNCHANGED),
            GoldenSourceVariant(xcresultActual, ExtraComparisonItemStatus.MODIFIED),
        )

        assertEquals(
            xcresultActual,
            comparisonSourceFile(golden, BuiltInSource.GENERATED, item),
        )
    }
}
