package com.github.dkwasniak.goldendiff.app

internal data class PendingSnapshotCommand(
    val projectPath: String,
    val kind: AppState.CommandKind,
    val relativePath: String?,
    val caseId: String?,
    val commands: List<String>,
    val label: String,
    val perFile: Boolean,
)

internal data class XcodeRunMismatch(
    val run: XcodeRunDevice,
    val destination: XcodeDestination,
)

internal object XcodeRunPreflight {
    fun awaitsRunCatalog(runsLoading: Boolean): Boolean = runsLoading

    fun mismatch(run: XcodeTestRun?, destination: XcodeDestination?): XcodeRunMismatch? {
        val device = run?.device ?: return null
        val target = destination ?: return null
        val platformDiffers = device.platform.isNotBlank() && target.platform.isNotBlank() &&
            normalizePlatform(device.platform) != normalizePlatform(target.platform)
        val nameDiffers = device.name.isNotBlank() && target.name.isNotBlank() &&
            !device.name.trim().equals(target.name.trim(), ignoreCase = true)
        val osDiffers = device.os.isNotBlank() && !target.os.isNullOrBlank() &&
            normalizeOs(device.os) != normalizeOs(target.os)
        return if (platformDiffers || nameDiffers || osDiffers) XcodeRunMismatch(device, target) else null
    }

    private fun normalizePlatform(value: String): String = when {
        value.contains("simulator", true) -> "ios simulator"
        value.contains("ios", true) -> "ios"
        else -> value.trim().lowercase()
    }

    private fun normalizeOs(value: String): String = value.trim().removePrefix("iOS ").lowercase()
}

/** Small app-only state machine for the recoverable mismatch confirmation. */
internal class PendingXcodeCommandWorkflow {
    data class Confirmation(val command: PendingSnapshotCommand, val mismatch: XcodeRunMismatch)

    var confirmation: Confirmation? = null
        private set

    /** Returns true when the command can start immediately. */
    fun request(
        command: PendingSnapshotCommand,
        run: XcodeTestRun?,
        destination: XcodeDestination?,
    ): Boolean {
        confirmation = null
        val mismatch = XcodeRunPreflight.mismatch(run, destination) ?: return true
        confirmation = Confirmation(command, mismatch)
        return false
    }

    fun cancel() {
        confirmation = null
    }

    /** Consumes the bypass; a second confirmation action cannot run the command again. */
    fun takeRunAnyway(projectPath: String): PendingSnapshotCommand? =
        confirmation?.command
            ?.takeIf { it.projectPath == projectPath }
            .also { confirmation = null }
}
