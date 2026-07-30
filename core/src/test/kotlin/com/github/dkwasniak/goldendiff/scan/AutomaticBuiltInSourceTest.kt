package com.github.dkwasniak.goldendiff.scan

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AutomaticBuiltInSourceTest {
    private val golden = File("goldens/Login.png")
    private val generated = File("output/Login_actual.png")

    private fun variant(file: File, status: ExtraComparisonItemStatus) =
        GoldenSourceVariant(file, status)

    @Test
    fun `changed and new test output win`() {
        listOf(ExtraComparisonItemStatus.MODIFIED, ExtraComparisonItemStatus.NEW).forEach { status ->
            val item = MergedGoldenItem(
                golden,
                variant(golden, ExtraComparisonItemStatus.UNCHANGED),
                variant(generated, status),
            )
            assertEquals(BuiltInSource.GENERATED, automaticBuiltInSource(item))
        }
    }

    @Test
    fun `working copy wins when test output is unchanged`() {
        listOf(ExtraComparisonItemStatus.MODIFIED, ExtraComparisonItemStatus.NEW).forEach { status ->
            val item = MergedGoldenItem(
                golden,
                variant(golden, status),
                variant(generated, ExtraComparisonItemStatus.UNCHANGED),
            )
            assertEquals(BuiltInSource.WORKING_COPY, automaticBuiltInSource(item))
            assertEquals(status, item.workingCopy?.status)
            assertEquals(ExtraComparisonItemStatus.UNCHANGED, item.testOutput?.status)
        }
    }

    @Test
    fun `single source and empty item use stable fallbacks`() {
        val generatedOnly = MergedGoldenItem(
            golden,
            null,
            variant(generated, ExtraComparisonItemStatus.UNCHANGED),
        )
        assertEquals(BuiltInSource.GENERATED, automaticBuiltInSource(generatedOnly))
        assertEquals(BuiltInSource.WORKING_COPY, automaticBuiltInSource(null))
        assertEquals(BuiltInSource.WORKING_COPY, automaticBuiltInSource(MergedGoldenItem(golden, null, null)))
    }

    @Test
    fun `removing accepted test output returns to working copy`() {
        val failed = MergedGoldenItem(
            golden,
            variant(golden, ExtraComparisonItemStatus.UNCHANGED),
            variant(generated, ExtraComparisonItemStatus.MODIFIED),
        )
        val accepted = failed.copy(
            workingCopy = variant(golden, ExtraComparisonItemStatus.MODIFIED),
            testOutput = null,
        )

        assertEquals(BuiltInSource.GENERATED, automaticBuiltInSource(failed))
        assertEquals(BuiltInSource.WORKING_COPY, automaticBuiltInSource(accepted))
    }

    @Test
    fun `ambiguous Test output is failed but keeps Working copy as safe source`() {
        val item = MergedGoldenItem(
            golden = golden,
            workingCopy = variant(golden, ExtraComparisonItemStatus.UNCHANGED),
            testOutput = null,
            testOutputResolution = GeneratedCounterpartResolution.Ambiguous(
                listOf(File("one/Login_actual.png"), File("two/Login_actual.png")),
            ),
        )

        assertEquals(MergedGoldenStatus.FAILED, item.displayStatus)
        assertEquals(BuiltInSource.WORKING_COPY, automaticBuiltInSource(item))
    }
}
