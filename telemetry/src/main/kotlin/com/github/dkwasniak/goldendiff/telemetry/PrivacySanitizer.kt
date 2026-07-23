package com.github.dkwasniak.goldendiff.telemetry

import java.io.File

object PrivacySanitizer {
    private val windowsPath = Regex("""(?i)\b[A-Z]:[\\/](?:[^\\/\s]+[\\/])*[^\\/\s]*""")
    private val unixPath = Regex("""(?<![\w.])/(?:[^/\s]+/)+[^/\s]*""")
    private val fileUri = Regex("""(?i)file:(?:/{2,3})?\S+""")

    fun sanitize(text: String?, projectRoot: File? = null): String {
        var result = text.orEmpty()
        val replacements = buildList {
            System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(::add)
            projectRoot?.absolutePath?.takeIf(String::isNotBlank)?.let(::add)
        }.sortedByDescending(String::length)
        replacements.forEach { path ->
            result = result.replace(path, "<path>", ignoreCase = isWindowsPath(path))
            result = result.replace(path.replace('\\', '/'), "<path>", ignoreCase = isWindowsPath(path))
        }
        return result
            .replace(fileUri, "<path>")
            .replace(windowsPath, "<path>")
            .replace(unixPath, "<path>")
            .take(1_000)
    }

    fun sanitizedThrowable(throwable: Throwable, projectRoot: File? = null): Throwable =
        SanitizedTelemetryException(
            originalType = throwable.javaClass.simpleName.take(100),
            // Exception messages are arbitrary user-controlled strings and may contain a bare
            // project/file name without enough syntax to recognize it as a path. Keep the type and
            // reviewed stack frames, but discard the message entirely.
            sanitizedMessage = "",
        ).also { sanitized ->
            sanitized.stackTrace = throwable.stackTrace
                .filter { frame ->
                    frame.className.startsWith("com.github.dkwasniak.goldendiff.") ||
                        frame.className.startsWith("java.") ||
                        frame.className.startsWith("kotlin.") ||
                        frame.className.startsWith("kotlinx.coroutines.")
                }
                .take(80)
                .toTypedArray()
        }

    fun containsSensitiveValue(value: String): Boolean =
        fileUri.containsMatchIn(value) || windowsPath.containsMatchIn(value) || unixPath.containsMatchIn(value)

    private fun isWindowsPath(path: String): Boolean =
        path.length >= 2 && path[1] == ':'
}

class SanitizedTelemetryException(
    originalType: String,
    sanitizedMessage: String,
) : RuntimeException(
    listOf(originalType, sanitizedMessage).filter(String::isNotBlank).joinToString(": "),
)
