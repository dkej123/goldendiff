package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.update.PluginUpdateStartupActivity
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JSplitPane
import javax.swing.JTextField

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

    fun testVerifyCaseCommandPersistsThroughTheUiAndReachesCoreConfig() {
        val settings = ScreenshotSettings.getInstance(project)
        settings.generateAllCommand = "generate all"
        settings.generateFileCommand = "generate {file_name}"
        settings.verifyAllCommand = "verify all"
        settings.verifyFileCommand = "verify {file_name}"
        settings.verifyCaseCommand = "verify {snapshot_method_name}"
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()

        val field = findNamed(component, "verifyCaseCommandField") as JTextField
        assertEquals("verify {snapshot_method_name}", field.text)
        field.text = "verify {snapshot_file_path}/{snapshot_method_name}"
        assertTrue(configurable.isModified)

        configurable.apply()

        assertEquals("verify {snapshot_file_path}/{snapshot_method_name}", settings.verifyCaseCommand)
        val config = settings.toConfig()
        assertEquals("generate all", config.generateAllCommand)
        assertEquals("generate {file_name}", config.generateFileCommand)
        assertEquals("verify all", config.verifyAllCommand)
        assertEquals("verify {file_name}", config.verifyFileCommand)
        assertEquals(settings.verifyCaseCommand, config.verifyCaseCommand)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testVerifyCaseMigrationDefaultAndCommandSpecificPlaceholderHelp() {
        val legacyState = ScreenshotSettings.State()
        assertEquals("", legacyState.verifyCaseCommand)

        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()
        val wholeProjectField = findNamed(component, "generateAllCommandField") as JTextField
        val viewField = findNamed(component, "generateViewCommandField") as JTextField
        val caseField = findNamed(component, "verifyCaseCommandField") as JTextField
        val wholeProjectHelp = wholeProjectField.toolTipText
        val viewHelp = viewField.toolTipText
        val help = caseField.toolTipText

        assertTrue(wholeProjectHelp.contains("placeholders are not expanded"))
        assertFalse(wholeProjectHelp.contains("{file_name}"))
        assertTrue(viewHelp.contains("{file_name}"))
        assertTrue(viewHelp.contains("{class_name}"))
        assertTrue(viewHelp.contains("{file_path}"))
        assertFalse(viewHelp.contains("{snapshot_method_name}"))
        assertTrue(help.contains("{snapshot_file_name}"))
        assertTrue(help.contains("{snapshot_class_name}"))
        assertTrue(help.contains("{snapshot_file_path}"))
        assertTrue(help.contains("{snapshot_method_name}"))
        assertFalse(help.contains("{file_name} ="))
        configurable.disposeUIResources()
    }

    fun testAllCommandFieldsKeepAUsableHeightWhenDescriptionsWrap() {
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()
        val fieldNames = listOf(
            "generateAllCommandField",
            "generateViewCommandField",
            "verifyAllCommandField",
            "verifyViewCommandField",
            "verifyCaseCommandField",
        )

        fieldNames.forEach { name ->
            val field = findNamed(component, name) as JTextField
            assertTrue("$name should have a visible editor height", field.minimumSize.height >= 28)
            assertEquals(field.minimumSize.height, field.maximumSize.height)
            assertTrue(
                "$name block must not collapse below its preferred content",
                field.parent.maximumSize.height >= field.parent.preferredSize.height,
            )
        }

        configurable.disposeUIResources()
    }

    fun testCommandLabelsMatchTheStandaloneApplication() {
        val configurable = ScreenshotConfigurable(project)
        val component = configurable.createComponent()
        val labels = allLabels(component).map(JLabel::getText)

        listOf(
            "Generate all goldens:",
            "Generate for current file:",
            "Verify all goldens:",
            "Verify current file:",
            "Verify current case:",
        ).forEach { expected ->
            assertTrue("Missing command label: $expected", expected in labels)
        }

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

    private fun allLabels(component: Component): List<JLabel> = buildList {
        if (component is JLabel) add(component)
        if (component is Container) {
            component.components.forEach { addAll(allLabels(it)) }
        }
    }
}
