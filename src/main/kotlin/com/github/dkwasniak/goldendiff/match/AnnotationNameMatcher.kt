package com.github.dkwasniak.goldendiff.match

object AnnotationNameMatcher {
    fun matches(annotationName: String, annotationNameRegex: String): Boolean {
        if (annotationName == "PreviewParameter") return false
        val pattern = runCatching { Regex(annotationNameRegex) }
            .getOrElse { Regex(MatchingDefaults.ANNOTATION_NAME_REGEX) }
        return pattern.matches(annotationName)
    }
}
