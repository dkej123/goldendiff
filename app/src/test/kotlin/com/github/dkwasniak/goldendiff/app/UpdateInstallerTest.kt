package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateInstallerTest {

    @Test fun betaDmgUrlUsesTheBetaTag() {
        assertEquals(
            "https://github.com/dkej123/goldendiff/releases/download/" +
                "app-beta-v1.5.0-beta.4/Golden-Diff-1.5.0-beta.4.dmg",
            UpdateInstaller.dmgUrl("1.5.0-beta.4", ReleaseChannel.BETA),
        )
    }

    @Test fun stableDmgUrlUsesTheStableTag() {
        assertEquals(
            "https://github.com/dkej123/goldendiff/releases/download/app-v1.5.0/Golden-Diff-1.5.0.dmg",
            UpdateInstaller.dmgUrl("1.5.0", ReleaseChannel.STABLE),
        )
    }

    @Test fun devBuildHasNoHomebrewCask() {
        assertNull(UpdateInstaller.homebrewCask(ReleaseChannel.DEV))
    }
}
