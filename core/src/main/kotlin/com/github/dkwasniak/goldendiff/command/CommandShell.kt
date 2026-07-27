package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.platform.Os

/**
 * Wraps a free-form command string in the host shell.
 *
 * User commands are typed for a shell (globs, quoting, `&&`, PATH from the login profile), so both
 * runners — the app's [CommandRunner] and the plugin's IntelliJ `GeneralCommandLine` — hand the
 * command to the shell instead of tokenising it, and share this one definition so they behave the same.
 */
object CommandShell {

    /** The argv that runs [command] through the platform shell. */
    fun invocation(command: String): List<String> = when (Os.current) {
        // -l loads the login profile so SDK managers / PATH additions match the user's own terminal.
        Os.MAC, Os.LINUX -> listOf("/bin/bash", "-lc", command)
        Os.WINDOWS -> listOf("cmd", "/c", command)
    }
}
