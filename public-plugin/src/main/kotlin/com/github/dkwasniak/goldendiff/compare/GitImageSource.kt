package com.github.dkwasniak.goldendiff.compare

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * [HeadBytesSource] backed by the IDE's own VCS integration.
 *
 * Preferred over the git CLI while running inside the IDE: the platform already knows which VCS root
 * a file belongs to, honours the user's configured git executable and credentials, and caches
 * revision content. The CLI implementation in core exists for hosts that have none of that.
 *
 * Reading the working copy and decoding images is host-independent and lives in
 * [com.github.dkwasniak.goldendiff.compare.ImageBytes].
 *
 * Runs a VCS operation — call off the EDT.
 */
class GitImageSource(private val project: Project) : HeadBytesSource {

    override fun headBytes(file: File): ByteArray? {
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
