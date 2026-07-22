package com.github.dkwasniak.goldendiff.git

/**
 * Reports which files differ from HEAD in the working copy.
 *
 * Separate from [com.github.dkwasniak.goldendiff.compare.HeadBytesSource] because the two questions
 * have different best answers per host: "what changed across the project" is one cheap git call, while
 * "the committed bytes of this one file" is better served by the IDE's VCS layer. Splitting them lets
 * a host mix implementations, which the plugin does.
 */
fun interface WorkingCopyStatus {

    fun changedFiles(): List<GitChange>
}
