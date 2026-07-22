package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.compare.PixelDiff
import com.github.dkwasniak.goldendiff.compare.TransparentBorder
import com.github.dkwasniak.goldendiff.compare.toArgbImage
import com.github.dkwasniak.goldendiff.compare.toBufferedImage
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.match.GenericScreenExtractor
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.project.FuzzyFileMatcher
import com.github.dkwasniak.goldendiff.project.ProjectFileIndex
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

/** Which list the left pane is showing. */
enum class Browse(val label: String) {
    CHANGED("Changed"),
    FILES("Project files"),
}

/** What the app is currently unable to do, if anything. */
sealed interface Blocker {
    data object NoGit : Blocker
    data object NotARepository : Blocker
    data object NoGoldenDirectories : Blocker
}

/**
 * All mutable state behind the window, so the composables stay declarative.
 *
 * Every operation that touches disk or git is dispatched to [Dispatchers.IO]; nothing here may run on
 * the UI thread, because a project-wide scan on a large repository takes long enough to freeze it.
 */
class AppState(private val scope: CoroutineScope) {

    var projectRoot by mutableStateOf<File?>(null)
        private set
    var config by mutableStateOf(GoldenDiffConfig())
        private set
    var blocker by mutableStateOf<Blocker?>(null)
        private set

    var browse by mutableStateOf(Browse.CHANGED)
    var source by mutableStateOf(BuiltInSource.WORKING_COPY)

    var fileIndex by mutableStateOf<ProjectFileIndex?>(null)
        private set
    var items by mutableStateOf<List<ExtraComparisonItem>>(emptyList())
        private set
    var selected by mutableStateOf<File?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    var comparison by mutableStateOf<Comparison?>(null)
        private set

    var quickOpenVisible by mutableStateOf(false)
    var quickOpenQuery by mutableStateOf("")

    val quickOpenResults: List<String>
        get() = fileIndex?.let { FuzzyFileMatcher.search(it.paths, quickOpenQuery, limit = 30).map { m -> m.path } }
            .orEmpty()

    data class Comparison(
        val old: ImageBitmap?,
        val new: ImageBitmap?,
        val diff: ImageBitmap?,
        val changedRatio: Double,
        val statusText: String,
        /** True when both sides are byte-identical, so the UI shows one preview instead of a diff. */
        val identical: Boolean,
    )

    fun openProject(root: File) {
        projectRoot = root
        config = AppConfig.load(root)
        AppConfig.rememberProject(root)
        selected = null
        comparison = null
        refresh()
        scope.launch {
            val index = withContext(Dispatchers.IO) { ProjectFileIndex.scan(root) }
            fileIndex = index
        }
    }

    fun updateConfig(newConfig: GoldenDiffConfig) {
        val root = projectRoot ?: return
        config = newConfig
        AppConfig.save(root, newConfig)
        refresh()
    }

    private fun scanner(root: File): ChangeScanner {
        val git = GitCli(root)
        // Unlike the plugin, which uses the IDE's VCS layer for HEAD reads, the app has only the CLI.
        return ChangeScanner(root, config, git, git)
    }

    fun refresh() {
        val root = projectRoot ?: return
        busy = true
        status = "Scanning…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val git = GitCli(root)
                when {
                    !git.isAvailable() -> Blocker.NoGit to emptyList()
                    !git.isInsideWorkTree() -> Blocker.NotARepository to emptyList()
                    !config.isConfigured -> Blocker.NoGoldenDirectories to emptyList()
                    else -> {
                        val scanner = scanner(root)
                        val found = when (source) {
                            BuiltInSource.GENERATED -> scanner.generatedChanges()
                            BuiltInSource.WORKING_COPY -> scanner.workingCopyChanges()
                        }
                        null to found
                    }
                }
            }
            blocker = result.first
            items = result.second
            busy = false
            status = when {
                result.first != null -> ""
                result.second.isEmpty() -> "No golden changes found."
                else -> "${result.second.size} changed golden(s)."
            }
        }
    }

    /** Selecting a source file replaces the editor the plugin relies on: it drives golden matching. */
    fun selectSourceFile(relativePath: String) {
        val root = projectRoot ?: return
        val file = File(root, relativePath)
        if (file.extension.equals("png", ignoreCase = true)) {
            select(file)
            return
        }
        busy = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val screen = GenericScreenExtractor.extract(
                    file.nameWithoutExtension,
                    runCatching { file.readText() }.getOrDefault(""),
                )
                val goldens = GoldenFinder.find(
                    config.resolvedGoldenPaths(root),
                    screen,
                    config.matchMode,
                    config.excludedSuffixes,
                    config.goldenFilePatterns,
                )
                scanner(root).itemsFor(goldens, source)
            }
            items = found
            busy = false
            status = if (found.isEmpty()) "No goldens match ${file.name}." else "${found.size} golden(s) for ${file.name}."
            select(found.firstOrNull()?.file)
        }
    }

    fun select(file: File?) {
        selected = file
        comparison = null
        val root = projectRoot ?: return
        if (file == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadComparison(root, file) }
            if (selected == file) comparison = result
        }
    }

    private fun loadComparison(root: File, golden: File): Comparison {
        val git = GitCli(root)
        val headBytes = git.headBytes(golden)
        val sourceFile = when (source) {
            BuiltInSource.WORKING_COPY -> golden
            BuiltInSource.GENERATED -> com.github.dkwasniak.goldendiff.compare.GeneratedImageSource.findForGolden(
                golden = golden,
                goldenRoots = config.resolvedGoldenPaths(root),
                generatedRoots = config.resolvedGeneratedPaths(root),
                generatedFileRegex = config.generatedFileRegex,
                excludedSuffixes = config.excludedSuffixes,
            )
        }
        val newBytes = sourceFile?.let(ImageBytes::workingBytes)

        // Comparing bytes before decoding is what makes "no change" cheap - two PNG decodes of a
        // large golden are not free, and most goldens in a change set are unchanged.
        val identical = headBytes != null && newBytes != null && headBytes.contentEquals(newBytes)

        val trim = config.trimTransparentPadding
        val oldImage = ImageBytes.decode(headBytes)?.let { trimIfEnabled(it, trim) }
        val newImage = ImageBytes.decode(newBytes)?.let { trimIfEnabled(it, trim) }

        val diff = if (!identical && oldImage != null && newImage != null) {
            PixelDiff.compute(oldImage.toArgbImage(), newImage.toArgbImage())
        } else {
            null
        }

        val text = when {
            identical -> "No changes vs HEAD — ${golden.name}"
            headBytes == null -> "New file (not in git HEAD) — ${golden.name}"
            newBytes == null -> "Working copy missing — showing HEAD."
            else -> golden.name
        }
        return Comparison(
            old = oldImage?.toComposeImageBitmap(),
            new = newImage?.toComposeImageBitmap(),
            diff = diff?.image?.toBufferedImage()?.toComposeImageBitmap(),
            changedRatio = diff?.changedRatio ?: 0.0,
            statusText = text,
            identical = identical,
        )
    }

    private fun trimIfEnabled(image: BufferedImage, enabled: Boolean): BufferedImage =
        TransparentBorder.trim(image, enabled) ?: image
}
