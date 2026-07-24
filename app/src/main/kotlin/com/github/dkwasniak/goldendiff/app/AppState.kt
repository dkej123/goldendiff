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
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.github.dkwasniak.goldendiff.telemetry.TelemetryClient
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpanStatus
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
class AppState(
    private val scope: CoroutineScope,
    val ui: UiPreferences = UiPreferences.load(),
    val telemetry: TelemetryClient,
) : AutoCloseable {

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

    // The project-tree row a single click highlights without opening it. Opening a file (double-click,
    // a tab, quick-open) realigns this to the opened path so the tree marks whatever is actually shown.
    var treeHighlight by mutableStateOf<String?>(null)

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
    var diagnosticsVisible by mutableStateOf(false)

    /** A newer app build on this install's channel, once the launch check has found one. */
    var update by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
        private set

    /** One-time launch banner for [update], shown until the user dismisses that version. */
    var updateBannerVisible by mutableStateOf(false)
        private set

    /** True while an update is being fetched/applied; disables the trigger and shows a spinner. */
    var updateBusy by mutableStateOf(false)
        private set

    /** Short human status while updating — the latest brew line, a phase, or an error. */
    var updateStatus by mutableStateOf<String?>(null)
        private set

    // Set once an in-place update finishes so the banner can offer a one-click restart into the new
    // build. Only the Homebrew path sets it — the download path still needs a manual install first.
    var updateCompleted by mutableStateOf(false)
        private set

    /**
     * The Homebrew cask this install can update through, detected once and cached. Null when the app
     * was not installed via Homebrew (the download-and-open path is used instead).
     */
    private val updateHomebrew: UpdateInstaller.HomebrewCask? by lazy {
        UpdateInstaller.homebrewCask(AppTelemetry.metadata.updateChannel)
    }

    /** Whether [installUpdate] will upgrade in place via Homebrew rather than download a `.dmg`. */
    val updateViaHomebrew: Boolean get() = updateHomebrew != null

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
    private var pendingProjectOpenTrigger: String? = null

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
        telemetry.event("product.feature_used", mapOf("feature" to "tab_close"))
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

    fun openProject(root: File, restored: Boolean = false) {
        pendingProjectOpenTrigger = if (restored) "restored" else "manual"
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
        refresh("automatic")
        scope.launch {
            val index = withContext(Dispatchers.IO) { ProjectFileIndex.scan(root) }
            fileIndex = index
        }
    }

    /**
     * Look for a newer build on this install's release channel. Runs once at launch; the check is
     * cached for a day and every failure is silent, so it never blocks or interrupts the app.
     */
    fun checkForUpdates() {
        scope.launch {
            val channel = AppTelemetry.metadata.updateChannel
            DevLog.record("update", "Checking (channel=${channel.wireValue}, current=${AppTelemetry.appVersion})")
            val info = withContext(Dispatchers.IO) {
                UpdateChecker.check(AppTelemetry.appVersion, channel)
            }
            if (info == null) {
                DevLog.record("update", "Up to date — no newer release found")
                return@launch
            }
            DevLog.record("update", "Available: ${info.version} (${info.url})")
            update = info
            updateBannerVisible = UpdateChecker.shouldShowBanner(info.version)
        }
    }

    /** Relaunch into the freshly-installed build. Offered once an in-place update has completed. */
    fun restartApp() {
        featureUsed("update_restart")
        DevLog.record("update", "Restarting into the new build")
        UpdateInstaller.restart()
    }

    /**
     * Remove the macOS quarantine flag from the updated bundle, then relaunch. The app is not
     * notarized, so a freshly-installed build is re-quarantined and may refuse to open; this runs the
     * `xattr` fix for the user instead of leaving it as a manual step.
     */
    fun removeQuarantineAndRestart() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Removing quarantine…"
        scope.launch {
            featureUsed("update_dequarantine")
            DevLog.record("update", "xattr -dr com.apple.quarantine, then restart")
            withContext(Dispatchers.IO) { UpdateInstaller.removeQuarantine() }
            UpdateInstaller.restart()
        }
    }

    /** Hide the launch banner and remember not to show it again for this version. */
    fun dismissUpdateBanner() {
        updateBannerVisible = false
        update?.let { UpdateChecker.rememberBannerDismissed(it.version) }
    }

    /** Open the release page for the available update in the user's browser. */
    fun openUpdatePage() {
        val info = update ?: return
        featureUsed("update_open")
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(info.url)) }
    }

    /**
     * Fetch and apply the available update. Homebrew installs upgrade in place and prompt to restart;
     * everything else downloads the `.dmg` and opens it. On any failure the release page opens as a
     * fallback. Guarded so a second click while [updateBusy] does nothing.
     */
    fun installUpdate() {
        val info = update ?: return
        if (updateBusy) return
        updateBusy = true
        updateStatus = null
        updateCompleted = false
        scope.launch {
            val cask = updateHomebrew
            if (cask != null) {
                featureUsed("update_homebrew")
                DevLog.record("update", "brew upgrade --cask ${cask.cask}")
                updateStatus = "Updating with Homebrew…"
                val exit = withContext(Dispatchers.IO) {
                    UpdateInstaller.upgradeViaHomebrew(cask) { line ->
                        if (line.isNotBlank()) updateStatus = line.trim()
                    }
                }
                DevLog.record("update", "brew finished with exit code $exit")
                if (exit == 0) {
                    updateStatus = "Updated — restart to finish."
                    updateCompleted = true
                } else {
                    updateStatus = "Homebrew update failed — opening the release page."
                    openUpdatePage()
                }
            } else {
                featureUsed("update_download")
                val url = UpdateInstaller.dmgUrl(info.version, AppTelemetry.metadata.updateChannel)
                DevLog.record("update", "Downloading $url")
                updateStatus = "Downloading…"
                val result = withContext(Dispatchers.IO) {
                    runCatching { UpdateInstaller.openDmg(UpdateInstaller.downloadDmg(url, info.version)) }
                }
                if (result.isSuccess) {
                    DevLog.record("update", "Installer opened from ~/Downloads")
                    updateStatus = "Opened installer — drag Golden Diff into Applications, then restart."
                } else {
                    DevLog.record("update", "Download failed: ${result.exceptionOrNull()?.message}")
                    updateStatus = "Download failed — opening the release page."
                    openUpdatePage()
                }
            }
            updateBusy = false
        }
    }

    fun updateConfig(newConfig: GoldenDiffConfig) {
        val root = projectRoot ?: return
        val old = config
        if (old == newConfig) return
        config = newConfig
        AppConfig.save(root, newConfig)
        telemetry.event(
            "product.configuration_saved",
            mapOf(
                "match_mode" to if (newConfig.matchMode.name == "ANNOTATED_METHOD") "annotated_method" else "file_class_regex",
                "golden_dir_count_bucket" to TelemetryBuckets.count(newConfig.goldenPaths.size),
                "generated_dir_count_bucket" to TelemetryBuckets.count(newConfig.generatedPaths.size),
                "generated_configured" to newConfig.generatedPaths.isNotEmpty().toString(),
                "trim_enabled" to newConfig.trimTransparentPadding.toString(),
                "changed_golden_dirs" to (old.goldenPaths != newConfig.goldenPaths).toString(),
                "changed_generated_dirs" to (old.generatedPaths != newConfig.generatedPaths).toString(),
                "changed_matching" to (
                    old.matchMode != newConfig.matchMode ||
                        old.annotatedFunctionRegex != newConfig.annotatedFunctionRegex ||
                        old.goldenFilePatterns != newConfig.goldenFilePatterns
                    ).toString(),
                "changed_filtering" to (old.excludedSuffixes != newConfig.excludedSuffixes).toString(),
                "changed_display" to (old.trimTransparentPadding != newConfig.trimTransparentPadding).toString(),
            ),
        )
        // Directories, matching and trim all change what is matched or how it decodes.
        clearCaches()
        refresh("config_change")
    }

    /** The toolbar Refresh: drop every cache and re-scan from disk. */
    fun forceRefresh() {
        clearCaches()
        refresh("manual_refresh")
    }

    fun deleteFile(file: File) {
        val root = projectRoot ?: return
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { file.isFile && file.delete() }
            telemetry.event(
                "product.golden_deleted",
                mapOf("result" to if (deleted) "success" else "failure", "source" to "working_copy"),
            )
            if (!deleted) {
                telemetry.event(
                    "product.operation_failed",
                    mapOf("operation" to "delete_file", "error_category" to "io", "retryable" to "true"),
                )
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
            refresh("automatic")
        }
    }

    private fun scanner(root: File): ChangeScanner {
        val git = GitCli(root)
        // Unlike the plugin, which uses the IDE's VCS layer for HEAD reads, the app has only the CLI.
        return ChangeScanner(root, config, git, git)
    }

    fun selectBrowse(value: Browse) {
        if (browse == value) return
        val previous = browse
        browse = value
        telemetry.event(
            "product.browse_scope_selected",
            mapOf("from" to previous.wireValue(), "to" to value.wireValue()),
        )
        selected = null
        comparison = null
        // Project-changes scope is not driven by an opened file, so it carries no tabs; returning to
        // Current-file scope restores the open file, if any, as its single tab.
        openTabs = if (value == Browse.FILES) listOfNotNull(selectedSourcePath) else emptyList()
        comparisonGeneration++
        refresh("scope_change")
    }

    fun selectSource(value: BuiltInSource) {
        if (source == value) return
        val previous = source
        source = value
        telemetry.event(
            "product.comparison_source_selected",
            mapOf("from" to previous.wireValue(), "to" to value.wireValue()),
        )
        selected = null
        comparison = null
        // The matched set and every comparison depend on the source; the caches no longer apply.
        clearCaches()
        comparisonGeneration++
        refresh("source_change")
    }

    fun refresh(trigger: String = "automatic") {
        val root = projectRoot ?: return
        val started = System.nanoTime()
        val generation = ++refreshGeneration
        val requestedBrowse = browse
        val requestedSource = source
        val requestedSourcePath = selectedSourcePath
        busy = true
        status = "Scanning…"
        scope.launch {
            val span = telemetry.startSpan(
                "golden.scan",
                mapOf("scope" to requestedBrowse.wireValue(), "source" to requestedSource.wireValue()),
            )
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val git = GitCli(root)
                    when {
                        !git.isAvailable() -> ScanOutcome(Blocker.NoGit, emptyList())
                        !git.isInsideWorkTree() -> ScanOutcome(Blocker.NotARepository, emptyList())
                        !config.isConfigured -> ScanOutcome(Blocker.NoGoldenDirectories, emptyList())
                        else -> {
                            val found = when (requestedBrowse) {
                                Browse.CHANGED -> {
                                    val scanner = scanner(root)
                                    when (requestedSource) {
                                        BuiltInSource.GENERATED -> telemetry.measureSpan(
                                            "generated.lookup",
                                            mapOf("scope" to "project_changes", "source" to "test_output"),
                                        ) { scanner.generatedChanges() }
                                        BuiltInSource.WORKING_COPY -> telemetry.measureSpan(
                                            "git.status",
                                            mapOf("scope" to "project_changes", "source" to "working_copy"),
                                        ) { scanner.workingCopyChanges() }
                                    }
                                }
                                Browse.FILES -> requestedSourcePath
                                    ?.let { findForSourceFile(root, it, requestedSource) }
                                    .orEmpty()
                            }
                            ScanOutcome(null, found)
                        }
                    }
                }.getOrElse { ScanOutcome(null, emptyList(), it) }
            }
            span.finish(if (outcome.error == null) TelemetrySpanStatus.OK else TelemetrySpanStatus.ERROR)
            if (generation != refreshGeneration) {
                return@launch
            }
            if (outcome.error != null) {
                telemetry.captureException(outcome.error, "app:scan", root)
                telemetry.event(
                    "product.operation_failed",
                    mapOf(
                        "operation" to "scan",
                        "error_category" to if (outcome.error is java.io.IOException) "io" else "internal",
                        "retryable" to "true",
                        "scope" to requestedBrowse.wireValue(),
                        "source" to requestedSource.wireValue(),
                    ),
                )
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val blockerValue = when (outcome.blocker) {
                Blocker.NoGit -> "no_git"
                Blocker.NotARepository -> "not_repository"
                Blocker.NoGoldenDirectories -> "no_configuration"
                null -> "none"
            }
            telemetry.event(
                "product.scan_completed",
                mapOf(
                    "trigger" to trigger,
                    "scope" to requestedBrowse.wireValue(),
                    "source" to requestedSource.wireValue(),
                    "result" to when {
                        outcome.error != null -> "failure"
                        outcome.blocker != null -> "blocked"
                        outcome.items.isEmpty() -> "success_empty"
                        else -> "success_nonempty"
                    },
                    "blocker" to blockerValue,
                    "duration_bucket" to TelemetryBuckets.duration(elapsedMs),
                    "item_count_bucket" to TelemetryBuckets.count(outcome.items.size),
                    "modified_count_bucket" to TelemetryBuckets.count(
                        outcome.items.count { it.status == ExtraComparisonItemStatus.MODIFIED },
                    ),
                    "new_count_bucket" to TelemetryBuckets.count(
                        outcome.items.count { it.status == ExtraComparisonItemStatus.NEW },
                    ),
                    "cache_hit" to "false",
                ),
            )
            pendingProjectOpenTrigger?.let { openTrigger ->
                telemetry.event(
                    "product.project_opened",
                    mapOf(
                        "trigger" to openTrigger,
                        "result" to when {
                            outcome.error != null -> "io_error"
                            outcome.blocker == Blocker.NoGit -> "no_git"
                            outcome.blocker == Blocker.NotARepository -> "not_repository"
                            else -> "success"
                        },
                        "configuration_present" to config.isConfigured.toString(),
                    ),
                )
                pendingProjectOpenTrigger = null
            }
            blocker = outcome.blocker
            items = outcome.items
            busy = false
            status = when {
                outcome.error != null -> "Could not scan this project."
                outcome.blocker != null -> ""
                requestedBrowse == Browse.FILES && requestedSourcePath == null -> "Choose a project file to find its screenshots."
                outcome.items.isEmpty() -> if (requestedBrowse == Browse.CHANGED) {
                    "No golden changes found."
                } else {
                    "No screenshots match ${File(requestedSourcePath.orEmpty()).name}."
                }
                requestedBrowse == Browse.CHANGED -> "${outcome.items.size} changed screenshot(s) found."
                else -> "${outcome.items.size} screenshot(s) found."
            }
            if (requestedBrowse == Browse.FILES && requestedSourcePath != null && outcome.blocker == null) {
                itemsCache[requestedSourcePath] = outcome.items
                restoreSelectionFor(requestedSourcePath, outcome.items)
            } else {
                select(outcome.items.firstOrNull()?.file, "automatic")
            }
        }
    }

    /** Selects the golden last viewed in this tab, if it still exists, otherwise the first one. */
    private fun restoreSelectionFor(path: String, list: List<ExtraComparisonItem>) {
        val remembered = tabSelection[path]?.takeIf { r -> list.any { it.file == r } }
        select(remembered ?: list.firstOrNull()?.file, "automatic")
    }

    /** Selecting a source file replaces the editor the plugin relies on: it drives golden matching. */
    fun selectSourceFile(relativePath: String, trigger: String = "project_tree") {
        val root = projectRoot ?: return
        val alreadyOpen = relativePath in openTabs
        browse = Browse.FILES
        selectedSourcePath = relativePath
        treeHighlight = relativePath
        // Opening a file adds a tab (an already-open file just reactivates its tab).
        if (relativePath !in openTabs) openTabs = openTabs + relativePath
        val file = File(root, relativePath)
        telemetry.sourceFileSelected(
            memoryKey = file.absolutePath,
            trigger = if (file.extension.equals("png", true)) "direct_png" else trigger,
            fileFamily = fileFamily(file.extension),
            alreadyOpen = alreadyOpen,
        )
        if (!alreadyOpen) telemetry.event("product.feature_used", mapOf("feature" to "tab_open"))
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
        refresh("automatic")
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
    ): List<ExtraComparisonItem> = telemetry.measureSpan(
        "golden.match",
        mapOf("scope" to "current_file", "source" to requestedSource.wireValue()),
    ) {
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
        scanner(root).itemsFor(goldens, requestedSource)
    }

    fun select(file: File?, selectionTrigger: String = "grid") {
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
            comparisonViewed(it, requestedSource, cacheHit = true, selectionTrigger)
            return
        }
        comparison = null
        scope.launch {
            val started = System.nanoTime()
            val span = telemetry.startSpan(
                "comparison.load",
                mapOf("scope" to browse.wireValue(), "source" to requestedSource.wireValue(), "cache_hit" to "false"),
            )
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loadComparison(root, file, requestedSource) }
            }
            span.finish(if (loaded.isSuccess) TelemetrySpanStatus.OK else TelemetrySpanStatus.ERROR)
            val result = loaded.getOrElse { error ->
                telemetry.captureException(error, "app:comparison_load", root)
                telemetry.event(
                    "product.operation_failed",
                    mapOf(
                        "operation" to "comparison_load",
                        "error_category" to if (error is java.io.IOException) "io" else "internal",
                        "retryable" to "true",
                        "scope" to browse.wireValue(),
                        "source" to requestedSource.wireValue(),
                    ),
                )
                Comparison(null, null, null, 0.0, "Could not read image.", false)
            }
            if (generation == comparisonGeneration && selected == file && source == requestedSource) {
                comparisonCache[comparisonKey(file, requestedSource)] = result
                comparison = result
                comparisonViewed(
                    result,
                    requestedSource,
                    cacheHit = false,
                    selectionTrigger = selectionTrigger,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                )
            }
        }
    }

    fun moveSelectionBy(delta: Int) {
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.file == selected }
        val start = current.takeIf { it >= 0 } ?: if (delta > 0) -1 else items.size
        val next = (start + delta).coerceIn(0, items.lastIndex)
        if (next != current) select(items[next].file, "keyboard")
    }

    private fun loadComparison(root: File, golden: File, requestedSource: BuiltInSource): Comparison {
        val git = GitCli(root)
        val headBytes = telemetry.measureSpan(
            "git.head_read",
            mapOf("scope" to browse.wireValue(), "source" to requestedSource.wireValue()),
        ) { git.headBytes(golden) }
        val sourceFile = when (requestedSource) {
            BuiltInSource.WORKING_COPY -> golden
            BuiltInSource.GENERATED -> telemetry.measureSpan(
                "generated.lookup",
                mapOf("scope" to browse.wireValue(), "source" to "test_output"),
            ) {
                com.github.dkwasniak.goldendiff.compare.GeneratedImageSource.findForGolden(
                    golden = golden,
                    goldenRoots = config.resolvedGoldenPaths(root),
                    generatedRoots = config.resolvedGeneratedPaths(root),
                    generatedFileRegex = config.generatedFileRegex,
                    excludedSuffixes = config.excludedSuffixes,
                )
            }
        }
        val newBytes = sourceFile?.let(ImageBytes::workingBytes)

        // Comparing bytes before decoding is what makes "no change" cheap - two PNG decodes of a
        // large golden are not free, and most goldens in a change set are unchanged.
        val identical = headBytes != null && newBytes != null && headBytes.contentEquals(newBytes)

        val trim = config.trimTransparentPadding
        val (oldImage, newImage) = telemetry.measureSpan(
            "image.decode",
            mapOf("scope" to browse.wireValue(), "source" to requestedSource.wireValue()),
        ) {
            ImageBytes.decode(headBytes)?.let { trimIfEnabled(it, trim) } to
                ImageBytes.decode(newBytes)?.let { trimIfEnabled(it, trim) }
        }

        val diff = if (!identical && oldImage != null && newImage != null) {
            telemetry.measureSpan(
                "pixel_diff.compute",
                mapOf("scope" to browse.wireValue(), "source" to requestedSource.wireValue()),
            ) { PixelDiff.compute(oldImage.toArgbImage(), newImage.toArgbImage()) }
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

    fun featureUsed(feature: String) {
        telemetry.event("product.feature_used", mapOf("feature" to feature))
    }

    fun toggleLeftPane() {
        ui.toggleLeftPane()
        featureUsed("left_pane_toggle")
    }

    fun compareModeSelected(mode: String, location: String) {
        telemetry.event("product.compare_mode_selected", mapOf("mode" to mode, "location" to location))
    }

    fun zoomSelected(zoom: String, action: String, location: String) {
        telemetry.event(
            "product.zoom_selected",
            mapOf("zoom" to zoom, "action" to action, "location" to location),
        )
    }

    private fun comparisonViewed(
        value: Comparison,
        requestedSource: BuiltInSource,
        cacheHit: Boolean,
        selectionTrigger: String,
        elapsedMs: Long = 0,
    ) {
        val dimensions = when {
            value.old == null || value.new == null -> "one_missing"
            value.old.width == value.new.width && value.old.height == value.new.height -> "same"
            else -> "different"
        }
        val result = when {
            value.old == null && value.new == null -> "decode_failed"
            value.identical -> "identical"
            value.old == null -> "new"
            value.new == null -> "missing_counterpart"
            else -> "modified"
        }
        telemetry.event(
            "product.comparison_viewed",
            mapOf(
                "source" to requestedSource.wireValue(),
                "result" to result,
                "load_duration_bucket" to TelemetryBuckets.duration(elapsedMs),
                "diff_ratio_bucket" to TelemetryBuckets.diffRatio(
                    value.changedRatio,
                    value.old != null && value.new != null,
                ),
                "dimensions" to dimensions,
                "cache_hit" to cacheHit.toString(),
                "selection_trigger" to selectionTrigger,
            ),
        )
        telemetry.activationCompleted(browse.wireValue(), requestedSource.wireValue())
    }

    override fun close() {
        telemetry.close()
    }

    private data class ScanOutcome(
        val blocker: Blocker?,
        val items: List<ExtraComparisonItem>,
        val error: Throwable? = null,
    )
}

private fun Browse.wireValue(): String = if (this == Browse.FILES) "current_file" else "project_changes"

private fun BuiltInSource.wireValue(): String =
    if (this == BuiltInSource.WORKING_COPY) "working_copy" else "test_output"

private fun fileFamily(extension: String): String = when (extension.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "jsx", "ts", "tsx" -> "js_ts"
    "swift" -> "swift"
    "png" -> "png"
    else -> "other"
}
