package com.github.dkwasniak.goldendiff.match

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationNameMatcherTest {

    @Test
    fun `default regex matches compose preview annotations`() {
        assertTrue(AnnotationNameMatcher.matches("Preview", MatchingDefaults.ANNOTATION_NAME_REGEX))
        assertTrue(AnnotationNameMatcher.matches("MultiPreviews", MatchingDefaults.ANNOTATION_NAME_REGEX))
    }

    @Test
    fun `default regex matches test annotation`() {
        assertTrue(AnnotationNameMatcher.matches("Test", MatchingDefaults.ANNOTATION_NAME_REGEX))
    }

    @Test
    fun `default regex does not match unrelated annotations`() {
        assertFalse(AnnotationNameMatcher.matches("Composable", MatchingDefaults.ANNOTATION_NAME_REGEX))
        assertFalse(AnnotationNameMatcher.matches("Before", MatchingDefaults.ANNOTATION_NAME_REGEX))
    }

    @Test
    fun `preview parameter is always ignored`() {
        assertFalse(AnnotationNameMatcher.matches("PreviewParameter", ".*Preview.*"))
        assertFalse(AnnotationNameMatcher.matches("PreviewParameter", "PreviewParameter"))
    }

    @Test
    fun `custom regex matches custom screenshot annotations`() {
        val regex = ".*Preview.*|Test|ScreenshotTest|Paparazzi"

        assertTrue(AnnotationNameMatcher.matches("ScreenshotTest", regex))
        assertTrue(AnnotationNameMatcher.matches("Paparazzi", regex))
        assertFalse(AnnotationNameMatcher.matches("Composable", regex))
    }

    @Test
    fun `invalid custom regex falls back to defaults`() {
        assertTrue(AnnotationNameMatcher.matches("Preview", "["))
        assertFalse(AnnotationNameMatcher.matches("ScreenshotTest", "["))
    }
}
