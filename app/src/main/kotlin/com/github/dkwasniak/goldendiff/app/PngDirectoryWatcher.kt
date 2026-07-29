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

/**
 * Recursive PNG-only watcher for the standalone host.
 *
 * Java's WatchService is not recursive, so every existing directory is registered and newly created
 * directories are added as they appear. The callback runs on the watcher thread; AppState batches and
 * moves it onto Compose's UI scope.
 */
internal class PngDirectoryWatcher(
    roots: Collection<File>,
    private val onEvents: (List<Event>, overflow: Boolean) -> Unit,
) : AutoCloseable {

    enum class Kind { CREATED, MODIFIED, DELETED }

    data class Event(val file: File, val kind: Kind)

    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val directories = ConcurrentHashMap<WatchKey, Path>()
    @Volatile private var closed = false
    private val worker: Thread

    init {
        roots.map { it.toPath().toAbsolutePath().normalize() }
            .distinct()
            .filter(Files::isDirectory)
            .forEach(::registerTree)
        worker = thread(name = "golden-diff-png-watcher", isDaemon = true, block = ::watch)
    }

    private fun registerTree(root: Path) {
        runCatching {
            root.toFile()
                .walkTopDown()
                .onEnter { directory -> directory.toPath() == root || directory.name !in EXCLUDED_DIRECTORIES }
                .filter(File::isDirectory)
                .forEach { registerDirectory(it.toPath()) }
        }
    }

    private fun registerDirectory(directory: Path) {
        if (closed) return
        runCatching {
            val key = directory.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            directories[key] = directory
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
            var overflowed = false
            val events = buildList {
                if (directory != null) {
                    key.pollEvents().forEach { raw ->
                        if (raw.kind() == OVERFLOW) {
                            overflowed = true
                            return@forEach
                        }
                        @Suppress("UNCHECKED_CAST")
                        val relative = (raw as WatchEvent<Path>).context()
                        val path = directory.resolve(relative).toAbsolutePath().normalize()
                        if (raw.kind() == ENTRY_CREATE && Files.isDirectory(path)) {
                            if (path.fileName?.toString() !in EXCLUDED_DIRECTORIES) {
                                registerTree(path)
                                // A tool may create a directory and its PNG before WatchService
                                // delivers the directory event. Pick up those already-written files.
                                path.toFile().walkTopDown()
                                    .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                                    .forEach { add(Event(it, Kind.CREATED)) }
                            }
                            return@forEach
                        }
                        if (!path.fileName.toString().endsWith(".png", ignoreCase = true)) return@forEach
                        val kind = when (raw.kind()) {
                            ENTRY_CREATE -> Kind.CREATED
                            ENTRY_DELETE -> Kind.DELETED
                            else -> Kind.MODIFIED
                        }
                        add(Event(path.toFile(), kind))
                    }
                }
            }
            if (events.isNotEmpty() || overflowed) onEvents(events, overflowed)
            if (!key.reset()) directories.remove(key)
        }
    }

    override fun close() {
        closed = true
        service.close()
        worker.interrupt()
        directories.clear()
    }

    private companion object {
        private val EXCLUDED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".build",
            ".swiftpm",
            "build",
            "dist",
            "node_modules",
            "out",
            "target",
            "DerivedData",
            "Pods",
            "Carthage",
        )
    }
}

internal enum class PngRefreshMode {
    NONE,
    IN_PLACE,
    RESCAN,
}

/** Exact file state produced by an app-owned write. Content is included so a fast Git undo with the
 * same size cannot be mistaken for the write we intended to ignore. */
internal data class PngFileState(
    val exists: Boolean,
    val size: Long,
    val contentHash: Int,
)

internal fun pngFileState(file: File): PngFileState =
    if (!file.isFile) {
        PngFileState(exists = false, size = 0L, contentHash = 0)
    } else {
        val bytes = runCatching { file.readBytes() }.getOrDefault(byteArrayOf())
        PngFileState(exists = true, size = bytes.size.toLong(), contentHash = bytes.contentHashCode())
    }

/**
 * Chooses the state transition for a debounced, already-filtered watcher batch.
 *
 * Structural changes can alter list membership and therefore require an atomic soft rescan.
 * Modifying a PNG that is already on screen can update its merged item in place.
 */
internal fun pngRefreshMode(
    events: List<PngDirectoryWatcher.Event>,
    overflow: Boolean,
): PngRefreshMode = when {
    !overflow && events.isEmpty() -> PngRefreshMode.NONE
    overflow || events.any { it.kind != PngDirectoryWatcher.Kind.MODIFIED } ->
        PngRefreshMode.RESCAN
    else -> PngRefreshMode.IN_PLACE
}
