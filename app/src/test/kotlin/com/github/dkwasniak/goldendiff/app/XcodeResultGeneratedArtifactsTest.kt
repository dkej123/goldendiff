package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartIndex
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.settings.GeneratedSource
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XcodeResultGeneratedArtifactsTest {

    @Test
    fun `directory source does not inspect Xcode results`() {
        val root = Files.createTempDirectory("xcresult-disabled").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        resultBundle(File(root, "DerivedData"), "AdHoc-hash", "Test.xcresult", 1_000L)
        var reads = 0
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = File(root, "DerivedData"),
            summaryReader = {
                reads++
                "{}"
            },
        )

        assertTrue(artifacts.find(project, GoldenDiffConfig()).isEmpty())
        assertEquals(0, reads)
    }

    @Test
    fun `xcresult source honors an explicit DerivedData project folder`() {
        val root = Files.createTempDirectory("xcresult-override").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        val explicitDerivedData = File(root, "CustomDerivedData").apply { mkdirs() }
        val result = resultBundle(
            explicitDerivedData.parentFile,
            explicitDerivedData.name,
            "Test.xcresult",
            System.currentTimeMillis(),
        )
        val actual = File(root, "CoreSimulator/tmp/Tests/test.1.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val goldenRoot = File(project, "goldens")
        val golden = File(goldenRoot, "Tests/test.1.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0))
        }
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = File(root, "UnusedDerivedData"),
            summaryReader = {
                """{"failureText":"@−\n\"${golden.toURI()}\"\n@+\n\"${actual.toURI()}\""}"""
            },
        )
        val config = GoldenDiffConfig(
            generatedSource = GeneratedSource.XCRESULT,
            xcresultDerivedDataPath = explicitDerivedData.absolutePath,
        )

        val candidates = artifacts.find(project, config)
        assertEquals(listOf(actual), candidates.map { it.file })
        val resolution = GeneratedCounterpartIndex.build(
            generatedRoots = emptyList(),
            generatedFileRegex = config.generatedFileRegex,
            explicitCandidates = candidates,
        ).resolve(golden, listOf(goldenRoot))
        assertEquals(actual, (resolution as GeneratedCounterpartResolution.Exact).file)
        assertTrue(result.isDirectory)
    }

    @Test
    fun `xcresult source lists recent runs but artifacts come from one selected run`() {
        val root = Files.createTempDirectory("xcresult-window").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        val derivedData = File(root, "DerivedData")
        val now = System.currentTimeMillis()
        val recent = resultBundle(derivedData, "AdHoc-hash", "Test-recent.xcresult", now - 60 * 60 * 1_000L)
        val latest = resultBundle(derivedData, "AdHoc-hash", "Test-latest.xcresult", now)
        val expired = resultBundle(derivedData, "AdHoc-hash", "Test-expired.xcresult", now - 72 * 60 * 60 * 1_000L)
        val recentActual = actualFile(root, "FirstTests/test_first.1.png")
        val latestActual = actualFile(root, "SecondTests/test_second.1.png")
        val expiredActual = actualFile(root, "OldTests/test_old.1.png")
        val requested = mutableListOf<File>()
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = derivedData,
            summaryReader = { bundle ->
                requested += bundle
                val actual = when (bundle) {
                    latest -> latestActual
                    recent -> recentActual
                    else -> expiredActual
                }
                """{"failureText":"@−\n\"${File(project, "goldens/${actual.parentFile.name}/${actual.name}").toURI()}\"\n@+\n\"${actual.toURI()}\""}"""
            },
        )
        val config = GoldenDiffConfig(
            generatedSource = GeneratedSource.XCRESULT,
            xcresultScanWindowHours = 48,
        )

        val runs = artifacts.runs(project, config)
        assertEquals(listOf(latest, recent), runs.map { it.path })
        assertEquals(listOf(latestActual), artifacts.find(project, config).map { it.file })
        assertEquals(listOf(recentActual), artifacts.find(project, config, recent.absolutePath).map { it.file })
        assertEquals(listOf(latest, recent), requested)
        assertTrue(expired.isDirectory)
    }

    @Test
    fun `latest xcresult resolves existing percent-encoded actual file URL`() {
        val root = Files.createTempDirectory("xcresult-artifacts").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        val derivedData = File(root, "DerivedData")
        val older = resultBundle(derivedData, "AdHoc-hash", "Test-older.xcresult", modified = 1_000L)
        val latest = resultBundle(derivedData, "AdHoc-hash", "Test-latest.xcresult", modified = 2_000L)
        val actual = File(root, "CoreSimulator/data/tmp/Badge ViewSnapshotTests/test_badge.1.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val summary = """
            {
              "testFailures": [
                {
                  "failureText": "failed - Snapshot does not match reference.\n\n@−\n\"file://${project.path}/goldens/BadgeViewSnapshotTests/test_badge.1.png\"\n@+\n\"${actual.toURI()}\""
                }
              ]
            }
        """.trimIndent()
        val requested = mutableListOf<File>()
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = derivedData,
            summaryReader = { bundle ->
                requested += bundle
                summary
            },
        )

        val candidates = artifacts.find(project)

        assertEquals(listOf(latest), requested)
        assertEquals(listOf(actual), candidates.map { it.file })
        assertEquals(listOf("Badge ViewSnapshotTests"), candidates.map { it.relativeParent })
        assertTrue(older.isDirectory)
    }

    @Test
    fun `unchanged result bundle is parsed once and a delayed actual file is rechecked`() {
        val root = Files.createTempDirectory("xcresult-cache").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        val result = resultBundle(File(root, "DerivedData"), "AdHoc-hash", "Test.xcresult", 1_000L)
        val missing = File(root, "CoreSimulator/data/tmp/MissingTests/test_missing.1.png")
        var reads = 0
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = File(root, "DerivedData"),
            summaryReader = {
                reads++
                """{"wrapped":{"_value":"@−\n\"${File(project, "goldens/MissingTests/test_missing.1.png").toURI()}\"\n@+\n\"${missing.toURI()}\""}}"""
            },
        )

        assertTrue(artifacts.find(project).isEmpty())
        missing.parentFile.mkdirs()
        missing.writeBytes(byteArrayOf(4, 5, 6))
        assertEquals(listOf(missing), artifacts.find(project).map { it.file })
        assertEquals(1, reads)
        assertTrue(result.isDirectory)
    }

    @Test
    fun `unreadable xcresult summary fails closed`() {
        val root = Files.createTempDirectory("xcresult-invalid").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        resultBundle(File(root, "DerivedData"), "AdHoc-hash", "Test.xcresult", 1_000L)
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = File(root, "DerivedData"),
            summaryReader = { "not json" },
        )

        assertTrue(artifacts.find(project).isEmpty())
        assertTrue(artifacts.runs(project, GoldenDiffConfig(
            generatedSource = GeneratedSource.XCRESULT,
            xcresultScanWindowHours = Int.MAX_VALUE,
        )).single().partial)
    }

    @Test
    fun `run metadata includes device os and pass fail counts`() {
        val root = Files.createTempDirectory("xcresult-metadata").toFile()
        val project = File(root, "project").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        resultBundle(File(root, "DerivedData"), "AdHoc-hash", "Test.xcresult", System.currentTimeMillis())
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = File(root, "DerivedData"),
            summaryReader = {
                """{"passedTests":12,"failedTests":2,"device":{"deviceName":"iPhone 16 Pro","osVersion":"18.5","platform":"iOS Simulator"}}"""
            },
        )

        val run = artifacts.runs(project, GoldenDiffConfig(
            generatedSource = GeneratedSource.XCRESULT,
            xcresultScanWindowHours = 48,
        )).single()

        assertEquals(XcodeRunDevice("iOS Simulator", "iPhone 16 Pro", "18.5"), run.device)
        assertEquals(12, run.passedTests)
        assertEquals(2, run.failedTests)
    }

    @Test
    fun `verify selects a new or updated run and otherwise preserves current`() {
        val old = XcodeTestRun("old", File("old"), 10, null, null, null, emptyList())
        val updated = old.copy(modifiedAt = 20)
        val newest = XcodeTestRun("new", File("new"), 30, null, null, null, emptyList())

        assertEquals("new", selectXcodeRunAfterVerify(listOf(old), listOf(newest, old), "old"))
        assertEquals("old", selectXcodeRunAfterVerify(listOf(old), listOf(old), "old"))
        assertEquals("old", selectXcodeRunAfterVerify(listOf(old), listOf(updated), "old"))
    }

    @Test
    fun `missing saved run falls back to newest available`() {
        val newest = XcodeTestRun("new", File("new"), 30, null, null, null, emptyList())
        val older = XcodeTestRun("old", File("old"), 20, null, null, null, emptyList())

        assertEquals("old", selectAvailableXcodeRun(listOf(newest, older), "old"))
        assertEquals("new", selectAvailableXcodeRun(listOf(newest, older), "deleted"))
        assertEquals(null, selectAvailableXcodeRun(emptyList(), "deleted"))
    }

    @Test
    fun `newer derived data from another checkout with the same project name is ignored`() {
        val root = Files.createTempDirectory("xcresult-checkout").toFile()
        val project = File(root, "current").apply { mkdirs() }
        val otherProject = File(root, "other").apply { mkdirs() }
        File(project, "AdHoc.xcodeproj").mkdirs()
        val derivedData = File(root, "DerivedData")
        val currentResult = resultBundle(derivedData, "AdHoc-current", "Test.xcresult", 1_000L)
        val otherResult = resultBundle(derivedData, "AdHoc-other", "Test.xcresult", 2_000L)
        derivedDataInfo(currentResult, File(project, "AdHoc.xcodeproj"))
        derivedDataInfo(otherResult, File(otherProject, "AdHoc.xcodeproj"))
        val actual = File(root, "CoreSimulator/data/tmp/Tests/test.1.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val requested = mutableListOf<File>()
        val artifacts = XcodeResultGeneratedArtifacts(
            derivedDataRoot = derivedData,
            summaryReader = { bundle ->
                requested += bundle
                """{"failureText":"@−\n\"${File(project, "goldens/Tests/test.1.png").toURI()}\"\n@+\n\"${actual.toURI()}\""}"""
            },
        )

        assertEquals(listOf(actual), artifacts.find(project).map { it.file })
        assertEquals(listOf(currentResult), requested)
        assertTrue(otherResult.isDirectory)
    }

    private fun resultBundle(root: File, project: String, name: String, modified: Long): File =
        File(root, "$project/Logs/Test/$name").apply {
            mkdirs()
            setLastModified(modified)
        }

    private fun derivedDataInfo(resultBundle: File, workspace: File) {
        val derivedData = resultBundle.parentFile.parentFile.parentFile
        File(derivedData, "info.plist").writeText(
            """<plist><dict><key>WorkspacePath</key><string>${workspace.path}</string></dict></plist>""",
        )
    }

    private fun actualFile(root: File, relativePath: String): File =
        File(root, "CoreSimulator/tmp/$relativePath").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
}
