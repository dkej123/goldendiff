package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.update.PluginUpdateStartupActivity
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JList
import javax.swing.JSplitPane
import java.util.concurrent.atomic.AtomicBoolean

class ScreenshotConfigurableTest : BasePlatformTestCase() {

    override fun setUp() {
        // These UI tests do not exercise update checks. Prevent the plugin's project startup activity
        // from launching a coroutine while the lightweight 2024.1 test sandbox is still opening.
        PluginUpdateStartupActivity::class.java.getDeclaredField("checkedThisSession").apply {
            isAccessible = true
            (get(null) as AtomicBoolean).set(true)
        }
        super.setUp()
    }

    fun testSettingsUseStandaloneStyleNavigationAndContextualPreview() {
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()

        val navigation = findNamed(component, "settingsNavigation") as JList<*>
        val labels = (0 until navigation.model.size).map { navigation.model.getElementAt(it) }
        assertEquals(
            listOf(
                "Directories",
                "Test output matching",
                "Golden matching",
                "Filtering",
                "Display",
                "Commands",
                "Notifications",
                "Privacy",
            ),
            labels,
        )

        val split = findNamed(component, "settingsPreviewSplit") as JSplitPane
        assertNotNull(split.rightComponent)
        navigation.selectedIndex = 4
        assertNull(split.rightComponent)
        navigation.selectedIndex = 2
        assertNotNull(split.rightComponent)

        configurable.disposeUIResources()
    }

    fun testMultipleAndRegexDirectoryEntriesReachTheSettingsModelUnchanged() {
        val regex = GoldenDiffConfig.goldenDirectoryRegexEntry(".*/src/test/snapshots")
        ScreenshotSettings.getInstance(project).paths = listOf("goldens/one", "goldens/two", regex)
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()

        val list = findNamed(component, "goldenDirectoriesList") as JList<*>
        val values = (0 until list.model.size).map { list.model.getElementAt(it) }
        assertEquals(listOf("goldens/one", "goldens/two", regex), values)

        configurable.disposeUIResources()
    }

    fun testApplyRejectsAnInvalidGoldenDirectoryRegex() {
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()
        @Suppress("UNCHECKED_CAST")
        val list = findNamed(component, "goldenDirectoriesList") as JList<String>
        val model = list.model as javax.swing.DefaultListModel<String>
        model.addElement(GoldenDiffConfig.goldenDirectoryRegexEntry("["))

        assertThrows(ConfigurationException::class.java) { configurable.apply() }
        configurable.disposeUIResources()
    }

    private fun findNamed(component: Component, name: String): Component {
        return findNamedOrNull(component, name) ?: error("Component '$name' was not found")
    }

    private fun findNamedOrNull(component: Component, name: String): Component? {
        if (component.name == name) return component
        if (component is Container) {
            component.components.forEach { child ->
                findNamedOrNull(child, name)?.let { return it }
            }
        }
        return null
    }
}
