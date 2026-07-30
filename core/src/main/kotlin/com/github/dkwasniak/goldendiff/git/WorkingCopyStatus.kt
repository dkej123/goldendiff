package com.github.dkwasniak.goldendiff.git

/**
 * Reports which files differ from HEAD in the working copy.
 *
 * Separate from [com.github.dkwasniak.goldendiff.compare.HeadBytesSource] because project-wide
 * changes and individual committed bytes remain distinct operations. Git-backed hosts normally use
 * one [GitCli] instance for both through
 * [com.github.dkwasniak.goldendiff.scan.ChangeScanner.forGitProject], so status labels cannot depend
 * on host-specific VCS mappings.
 */
fun interface WorkingCopyStatus {

    fun changedFiles(): List<GitChange>
}
