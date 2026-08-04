package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AcceptAllCandidateFreezerTest {

    @Test
    fun `freezes acceptable outputs that have not been opened in Compare`() {
        val root = Files.createTempDirectory("plugin-accept-all").toFile()
        val first = item(root, "First")
        val unopened = item(root, "Unopened")
        val firstGenerated = requireNotNull(first.testOutput).file
        val unopenedGenerated = requireNotNull(unopened.testOutput).file
        val displayed = GoldenAccept.freeze(first.golden, firstGenerated).getOrThrow()

        val frozen = freezeAcceptAllCandidates(
            candidates = listOf(first, unopened),
            displayedCandidates = mapOf(first.golden.normalize().path to displayed),
        )

        assertEquals(2, frozen.size)
        assertTrue(frozen.all { it.source is GoldenAccept.FrozenSource.Exact })
        assertEquals(
            listOf(firstGenerated, unopenedGenerated),
            frozen.map { (it.source as GoldenAccept.FrozenSource.Exact).generated },
        )
    }

    private fun item(root: File, name: String): MergedGoldenItem {
        val golden = File(root, "goldens/$name.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val generated = File(root, "output/${name}_actual.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        return MergedGoldenItem(
            golden = golden,
            workingCopy = GoldenSourceVariant(golden, ExtraComparisonItemStatus.UNCHANGED),
            testOutput = GoldenSourceVariant(generated, ExtraComparisonItemStatus.MODIFIED),
        )
    }
}
