package com.github.dkwasniak.goldendiff.git

import com.github.dkwasniak.goldendiff.compare.HeadBytesSource
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Git access via the `git` command-line tool, for hosts with no VCS integration of their own.
 *
 * Requires `git` on the PATH. That is a safe assumption inside a developer's IDE but not for a
 * standalone app — macOS ships it with the Xcode command line tools and most Linux installs have it,
 * while Windows does not unless Git for Windows was installed. Call [isAvailable] once at startup and
 * tell the user plainly, rather than showing an empty list that looks like "no changes".
 */
class GitCli(private val projectRoot: File) : HeadBytesSource, WorkingCopyStatus {

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

    /** Current commit identity, or null when HEAD cannot be resolved (for example in an empty repo). */
    fun headRevision(): String? =
        run("rev-parse", "--verify", "HEAD", cwd = projectRoot)
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Metadata directories whose HEAD/refs changes can move [headRevision]. Both paths are needed
     * for linked worktrees: their HEAD lives in a worktree-specific git dir while branch refs live
     * in the repository's common dir.
     */
    fun gitMetadataDirectories(): List<File> {
        val gitDirectory = run("rev-parse", "--absolute-git-dir", cwd = projectRoot)
            ?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
        val commonValue = run("rev-parse", "--git-common-dir", cwd = projectRoot)
            ?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)
        val commonDirectory = commonValue?.let { path ->
            File(path).takeIf(File::isAbsolute) ?: File(projectRoot, path)
        }
        return listOfNotNull(gitDirectory, commonDirectory)
            .map { directory ->
                runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile.normalize() }
            }
            .filter(File::isDirectory)
            .distinctBy(File::getPath)
    }

    /**
     * Files changed in the working copy, with the status derived straight from the porcelain code.
     *
     * Reading NEW/MODIFIED from `git status` avoids a per-file HEAD revision fetch, which is what
     * makes the project-wide view usable on a large change set.
     *
     * Deletions are skipped: a golden that no longer exists has nothing to display.
     */
    override fun changedFiles(): List<GitChange> {
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
     * One `git cat-file --batch` process for every file instead of one `git show` per file.
     *
     * A project-wide status refresh calls [headBytes] once per golden; each call used to spawn its own
     * process, and process spawn overhead (tens of milliseconds on macOS) dominates once a project has
     * more than a handful of goldens. This still degrades to the one-shot form — per unresolved path,
     * or for the whole batch if the process itself fails to start or read — so a bad environment loses
     * speed, not correctness.
     */
    override fun headBytes(files: Collection<File>): Map<File, ByteArray?> {
        if (files.isEmpty()) return emptyMap()
        val distinct = files.distinct()
        val (batchable, unbatchable) = distinct.partition { it.batchSpec() != null }

        val batchedContent = batchable.takeIf { it.isNotEmpty() }
            ?.let { targets -> runCatching { readBatch(targets.map { file -> file.batchSpec()!! }) }.getOrNull() }

        val result = LinkedHashMap<File, ByteArray?>(distinct.size)
        if (batchedContent != null) {
            batchable.forEachIndexed { index, file -> result[file] = batchedContent.getOrNull(index) }
        } else {
            // The batch process failed outright (git missing mid-scan, permissions, ...) — degrade to
            // the one-shot form per file rather than losing every status in the scan.
            batchable.forEach { file -> result[file] = headBytes(file) }
        }
        unbatchable.forEach { file -> result[file] = headBytes(file) }
        return result
    }

    /** A `HEAD:./relative/path` object spec resolved against [projectRoot], or null if [this] is not
     * inside it (the one-shot [headBytes] path handles those instead). */
    private fun File.batchSpec(): String? {
        val relative = runCatching { normalize().relativeTo(projectRoot.normalize()) }.getOrNull() ?: return null
        val path = relative.path.replace(File.separatorChar, '/')
        if (relative.isAbsolute || path == ".." || path.startsWith("../")) return null
        return "HEAD:./$path"
    }

    /**
     * Runs one `git cat-file --batch` process for [specs], returning content in the same order — a
     * null entry marks an object that is missing (untracked, deleted in HEAD, or unreadable).
     *
     * `git cat-file --batch` reads one query line, writes its full payload, then reads the next query —
     * so writing every query upfront and only then reading stdout can deadlock: once a payload exceeds
     * the OS pipe buffer, git blocks writing stdout while this process is still blocked writing stdin.
     * The write happens on a separate thread so stdout is drained concurrently, the same way the query
     * lines are produced.
     */
    private fun readBatch(specs: List<String>): List<ByteArray?> {
        val process = ProcessBuilder("git", "cat-file", "--batch")
            .directory(projectRoot)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            val stdin = process.outputStream
            val stdout = process.inputStream
            val writer = Thread({
                runCatching {
                    specs.forEach { stdin.write(("$it\n").toByteArray(Charsets.UTF_8)) }
                    stdin.flush()
                }
                stdin.close()
            }, "git-cat-file-batch-writer").apply { isDaemon = true; start() }
            val results = specs.map { readBatchEntry(stdout) }
            writer.join()
            stdout.close()
            process.waitFor()
            return results
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Reads one `<sha1> <type> <size>\n<content><\n>` (or `<object> missing\n`) record. */
    private fun readBatchEntry(stdout: InputStream): ByteArray? {
        val header = readLine(stdout) ?: return null
        if (header.endsWith(" missing")) return null
        val size = header.substringAfterLast(' ').toIntOrNull() ?: return null
        val content = stdout.readNBytes(size)
        stdout.read() // the newline that follows the object payload
        return content.takeIf { it.size == size }
    }

    /** A hand-rolled line reader: the header line is text, but what follows it is a binary blob, so a
     * buffering [java.io.BufferedReader] could read past the header and swallow part of that blob. */
    private fun readLine(stream: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val next = stream.read()
            if (next == -1) return null
            if (next == '\n'.code) return line.toString(Charsets.UTF_8.name())
            line.write(next)
        }
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
