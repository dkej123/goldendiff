package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.CommandShell
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The host-level result of a sequential command batch. A non-zero command does not stop the batch:
 * [Failure] means that no command succeeded (or the runner ended before any success), while
 * [PartialFailure] retains the mixed result needed for useful View-level feedback.
 */
sealed interface BatchCommandOutcome {
    val results: List<BatchCommandResult>

    data class Success(
        override val results: List<BatchCommandResult>,
    ) : BatchCommandOutcome

    data class Failure(
        override val results: List<BatchCommandResult>,
        val incompleteCommands: Int = 0,
    ) : BatchCommandOutcome

    data class PartialFailure(
        override val results: List<BatchCommandResult>,
        val incompleteCommands: Int = 0,
    ) : BatchCommandOutcome

    data class Cancelled(
        override val results: List<BatchCommandResult>,
    ) : BatchCommandOutcome
}

data class BatchCommandResult(
    val index: Int,
    val command: String,
    val exitCode: Int,
) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Pure, synchronized state machine used by the IntelliJ process adapter. It deliberately accepts
 * non-zero results and advances to the next index; only [cancel] prevents remaining work.
 */
internal class BatchCommandAggregator(
    private val commands: List<String>,
) {
    private val completed = ArrayList<BatchCommandResult>(commands.size)
    private var cancelled = false

    @Synchronized
    fun record(index: Int, exitCode: Int): Boolean {
        if (cancelled || index != completed.size || index !in commands.indices) return false
        completed += BatchCommandResult(index, commands[index], exitCode)
        return true
    }

    @Synchronized
    fun cancel() {
        cancelled = true
    }

    @Synchronized
    fun outcome(): BatchCommandOutcome {
        val results = completed.toList()
        if (cancelled) return BatchCommandOutcome.Cancelled(results)
        val incomplete = commands.size - results.size
        val successes = results.count(BatchCommandResult::succeeded)
        return when {
            incomplete == 0 && successes == results.size -> BatchCommandOutcome.Success(results)
            successes == 0 -> BatchCommandOutcome.Failure(results, incomplete)
            else -> BatchCommandOutcome.PartialFailure(results, incomplete)
        }
    }
}

/**
 * Runs a sequential queue through one [OSProcessHandler], keeping all output in one IntelliJ Run
 * content. The generated shell wrapper records each exit code but intentionally keeps going after a
 * normal test failure. Destroying the wrapper process cancels both the active entry and the tail.
 *
 * The existing single-command [CommandExecution] API remains unchanged.
 */
object BatchCommandExecution {

    interface Handle {
        val isCancelled: Boolean
        fun cancel()
    }

    fun run(
        project: Project,
        commands: List<String>,
        workingDir: File,
        title: String,
        onStarted: () -> Unit = {},
        onCompleted: (BatchCommandOutcome) -> Unit = {},
    ): Handle? {
        require(commands.isNotEmpty()) { "A command batch must not be empty." }
        val marker = "__GOLDEN_DIFF_BATCH_${UUID.randomUUID().toString().replace("-", "")}__"
        val aggregator = BatchCommandAggregator(commands)
        val commandLine = GeneralCommandLine(CommandShell.invocation(shellWrapper(commands, marker)))
            .withWorkDirectory(workingDir)
        val handler = runCatching { OSProcessHandler(commandLine) }.getOrElse {
            onCompleted(aggregator.outcome())
            return null
        }
        val cancelled = AtomicBoolean(false)
        val completionDelivered = AtomicBoolean(false)
        fun completeOnce() {
            if (completionDelivered.compareAndSet(false, true)) {
                onCompleted(aggregator.outcome())
            }
        }
        val handle = object : Handle {
            override val isCancelled: Boolean get() = cancelled.get()

            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    aggregator.cancel()
                    if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
                        handler.destroyProcess()
                    }
                }
            }
        }
        val outputParser = ExitMarkerParser(marker) { index, exitCode ->
            aggregator.record(index, exitCode)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) = onStarted()

            override fun processTerminated(event: ProcessEvent) {
                outputParser.finish()
                completeOnce()
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                outputParser.accept(event.text)
            }
        })
        val executor = RunContentExecutor(project, handler)
            .withTitle(title)
            .withActivateToolWindow(true)
            .withStop(handle::cancel) { !handler.isProcessTerminated }

        return runCatching {
            // Matches CommandExecution: RunContentExecutor saves documents and needs the write lock
            // on the oldest supported IntelliJ Platform (241).
            ApplicationManager.getApplication().runWriteAction { executor.run() }
            handle
        }.getOrElse {
            handle.cancel()
            completeOnce()
            null
        }
    }

    internal fun shellWrapper(commands: List<String>, marker: String): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return if (windows) windowsWrapper(commands, marker) else posixWrapper(commands, marker)
    }

    private fun posixWrapper(commands: List<String>, marker: String): String = buildString {
        appendLine("set +e")
        commands.forEachIndexed { index, command ->
            appendLine("printf '\\n— ${index + 1}/${commands.size} —\\n'")
            appendLine("printf '%s\\n' '$ ${escapeSingleQuoted(command)}'")
            appendLine("(")
            appendLine(command)
            appendLine(")")
            appendLine("golden_diff_exit=\$?")
            appendLine("printf '\\n$marker:$index:%s\\n' \"\$golden_diff_exit\"")
        }
        appendLine("exit 0")
    }

    private fun windowsWrapper(commands: List<String>, marker: String): String = buildString {
        commands.forEachIndexed { index, command ->
            appendLine("@echo.")
            appendLine("@echo — ${index + 1}/${commands.size} —")
            appendLine("@echo ^$ ${escapeCmdEcho(command)}")
            appendLine("call $command")
            appendLine("@set \"golden_diff_exit=%ERRORLEVEL%\"")
            appendLine("@call echo $marker:$index:%%golden_diff_exit%%")
        }
        appendLine("@exit /b 0")
    }

    private fun escapeSingleQuoted(value: String): String = value.replace("'", "'\"'\"'")

    private fun escapeCmdEcho(value: String): String =
        value.replace("^", "^^").replace("&", "^&").replace("|", "^|").replace("<", "^<").replace(">", "^>")
}

internal class ExitMarkerParser(
    marker: String,
    private val onExitCode: (Int, Int) -> Unit,
) {
    private val prefix = "$marker:"
    private val pending = StringBuilder()

    @Synchronized
    fun accept(text: String) {
        pending.append(text)
        drain(keepTrailingLine = true)
    }

    @Synchronized
    fun finish() {
        drain(keepTrailingLine = false)
    }

    private fun drain(keepTrailingLine: Boolean) {
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            parseLine(pending.substring(0, newline))
            pending.delete(0, newline + 1)
        }
        if (!keepTrailingLine && pending.isNotEmpty()) {
            parseLine(pending.toString())
            pending.setLength(0)
        }
    }

    private fun parseLine(raw: String) {
        val line = raw.trim()
        if (!line.startsWith(prefix)) return
        val parts = line.removePrefix(prefix).split(':')
        if (parts.size != 2) return
        val index = parts[0].toIntOrNull() ?: return
        val exitCode = parts[1].toIntOrNull() ?: return
        onExitCode(index, exitCode)
    }
}
