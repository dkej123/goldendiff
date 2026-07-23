package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun semVerOrdersReleaseAboveItsPrerelease() {
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.5.0-beta.4"))
        assertFalse(UpdateChecker.isNewer("1.5.0-beta.4", "1.5.0"))
    }

    @Test fun semVerComparesPrereleaseNumbersNumerically() {
        assertTrue(UpdateChecker.isNewer("1.5.0-beta.10", "1.5.0-beta.9"))
        assertFalse(UpdateChecker.isNewer("1.5.0-beta.9", "1.5.0-beta.10"))
    }

    @Test fun semVerComparesCoreNumerically() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.9.9", "1.10.0"))
        assertFalse(UpdateChecker.isNewer("1.5.0", "1.5.0"))
    }

    @Test fun betaChannelPicksHighestPrereleaseTag() {
        val releases = listOf(
            UpdateChecker.Release("app-beta-v1.5.0-beta.3", prerelease = true),
            UpdateChecker.Release("app-beta-v1.5.0-beta.5", prerelease = true),
            UpdateChecker.Release("app-v1.4.0", prerelease = false),
        )
        val newest = UpdateChecker.newestForChannel(ReleaseChannel.BETA, releases)
        assertEquals("1.5.0-beta.5", newest?.version)
        assertEquals("https://github.com/dkej123/goldendiff/releases/tag/app-beta-v1.5.0-beta.5", newest?.url)
    }

    @Test fun stableChannelIgnoresPrereleasesAndBetaTags() {
        val releases = listOf(
            UpdateChecker.Release("app-v1.4.0", prerelease = false),
            UpdateChecker.Release("app-v1.5.0", prerelease = false),
            UpdateChecker.Release("app-beta-v1.6.0-beta.1", prerelease = true),
            UpdateChecker.Release("app-v1.7.0-rc.1", prerelease = true),
        )
        val newest = UpdateChecker.newestForChannel(ReleaseChannel.STABLE, releases)
        assertEquals("1.5.0", newest?.version)
    }

    @Test fun devChannelNeverMatchesARelease() {
        val releases = listOf(UpdateChecker.Release("app-v9.9.9", prerelease = false))
        assertNull(UpdateChecker.newestForChannel(ReleaseChannel.DEV, releases))
    }

    @Test fun noReleasesForChannelYieldsNoUpdate() {
        val releases = listOf(UpdateChecker.Release("app-beta-v2.0.0-beta.1", prerelease = true))
        assertNull(UpdateChecker.newestForChannel(ReleaseChannel.STABLE, releases))
    }

    @Test fun devChannelChecksWithoutHittingTheNetwork() {
        var fetched = false
        val result = UpdateChecker.check(
            currentVersion = "1.5.0",
            channel = ReleaseChannel.DEV,
            fetch = { fetched = true; emptyList() },
        )
        assertNull(result)
        assertFalse("A developer build must return before any network access", fetched)
    }
}
