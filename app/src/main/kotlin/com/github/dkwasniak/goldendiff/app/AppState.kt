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
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

/** Mirrors the plugin's Scope control. */
enum class Browse(val label: String) {
    FILES("Current file"),
    CHANGED("Project changes"),
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
class AppState(private val scope: CoroutineScope, val ui: UiPreferences = UiPreferences.load()) {

    var projectRoot by mutableStateOf<File?>(null)
        private set
    var config by mutableStateOf(GoldenDiffConfig())
        private set
    var blocker by mutableStateOf<Blocker?>(null)
        private set

    var browse by mutableStateOf(Browse.FILES)
        private set
    var source by mutableStateOf(BuiltInSource.WORKING_COPY)
        private set

    var selectedSourcePath by mutableStateOf<String?>(null)
        private set

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
    var settingsVisible by mutableStateOf(false)
    var compareWindowVisible by mutableStateOf(false)

    /**
     * Project files the user has opened, shown as editor-style tabs above the panes.
     *
     * Relative paths (the same values as [selectedSourcePath]) — the file drives golden matching, so
     * a tab reopens that file rather than a single golden. Only meaningful in [Browse.FILES] scope.
     */
    var openTabs by mutableStateOf<List<String>>(emptyList())
        private set

    /** Free-text filter over the project tree; empty means "show everything". */
    var treeFilter by mutableStateOf("")

    /** Toolbar summary: "9 screenshots · 4 changed · 3 new", with the zero parts dropped. */
    val summaryText: String
        get() {
            if (items.isEmpty()) return ""
            val changed = items.count { it.status == ExtraComparisonItemStatus.MODIFIED }
            val added = items.count { it.status == ExtraComparisonItemStatus.NEW }
            return buildList {
                add("${items.size} screenshot${if (items.size == 1) "" else "s"}")
                if (changed > 0) add("$changed changed")
                if (added > 0) add("$added new")
            }.joinToString(" · ")
        }

    private var refreshGeneration = 0L
    private var comparisonGeneration = 0L

    // Per-tab state so switching between open files is instant instead of re-scanning and re-decoding.
    // Match results per opened file, the golden last selected in each, and decoded comparisons keyed
    // by golden + source + mtime + trim. Decoded thumbnails are cached the same way for the grid.
    // File mtime is part of every image key, so a re-run that rewrites a golden re-decodes on its own;
    // config edits and the Refresh button clear the caches wholesale via [clearCaches].
    private val itemsCache = HashMap<String, List<ExtraComparisonItem>>()
    private val tabSelection = HashMap<String, File>()
    private val comparisonCache = HashMap<String, Comparison>()
    val thumbnailCache = HashMap<String, ImageBitmap>()

    private fun comparisonKey(golden: File, source: BuiltInSource): String =
        "${source.name}|${golden.absolutePath}|${golden.lastModified()}|${config.trimTransparentPadding}"

    private fun clearCaches() {
        itemsCache.clear()
        comparisonCache.clear()
        thumbnailCache.clear()
    }

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
    ) {
        /**
         * The golden is committed but has no working-copy or generated counterpart.
         *
         * The compare pane shows an explicit error for this instead of a lone HEAD preview, which
         * would read as "unchanged" — the opposite of what happened.
         */
        val missingCounterpart: Boolean get() = old != null && new == null

        /** Both sides present and different: the four compare modes apply. */
        val hasDiff: Boolean get() = !identical && old != null && new != null
    }

    fun closeTab(path: String) {
        val index = openTabs.indexOf(path)
        if (index < 0) return
        val remaining = openTabs.toMutableList().apply { removeAt(index) }
        openTabs = remaining
        if (selectedSourcePath != path) return
        // Land on the tab that slides into the closed one's place, else the previous, else nothing.
        val next = remaining.getOrNull(index) ?: remaining.getOrNull(index - 1)
        if (next != null) selectSourceFile(next) else clearSelection()
    }

    /** Drops the current file selection and its comparison — used when the last tab closes. */
    private fun clearSelection() {
        selectedSourcePath = null
        selected = null
        comparison = null
        comparisonGeneration++
        items = emptyList()
        status = "Choose a project file to find its screenshots."
    }

    fun openProject(root: File) {
        projectRoot = root
        config = AppConfig.load(root)
        AppConfig.rememberProject(root)
        browse = Browse.FILES
        selectedSourcePath = null
        selected = null
        comparison = null
        openTabs = emptyList()
        treeFilter = ""
        clearCaches()
        tabSelection.clear()
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
        // Directories, matching and trim all change what is matched or how it decodes.
        clearCaches()
        refresh()
    }

    /** The toolbar Refresh: drop every cache and re-scan from disk. */
    fun forceRefresh() {
        clearCaches()
        refresh()
    }

