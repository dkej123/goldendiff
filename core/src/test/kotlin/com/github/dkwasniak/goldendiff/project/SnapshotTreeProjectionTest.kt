package com.github.dkwasniak.goldendiff.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTreeProjectionTest {
    private fun view(
        path: String,
        status: SnapshotFileStatus,
        statuses: Set<SnapshotFileStatus> = setOf(status),
        cases: List<SnapshotCase> = emptyList(),
        unmatched: List<String> = emptyList(),
    ) = SnapshotSourceFile(
        relativePath = path,
        goldenPaths = cases.flatMap { it.goldenPaths } + unmatched,
        status = status,
        failedCount = if (status == SnapshotFileStatus.FAILED) 1 else 0,
        cases = cases,
        unmatchedGoldenPaths = unmatched,
        statuses = statuses,
    )

    private fun case(
        id: String,
        method: String,
        generator: String,
        status: SnapshotFileStatus,
        statuses: Set<SnapshotFileStatus> = setOf(status),
    ) = SnapshotCase(
        id = id,
        methodName = method,
        generatorPath = generator,
        className = null,
        goldenPaths = listOf("$id.png"),
        status = status,
        failedCount = if (status == SnapshotFileStatus.FAILED) 1 else 0,
        statuses = statuses,
    )

    @Test
    fun `search covers normalized view path name generator path and case`() {
        val button = view(
            "feature\\src\\main\\kotlin\\com\\example\\ui\\Button.kt",
            SnapshotFileStatus.FAILED,
            cases = listOf(
                case("primary", "PrimaryPreview", "tests\\ButtonSnapshots.kt", SnapshotFileStatus.FAILED),
            ),
        )

        listOf("Button.kt", "feature/src", "tests/Button", "PrimaryPreview").forEach { query ->
            val result = projectSnapshotTree(listOf(button), query)
            assertEquals(query, listOf(button.stableId), result.views.map { it.id })
            assertEquals(listOf("primary"), result.views.single().cases.map { it.id })
        }
    }

    @Test
    fun `status filters use or semantics across all represented statuses`() {
        val mixedCase = case(
            "mixed",
            "MixedPreview",
            "tests/Mixed.kt",
            SnapshotFileStatus.MODIFIED,
            setOf(SnapshotFileStatus.MODIFIED, SnapshotFileStatus.NEW),
        )
        val mixed = view(
            "ui/Mixed.kt",
            SnapshotFileStatus.FAILED,
            setOf(SnapshotFileStatus.FAILED, SnapshotFileStatus.MODIFIED, SnapshotFileStatus.NEW),
            listOf(mixedCase),
        )
        val unchanged = view("ui/Unchanged.kt", SnapshotFileStatus.UNCHANGED)

        SnapshotFileStatus.entries.filterNot { it == SnapshotFileStatus.UNCHANGED }.forEach { status ->
            assertEquals(listOf("ui/Mixed.kt"), projectSnapshotTree(listOf(mixed, unchanged), statusFilters = setOf(status)).views.map { it.id })
        }
        assertEquals(
            listOf("ui/Mixed.kt"),
            projectSnapshotTree(
                listOf(mixed, unchanged),
                statusFilters = setOf(
                    SnapshotFileStatus.FAILED,
                    SnapshotFileStatus.MODIFIED,
                    SnapshotFileStatus.NEW,
                ),
            ).views.map { it.id },
        )
    }

    @Test
    fun `selecting multiple status filters returns the union rather than intersection`() {
        val failed = view("ui/Failed.kt", SnapshotFileStatus.FAILED)
        val modified = view("ui/Modified.kt", SnapshotFileStatus.MODIFIED)
        val fresh = view("ui/New.kt", SnapshotFileStatus.NEW)
        val unchanged = view("ui/Unchanged.kt", SnapshotFileStatus.UNCHANGED)

        val result = projectSnapshotTree(
            listOf(failed, modified, fresh, unchanged),
            statusFilters = setOf(SnapshotFileStatus.FAILED, SnapshotFileStatus.NEW),
        )

        assertEquals(
            setOf("ui/Failed.kt", "ui/New.kt"),
            result.views.map { it.id }.toSet(),
        )
    }

    @Test
    fun `case match keeps ancestors and unmatched golden remains at view level`() {
        val entry = view(
            "feature/src/main/kotlin/com/example/buttons/Button.kt",
            SnapshotFileStatus.FAILED,
            cases = listOf(
                case("primary", "PrimaryPreview", "tests/ButtonTests.kt", SnapshotFileStatus.FAILED),
                case("disabled", "DisabledPreview", "tests/ButtonTests.kt", SnapshotFileStatus.UNCHANGED),
            ),
            unmatched = listOf("goldens/Legacy.png"),
        )

        val result = projectSnapshotTree(listOf(entry), "Primary")

        assertEquals(listOf("feature", "buttons"), flattenFolders(result.folders).map { it.name })
        assertEquals(listOf("Button.kt"), result.views.map { java.io.File(it.entry.relativePath).name })
        assertEquals(listOf("primary"), result.views.single().cases.map { it.id })
        assertEquals(listOf("goldens/Legacy.png"), result.views.single().entry.unmatchedGoldenPaths)
    }

    @Test
    fun `folder counters count failed views once and ordering is stable`() {
        val files = listOf(
            view(
                "z/src/main/kotlin/com/example/ui/Zeta.kt",
                SnapshotFileStatus.FAILED,
                cases = listOf(
                    case("z1", "One", "tests/Zeta.kt", SnapshotFileStatus.FAILED),
                    case("z2", "Two", "tests/Zeta.kt", SnapshotFileStatus.FAILED),
                ),
            ),
            view("z/src/main/kotlin/com/example/ui/Alpha.kt", SnapshotFileStatus.FAILED),
            view("a/src/main/kotlin/com/example/data/Store.kt", SnapshotFileStatus.MODIFIED),
            view("u/src/main/kotlin/com/example/plain/Quiet.kt", SnapshotFileStatus.UNCHANGED),
        )

        val first = projectSnapshotTree(files)
        val second = projectSnapshotTree(files.reversed())
        val firstIds = flattenFolders(first.folders).map { it.id } + first.views.map { it.id }
        val secondIds = flattenFolders(second.folders).map { it.id } + second.views.map { it.id }

        assertEquals(firstIds, secondIds)
        val z = flattenFolders(first.folders).single { it.path == "z" }
        assertEquals(2, z.failedViewCount)
        assertEquals(listOf("Alpha.kt", "Zeta.kt"), z.folders.single().views.map { java.io.File(it.entry.relativePath).name })
        assertEquals(2, flattenFolders(projectSnapshotTree(files, "Alpha").folders).single { it.path == "z" }.failedViewCount)
        assertEquals(listOf("z", "a", "u"), first.folders.map { it.path })
        assertTrue(first.folders.last().path == "u")
    }

    @Test
    fun `stable ids are independent of object replacement and path separators`() {
        val first = view("ui\\Button.kt", SnapshotFileStatus.FAILED)
        val refreshed = first.copy(status = SnapshotFileStatus.MODIFIED, failedCount = 0)

        assertEquals("ui/Button.kt", first.stableId)
        assertEquals(
            projectSnapshotTree(listOf(first)).views.single().id,
            projectSnapshotTree(listOf(refreshed)).views.single().id,
        )
    }

    private fun flattenFolders(folders: List<SnapshotFolderProjection>): List<SnapshotFolderProjection> =
        folders.flatMap { listOf(it) + flattenFolders(it.folders) }
}
