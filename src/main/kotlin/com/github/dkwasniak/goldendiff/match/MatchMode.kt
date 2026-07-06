package com.github.dkwasniak.goldendiff.match

/**
 * How the golden list is matched against the file open in the editor. The two modes are mutually
 * exclusive and picked explicitly in Settings.
 */
enum class MatchMode {
    /** Match goldens whose relative path contains the name of an annotated screenshot function. */
    ANNOTATED_METHOD,

    /** Match goldens with a user-supplied regex that may use {file_name} and {class_name}. */
    FILE_CLASS_REGEX;

    companion object {
        /** Parses a persisted mode name, falling back to [MatchingDefaults.DEFAULT_MATCH_MODE]. */
        fun fromName(name: String?): MatchMode =
            entries.firstOrNull { it.name == name } ?: MatchingDefaults.DEFAULT_MATCH_MODE
    }
}
