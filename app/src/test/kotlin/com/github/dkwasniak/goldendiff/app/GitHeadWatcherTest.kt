package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.git.GitCli
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitHeadWatcherTest {
    private val git = Path.of("/repo/.git")

    @Test
    fun `recognizes only metadata that can change the resolved HEAD`() {
        assertTrue(isGitHeadMetadataPath(git.resolve("HEAD"), git, git))
        assertTrue(isGitHeadMetadataPath(git.resolve("packed-refs"), git, git))
        assertTrue(isGitHeadMetadataPath(git.resolve("refs/heads/main"), git, git))
        assertTrue(isGitHeadMetadataPath(git.resolve("refs/tags/v1"), git, git))

        assertFalse(isGitHeadMetadataPath(git.resolve("index"), git, git))
        assertFalse(isGitHeadMetadataPath(git.resolve("objects/ab/cdef"), git, git))
    }

    @Test
    fun `supports a worktree git directory with refs in the common directory`() {
        val worktreeGit = Path.of("/repo/.git/worktrees/feature")

        assertTrue(isGitHeadMetadataPath(worktreeGit.resolve("HEAD"), worktreeGit, git))
        assertTrue(isGitHeadMetadataPath(git.resolve("refs/heads/feature"), worktreeGit, git))
        assertFalse(isGitHeadMetadataPath(worktreeGit.resolve("index"), worktreeGit, git))
    }

    @Test
    fun `real commit emits metadata event and changes detected revision`() {
        val repo = Files.createTempDirectory("golden-diff-head-watcher").toFile()
        try {
            fun runGit(vararg arguments: String) {
                val process = ProcessBuilder(listOf("git", *arguments)).directory(repo).start()
                process.inputStream.readBytes()
                process.errorStream.readBytes()
                check(process.waitFor() == 0)
            }
            runGit("init", "--quiet")
            runGit(
                "-c", "user.email=t@example.com", "-c", "user.name=T",
                "commit", "--allow-empty", "-qm", "initial",
            )
            val cli = GitCli(repo)
            val detector = GitHeadChangeDetector(cli::headRevision)
            assertFalse(detector.poll())
            val event = CountDownLatch(1)

            GitHeadWatcher(cli.gitMetadataDirectories(), event::countDown).use {
                File(repo, "change.txt").writeText("changed")
                runGit("add", "change.txt")
                runGit(
                    "-c", "user.email=t@example.com", "-c", "user.name=T",
                    "commit", "-qm", "change",
                )

                assertTrue("expected a refs metadata event", event.await(5, TimeUnit.SECONDS))
                assertTrue(detector.poll())
            }
        } finally {
            repo.deleteRecursively()
        }
    }
}
