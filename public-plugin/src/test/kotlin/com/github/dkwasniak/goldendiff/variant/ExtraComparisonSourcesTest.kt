package com.github.dkwasniak.goldendiff.variant

import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ExtraComparisonSourcesTest : BasePlatformTestCase() {

    fun `test listener observes dynamically registered comparison source`() {
        val changes = AtomicInteger()
        ExtraComparisonSources.addChangeListener(testRootDisposable) {
            changes.incrementAndGet()
        }

        val extensionPoint = ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<ExtraComparisonSource>(
                "com.github.dkwasniak.goldendiff.comparisonSource",
            )
        extensionPoint.registerExtension(FakeComparisonSource, testRootDisposable)

        assertEquals(1, changes.get())
        assertTrue(ExtraComparisonSources.all.any { it.id == FakeComparisonSource.id })
    }

    private object FakeComparisonSource : ExtraComparisonSource {
        override val id: String = "compatibility-test"
        override val title: String = "Compatibility test"

        override fun findFiles(
            project: Project,
            screen: Screen,
            settings: ScreenshotSettings,
        ): List<File> = emptyList()

        override fun loadComparison(
            project: Project,
            file: File,
            screen: Screen,
            settings: ScreenshotSettings,
        ): ExtraComparisonResult =
            ExtraComparisonResult.Single(image = null, statusText = "Not used")
    }
}
