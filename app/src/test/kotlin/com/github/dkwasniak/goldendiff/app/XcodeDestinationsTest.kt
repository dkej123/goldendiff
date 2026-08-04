package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XcodeDestinationsTest {
    private val simulator = XcodeDestination("iOS Simulator", "iPhone 16 Pro", "18.5", "ignored")

    @Test
    fun `parses simulator and connected physical destinations`() {
        val output = """
            Available destinations for the "App" scheme:
                { platform:iOS, arch:arm64, id:00008110-ABC, name:Damian's iPhone }
                { platform:iOS Simulator, arch:arm64, id:SIM-1, OS:18.5, name:iPhone 16 Pro }
                { platform:iOS Simulator, id:placeholder, name:Any iOS Simulator Device }
        """.trimIndent()

        assertEquals(
            listOf(
                XcodeDestination("iOS Simulator", "iPhone 16 Pro", "18.5", "SIM-1"),
                XcodeDestination("iOS", "Damian's iPhone", id = "00008110-ABC"),
            ),
            XcodeCommandDestination.parseShowDestinations(output),
        )
    }

    @Test
    fun `parses fast local simulator and physical device catalogs`() {
        val simulators = XcodeCommandDestination.parseSimctlDevices(
            """
            {"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-26-3":[
              {"isAvailable":true,"name":"iPhone 17 Pro","udid":"SIM-1"},
              {"isAvailable":false,"name":"Unavailable","udid":"SIM-2"}
            ]}}
            """.trimIndent(),
        )
        val physical = XcodeCommandDestination.parseDevicectlDevices(
            """
            {"result":{"devices":[{
              "deviceProperties":{"name":"Damian's iPhone","osVersionNumber":"26.5.2"},
              "hardwareProperties":{"platform":"iOS","reality":"physical","udid":"00008110-ABC"}
            }]}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                XcodeDestination("iOS Simulator", "iPhone 17 Pro", "26.3", "SIM-1"),
                XcodeDestination("iOS", "Damian's iPhone", "26.5.2", "00008110-ABC"),
            ),
            XcodeCommandDestination.combineDestinations(simulators, physical),
        )
    }

    @Test
    fun `catalog uses local devices without starting slow xcodebuild fallback`() {
        val expected = listOf(simulator)
        val catalog = XcodeDestinationCatalog(
            localReader = LocalXcodeDeviceReader { Result.success(expected) },
            fallbackReader = XcodeDestinationReader { _, _ ->
                fail("xcodebuild fallback must not run when the local catalog is available")
                Result.failure(AssertionError())
            },
        )

        assertEquals(
            expected,
            catalog.load(
                java.io.File("."),
                GoldenDiffConfig(generateAllCommand = "xcodebuild test -scheme App"),
            ).getOrThrow(),
        )
    }

    @Test
    fun `replaces quoted destination without expanding variables or placeholders`() {
        val command = "TEST_PATH=\"{snapshot_file_path}\"; xcodebuild test -scheme 'Unit Tests' " +
            "-destination \"platform=iOS Simulator,name=iPhone 15,OS=17.5\" -only-testing:\"\$TARGET/{snapshot_class_name}\""

        val rewritten = XcodeCommandDestination.rewrite(command, simulator).getOrThrow()

        assertTrue(rewritten.contains("TEST_PATH=\"{snapshot_file_path}\""))
        assertTrue(rewritten.contains("\$TARGET/{snapshot_class_name}"))
        assertTrue(rewritten.contains("-destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.5'"))
    }

    @Test
    fun `adds missing destination to one recognized xcodebuild`() {
        val command = "xcodebuild test -workspace App.xcworkspace -scheme App"
        assertEquals(
            "$command -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.5'",
            XcodeCommandDestination.rewrite(command, simulator).getOrThrow(),
        )
    }

    @Test
    fun `adds destination before a pipeline without rewriting the rest`() {
        val command = "set -o pipefail; xcodebuild test -scheme App | xcpretty --report junit"
        assertEquals(
            "set -o pipefail; xcodebuild test -scheme App -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.5' | xcpretty --report junit",
            XcodeCommandDestination.rewrite(command, simulator).getOrThrow(),
        )
    }

    @Test
    fun `rewrites all five commands atomically`() {
        val base = "xcodebuild test -project App.xcodeproj -scheme App"
        val config = GoldenDiffConfig(
            generateAllCommand = base,
            generateFileCommand = "$base -only-testing:'{class_name}'",
            verifyAllCommand = "$base -destination 'platform=iOS Simulator,name=Old,OS=17.0'",
            verifyFileCommand = "$base -only-testing:\"{snapshot_class_name}\"",
            verifyCaseCommand = "$base -only-testing:'{snapshot_method_name}'",
        )

        val changed = XcodeCommandDestination.rewriteAll(config, simulator).getOrThrow()

        listOf(
            changed.generateAllCommand, changed.generateFileCommand, changed.verifyAllCommand,
            changed.verifyFileCommand, changed.verifyCaseCommand,
        ).forEach { assertTrue(it.contains(simulator.commandValue)) }
    }

    @Test
    fun `different contexts or ambiguous commands reject the whole update`() {
        val different = GoldenDiffConfig(
            generateAllCommand = "xcodebuild test -scheme One",
            verifyAllCommand = "xcodebuild test -scheme Two",
        )
        assertTrue(XcodeCommandDestination.rewriteAll(different, simulator).isFailure)

        val multiple = GoldenDiffConfig(
            generateAllCommand = "xcodebuild test -scheme One && xcodebuild test -scheme One",
        )
        assertTrue(XcodeCommandDestination.rewriteAll(multiple, simulator).isFailure)
    }

    @Test
    fun `inconsistent stored destinations require a new choice`() {
        val config = GoldenDiffConfig(
            generateAllCommand = "xcodebuild test -scheme App -destination 'platform=iOS Simulator,name=A,OS=18.0'",
            verifyAllCommand = "xcodebuild test -scheme App -destination 'platform=iOS Simulator,name=B,OS=18.0'",
        )
        assertTrue(XcodeCommandDestination.selectedDestination(config).isFailure)
    }

    @Test
    fun `fast local destination cannot be selected before scheme-aware validation`() {
        val fast = XcodeDestination("iOS Simulator", "iPhone 17 Pro", "26.3", "SIM-1")
        val authoritative = XcodeDestination("iOS Simulator", "iPhone 17 Pro", "26.3.1", "SIM-1")

        assertTrue(!isSelectableXcodeDestination(fast, listOf(fast), authoritative = false))
        assertTrue(!isSelectableXcodeDestination(fast, listOf(authoritative), authoritative = true))
        assertTrue(isSelectableXcodeDestination(authoritative, listOf(authoritative), authoritative = true))
    }

    @Test
    fun `fast catalog hides loader while selection waits for authoritative validation`() {
        val fast = XcodeDestination("iOS Simulator", "iPhone 17 Pro", "26.3", "SIM-1")
        val authoritative = fast.copy(id = "AUTHORITATIVE-ID")

        val loading = XcodeDestinationPickerState().startLoading()
        val local = loading.localLoaded(Result.success(listOf(fast)))
        val pending = local.requestSelection(fast)
        val verified = pending.state.authoritativeLoaded(Result.success(listOf(authoritative)))

        assertTrue(loading.loading)
        assertFalse(local.loading)
        assertFalse(local.authoritative)
        assertNull(pending.confirmedSelection)
        assertEquals(fast, pending.state.pendingSelection)
        assertTrue(verified.state.authoritative)
        assertEquals(authoritative, verified.confirmedSelection)
        assertNull(verified.state.pendingSelection)
    }

    @Test
    fun `authoritative mismatch rejects pending fast selection`() {
        val fast = XcodeDestination("iOS Simulator", "iPhone 17 Pro", "26.3", "SIM-1")
        val schemeDestination = fast.copy(os = "26.3.1")
        val pending = XcodeDestinationPickerState()
            .startLoading()
            .localLoaded(Result.success(listOf(fast)))
            .requestSelection(fast)
            .state

        val verified = pending.authoritativeLoaded(Result.success(listOf(schemeDestination)))

        assertNull(verified.confirmedSelection)
        assertTrue(verified.state.authoritative)
        assertTrue(verified.state.error?.contains("not available", ignoreCase = true) == true)
    }
}
