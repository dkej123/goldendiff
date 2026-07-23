package com.github.dkwasniak.goldendiff.telemetry

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

@Service(Service.Level.APP)
@State(name = "GoldenDiffTelemetrySettings", storages = [Storage("goldenDiffTelemetry.xml")])
class PluginTelemetrySettings : PersistentStateComponent<PluginTelemetrySettings.State> {
    class State {
        var analyticsEnabled: Boolean = false
        var diagnosticsEnabled: Boolean = false
        var consentPromptShown: Boolean = false
    }

    private var value = State()
    override fun getState(): State = value
    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, value)

    var analyticsEnabled: Boolean
        get() = value.analyticsEnabled
        set(enabled) {
            value.analyticsEnabled = enabled
        }
    var diagnosticsEnabled: Boolean
        get() = value.diagnosticsEnabled
        set(enabled) {
            value.diagnosticsEnabled = enabled
        }

    val consent: TelemetryConsent
        get() = TelemetryConsent(analyticsEnabled, diagnosticsEnabled)
    var consentPromptShown: Boolean
        get() = value.consentPromptShown
        set(shown) {
            value.consentPromptShown = shown
        }

    companion object {
        fun getInstance(): PluginTelemetrySettings = service()
    }
}

private object PluginTelemetryStore : TelemetryStore {
    private const val PREFIX = "golden.diff."
    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance()

    override fun get(key: String): String? = properties.getValue(PREFIX + key)
    override fun put(key: String, value: String) = properties.setValue(PREFIX + key, value)
    override fun remove(key: String) = properties.unsetValue(PREFIX + key)
}

@Service(Service.Level.APP)
class PluginTelemetryService {
    private val activePanels = AtomicInteger()
    private val sessionLifecycleStarted = AtomicBoolean()
    private val sessionEventSent = AtomicBoolean()
    private val consentPromptScheduled = AtomicBoolean()
    private val buildProperties = Properties().apply {
        PluginTelemetryService::class.java.classLoader
            .getResourceAsStream("golden-diff-telemetry.properties")
            ?.use(::load)
    }
    private val version: String = buildProperties.getProperty("version").orEmpty().ifBlank {
        PluginManagerCore.getPlugin(PluginId.getId("com.github.dkwasniak.goldendiff"))
            ?.version.orEmpty().ifBlank { "dev" }
    }
    private val releaseChannel = ReleaseChannel.fromVersion(version)
    val client = TelemetryClient(
        environment = TelemetryEnvironment(
            surface = TelemetrySurface.PLUGIN,
            releaseChannel = releaseChannel,
            appVersion = version,
            ideProduct = ideProduct(),
            ideBuildMajor = ApplicationInfo.getInstance().build.baselineVersion.toString(),
        ),
        store = PluginTelemetryStore,
        backendFactory = { consent ->
            GoldenDiffTelemetryBackend.create(
                amplitudeApiKey = buildProperties.getProperty("amplitude.api_key").orEmpty(),
                sentryDsn = buildProperties.getProperty("sentry.dsn").orEmpty(),
                release = "golden-diff-plugin@$version",
                environment = releaseChannel.wireValue,
                consent = consent,
            )
        },
        initialConsent = PluginTelemetrySettings.getInstance().consent,
    )

    fun panelOpened(project: Project) {
        activePanels.incrementAndGet()
        showConsentPromptIfNeeded(project)
        sessionLifecycleStarted.compareAndSet(false, true)
        sendSessionEventIfEnabled()
    }

    fun panelClosed() {
        if (activePanels.decrementAndGet().coerceAtLeast(0) == 0) client.sessionEnded()
    }

    fun updateConsent() {
        client.updateConsent(PluginTelemetrySettings.getInstance().consent)
        sendSessionEventIfEnabled()
    }

    private fun showConsentPromptIfNeeded(project: Project) {
        val settings = PluginTelemetrySettings.getInstance()
        if (settings.consentPromptShown || !consentPromptScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            TelemetryConsentDialog(project) { analytics, diagnostics ->
                settings.consentPromptShown = true
                settings.analyticsEnabled = analytics
                settings.diagnosticsEnabled = diagnostics
                updateConsent()
            }.show()
        }
    }

    private fun sendSessionEventIfEnabled() {
        if (
            sessionLifecycleStarted.get() &&
            PluginTelemetrySettings.getInstance().analyticsEnabled &&
            sessionEventSent.compareAndSet(false, true)
        ) {
            client.installationFirstSeen()
            client.sessionStarted("tool_window", projectRestored = true)
        }
    }

    companion object {
        fun getInstance(): PluginTelemetryService =
            ApplicationManager.getApplication().getService(PluginTelemetryService::class.java)

        private fun ideProduct(): String {
            val name = ApplicationInfo.getInstance().fullApplicationName.lowercase(Locale.ROOT)
            return when {
                "android studio" in name -> "android_studio"
                "intellij idea" in name -> "intellij_idea"
                else -> "other"
            }
        }
    }
}

private class TelemetryConsentDialog(
    project: Project,
    private val onDecision: (Boolean, Boolean) -> Unit,
) : DialogWrapper(project, true) {
    private val analytics = JBCheckBox("Anonymous usage analytics", true)
    private val diagnostics = JBCheckBox("Crash reporting", true)

    init {
        title = "Help make Golden Diff better"
        setOKButtonText("Enable and continue")
        setCancelButtonText("Not now")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(610), JBUI.scale(310))
            border = JBUI.Borders.empty(20, 22)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JBLabel("Help make Golden Diff better").apply {
                        font = font.deriveFont(Font.BOLD, 22f)
                        alignmentX = JComponent.LEFT_ALIGNMENT
                    })
                    add(JBLabel("<html><body style='width:540px;padding-top:12px'>" +
                        "Sharing anonymous usage data and crash reports helps us find bugs faster, " +
                        "prioritize the features teams actually use, and ship fixes sooner. It is optional, " +
                        "and you can change your mind anytime in Settings.</body></html>").apply {
                        alignmentX = JComponent.LEFT_ALIGNMENT
                    })
                    add(JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.emptyTop(22)
                        add(analytics, BorderLayout.NORTH)
                        add(
                            JBLabel("<html><body style='width:480px;padding-left:24px'>" +
                                "Which features you use — never filenames, paths, source code, or image content." +
                                "</body></html>"),
                            BorderLayout.CENTER,
                        )
                    })
                    add(JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.emptyTop(18)
                        add(diagnostics, BorderLayout.NORTH)
                        add(
                            JBLabel("<html><body style='width:480px;padding-left:24px'>" +
                                "Sanitized stack traces from failures inside Golden Diff.</body></html>"),
                            BorderLayout.CENTER,
                        )
                    })
                },
                BorderLayout.CENTER,
            )
        }

    override fun doOKAction() {
        onDecision(analytics.isSelected, diagnostics.isSelected)
        super.doOKAction()
    }

    override fun doCancelAction() {
        onDecision(false, false)
        super.doCancelAction()
    }
}
