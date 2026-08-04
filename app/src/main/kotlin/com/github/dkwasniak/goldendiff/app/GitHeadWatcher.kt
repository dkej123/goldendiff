package com.github.dkwasniak.goldendiff.app

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Pure revision transition detector; unavailable reads retain the last known good revision. */
internal class GitHeadChangeDetector(
    private val readRevision: () -> String?,
) {
    private var previous: String? = null

    @Synchronized
    fun poll(): Boolean {
        val current = readRevision()?.takeIf(String::isNotBlank) ?: return false
        val changed = previous != null && previous != current
        previous = current
        return changed
    }
}

/**
 * Event-driven watcher for metadata capable of moving Git HEAD. It consumes no CPU and spawns no Git
 * process while idle; the callback merely asks the app to debounce and verify the revision once.
 */
internal class GitHeadWatcher(
    metadataDirectories: Collection<File>,
    private val onPotentialChange: () -> Unit,
) : AutoCloseable {
    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val directories = ConcurrentHashMap<WatchKey, Path>()
    private val roots = metadataDirectories.map { it.toPath().toAbsolutePath().normalize() }.distinct()
    private val gitDirectory = roots.firstOrNull()
    private val commonDirectory = roots.lastOrNull()
    @Volatile private var closed = false
    private val worker: Thread

    init {
        roots.forEach(::registerDirectory)
        commonDirectory?.resolve("refs")?.takeIf(Files::isDirectory)?.let(::registerTree)
        worker = thread(name = "golden-diff-git-head-watcher", isDaemon = true, block = ::watch)
    }

    private fun registerTree(root: Path) {
        runCatching {
            root.toFile().walkTopDown().filter(File::isDirectory).forEach { registerDirectory(it.toPath()) }
        }
    }

    private fun registerDirectory(directory: Path) {
        if (closed || !Files.isDirectory(directory)) return
        runCatching {
            val normalized = directory.toAbsolutePath().normalize()
            if (normalized !in directories.values) {
                directories[normalized.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = normalized
            }
        }
    }

    private fun watch() {
        while (!closed) {
            val key = try {
                service.take()
            } catch (_: InterruptedException) {
                return
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                return
            }
            val directory = directories[key]
            var relevant = false
            if (directory != null) {
                key.pollEvents().forEach { raw ->
                    if (raw.kind() == OVERFLOW) {
                        relevant = true
                        return@forEach
                    }
                    @Suppress("UNCHECKED_CAST")
                    val relative = (raw as WatchEvent<Path>).context()
                    val path = directory.resolve(relative).toAbsolutePath().normalize()
                    if (raw.kind() == ENTRY_CREATE && Files.isDirectory(path)) {
                        val refs = commonDirectory?.resolve("refs")
                        if (refs != null && path.startsWith(refs)) registerTree(path)
                    }
                    if (isGitHeadMetadataPath(path, gitDirectory, commonDirectory)) relevant = true
                }
            }
            if (relevant) onPotentialChange()
            if (!key.reset()) directories.remove(key)
        }
    }

    override fun close() {
        closed = true
        service.close()
        worker.interrupt()
        directories.clear()
    }
}

internal fun isGitHeadMetadataPath(path: Path, gitDirectory: Path?, commonDirectory: Path?): Boolean {
    val normalized = path.toAbsolutePath().normalize()
    val git = gitDirectory?.toAbsolutePath()?.normalize()
    val common = commonDirectory?.toAbsolutePath()?.normalize()
    return git?.resolve("HEAD") == normalized ||
        common?.resolve("HEAD") == normalized ||
        common?.resolve("packed-refs") == normalized ||
        common?.resolve("refs")?.let(normalized::startsWith) == true
}
