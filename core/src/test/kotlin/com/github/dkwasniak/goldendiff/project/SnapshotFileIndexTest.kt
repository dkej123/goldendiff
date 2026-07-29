package com.github.dkwasniak.goldendiff.project

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotFileIndexTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("golden-diff-snapshot-index").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun source(path: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText("class ${nameWithoutExtension}")
    }

    private fun source(path: String, contents: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(contents)
    }

    private fun golden(path: String): File = File(root, "goldens/$path").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(1))
    }

    private fun config() = GoldenDiffConfig(
        goldenPaths = listOf("goldens"),
        matchMode = MatchMode.FILE_CLASS_REGEX,
        goldenFilePatterns = listOf(""".*{file_name}.*"""),
    )

    @Test
    fun `includes only source files associated with an existing golden and deduplicates associations`() {
        source("ui/Login.kt")
        source("ui/NoSnapshots.kt")
        val login = golden("Login_dark.png")
        val merged = MergedGoldenItem(
            login,
            GoldenSourceVariant(login, ExtraComparisonItemStatus.UNCHANGED),
            null,
        )

        val index = SnapshotFileIndex.build(ProjectFileIndex.scan(root), config(), listOf(merged, merged))

        assertEquals(listOf("ui/Login.kt"), index.paths)
        assertEquals(1, index.files.single().goldenPaths.size)
    }

    @Test
    fun `failed status wins and failed files aggregate into every ancestor`() {
        source("modules/login/ui/Login.kt")
        val login = golden("Login.png")
        val output = File(root, "out/Login_actual.png")
        val merged = MergedGoldenItem(
            login,
            GoldenSourceVariant(login, ExtraComparisonItemStatus.MODIFIED),
            GoldenSourceVariant(output, ExtraComparisonItemStatus.NEW),
        )

        val index = SnapshotFileIndex.build(ProjectFileIndex.scan(root), config(), listOf(merged))

        assertEquals(SnapshotFileStatus.FAILED, index.files.single().status)
        assertEquals(1, index.files.single().failedCount)
        assertEquals(
            mapOf("modules/login/ui" to 1, "modules/login" to 1, "modules" to 1),
            index.failedFilesByDirectory,
        )
    }

    @Test
    fun `view retains every represented status while failed remains the primary status`() {
        source("ui/Login.kt")
        val failed = golden("Login_failed.png")
        val modified = golden("Login_modified.png")
        val fresh = golden("Login_new.png")
        val output = File(root, "out/Login_failed_actual.png")
        val merged = listOf(
            MergedGoldenItem(
                failed,
                GoldenSourceVariant(failed, ExtraComparisonItemStatus.UNCHANGED),
                GoldenSourceVariant(output, ExtraComparisonItemStatus.NEW),
            ),
            MergedGoldenItem(
                modified,
                GoldenSourceVariant(modified, ExtraComparisonItemStatus.MODIFIED),
                null,
            ),
            MergedGoldenItem(
                fresh,
                GoldenSourceVariant(fresh, ExtraComparisonItemStatus.NEW),
                null,
            ),
        )

        val view = SnapshotFileIndex.build(ProjectFileIndex.scan(root), config(), merged).files.single()

        assertEquals(SnapshotFileStatus.FAILED, view.status)
        assertEquals(
            setOf(
                SnapshotFileStatus.FAILED,
                SnapshotFileStatus.MODIFIED,
                SnapshotFileStatus.NEW,
            ),
            view.statuses,
        )
    }

    @Test
    fun `fingerprint changes for association settings but not display settings`() {
        val base = config()
        val first = SnapshotIndexFingerprint.from(root, base)

        assertEquals(first, SnapshotIndexFingerprint.from(root, base.copy(trimTransparentPadding = true)))
        assertEquals(first, SnapshotIndexFingerprint.from(root, base.copy(generatedPaths = listOf("output"))))
        assertEquals(first, SnapshotIndexFingerprint.from(root, base.copy(verifyCaseCommand = "verify one case")))
        assertNotEquals(first, SnapshotIndexFingerprint.from(root, base.copy(excludedSuffixes = listOf("_actual"))))
        assertNotEquals(first, SnapshotIndexFingerprint.from(root, base.copy(goldenPaths = listOf("other"))))
    }

    @Test
    fun `status refresh preserves cached associations and stable case ids`() {
        source(
            "ui/Button.kt",
            """
            @Preview
            fun PrimaryButtonPreview() {}
            """.trimIndent(),
        )
        val image = golden("PrimaryButtonPreview_phone.png")
        val unchanged = MergedGoldenItem(
            image,
            GoldenSourceVariant(image, ExtraComparisonItemStatus.UNCHANGED),
            null,
        )
        val annotated = GoldenDiffConfig(
            goldenPaths = listOf("goldens"),
            matchMode = MatchMode.ANNOTATED_METHOD,
            annotatedFunctionRegex = ".*Preview.*",
        )
        val original = SnapshotFileIndex.build(ProjectFileIndex.scan(root), annotated, listOf(unchanged))
        val originalCase = original.files.single().cases.single()
        val output = File(root, "output/PrimaryButtonPreview_phone.png")
        val failed = MergedGoldenItem(
            image,
            GoldenSourceVariant(image, ExtraComparisonItemStatus.UNCHANGED),
            GoldenSourceVariant(output, ExtraComparisonItemStatus.NEW),
        )

        val refreshed = SnapshotFileIndex.refreshStatuses(original, listOf(failed))
        val refreshedCase = refreshed.files.single().cases.single()

        assertEquals(originalCase.id, refreshedCase.id)
        assertEquals(originalCase.goldenPaths, refreshedCase.goldenPaths)
        assertEquals(SnapshotFileStatus.FAILED, refreshedCase.status)
        assertEquals(SnapshotFileStatus.FAILED, refreshed.files.single().status)
        assertEquals(1, refreshed.files.single().failedCount)
    }

    @Test
    fun `source cache reparses only files changed between index refreshes`() {
        val source = source(
            "ui/Button.kt",
            """
            @Preview
            fun PrimaryButtonPreview() {}
            """.trimIndent(),
        )
        val image = golden("PrimaryButtonPreview_phone.png")
        val merged = MergedGoldenItem(
            image,
            GoldenSourceVariant(image, ExtraComparisonItemStatus.UNCHANGED),
            null,
        )
        val annotated = GoldenDiffConfig(
            goldenPaths = listOf("goldens"),
            matchMode = MatchMode.ANNOTATED_METHOD,
            annotatedFunctionRegex = ".*Preview.*",
        )
        val candidates = ProjectFileIndex.scan(root)
        val cache = SnapshotSourceCache()

        SnapshotFileIndex.build(candidates, annotated, listOf(merged), cache)
        val afterFirst = cache.stats()
        SnapshotFileIndex.build(candidates, annotated, listOf(merged), cache)
        val afterSecond = cache.stats()
        source.appendText("\n// changed")
        SnapshotFileIndex.build(candidates, annotated, listOf(merged), cache)
        val afterChange = cache.stats()

        assertEquals(1L, afterFirst.misses)
        assertEquals(afterFirst.misses, afterSecond.misses)
        assertEquals(1L, afterSecond.hits)
        assertEquals(afterSecond.misses + 1, afterChange.misses)
    }

    @Test
    fun `android previews become cases of one view and keep their variants separate`() {
        source(
            "ui/Button.kt",
            """
            class Button
            @Preview
            fun PrimaryButtonPreview() {}
            @Preview
            fun DisabledButtonPreview() {}
            """.trimIndent(),
        )
        val primary = golden("PrimaryButtonPreview_phone.png")
        val primaryDark = golden("PrimaryButtonPreview_dark.png")
        val disabled = golden("DisabledButtonPreview_phone.png")
        val merged = listOf(primary, primaryDark, disabled).map {
            MergedGoldenItem(it, GoldenSourceVariant(it, ExtraComparisonItemStatus.UNCHANGED), null)
        }
        val annotated = GoldenDiffConfig(
            goldenPaths = listOf("goldens"),
            matchMode = MatchMode.ANNOTATED_METHOD,
            annotatedFunctionRegex = ".*Preview.*",
        )

        val view = SnapshotFileIndex.build(ProjectFileIndex.scan(root), annotated, merged).files.single()

        assertEquals("ui/Button.kt", view.relativePath)
        assertEquals(listOf("DisabledButtonPreview", "PrimaryButtonPreview"), view.cases.map { it.methodName })
        assertEquals(2, view.cases.single { it.methodName == "PrimaryButtonPreview" }.goldenPaths.size)
        assertEquals(3, view.goldenPaths.distinct().size)
    }

    @Test
    fun `swift test methods are children of production view and test file is not another parent`() {
        source("Sources/LoginView.swift", "struct LoginView: View {}")
        source(
            "Tests/LoginSnapshotTests.swift",
            """
            final class LoginSnapshotTests: XCTestCase {
                func testLoginViewDefault() {}
                func testLoginViewLoading() {}
            }
            """.trimIndent(),
        )
        val first = golden("LoginView/testLoginViewDefault.1.png")
        val second = golden("LoginView/testLoginViewLoading.1.png")
        val merged = listOf(first, second).map {
            MergedGoldenItem(it, GoldenSourceVariant(it, ExtraComparisonItemStatus.UNCHANGED), null)
        }
        val fileClass = GoldenDiffConfig(
            goldenPaths = listOf("goldens"),
            matchMode = MatchMode.FILE_CLASS_REGEX,
            goldenFilePatterns = listOf("{file_name}", "{class_name}"),
        )

        val index = SnapshotFileIndex.build(ProjectFileIndex.scan(root), fileClass, merged)

        assertEquals(listOf("Sources/LoginView.swift"), index.paths)
        assertEquals(
            listOf("testLoginViewDefault", "testLoginViewLoading"),
            index.files.single().cases.map { it.methodName },
        )
        assertEquals(listOf("Tests/LoginSnapshotTests.swift"), index.files.single().cases.map { it.generatorPath }.distinct())
    }

    @Test
    fun `unmatched golden remains on one stable longest parent without an invented case`() {
        source("ui/Login.kt", "class Login")
        source("ui/LoginDetails.kt", "class LoginDetails")
        val image = golden("LoginDetails_phone.png")
        val merged = MergedGoldenItem(image, GoldenSourceVariant(image, ExtraComparisonItemStatus.UNCHANGED), null)
        val broad = config().copy(goldenFilePatterns = listOf("{file_name}"))

        val index = SnapshotFileIndex.build(ProjectFileIndex.scan(root), broad, listOf(merged))

        assertEquals(listOf("ui/LoginDetails.kt"), index.paths)
        assertEquals(emptyList<SnapshotCase>(), index.files.single().cases)
        assertEquals(index.files.single().goldenPaths, index.files.single().unmatchedGoldenPaths)
    }

    @Test
    fun `loosely matching model is not used as a view parent and generator remains the fallback`() {
        source("Sources/Price.swift", "struct Price {}")
        source(
            "Tests/SubscriptionInfoSnapshotTests.swift",
            """
            final class SubscriptionInfoSnapshotTests {
                func test_subscription_withPartnerPrice() {}
            }
            """.trimIndent(),
        )
        val image = golden("SubscriptionInfoSnapshotTests/test_subscription_withPartnerPrice.1.png")
        val merged = MergedGoldenItem(image, GoldenSourceVariant(image, ExtraComparisonItemStatus.UNCHANGED), null)
        val fileClass = GoldenDiffConfig(
            goldenPaths = listOf("goldens"),
            matchMode = MatchMode.FILE_CLASS_REGEX,
            goldenFilePatterns = listOf("{file_name}", "{class_name}"),
        )

        val index = SnapshotFileIndex.build(ProjectFileIndex.scan(root), fileClass, listOf(merged))

        assertEquals(listOf("Tests/SubscriptionInfoSnapshotTests.swift"), index.paths)
        assertEquals("test_subscription_withPartnerPrice", index.files.single().cases.single().methodName)
    }
}
