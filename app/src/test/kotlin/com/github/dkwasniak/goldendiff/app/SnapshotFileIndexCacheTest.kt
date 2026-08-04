package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SnapshotFileIndexCacheTest {

    private fun sampleIndex(): SnapshotFileIndex {
        val case = SnapshotCase(
            id = "A.kt\u0000A.kt\u0000testA",
            methodName = "testA",
            generatorPath = "A.kt",
            className = "A",
            goldenPaths = listOf("golden/a.png"),
            status = SnapshotFileStatus.MODIFIED,
            failedCount = 0,
            statuses = setOf(SnapshotFileStatus.MODIFIED),
        )
        val file = SnapshotSourceFile(
            relativePath = "A.kt",
            goldenPaths = listOf("golden/a.png"),
            status = SnapshotFileStatus.MODIFIED,
            failedCount = 0,
            cases = listOf(case),
            unmatchedGoldenPaths = listOf("golden/unmatched.png"),
            statuses = setOf(SnapshotFileStatus.MODIFIED),
        )
        return SnapshotFileIndex(
            root = File("/tmp/project"),
            files = listOf(file),
            failedFilesByDirectory = mapOf("golden" to 1),
            fingerprint = SnapshotIndexFingerprint(
                projectPath = "/tmp/project",
                goldenPaths = listOf("golden"),
                matchMode = MatchMode.ANNOTATED_METHOD,
                annotatedFunctionRegex = "@Preview",
                goldenFilePatterns = listOf("*Screen*"),
                excludedSuffixes = listOf("_diff"),
            ),
            goldenInventory = setOf("golden/a.png", "golden/unmatched.png"),
        )
    }

    @Test
    fun `round trips through JSON`() {
        val index = sampleIndex()

        val restored = SnapshotFileIndexCache.fromJson(SnapshotFileIndexCache.toJson(index))

        assertEquals(index, restored)
    }

    @Test
    fun `malformed JSON fails closed instead of throwing`() {
        assertNull(SnapshotFileIndexCache.fromJson(org.json.JSONObject("""{"root":"/tmp"}""")))
    }
}
