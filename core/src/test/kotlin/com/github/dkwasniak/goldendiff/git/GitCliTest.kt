package com.github.dkwasniak.goldendiff.git

import org.junit.After
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitCliTest {

    private lateinit var repo: File
    private lateinit var git: GitCli

    @Before
    fun setUp() {
        repo = Files.createTempDirectory("golden-diff-git").toFile()
        git = GitCli(repo)
        assumeTrue("git is not on PATH", git.isAvailable())
        // -c rather than `git config` so the test never depends on, or writes to, the machine's
        // global git identity.
        run("init", "--quiet")
        run("-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "--allow-empty", "-qm", "init")
    }

    @After
    fun tearDown() {
        repo.deleteRecursively()
    }

    private fun run(vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed" }
    }

    private fun commit(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        run("add", "--all")
        run("-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "-qm", "add")
    }

    @Test
    fun `headBytes returns the committed content, not the working copy`() {
        val golden = File(repo, "goldens/LoginScreen.png")
        val committed = byteArrayOf(1, 2, 3, 4)
        commit(golden, committed)
        golden.writeBytes(byteArrayOf(9, 9, 9)) // dirty the working copy

        assertArrayEquals(committed, git.headBytes(golden))
    }

    @Test
    fun `headBytes survives bytes that are not valid text`() {
        // The whole point of this class is reading PNGs. Anything that decodes stdout as a string, or
        // folds stderr into it, corrupts binary content in ways that only surface much later.
        val golden = File(repo, "raw.png")
        val committed = ByteArray(256) { it.toByte() }
        commit(golden, committed)

        assertArrayEquals(committed, git.headBytes(golden))
    }

    @Test
    fun `headBytes is null for an untracked file`() {
        val untracked = File(repo, "goldens/New.png")
        untracked.parentFile.mkdirs()
        untracked.writeBytes(byteArrayOf(1))

        assertNull(git.headBytes(untracked))
    }

    @Test
    fun `headBytes resolves files in nested directories without knowing the repo root`() {
        val nested = File(repo, "a/b/c/Deep.png")
        val committed = byteArrayOf(7, 7)
        commit(nested, committed)

        // GitCli is rooted at the repo, but resolves via the file's own parent directory, so depth
        // and the location of the repository root are irrelevant.
        assertArrayEquals(committed, GitCli(repo).headBytes(nested))
    }

    @Test
    fun `changedFiles reports modified as MODIFIED and untracked as NEW`() {
        val tracked = File(repo, "goldens/Tracked.png")
        commit(tracked, byteArrayOf(1))
        tracked.writeBytes(byteArrayOf(2))
        val untracked = File(repo, "goldens/Untracked.png")
        untracked.writeBytes(byteArrayOf(3))

        val byName = git.changedFiles().associateBy { it.file.name }

        assertEquals(ExtraComparisonItemStatus.MODIFIED, byName.getValue("Tracked.png").status)
        assertEquals(ExtraComparisonItemStatus.NEW, byName.getValue("Untracked.png").status)
    }

    @Test
    fun `changedFiles keeps paths with spaces and non-ASCII characters intact`() {
        // This is what --porcelain -z buys: the default output would quote and escape this name, and
        // the file would then be looked up under a path that does not exist.
        val awkward = File(repo, "goldens/Zażółć gęślą jaźń.png")
        awkward.parentFile.mkdirs()
        awkward.writeBytes(byteArrayOf(1))

        val changed = git.changedFiles()

        assertTrue(
            "expected the awkward path, got ${changed.map { it.file.path }}",
            changed.any { it.file.name == "Zażółć gęślą jaźń.png" && it.file.isFile },
        )
    }

    @Test
    fun `changedFiles lists files inside a brand-new untracked directory`() {
        // git collapses an untracked directory into one entry for the directory unless asked not to.
        // Adding a screen usually creates a whole new golden folder, and callers filter on a `.png`
        // extension — so the collapsed form would show no new goldens and read as "nothing changed".
        val fresh = File(repo, "brand/new/dir/Fresh.png")
        fresh.parentFile.mkdirs()
        fresh.writeBytes(byteArrayOf(1))

        val changed = git.changedFiles()

        assertTrue(
            "expected the file, not its directory; got ${changed.map { it.file.path }}",
            changed.any { it.file.name == "Fresh.png" },
        )
        assertEquals(ExtraComparisonItemStatus.NEW, changed.first { it.file.name == "Fresh.png" }.status)
    }

    @Test
    fun `changedFiles skips deletions`() {
        val doomed = File(repo, "goldens/Gone.png")
        commit(doomed, byteArrayOf(1))
        doomed.delete()

        // A golden that no longer exists has nothing to render, so it must not reach the list.
        assertTrue(git.changedFiles().none { it.file.name == "Gone.png" })
    }

    @Test
    fun `changedFiles handles renames, whose records carry a second path token`() {
        val original = File(repo, "goldens/Before.png")
        commit(original, byteArrayOf(1, 2, 3))
        run("mv", "goldens/Before.png", "goldens/After.png")

        val changed = git.changedFiles()

        // The origin path token must be consumed, not parsed as another record.
        assertTrue(changed.any { it.file.name == "After.png" })
        assertTrue(changed.none { it.file.name == "Before.png" })
    }

    @Test
    fun `work-tree detection distinguishes a repo from a plain directory`() {
        assertTrue(git.isInsideWorkTree())

        val plain = Files.createTempDirectory("golden-diff-plain").toFile()
        try {
            assertFalse(GitCli(plain).isInsideWorkTree())
        } finally {
            plain.deleteRecursively()
        }
    }
}
