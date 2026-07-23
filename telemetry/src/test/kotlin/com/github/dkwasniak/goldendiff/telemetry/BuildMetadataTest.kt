package com.github.dkwasniak.goldendiff.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class BuildMetadataTest {

    @Test fun `stable release has no badges and the stable channel`() {
        val metadata = BuildMetadata(version = "1.5.0", isDeveloperBuild = false)
        assertFalse(metadata.isBeta)
        assertEquals(ReleaseChannel.STABLE, metadata.releaseChannel)
        assertEquals(emptyList<BuildBadge>(), metadata.badges)
    }

    @Test fun `beta release shows the beta badge and the beta channel`() {
        val metadata = BuildMetadata(version = "1.5.0-beta.4", isDeveloperBuild = false)
        assertTrue(metadata.isBeta)
        assertEquals(ReleaseChannel.BETA, metadata.releaseChannel)
        assertEquals(listOf(BuildBadge.BETA), metadata.badges)
    }

    @Test fun `developer build of a stable version shows only the dev badge on the dev channel`() {
        val metadata = BuildMetadata(version = "1.5.0", isDeveloperBuild = true)
        assertFalse(metadata.isBeta)
        assertEquals(ReleaseChannel.DEV, metadata.releaseChannel)
        assertEquals(listOf(BuildBadge.DEV), metadata.badges)
    }

    @Test fun `developer build of a beta version shows dev then beta on the dev channel`() {
        val metadata = BuildMetadata(version = "1.5.0-beta.4", isDeveloperBuild = true)
        assertTrue(metadata.isBeta)
        // The effective channel collapses to DEV even though the version is a beta.
        assertEquals(ReleaseChannel.DEV, metadata.releaseChannel)
        assertEquals(listOf(BuildBadge.DEV, BuildBadge.BETA), metadata.badges)
    }

    @Test fun `update channel follows the version and never collapses to dev`() {
        // A developer build still checks the channel its version belongs to, so the update mechanism
        // works in dev even though telemetry stays on the DEV channel and offline.
        assertEquals(ReleaseChannel.BETA, BuildMetadata("1.5.0-beta.4", isDeveloperBuild = true).updateChannel)
        assertEquals(ReleaseChannel.STABLE, BuildMetadata("1.5.0", isDeveloperBuild = true).updateChannel)
        assertEquals(ReleaseChannel.BETA, BuildMetadata("1.5.0-beta.4", isDeveloperBuild = false).updateChannel)
        assertEquals(ReleaseChannel.STABLE, BuildMetadata("1.5.0", isDeveloperBuild = false).updateChannel)
    }

    @Test fun `from reads build_developer from properties`() {
        val developer = BuildMetadata.from("1.5.0", propertiesOf("build.developer" to "true"))
        assertTrue(developer.isDeveloperBuild)
        assertEquals(ReleaseChannel.DEV, developer.releaseChannel)

        val release = BuildMetadata.from("1.5.0", propertiesOf("build.developer" to "false"))
        assertFalse(release.isDeveloperBuild)
        assertEquals(ReleaseChannel.STABLE, release.releaseChannel)
    }

    @Test fun `from treats a missing build_developer as a developer build`() {
        val metadata = BuildMetadata.from("1.5.0", Properties())
        assertTrue(metadata.isDeveloperBuild)
        assertEquals(ReleaseChannel.DEV, metadata.releaseChannel)
    }

    @Test fun `from defaults a blank version to dev`() {
        val metadata = BuildMetadata.from("", propertiesOf("build.developer" to "false"))
        assertEquals("dev", metadata.version)
    }

    private fun propertiesOf(vararg pairs: Pair<String, String>): Properties =
        Properties().apply { pairs.forEach { (key, value) -> setProperty(key, value) } }
}
