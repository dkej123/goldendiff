package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.platform.Os
import java.io.File

/**
 * Locates the JetBrains Runtime that Android Studio bundles, so the settings screen can offer it as a
 * one-click `JAVA_HOME` for the generate/verify commands. Android Studio runs Gradle on this JBR, so a
 * project whose toolchain is "whatever Android Studio uses" resolves against it — but a GUI-launched
 * shell never sees it, which is why verify fails from the app while it works from the IDE.
 */
object AndroidStudioJbr {

    /** Returns the absolute `Home` path of a usable bundled JBR, or null if none is found. */
    fun detect(): String? = candidateHomes().firstOrNull { File(it, "bin/java").canExecute() }

    private fun candidateHomes(): List<String> = when (Os.current) {
        Os.MAC -> macApplicationDirs().flatMap { apps ->
            // Stable, Beta, Canary and the JetBrains Toolbox all install under recognisable names.
            apps.listFiles { f -> f.isDirectory && f.name.startsWith("Android Studio") }.orEmpty()
                .sortedByDescending { it.name }
                .map { File(it, "Contents/jbr/Contents/Home").path }
        }
        Os.LINUX -> listOf(
            File(System.getProperty("user.home"), "android-studio/jbr").path,
            "/opt/android-studio/jbr",
            "/usr/local/android-studio/jbr",
        )
        Os.WINDOWS -> listOf(
            "C:\\Program Files\\Android\\Android Studio\\jbr",
        )
    }

    private fun macApplicationDirs(): List<File> = listOf(
        File("/Applications"),
        File(System.getProperty("user.home"), "Applications"),
    ).filter { it.isDirectory }
}
