package com.github.dkwasniak.goldendiff.update

import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Once per IDE session (and at most once a day across sessions), checks the Marketplace for a newer
 * Golden Diff and, if there is one, shows a balloon in the bottom-right notification area. Runs off
 * the EDT; every failure is silent so it can never disturb startup.
 */
class PluginUpdateStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!checkedThisSession.compareAndSet(false, true)) return

        val properties = PropertiesComponent.getInstance()
        val now = System.currentTimeMillis()
        val lastCheck = properties.getLong(LAST_CHECK_KEY, 0)
        if (now - lastCheck in 0..CHECK_INTERVAL_MS) return

        val service = PluginTelemetryService.getInstance()
        val currentVersion = service.appVersion
        // The effective channel: a developer build resolves to DEV, so the check returns before any
        // network access (PluginUpdateChecker.check short-circuits DEV).
        val channel = service.releaseChannel

        // Keep this suspend override free of suspension points. Kotlin 2.2 otherwise emits a
        // coroutine state machine that references SpillingKt, which is absent from the Kotlin
        // runtime bundled with IntelliJ Platform 241–243.
        AppExecutorUtil.getAppExecutorService().execute task@{
            val update = runCatching {
                PluginUpdateChecker.check(currentVersion, channel)
            }.getOrNull()
            properties.setValue(LAST_CHECK_KEY, now.toString())
            if (update == null || project.isDisposed) return@task

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "Golden Diff ${update.version} is available",
                        "You are running $currentVersion. Update to get the latest version.",
                        NotificationType.INFORMATION,
                    )
                    .addAction(NotificationAction.createSimple("Update…") {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, PLUGIN_MANAGER_CONFIGURABLE_ID)
                    })
                    .notify(project)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_GROUP = "Golden Diff Updates"
        private const val PLUGIN_MANAGER_CONFIGURABLE_ID = "preferences.pluginManager"
        private const val LAST_CHECK_KEY = "goldendiff.update.lastCheckMs"
        private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

        // App-wide: a fresh IDE checks once, no matter how many projects open in that session.
        private val checkedThisSession = AtomicBoolean(false)
    }
}
