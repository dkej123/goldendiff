package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.telemetry.TelemetryClient
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpan
import com.intellij.openapi.project.Project
import java.io.File

internal class PluginTelemetryReporter(
    val client: TelemetryClient,
    private val openPanel: (Project) -> Unit,
    private val closePanel: () -> Unit,
) {
    fun panelOpened(project: Project) = openPanel(project)

    fun panelClosed() = closePanel()

    fun event(name: String, properties: Map<String, String>): Boolean =
        client.event(name, properties)

    fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan =
        client.startSpan(name, properties)

    fun sourceFileSelected(memoryKey: String, trigger: String, fileFamily: String, alreadyOpen: Boolean) {
        client.sourceFileSelected(memoryKey, trigger, fileFamily, alreadyOpen)
    }

    fun activationCompleted(source: String) {
        client.activationCompleted("project_tree", source)
    }

    fun reportFailure(operation: String, error: Throwable, projectRoot: File?, retryable: Boolean = true) {
        client.captureException(error, "plugin:$operation", projectRoot)
        client.event(
            "product.operation_failed",
            mapOf(
                "operation" to operation,
                "error_category" to when (error) {
                    is java.io.IOException -> "io"
                    is IllegalArgumentException -> "invalid_config"
                    else -> "internal"
                },
                "retryable" to retryable.toString(),
                "scope" to "project_tree",
            ),
        )
    }

    companion object {
        fun live(): PluginTelemetryReporter {
            val service = PluginTelemetryService.getInstance()
            return PluginTelemetryReporter(
                client = service.client,
                openPanel = service::panelOpened,
                closePanel = service::panelClosed,
            )
        }
    }
}
