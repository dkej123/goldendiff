package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.CommandShell
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File

/**
 * Runs a user-configured screenshot command through IntelliJ's own execution layer rather than a raw
 * process, so the output lands in the Run tool window with live streaming, ANSI colours and a Stop
 * button — the native "log window" for a running task. Stable platform API since 241.
 *
 * The command is handed to the platform shell (via [CommandShell]) because it is a free-form string
 * the user wrote for a shell. [onTerminated] fires on process end with the exit code (null if the
 * process failed to start), on whatever thread the process handler uses — callers marshal to the EDT.
 */
object CommandExecution {

    fun run(
        project: Project,
        command: String,
        workingDir: File,
        title: String,
        onStarted: () -> Unit = {},
        onTerminated: (Int?) -> Unit = {},
    ): Boolean {
        val commandLine = GeneralCommandLine(CommandShell.invocation(command))
            .withWorkDirectory(workingDir)
        val handler = runCatching { OSProcessHandler(commandLine) }.getOrElse {
            onTerminated(null)
            return false
        }
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) = onStarted()
            override fun processTerminated(event: ProcessEvent) = onTerminated(event.exitCode)
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = Unit
        })
        val executor = RunContentExecutor(project, handler)
            .withTitle(title)
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })

        // Swing listeners run on the EDT but, on recent IDEs, no longer implicitly hold the
        // write-intent lock. RunContentExecutor saves open documents before launch and therefore
        // requires that lock even though this method is already called from the EDT.
        @Suppress("UnstableApiUsage")
        WriteIntentReadAction.run { executor.run() }
        return true
    }
}
