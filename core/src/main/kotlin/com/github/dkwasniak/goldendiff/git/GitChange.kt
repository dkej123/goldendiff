package com.github.dkwasniak.goldendiff.git

import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.File

/** One changed file reported by `git status`, with its status already mapped to the UI's vocabulary. */
data class GitChange(
    val file: File,
    val status: ExtraComparisonItemStatus,
)