    fun deleteFile(file: File) {
        val root = projectRoot ?: return
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { file.isFile && file.delete() }
            if (!deleted) {
                status = "Could not delete ${file.name}."
                return@launch
            }
            if (selected == file) {
                selected = null
                comparison = null
                comparisonGeneration++
            }
            tabSelection.values.removeAll { it == file }
            clearCaches()
            items = items.filterNot { it.file == file }
            fileIndex = withContext(Dispatchers.IO) { ProjectFileIndex.scan(root) }
            refresh()
        }
    }

    private fun scanner(root: File): ChangeScanner {
        val git = GitCli(root)
        // Unlike the plugin, which uses the IDE's VCS layer for HEAD reads, the app has only the CLI.
        return ChangeScanner(root, config, git, git)
    }

    fun selectBrowse(value: Browse) {
        if (browse == value) return
        browse = value
        selected = null
        comparison = null
        // Project-changes scope is not driven by an opened file, so it carries no tabs; returning to
        // Current-file scope restores the open file, if any, as its single tab.
        openTabs = if (value == Browse.FILES) listOfNotNull(selectedSourcePath) else emptyList()
        comparisonGeneration++
        refresh()
    }

    fun selectSource(value: BuiltInSource) {
        if (source == value) return
        source = value
        selected = null
        comparison = null
        // The matched set and every comparison depend on the source; the caches no longer apply.
        clearCaches()
        comparisonGeneration++
        refresh()
    }

    fun refresh() {
        val root = projectRoot ?: return
        val generation = ++refreshGeneration
        val requestedBrowse = browse
        val requestedSource = source
        val requestedSourcePath = selectedSourcePath
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
                        val found = when (requestedBrowse) {
                            Browse.CHANGED -> {
                                val scanner = scanner(root)
                                when (requestedSource) {
                                    BuiltInSource.GENERATED -> scanner.generatedChanges()
                                    BuiltInSource.WORKING_COPY -> scanner.workingCopyChanges()
                                }
                            }
                            Browse.FILES -> requestedSourcePath
                                ?.let { findForSourceFile(root, it, requestedSource) }
                                .orEmpty()
                        }
                        null to found
                    }
                }
            }
            if (generation != refreshGeneration) return@launch
            blocker = result.first
            items = result.second
            busy = false
            status = when {
                result.first != null -> ""
                requestedBrowse == Browse.FILES && requestedSourcePath == null -> "Choose a project file to find its screenshots."
                result.second.isEmpty() -> if (requestedBrowse == Browse.CHANGED) {
                    "No golden changes found."
                } else {
                    "No screenshots match ${File(requestedSourcePath.orEmpty()).name}."
                }
                requestedBrowse == Browse.CHANGED -> "${result.second.size} changed screenshot(s) found."
                else -> "${result.second.size} screenshot(s) found."
            }
            if (requestedBrowse == Browse.FILES && requestedSourcePath != null && result.first == null) {
                itemsCache[requestedSourcePath] = result.second
                restoreSelectionFor(requestedSourcePath, result.second)
            } else {
                select(result.second.firstOrNull()?.file)
            }
        }
    }

    /** Selects the golden last viewed in this tab, if it still exists, otherwise the first one. */
    private fun restoreSelectionFor(path: String, list: List<ExtraComparisonItem>) {
        val remembered = tabSelection[path]?.takeIf { r -> list.any { it.file == r } }
        select(remembered ?: list.firstOrNull()?.file)
    }

    /** Selecting a source file replaces the editor the plugin relies on: it drives golden matching. */
    fun selectSourceFile(relativePath: String) {
        val root = projectRoot ?: return
        browse = Browse.FILES
        selectedSourcePath = relativePath
        // Opening a file adds a tab (an already-open file just reactivates its tab).
        if (relativePath !in openTabs) openTabs = openTabs + relativePath
        val file = File(root, relativePath)
        if (file.extension.equals("png", ignoreCase = true)) {
            val single = listOf(ExtraComparisonItem(file, file.name))
            applyCachedItems(relativePath, single, "1 screenshot selected.")
            return
        }
        // Reopening a tab shows its remembered result immediately; the images come from the caches.
        itemsCache[relativePath]?.let { cached ->
            val label = if (cached.isEmpty()) "No screenshots match ${file.name}." else "${cached.size} screenshot(s) found."
            applyCachedItems(relativePath, cached, label)
            return
        }
        refresh()
    }

    /** Shows a tab's cached match list without a scan, cancelling any in-flight refresh. */
    private fun applyCachedItems(path: String, list: List<ExtraComparisonItem>, label: String) {
        refreshGeneration++
        itemsCache[path] = list
        blocker = null
        busy = false
        items = list
        status = label
        restoreSelectionFor(path, list)
    }

    private fun findForSourceFile(
        root: File,
        relativePath: String,
        requestedSource: BuiltInSource,
    ): List<ExtraComparisonItem> {
        val file = File(root, relativePath)
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
        return scanner(root).itemsFor(goldens, requestedSource)
    }

    fun select(file: File?) {
        selected = file
        val generation = ++comparisonGeneration
        val requestedSource = source
        val root = projectRoot
        if (file == null || root == null) {
            comparison = null
            return
        }
        // Remember the choice for the active tab so returning to it restores this golden.
        selectedSourcePath?.let { tabSelection[it] = file }
        // A cached comparison shows at once — no null flash, no re-decode.
        comparisonCache[comparisonKey(file, requestedSource)]?.let {
            comparison = it
            return
        }
        comparison = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadComparison(root, file, requestedSource) }
            if (generation == comparisonGeneration && selected == file && source == requestedSource) {
                comparisonCache[comparisonKey(file, requestedSource)] = result
                comparison = result
            }
        }
    }

    fun moveSelectionBy(delta: Int) {
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.file == selected }
        val start = current.takeIf { it >= 0 } ?: if (delta > 0) -1 else items.size
        val next = (start + delta).coerceIn(0, items.lastIndex)
        if (next != current) select(items[next].file)
    }

    private fun loadComparison(root: File, golden: File, requestedSource: BuiltInSource): Comparison {
        val git = GitCli(root)
        val headBytes = git.headBytes(golden)
        val sourceFile = when (requestedSource) {
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
