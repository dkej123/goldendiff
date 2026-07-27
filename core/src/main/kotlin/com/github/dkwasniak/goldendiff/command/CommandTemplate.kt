package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.match.Screen

/**
 * Expands the placeholders a per-file command template may contain into the concrete names of the
 * file the user is looking at.
 *
 * This is the single source of truth for which placeholders exist, so the settings windows can
 * document exactly what [expand] supports without the two drifting apart. Kept tool-agnostic on
 * purpose: the tool never assumes a framework, so the user composes whatever single-file filter
 * their tests need around these names — `--tests '*{file_name}Kt*'`, `-only-testing:Foo/{class_name}`,
 * `pytest {file_path}`, and so on.
 *
 * Placeholders that cannot be resolved (e.g. `{file_path}` with no known path) are left untouched
 * rather than blanked, so a misconfigured command fails loudly instead of silently dropping a filter.
 */
object CommandTemplate {

    const val FILE_NAME = "{file_name}"
    const val CLASS_NAME = "{class_name}"
    const val FILE_PATH = "{file_path}"

    /** Every placeholder [expand] understands, with a short description for UI help text. */
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        FILE_NAME to "current file name without extension",
        CLASS_NAME to "first class declared in the file (falls back to the file name)",
        FILE_PATH to "project-relative path of the file without extension",
    )

    /**
     * Substitutes the placeholders in [template] using [screen] and the optional [relativePath]
     * (project-relative, extension stripped). Order-independent, single pass per placeholder.
     */
    fun expand(template: String, screen: Screen, relativePath: String? = null): String {
        var result = template
        if (result.contains(FILE_NAME)) {
            result = result.replace(FILE_NAME, screen.fileName)
        }
        if (result.contains(CLASS_NAME)) {
            val className = screen.classNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: screen.fileName
            result = result.replace(CLASS_NAME, className)
        }
        if (relativePath != null && result.contains(FILE_PATH)) {
            result = result.replace(FILE_PATH, relativePath)
        }
        return result
    }
}
