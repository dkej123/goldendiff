package com.github.dkwasniak.goldendiff.scan

import com.github.dkwasniak.goldendiff.compare.HeadBytesSource
import com.github.dkwasniak.goldendiff.git.GitChange
import com.github.dkwasniak.goldendiff.git.WorkingCopyStatus
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChangeScannerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("golden-diff-scan").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun golden(relative: String, bytes: ByteArray = byteArrayOf(1)): File =
        File(root, "goldens/$relative").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }

    private fun scanner(
        head: Map<File, ByteArray> = emptyMap(),
        changes: List<GitChange> = emptyList(),
        config: GoldenDiffConfig = GoldenDiffConfig(goldenPaths = listOf("goldens")),
    ) = ChangeScanner(
        projectRoot = root,
        config = config,
        headBytes = object : HeadBytesSource {
            override fun headBytes(file: File): ByteArray? = head[file.normalize()]
        },
        workingCopyStatus = object : WorkingCopyStatus {
            override fun changedFiles(): List<GitChange> = changes
        },
    )

    @Test
    fun `working copy changes keep only goldens under a configured root`() {
        val inside = golden("LoginScreen.png")
        val outside = File(root, "elsewhere/Other.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }

        val items = scanner(
            changes = listOf(
                GitChange(inside, ExtraComparisonItemStatus.MODIFIED),
                GitChange(outside, ExtraComparisonItemStatus.MODIFIED),
            ),
        ).workingCopyChanges()

        assertEquals(listOf("LoginScreen.png"), items.map { it.title })
    }

    @Test
    fun `non-png and excluded-suffix files are not goldens`() {
        val png = golden("Ok.png")
        val notPng = golden("Notes.txt")
        val artifact = golden("Ok_compare.png")

        val items = scanner(
            changes = listOf(png, notPng, artifact).map { GitChange(it, ExtraComparisonItemStatus.MODIFIED) },
        ).workingCopyChanges()

        assertEquals(listOf("Ok.png"), items.map { it.title })
    }

    @Test
    fun `items are sorted changed first, then new, then unchanged`() {
        val unchanged = golden("A_unchanged.png")
        val new = golden("B_new.png")
        val modified = golden("C_modified.png")

        val items = scanner(
            changes = listOf(
                GitChange(unchanged, ExtraComparisonItemStatus.UNCHANGED),
                GitChange(new, ExtraComparisonItemStatus.NEW),
                GitChange(modified, ExtraComparisonItemStatus.MODIFIED),
            ),
        ).workingCopyChanges()

        // Alphabetically this would be A, B, C — status has to win, so the files a reviewer must look
        // at come first.
        assertEquals(listOf("C_modified.png", "B_new.png", "A_unchanged.png"), items.map { it.title })
    }

    @Test
    fun `status against the working copy compares bytes with HEAD`() {
        val same = golden("Same.png", byteArrayOf(1, 2))
        val differs = golden("Differs.png", byteArrayOf(1, 2))
        val untracked = golden("Untracked.png", byteArrayOf(1, 2))
        val scanner = scanner(
            head = mapOf(
                same.normalize() to byteArrayOf(1, 2),
                differs.normalize() to byteArrayOf(9, 9),
            ),
        )

        assertEquals(ExtraComparisonItemStatus.UNCHANGED, scanner.statusOf(same, BuiltInSource.WORKING_COPY))
        assertEquals(ExtraComparisonItemStatus.MODIFIED, scanner.statusOf(differs, BuiltInSource.WORKING_COPY))
        assertEquals(ExtraComparisonItemStatus.NEW, scanner.statusOf(untracked, BuiltInSource.WORKING_COPY))
    }

    @Test
    fun `generated changes compare test output against HEAD, not against the working copy`() {
        val golden = golden("LoginScreen.png", byteArrayOf(1))
        File(root, "out").mkdirs()
        File(root, "out/LoginScreen_actual.png").writeBytes(byteArrayOf(5, 5))

        val items = scanner(
            head = mapOf(golden.normalize() to byteArrayOf(1)),
            config = GoldenDiffConfig(goldenPaths = listOf("goldens"), generatedPaths = listOf("out")),
        ).generatedChanges()

        // The golden on disk still equals HEAD; it is the generated output that differs.
        assertEquals(listOf("LoginScreen.png"), items.map { it.title })
        assertEquals(ExtraComparisonItemStatus.MODIFIED, items.single().status)
    }

    @Test
    fun `generated changes ignore goldens with no counterpart`() {
        golden("Lonely.png")
        File(root, "out").mkdirs()

        val items = scanner(
            config = GoldenDiffConfig(goldenPaths = listOf("goldens"), generatedPaths = listOf("out")),
        ).generatedChanges()

        assertEquals(emptyList<String>(), items.map { it.title })
    }

    @Test
    fun `relative paths are reported with forward slashes regardless of platform`() {
        val nested = golden("nested/deep/Screen.png")

        assertEquals(
            "nested/deep/Screen.png",
            ChangeScanner.invariantPathIn(nested, listOf(File(root, "goldens"))),
        )
    }
}
