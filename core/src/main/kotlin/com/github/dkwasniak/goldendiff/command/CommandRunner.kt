package com.github.dkwasniak.goldendiff.command

import java.io.File

/**
 * Runs one user-configured shell command in the project directory, streaming its output line by line.
 *
 * The command is a free-form string the user typed (e.g. `./gradlew :screenshotTests:recordRoborazziDebug`),
 * so it is handed to the platform shell rather than tokenised here — the shell is what the user's
 * command was written for (globs, quoting, `&&`, environment). This mirrors how [GitCli] and the app's
 * update installer already shell out.
 *
 * Every call blocks on the child process and must run off any UI thread. stdout and stderr are merged
 * so the caller sees interleaved progress exactly as a terminal would; unlike git blob reads there is
 * no binary output to corrupt here.
 */
object CommandRunner {

    /** The outcome of a run: the process exit code, or a launch failure that never produced one. */
    data class Result(val exitCode: Int?, val failure: Throwable? = null) {
        val succeeded: Boolean get() = exitCode == 0
    }

    /**
     * Runs [command] in [workingDir], forwarding each output line to [onLine]. [isCancelled] is polled
     * between lines so a host can stop a long run by destroying the process. Returns a non-zero (or
     * null) [Result] rather than throwing, so callers can report the failure in the UI.
     */
    fun run(
        command: String,
        workingDir: File,
        isCancelled: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Result {
        val builder = ProcessBuilder(CommandShell.invocation(command))
            .directory(workingDir)
            .redirectErrorStream(true)
        return runCatching {
            val process = builder.start()
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    if (isCancelled()) {
                        process.destroy()
                        break
                    }
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
            Result(process.waitFor())
        }.getOrElse { Result(exitCode = null, failure = it) }
    }
}
