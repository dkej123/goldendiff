package com.github.dkwasniak.goldendiff.compare

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Loads the raw bytes of the two sides of the comparison:
 *  - "old" = the committed version of the golden (git HEAD), via the VCS DiffProvider,
 *  - "new" = the working-copy file on disk.
 *
 * Bytes (not decoded images) are exposed so the caller can cheaply detect "no change vs HEAD" by
 * comparing them directly. All methods do I/O / may run a git command, so call them off the EDT.
 */
object GitImageSource {

    fun workingBytes(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    fun decode(bytes: ByteArray?): BufferedImage? =
        bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }

    fun headBytes(project: Project, file: File): ByteArray? {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null
        return try {
            val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(vFile) ?: return null
            val diffProvider = vcs.diffProvider ?: return null
            val revision = diffProvider.getCurrentRevision(vFile) ?: return null
            val content = diffProvider.createFileContent(revision, vFile) ?: return null
            when (content) {
                is ByteBackedContentRevision -> content.contentAsBytes
                else -> content.content?.toByteArray(Charsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to load HEAD content for ${file.path}", e)
            null
        }
    }
}
