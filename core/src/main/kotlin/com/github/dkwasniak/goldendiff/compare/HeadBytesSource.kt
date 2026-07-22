package com.github.dkwasniak.goldendiff.compare

import java.io.File

/**
 * Reads the committed (git HEAD) version of a file — the "before" side of every comparison.
 *
 * An interface because the two hosts have genuinely different best answers. Inside the IDE the VCS
 * integration already knows the repository, honours the user's git configuration and caches
 * revisions, so shelling out there would be both slower and less correct. Outside the IDE none of
 * that exists and the git CLI is the only option.
 *
 * It is also the seam for dropping the external `git` requirement altogether: a JGit-backed
 * implementation would slot in here without touching any caller.
 *
 * Implementations do I/O and may spawn a process — never call them on a UI thread.
 */
interface HeadBytesSource {

    /** Committed content of [file], or null if it is untracked, deleted in HEAD, or unreadable. */
    fun headBytes(file: File): ByteArray?
}
