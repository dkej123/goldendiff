package com.github.dkwasniak.goldendiff.git

import com.github.dkwasniak.goldendiff.compare.HeadBytesSource
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.File

/**
 * Git access via the `git` command-line tool, for hosts with no VCS integration of their own.
 *
 * Requires `git` on the PATH. That is a safe assumption inside a developer's IDE but not for a
 * standalone app — macOS ships it with the Xcode command line tools and most Linux installs have it,
 * while Windows does not unless Git for Windows was installed. Call [isAvailable] once at startup and
 * tell the user plainly, rather than showing an empty list that looks like "no changes".
 */
class GitCli(private val projectRoot: File) : HeadBytesSource {

    fun isAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)

    fun isInsideWorkTree(): Boolean =
        run("rev-parse", "--is-inside-work-tree", cwd = projectRoot)
            ?.toString(Charsets.UTF_8)?.trim() == "true"

    /**
     * Files changed in the working copy, with the status derived straight from the porcelain code.
     *
     * Reading NEW/MODIFIED from `git status` avoids a per-file HEAD revision fetch, which is what
     * makes the project-wide view usable on a large change set.
     *
     * Deletions are skipped: a golden that no longer exists has nothing to display.
     */
    fun changedFiles(): List<GitChange> {
        // -z: records are NUL-separated and paths are never quoted or escaped, so filenames with
        // spaces, quotes or non-ASCII characters survive intact. The default output would mangle them.
        //
        // --untracked-files=all: without it git collapses an untracked directory into a single entry
        // for the directory itself. Adding a new screen typically creates a whole new golden folder,
        // and the caller filters on a `.png` extension — so the collapsed form silently yields no new
        // goldens at all, which reads as "nothing changed".
        val output = run("status", "--porcelain=v1", "-z", "--untracked-files=all", cwd = projectRoot)
            ?: return emptyList()
        if (output.isEmpty()) return emptyList()

        val records = output.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        val entries = ArrayList<GitChange>()
        var index = 0
        while (index < records.size) {
            val record = records[index]
            if (record.length >= 4) {
                val status = record.substring(0, 2)
                val path = record.substring(3)
                if (status.any { it != ' ' && it != 'D' }) {
                    entries.add(GitChange(File(projectRoot, path), status.toChangeStatus()))
                }
                // Rename/copy records carry a second (origin) path token that must be skipped.
                if (status.any { it == 'R' || it == 'C' }) {
                    index++
                }
            }
            index++
        }
        return entries
    }

    // Untracked/added/renamed/copied paths do not exist in HEAD → NEW; anything else that changed
    // exists in HEAD → MODIFIED.
    private fun String.toChangeStatus(): ExtraComparisonItemStatus =
        if (any { it == '?' || it == 'A' || it == 'R' || it == 'C' }) {
            ExtraComparisonItemStatus.NEW
        } else {
            ExtraComparisonItemStatus.MODIFIED
        }

    override fun headBytes(file: File): ByteArray? {
        val parent = file.parentFile ?: return null
        // `HEAD:./name` resolves relative to the working directory, so this works without first
        // locating the repository root — which need not be the project root (submodules, monorepos,
        // a project opened on a subdirectory).
        return run("show", "HEAD:./${file.name}", cwd = parent)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Runs git and returns stdout, or null on a non-zero exit.
     *
     * stderr is discarded by the OS rather than merged into stdout. Merging would corrupt binary file
     * content — a warning line folded into a PNG stream only surfaces later as an image that silently
     * fails to decode. Discarding also avoids having to drain a second pipe: leaving stderr unread
     * risks filling its buffer and deadlocking git midway through a large blob.
     */
    private fun run(vararg args: String, cwd: File): ByteArray? =
        runCatching {
            val process = ProcessBuilder(listOf("git", *args))
                .directory(cwd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val out = process.inputStream.readBytes()
            if (process.waitFor() == 0) out else null
        }.getOrNull()
}
