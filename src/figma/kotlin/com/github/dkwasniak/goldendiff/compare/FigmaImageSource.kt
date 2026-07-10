package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.io.File
import javax.imageio.ImageIO

/** Figma-variant helpers for comparing Figma reference PNGs with current screenshot goldens. */
class FigmaImageSource : ExtraComparisonSource {
    override val id: String = "figma"
    override val title: String = "Figma"
    @Volatile
    private var lastListStatus: String? = null

    override fun createSettingsComponent(project: Project) =
        FigmaSettingsComponent(project)

    override fun findFiles(
        project: Project,
        screen: CurrentScreen.Screen,
        settings: ScreenshotSettings,
    ): List<File> =
        findItems(project, screen, settings).map { it.file }

    override fun findItems(
        project: Project,
        screen: CurrentScreen.Screen,
        settings: ScreenshotSettings,
    ): List<ExtraComparisonItem> {
        val basePath = project.basePath ?: return emptyList()
        val previews = figmaPreviews(project)
        val items = previews.mapNotNull { preview ->
            val file = findFigmaPngForFunction(preview.functionName, basePath)
                ?: FigmaReferenceCache.expectedFile(project, preview)
                ?: return@mapNotNull null
            ExtraComparisonItem(
                file = file,
                title = preview.functionName,
                isLoading = !file.isFile,
            )
        }
        lastListStatus = when {
            previews.isEmpty() -> "No figma-compose-preview @Preview functions found in this file."
            items.isEmpty() -> "No valid Figma references found in this file."
            items.any { it.isLoading } && FigmaTokenStore.get(project) == null ->
                "${items.size} Figma preview(s) found. Add a Figma token in Golden Diff settings to download missing references."
            items.any { it.isLoading } -> "${items.size} Figma preview(s) found. Missing references will download when selected."
            else -> null
        }
        return items
    }

    override fun listStatus(files: List<File>): String? = lastListStatus

    override fun loadComparison(
        project: Project,
        file: File,
        screen: CurrentScreen.Screen,
        settings: ScreenshotSettings,
    ): ExtraComparisonResult {
        val previews = figmaPreviews(project)
        val preview = findPreviewForFile(project, file, previews) ?: return ExtraComparisonResult.Single(
            GitImageSource.decode(GitImageSource.workingBytes(file)),
            "No figma-compose-preview function found for ${file.name}.",
        )
        val figmaFile = if (file.isFile) {
            file
        } else {
            val lookup = FigmaReferenceCache.findOrDownload(project, preview)
            if (lookup.file == null) {
                return ExtraComparisonResult.Single(null, "${preview.functionName}: ${lookup.message ?: "Figma reference could not be downloaded."}")
            }
            lookup.file
        }
        val goldenFile = findWorkingGolden(preview.functionName, settings.paths.map(::File), settings.excludedSuffixes)
        val figma = GitImageSource.decode(GitImageSource.workingBytes(figmaFile))
        val golden = GitImageSource.decode(goldenFile?.let(GitImageSource::workingBytes))
        return when {
            figma == null ->
                ExtraComparisonResult.Single(null, "Figma reference not found on disk: ${figmaFile.path}")
            goldenFile == null ->
                ExtraComparisonResult.Single(figma, "No screenshot found. Run screenshot tests to generate one.")
            golden == null ->
                ExtraComparisonResult.Single(figma, "Could not read screenshot: ${goldenFile.path}")
            else ->
                ExtraComparisonResult.Comparison(
                    old = figma,
                    new = golden,
                    statusText = "${file.name} ↔ ${goldenFile.name}",
                    oldLabel = "Figma",
                    newLabel = "Current Implementation",
                )
        }
    }

    private fun findPreviewForFile(project: Project, figmaFile: File, figmaPreviews: List<FigmaPreview>): FigmaPreview? {
        readPngTextChunks(figmaFile)["functionName"]?.let { functionName ->
            figmaPreviews.firstOrNull { it.functionName == functionName }?.let { return it }
        }
        return figmaPreviews.firstOrNull { preview ->
            FigmaReferenceCache.expectedFile(project, preview)?.absolutePath == figmaFile.absolutePath
        }
    }

    private fun findFigmaPngForFunction(functionName: String, projectBasePath: String): File? {
        val goldens = File(projectBasePath, "screenshotTests/src/test/figma_goldens")
        if (!goldens.isDirectory) return null
        return goldens.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .firstOrNull { readPngTextChunks(it)["functionName"] == functionName }
    }

    private fun findWorkingGolden(
        functionName: String,
        goldenRoots: List<File>,
        excludedSuffixes: List<String>,
    ): File? {
        val suffixes = excludedSuffixes.filter { it.isNotBlank() }
        return goldenRoots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .firstOrNull { file ->
                file.isFile &&
                    file.extension.equals("png", ignoreCase = true) &&
                    suffixes.none { file.nameWithoutExtension.endsWith(it) } &&
                    file.nameWithoutExtension.contains(functionName, ignoreCase = true)
            }
    }

    private fun figmaPreviews(project: Project): List<FigmaPreview> =
        runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction emptyList()
            parseFigmaPreviews(editor.document.text)
        }

    internal fun parseFigmaPreviews(fileText: String): List<FigmaPreview> {
        val result = mutableListOf<FigmaPreview>()
        val lines = fileText.lineSequence().toList()
        var index = 0
        while (index < lines.size) {
            if (!lines[index].contains("Generated by figma-compose-preview")) {
                index++
                continue
            }

            var sourceUrl: String? = null
            var sawPreviewAnnotation = false
            var lookahead = index + 1
            while (lookahead < lines.size && lookahead - index <= MAX_FIGMA_PREVIEW_BLOCK_LINES) {
                val line = lines[lookahead].trim()
                sourceUrl = sourceUrl ?: FIGMA_SOURCE_REGEX.find(line)?.groupValues?.get(1)
                if (line.startsWith("@Preview") || line.contains(".Preview(")) {
                    sawPreviewAnnotation = true
                }
                val functionMatch = FUN_REGEX.find(line)
                if (functionMatch != null) {
                    val functionName = functionMatch.groupValues[1]
                    if (sourceUrl != null && sawPreviewAnnotation) {
                        result += FigmaPreview(functionName, sourceUrl)
                    }
                    break
                }
                lookahead++
            }
            index = lookahead + 1
        }
        return result
    }

    private fun readPngTextChunks(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val iis = runCatching { ImageIO.createImageInputStream(file) }.getOrNull() ?: return result
        val readers = ImageIO.getImageReadersByFormatName("png")
        if (!readers.hasNext()) {
            iis.close()
            return result
        }
        val reader = readers.next()
        try {
            reader.setInput(iis, true)
            val meta = reader.getImageMetadata(0)
            val tree = meta.getAsTree("javax_imageio_png_1.0")
            var node = tree.firstChild
            while (node != null) {
                if (node.nodeName == "tEXt") {
                    var entry = node.firstChild
                    while (entry != null) {
                        val key = entry.attributes?.getNamedItem("keyword")?.nodeValue
                        val value = entry.attributes?.getNamedItem("value")?.nodeValue
                        if (key != null && value != null) result[key] = value
                        entry = entry.nextSibling
                    }
                }
                node = node.nextSibling
            }
        } finally {
            reader.dispose()
            iis.close()
        }
        return result
    }

    companion object {
        private val FIGMA_SOURCE_REGEX = Regex("""//\s*Figma source:\s*(\S+)""")
        private val FUN_REGEX = Regex("""fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private const val MAX_FIGMA_PREVIEW_BLOCK_LINES = 80
    }
}
