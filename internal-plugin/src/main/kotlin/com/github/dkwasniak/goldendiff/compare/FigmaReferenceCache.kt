package com.github.dkwasniak.goldendiff.compare

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.io.File
import java.security.MessageDigest

object FigmaReferenceCache {
    const val MAX_CACHE_BYTES: Long = 20L * 1024L * 1024L

    fun findOrDownload(project: Project, preview: FigmaPreview): LookupResult {
        val target = FigmaTarget.parse(preview.sourceUrl)
            ?: return LookupResult(null, "Invalid Figma URL for ${preview.functionName}.")
        val file = fileFor(project, preview, target)
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
            return LookupResult(file, null)
        }

        file.parentFile.mkdirs()
        val downloaded = FigmaReferenceDownloader(project).download(preview, target, file)
        if (downloaded is FigmaDownloadResult.Failure) {
            file.delete()
            return LookupResult(null, downloaded.message)
        }
        prune(cacheDir(project), MAX_CACHE_BYTES)
        return LookupResult(file.takeIf { it.isFile }, null)
    }

    fun expectedFile(project: Project, preview: FigmaPreview): File? {
        val target = FigmaTarget.parse(preview.sourceUrl) ?: return null
        return fileFor(project, preview, target)
    }

    fun prune(dir: File, maxBytes: Long = MAX_CACHE_BYTES) {
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .toList()

        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > maxBytes) {
                file.delete()
            }
        }
    }

    private fun fileFor(project: Project, preview: FigmaPreview, target: FigmaTarget): File {
        val key = sha256("${target.fileKey}|${target.nodeId}|${preview.functionName}|crop-v1")
        return File(cacheDir(project), "$key.png")
    }

    private fun cacheDir(project: Project): File {
        val base = project.basePath ?: project.name
        return File(PathManager.getSystemPath(), "golden-diff/figma/${sha256(base).take(16)}")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class LookupResult(val file: File?, val message: String?)
}
