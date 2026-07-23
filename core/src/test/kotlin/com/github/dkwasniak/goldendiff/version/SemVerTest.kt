package com.github.dkwasniak.goldendiff.version

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test fun releaseOutranksItsPrerelease() {
        assertTrue(SemVer.isNewer("1.5.0", "1.5.0-beta.4"))
        assertFalse(SemVer.isNewer("1.5.0-beta.4", "1.5.0"))
    }

    @Test fun prereleaseNumbersCompareNumerically() {
        assertTrue(SemVer.isNewer("1.5.0-beta.10", "1.5.0-beta.9"))
        assertFalse(SemVer.isNewer("1.5.0-beta.9", "1.5.0-beta.10"))
    }

    @Test fun coreComparesNumericallyNotLexically() {
        assertTrue(SemVer.isNewer("1.10.0", "1.9.9"))
        assertFalse(SemVer.isNewer("1.9.9", "1.10.0"))
    }

    @Test fun equalVersionsAreNotNewer() {
        assertFalse(SemVer.isNewer("1.5.0", "1.5.0"))
        assertFalse(SemVer.isNewer("1.5.0-beta.2", "1.5.0-beta.2"))
    }

    @Test fun numericPrereleaseRanksBelowAlphanumeric() {
        assertTrue(SemVer.isNewer("1.5.0-rc", "1.5.0-1"))
    }

    @Test fun buildMetadataAndLeadingVAreIgnored() {
        assertFalse(SemVer.isNewer("v1.5.0+build.7", "1.5.0"))
        assertTrue(SemVer.isNewer("v1.5.1", "1.5.0"))
    }

    @Test fun garbageDoesNotParse() {
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse(""))
    }
}
