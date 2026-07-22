package com.github.dkwasniak.goldendiff.git

import com.github.dkwasniak.goldendiff.compare.HeadBytesSource
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
