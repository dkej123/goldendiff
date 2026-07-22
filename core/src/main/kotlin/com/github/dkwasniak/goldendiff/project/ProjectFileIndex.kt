package com.github.dkwasniak.goldendiff.project

import java.io.File
import java.nio.file.Files

/**
 * A flat, sorted snapshot of the source files in a project, for hosts that have to provide their own
 * navigation.
 *
 * The IDE plugin needs nothing like this — the IDE already has a project tree and Go to File. The
 * standalone app has neither, so it indexes once and answers from memory rather than walking the disk
 * on every keystroke.
 *
 * Build directories are skipped rather than filtered afterwards. On a real Android project `build/`
 * and `.gradle/` dwarf the source tree, and descending into them would dominate both the scan time
 * and the memory held by the result.
 */
class ProjectFileIndex private constructor(
    val root: File,
    /** Project-relative paths with `/` separators, sorted case-insensitively then by case. */
    val paths: List<String>,
) {

    fun file(path: String): File = File(root, path)

    companion object {

        /**
         * Directory names never worth indexing: build output, VCS internals, IDE metadata and
         * dependency caches. Matched by exact name at any depth.
         */
        val SKIPPED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".svn",
            ".hg",
            "build",
            "out",
            "target",
            "node_modules",
            "Pods",
            "DerivedData",
            ".venv",
            "__pycache__",
        )

        /**
         * Order used for indexed paths: case-insensitive, with the raw string as a tiebreaker.
         *
         * The tiebreaker is not cosmetic. Without it, paths differing only in case compare equal and
         * their order becomes whatever the filesystem happened to return — which differs between a
         * case-insensitive macOS volume and a case-sensitive Linux one, so the same project would
         * list differently on each.
         *
         * Exposed because it cannot be exercised through [scan] on macOS: creating `Alpha.kt` and
         * `alpha.kt` side by side is impossible there, so a filesystem-based test would silently
         * assert nothing.
         */
        val PATH_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenBy { it }

        fun scan(root: File, skipped: Set<String> = SKIPPED_DIRECTORIES): ProjectFileIndex {
            val paths = ArrayList<String>()
            collect(root, root, skipped, paths)
            paths.sortWith(PATH_ORDER)
            return ProjectFileIndex(root, paths)
        }

        private fun collect(root: File, dir: File, skipped: Set<String>, into: MutableList<String>) {
            // listFiles returns null on an unreadable directory; skip it rather than aborting the scan.
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in skipped) continue
                    // Symlinked directories are not descended into at all. Following them risks an
                    // infinite loop - a link to any ancestor is enough - and source trees rarely
                    // depend on them for reachability.
                    if (Files.isSymbolicLink(child.toPath())) continue
                    collect(root, child, skipped, into)
                } else {
                    into.add(child.relativeTo(root).path.replace(File.separatorChar, '/'))
                }
            }
        }
    }
}
