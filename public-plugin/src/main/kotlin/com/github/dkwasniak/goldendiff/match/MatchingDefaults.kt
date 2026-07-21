package com.github.dkwasniak.goldendiff.match

object MatchingDefaults {
    const val ANNOTATION_NAME_REGEX = ".*Preview.*|Test"
    val DEFAULT_MATCH_MODE = MatchMode.ANNOTATED_METHOD
    /** Default general patterns for [MatchMode.FILE_CLASS_REGEX]. */
    val DEFAULT_FILE_CLASS_PATTERNS = listOf("{file_name}", "{class_name}")
}
