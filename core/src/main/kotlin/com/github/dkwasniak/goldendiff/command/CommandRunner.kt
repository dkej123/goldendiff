package com.github.dkwasniak.goldendiff.command

import java.io.File
import java.util.concurrent.TimeUnit

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

    /** How often [isCancelled] is checked while the child produces no output. */
    private const val CANCEL_POLL_MS = 100L

    /**
     * Runs [command] in [workingDir], forwarding each output line to [onLine]. [isCancelled] is polled
     * continuously — not just between output lines — so a host can stop a long run promptly even while
     * the child is silent (compiling, running tests). Returns a non-zero (or null) [Result] rather than
     * throwing, so callers can report the failure in the UI.
     *
     * [onLine] may be invoked from an internal reader thread rather than the caller's; it must be safe
     * to call off the caller's thread. All lines emitted before this returns are delivered first.
     *
     * When [javaHome] is set it is exported as `JAVA_HOME` (and prepended to `PATH`) for the child, so
     * a project whose toolchain lives outside the app's shell environment — e.g. Android Studio's
     * bundled JBR — resolves without the user editing their shell profile.
     */
    fun run(
        command: String,
        workingDir: File,
        javaHome: String? = null,
        isCancelled: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Result {
        val builder = ProcessBuilder(CommandShell.invocation(command))
            .directory(workingDir)
            .redirectErrorStream(true)
        javaHome?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val env = builder.environment()
            env["JAVA_HOME"] = home
            val binDir = File(home, "bin").path
            env.merge("PATH", binDir) { existing, added -> "$added${File.pathSeparator}$existing" }
        }
        return runCatching {
            val process = builder.start()
            // Drain output on a dedicated thread: reader.readLine() blocks until a line arrives, so
            // polling cancellation on this thread instead lets us kill a silent child without waiting
            // for its next line. Destroying the process closes the stream, ending the reader.
            val reader = Thread {
                process.inputStream.bufferedReader().use { r ->
                    runCatching {
                        while (true) onLine(r.readLine() ?: break)
                    }
                }
            }.apply { isDaemon = true; start() }

            var cancelled = false
            while (!process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    cancelled = true
                    process.destroyForcibly()
                    break
                }
            }
            // Let the reader flush whatever was buffered before we report the result. Exiting or being
            // killed closes the child's stdout, so readLine hits EOF and the thread ends on its own.
            reader.join()
            Result(if (cancelled) process.waitFor() else process.exitValue())
        }.getOrElse { Result(exitCode = null, failure = it) }
    }
}
