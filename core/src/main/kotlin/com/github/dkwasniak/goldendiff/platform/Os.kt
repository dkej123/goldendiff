package com.github.dkwasniak.goldendiff.platform

import java.io.File

/**
 * The host operating system, resolved once.
 *
 * A single place on purpose. Scattering `os.name.contains("mac")` around the UI is what turns "add
 * Windows support" into a hunt through every file instead of one change here.
 *
 * Only macOS is built and verified today, but every branch is written now — the cost is a `when`, and
 * the alternative is discovering the assumptions much later.
 */
enum class Os {
    MAC,
    WINDOWS,
    LINUX;

    companion object {
        val current: Os = System.getProperty("os.name").orEmpty().lowercase().let {
            when {
                it.contains("mac") || it.contains("darwin") -> MAC
                it.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * Reveals [file] in the platform's file manager.
 *
 * Not `java.awt.Desktop.browseFileDirectory`, which is unsupported on Windows and most Linux desktops
 * and throws rather than degrading.
 */
object RevealInFileManager {

    fun reveal(file: File): Boolean {
        val command = when (Os.current) {
            Os.MAC -> listOf("open", "-R", file.absolutePath)
            Os.WINDOWS -> listOf("explorer", "/select,${file.absolutePath}")
            // No cross-desktop way to select a file on Linux, so open the containing directory.
            Os.LINUX -> listOf("xdg-open", (file.parentFile ?: file).absolutePath)
        }
        return runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }
}
