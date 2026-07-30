package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.dkwasniak.goldendiff.command.CommandRunner
import com.github.dkwasniak.goldendiff.command.CommandTemplate
import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.command.CompletionCommandKind
import com.github.dkwasniak.goldendiff.command.CompletionCommandScope
import com.github.dkwasniak.goldendiff.command.CompletionNotificationPolicy
import com.github.dkwasniak.goldendiff.command.CompletionResult
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
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.project.FuzzyFileMatcher
import com.github.dkwasniak.goldendiff.project.ProjectFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotSourceCache
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptFailure
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptMode
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptResult
import com.github.dkwasniak.goldendiff.telemetry.TelemetryClient
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandKind
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandResult
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandScope
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpanStatus
import com.github.dkwasniak.goldendiff.telemetry.acceptCompleted
import com.github.dkwasniak.goldendiff.telemetry.commandCompleted
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.github.dkwasniak.goldendiff.ui.CompareMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

internal fun snapshotViewGeneratorPaths(
    view: com.github.dkwasniak.goldendiff.project.SnapshotSourceFile?,
    parentPath: String,
): List<String> = view?.cases?.map(SnapshotCase::generatorPath).orEmpty()
    .ifEmpty { listOf(parentPath) }
    .distinct()
    .sortedWith(ProjectFileIndex.PATH_ORDER)

internal fun deduplicateSnapshotCommands(commands: List<String>): List<String> = commands.distinct()

internal data class GoldenTreeLocation(val parentPath: String, val caseId: String?)

internal data class QuickOpenResult(
    val path: String,
    val label: String,
    val caseId: String? = null,
)

internal fun snapshotQuickOpenResults(
    index: SnapshotFileIndex?,
    query: String,
    limit: Int = 30,
): List<QuickOpenResult> {
    val trimmed = query.trim()
    if (index == null || trimmed.isEmpty()) return emptyList()
    data class Scored(val result: QuickOpenResult, val score: Int)

    return index.files.asSequence()
        .flatMap { file ->
            sequence {
                FuzzyFileMatcher.score(file.relativePath, trimmed)?.let { score ->
                    yield(
                        Scored(
                            QuickOpenResult(file.relativePath, File(file.relativePath).name),
                            score,
                        ),
                    )
                }
                file.cases.forEach { case ->
                    FuzzyFileMatcher.score(case.methodName, trimmed)?.let { score ->
                        yield(Scored(QuickOpenResult(file.relativePath, case.methodName, case.id), score))
                    }
                }
            }
        }
        .sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.result.label.length }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.result.label }
                .thenBy(ProjectFileIndex.PATH_ORDER) { it.result.path },
        )
        .take(limit)
        .map { it.result }
        .toList()
}

internal fun goldenTreeLocation(
    index: SnapshotFileIndex?,
    preferredParentPath: String?,
    goldenPath: String,
): GoldenTreeLocation? {
    val normalized = File(goldenPath).normalize().path
    val preferred = index?.files?.firstOrNull {
        it.relativePath == preferredParentPath && normalized in it.goldenPaths
    }
    val owner = preferred ?: index?.files?.firstOrNull { normalized in it.goldenPaths } ?: return null
    return GoldenTreeLocation(
        owner.relativePath,
        owner.cases.firstOrNull { normalized in it.goldenPaths }?.id,
    )
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

    var selectedSourcePath by mutableStateOf<String?>(null)
        private set
    var selectedSnapshotCaseId by mutableStateOf<String?>(null)
        private set

    data class TreeRevealRequest(val sourcePath: String, val caseId: String?, val sequence: Long)

    // The project-tree row associated with the active comparison.
    var treeHighlight by mutableStateOf<String?>(null)
    var treeHighlightCaseId by mutableStateOf<String?>(null)
        private set
    var treeRevealRequest by mutableStateOf<TreeRevealRequest?>(null)
        private set
    private var treeRevealSequence = 0L

    var fileIndex by mutableStateOf<ProjectFileIndex?>(null)
        private set
    var snapshotFileIndex by mutableStateOf<SnapshotFileIndex?>(null)
        private set
    var snapshotIndexing by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<ExtraComparisonItem>>(emptyList())
        private set
    var mergedItems by mutableStateOf<List<MergedGoldenItem>>(emptyList())
        private set
    /**
     * Goldens accepted since the last scan, by absolute path. Drives the transient "Accepted" marker
     * in the list and compare pane so a promotion reads as confirmed without a disruptive rescan.
     * Cleared whenever the item list is rebuilt (scan, tab switch, project open).
     */
    var acceptedGoldens by mutableStateOf<Set<String>>(emptySet())
        private set
    var selected by mutableStateOf<File?>(null)
        private set
    data class GoldenScrollRequest(val path: String, val sequence: Long)
    var goldenScrollRequest by mutableStateOf<GoldenScrollRequest?>(null)
        private set
    private var goldenScrollSequence = 0L
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    var comparison by mutableStateOf<Comparison?>(null)
        private set
    var compareMode by mutableStateOf(CompareMode.TWO_UP)
        private set

    fun selectCompareMode(value: CompareMode, location: String) {
        if (compareMode == value) return
        compareMode = value
        compareModeSelected(
            when (value) {
                CompareMode.TWO_UP -> "side_by_side"
                CompareMode.SWIPE -> "swipe"
                CompareMode.ONION -> "onion"
                CompareMode.DIFF -> "diff"
            },
            location,
        )
    }

    // Screenshot-test commands. `commandStatus` is the one-line summary shown in the toolbar/status
    // bar; `commandLog` is the full streamed output the expandable log window renders.
    var commandRunning by mutableStateOf(false)
        private set
    // Which command is currently running, so the toolbar pair can spinner the exact button and dim
    // the other. Left in place after a run finishes; call sites only read these while `commandRunning`.
    var commandKind by mutableStateOf<CommandKind?>(null)
        private set
    var commandPerFile by mutableStateOf(false)
        private set
    var commandSourcePath by mutableStateOf<String?>(null)
        private set
    var commandCaseId by mutableStateOf<String?>(null)
        private set
    var commandLabel by mutableStateOf<String?>(null)
        private set
    var commandStatus by mutableStateOf<String?>(null)
        private set
    val commandLog = mutableStateListOf<String>()
    var commandLogVisible by mutableStateOf(false)

    // The status-bar chip stays after a run finishes so the last outcome (e.g. "Verify failed (exit 1)")
    // remains visible; the user dismisses it with the chip's ✕. Set false when a new run starts.
    var commandStatusDismissed by mutableStateOf(false)

    fun dismissCommandStatus() {
        commandStatusDismissed = true
    }

    @Volatile
    private var commandCancelled = false

    var quickOpenVisible by mutableStateOf(false)
    var quickOpenQuery by mutableStateOf("")
    var settingsVisible by mutableStateOf(false)
    var onboardingVisible by mutableStateOf(false)
    var compareWindowVisible by mutableStateOf(false)
    var diagnosticsVisible by mutableStateOf(false)

    sealed interface AcceptAllState {
        data class Confirm(val candidates: List<File>) : AcceptAllState
        data class Progress(val completed: Int, val total: Int) : AcceptAllState
        data class Success(val count: Int) : AcceptAllState
        data class Partial(val accepted: Int, val total: Int, val failed: List<GoldenAccept.BulkFailure>) : AcceptAllState
        data object NothingToAccept : AcceptAllState
    }

    var acceptAllState by mutableStateOf<AcceptAllState?>(null)
        private set

    val acceptableCurrentFileGoldens: List<File>
        get() = mergedItems.filter { it.hasAcceptableTestOutput }.map { it.golden }

    fun requestAcceptAll() {
        acceptAllState = acceptableCurrentFileGoldens
            .takeIf { it.isNotEmpty() }
            ?.let(AcceptAllState::Confirm)
            ?: AcceptAllState.NothingToAccept
    }

    fun dismissAcceptAll() {
        acceptAllState = null
    }

    fun confirmAcceptAll(candidates: List<File>) = runAcceptAll(candidates, retry = false)

    fun retryAcceptAll(failures: List<GoldenAccept.BulkFailure>) =
        runAcceptAll(failures.map { it.golden }, retry = true)

    private fun runAcceptAll(candidates: List<File>, retry: Boolean) {
        if (candidates.isEmpty()) {
            acceptAllState = AcceptAllState.NothingToAccept
            telemetry.acceptCompleted(
                TelemetryAcceptMode.BULK,
                TelemetryAcceptResult.NOTHING_TO_ACCEPT,
                TelemetryAcceptFailure.NONE,
                candidates = 0,
                accepted = 0,
                failed = 0,
                retry = retry,
            )
            return
        }
        val root = projectRoot
        acceptAllState = AcceptAllState.Progress(0, candidates.size)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                GoldenAccept.acceptAll(candidates, config, root) { completed, total ->
                    scope.launch { acceptAllState = AcceptAllState.Progress(completed, total) }
                }
            }
            result.accepted.forEach(::suppressPngEvent)
            acceptedGoldens = acceptedGoldens + result.accepted.map { it.absolutePath }
            result.accepted.forEach(::invalidateCachesFor)
            val acceptedPaths = result.accepted.mapTo(hashSetOf()) { it.normalize().path }
            mergedItems = mergedItems.map { item ->
                if (item.golden.normalize().path in acceptedPaths) {
                    item.copy(
                        workingCopy = item.workingCopy?.copy(status = ExtraComparisonItemStatus.MODIFIED),
                        testOutput = null,
                    )
                } else {
                    item
                }
            }
            items = mergedItems.toLegacyItems()
            selectedSourcePath?.let {
                val key = selectionKey(it)
                itemsCache[key] = items
                mergedItemsCache[key] = mergedItems
            }
            pngRevision++
            acceptAllState = if (result.failed.isEmpty()) {
                AcceptAllState.Success(result.accepted.size)
            } else {
                AcceptAllState.Partial(result.accepted.size, candidates.size, result.failed)
            }
            telemetry.acceptCompleted(
                TelemetryAcceptMode.BULK,
                when {
                    result.failed.isEmpty() -> TelemetryAcceptResult.SUCCESS
                    result.accepted.isNotEmpty() -> TelemetryAcceptResult.PARTIAL
                    else -> TelemetryAcceptResult.FAILURE
                },
                when (result.failed.firstOrNull()?.failure) {
                    null -> TelemetryAcceptFailure.NONE
                    GoldenAccept.Failure.NoGeneratedCounterpart -> TelemetryAcceptFailure.MISSING
                    is GoldenAccept.Failure.Io -> TelemetryAcceptFailure.IO
                },
                candidates = candidates.size,
                accepted = result.accepted.size,
                failed = result.failed.size,
                retry = retry,
            )
            selected?.takeIf { it.normalize().path in acceptedPaths }?.let { golden ->
                select(golden, "accept_all")
            }
            refreshSnapshotStatuses()
        }
    }

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

    private val onionOpacityByGolden = HashMap<String, Float>()

    val selectedMergedItem: MergedGoldenItem?
        get() = selected?.normalize()?.path?.let { path ->
            mergedItems.firstOrNull { it.golden.normalize().path == path }
        }

    val selectedSource: BuiltInSource
        get() = automaticGoldenSource(selectedMergedItem)

    fun onionOpacity(golden: File?): Float =
        golden?.absolutePath?.let { onionOpacityByGolden[it] } ?: 0.5f

    fun rememberOnionOpacity(golden: File?, value: Float) {
        golden?.absolutePath?.let { onionOpacityByGolden[it] = value }
    }

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
    private var snapshotIndexGeneration = 0L
    private data class CachedProjectIndexes(
        val files: ProjectFileIndex,
        val snapshots: SnapshotFileIndex?,
        val sources: SnapshotSourceCache,
    )
    private val projectIndexCache = object : LinkedHashMap<String, CachedProjectIndexes>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedProjectIndexes>?): Boolean =
            size > MAX_CACHED_PROJECTS
    }
    private var snapshotSourceCache = SnapshotSourceCache()

    // Per-source state so switching files is instant instead of re-scanning and re-decoding.
    // Match results per selected file, the golden last selected in each, and decoded comparisons keyed
    // by golden + source + mtime + trim. Decoded thumbnails are cached the same way for the grid.
    // File mtime is part of every image key, so a re-run that rewrites a golden re-decodes on its own;
    // Configuration changes clear the caches wholesale via [clearCaches].
    private val itemsCache = HashMap<String, List<ExtraComparisonItem>>()
    private val mergedItemsCache = HashMap<String, List<MergedGoldenItem>>()
    private val tabSelection = HashMap<String, File>()
    private val comparisonCache = HashMap<String, Comparison>()
    val thumbnailCache = HashMap<String, ImageBitmap>()

    /**
     * Last known width/height of each golden's thumbnail, keyed by absolute path.
     *
     * Kept out of [clearCaches] and never invalidated: it is only a layout hint, and a slightly
     * stale ratio still reserves roughly the right cell height. Without it a card that scrolls back
     * into view is first laid out at the placeholder ratio and then resizes once its bitmap arrives,
     * which changes the grid's content height under an in-flight scroll.
     */
    val thumbnailRatios = HashMap<String, Float>()
    var pngRevision by mutableStateOf(0L)
        private set
    private var pngWatcher: PngDirectoryWatcher? = null
    private var pngDebounceJob: Job? = null
    private val pendingPngEvents = mutableListOf<PngDirectoryWatcher.Event>()
    private var pendingPngOverflow = false
    private var pngUpdateGeneration = 0L
    private data class SuppressedPngWrite(val state: PngFileState, val expiresAt: Long)
    private val suppressedPngPaths = HashMap<String, SuppressedPngWrite>()

    private fun comparisonKey(golden: File, source: BuiltInSource): String {
        val variantMtime = mergedItems
            .firstOrNull { it.golden.normalize() == golden.normalize() }
            ?.variant(source)?.file?.lastModified()
            ?: golden.lastModified()
        return "${source.name}|${golden.absolutePath}|$variantMtime|${config.trimTransparentPadding}"
    }

    private fun clearCaches() {
        itemsCache.clear()
        mergedItemsCache.clear()
        comparisonCache.clear()
        thumbnailCache.clear()
    }

    private fun selectionKey(path: String, caseId: String? = selectedSnapshotCaseId): String =
        caseId ?: path

    internal val quickOpenResults: List<QuickOpenResult>
        get() = snapshotQuickOpenResults(snapshotFileIndex, quickOpenQuery)

    data class Comparison(
        val old: ImageBitmap?,
        val new: ImageBitmap?,
        val diff: ImageBitmap?,
        val changedRatio: Double,
        val statusText: String,
        /** True when both sides are byte-identical, so the UI shows one preview instead of a diff. */
        val identical: Boolean,
        /**
         * True right after the test output was accepted onto this golden. Drives the "Accepted"
         * banner in place of the plain "Unchanged" one, and shows the freshly written golden alone
         * (the generated counterpart it was promoted from is gone, so there is nothing to diff).
         */
        val accepted: Boolean = false,
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

    /** Drops the current source-file selection and its comparison. */
    private fun clearSelection() {
        selectedSourcePath = null
        selectedSnapshotCaseId = null
        selected = null
        comparison = null
        comparisonGeneration++
        items = emptyList()
        mergedItems = emptyList()
        acceptedGoldens = emptySet()
        status = "Choose a project file to find its screenshots."
    }

    fun openProject(root: File, restored: Boolean = false) {
        rememberCurrentProjectIndexes()
        pendingProjectOpenTrigger = if (restored) "restored" else "manual"
        val loadedConfig = AppConfig.load(root)
        val cached = projectIndexCache[projectCacheKey(root)]
        val cachedSnapshots = cached?.snapshots?.takeIf {
            it.fingerprint == SnapshotIndexFingerprint.from(root, loadedConfig)
        }
        // Invalidate both the visible tree and every in-flight index build before publishing the new
        // root. Otherwise Compose can briefly render the previous project's tree under the new name,
        // and a slower old scan can race the new one.
        val openingGeneration = ++snapshotIndexGeneration
        fileIndex = cached?.files
        snapshotFileIndex = cachedSnapshots
        val sourceCache = cached?.sources ?: SnapshotSourceCache()
        snapshotSourceCache = sourceCache
        snapshotIndexing = true
        projectRoot = root
        config = loadedConfig
        restartPngWatcher()
        AppConfig.rememberProject(root)
        selectedSourcePath = null
        selectedSnapshotCaseId = null
        treeHighlight = null
        treeHighlightCaseId = null
        treeRevealRequest = null
        selected = null
        comparison = null
        onionOpacityByGolden.clear()
        treeFilter = ""
        clearCaches()
        tabSelection.clear()
        refresh("automatic")
        scope.launch {
            val (index, refreshed) = withContext(Dispatchers.IO) {
                val scanned = ProjectFileIndex.scan(root)
                val reused = cachedSnapshots
                    ?.takeIf { sourceCache.isCurrent(scanned, loadedConfig) }
                    ?.let { existing ->
                        val allGoldens = GoldenFinder.findAll(
                            loadedConfig.resolvedGoldenPaths(root),
                            loadedConfig.excludedSuffixes,
                        )
                        val inventory = allGoldens.mapTo(linkedSetOf()) { it.normalize().path }
                        if (inventory != existing.goldenInventory) {
                            null
                        } else {
                            SnapshotFileIndex.refreshStatuses(
                                existing,
                                scanner(root, loadedConfig).mergedItemsFor(allGoldens),
                            )
                        }
                    }
                scanned to reused
            }
            if (projectRoot != root || snapshotIndexGeneration != openingGeneration) return@launch
            fileIndex = index
            if (refreshed != null) {
                snapshotFileIndex = refreshed
                snapshotIndexing = false
                projectIndexCache[projectCacheKey(root)] = CachedProjectIndexes(index, refreshed, sourceCache)
                refreshed.files.firstOrNull()?.let { selectSourceFile(it.relativePath, "automatic") }
            } else {
                rebuildSnapshotIndex(index, selectFirst = true)
            }
        }
    }

    private suspend fun rebuildSnapshotIndex(index: ProjectFileIndex, selectFirst: Boolean = false) {
        val root = projectRoot ?: return
        if (index.root.normalize() != root.normalize()) return
        val requestedConfig = config
        val sourceCache = snapshotSourceCache
        val generation = ++snapshotIndexGeneration
        snapshotIndexing = true
        val rebuilt = withContext(Dispatchers.IO) {
            val scanner = scanner(root, requestedConfig)
            val allGoldens = GoldenFinder.findAll(
                requestedConfig.resolvedGoldenPaths(root),
                requestedConfig.excludedSuffixes,
            )
            SnapshotFileIndex.build(index, requestedConfig, scanner.mergedItemsFor(allGoldens), sourceCache)
        }
        if (
            generation != snapshotIndexGeneration ||
            projectRoot != root ||
            SnapshotIndexFingerprint.from(root, config) != rebuilt.fingerprint
        ) {
            return
        }
        snapshotFileIndex = rebuilt
        snapshotIndexing = false
        projectIndexCache[projectCacheKey(root)] = CachedProjectIndexes(index, rebuilt, sourceCache)
        val selectedPath = selectedSourcePath
        when {
            selectedPath != null && rebuilt.files.any { it.relativePath == selectedPath } -> {
                selectedSnapshotCaseId = selectedSnapshotCaseId
                    ?.takeIf { id -> rebuilt.files.any { file -> file.cases.any { it.id == id } } }
            }
            selectFirst -> rebuilt.files.firstOrNull()?.let { selectSourceFile(it.relativePath, "automatic") }
            selectedPath != null -> clearSelection()
        }
    }

    private fun rememberCurrentProjectIndexes() {
        val root = projectRoot ?: return
        val files = fileIndex ?: return
        projectIndexCache[projectCacheKey(root)] =
            CachedProjectIndexes(files, snapshotFileIndex, snapshotSourceCache)
    }

    private fun projectCacheKey(root: File): String =
        runCatching { root.canonicalPath }.getOrElse { root.normalize().absolutePath }

    /** Keeps a stale tab selectable without trying to scan a source file that no longer exists. */
    private fun showMissingSourceFile(relativePath: String) {
        refreshGeneration++
        comparisonGeneration++
        blocker = null
        busy = false
        selected = null
        comparison = null
        items = emptyList()
        mergedItems = emptyList()
        acceptedGoldens = emptySet()
        status = "${File(relativePath).name} no longer exists."
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
        AppConfig.save(root, newConfig).exceptionOrNull()?.let { error ->
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "unknown I/O error"
            status = "Could not save settings: $detail"
            return
        }
        config = newConfig
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
        applyConfigChanges(root, old, newConfig, "config_change")
    }

    /**
     * Picks up settings written by the standalone CLI without reopening the project.
     *
     * The signal may belong to another project configured by a separate terminal, in which case this
     * window stays where it is. Command-only changes need only replace the observable config (Compose
     * immediately enables the matching toolbar actions); settings that affect matching or rendering
     * also invalidate caches and rescan the current project.
     */
    fun reloadConfigFromDisk(signaledProject: File): Boolean {
        val root = projectRoot ?: return false
        val currentPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val signaledPath = runCatching { signaledProject.canonicalPath }.getOrElse { signaledProject.absolutePath }
        if (currentPath != signaledPath) return false

        val old = config
        val reloaded = AppConfig.load(root)
        if (old == reloaded) return false
        config = reloaded
        applyConfigChanges(root, old, reloaded, "cli_config_change")
        return true
    }

    /**
     * Invalidates only the layer affected by a settings change. Command edits are configuration-only;
     * image presentation edits evict decoded bitmaps; generated-output edits rescan statuses; only
     * source-to-golden association changes rebuild the snapshot method index.
     */
    private fun applyConfigChanges(
        root: File,
        old: GoldenDiffConfig,
        updated: GoldenDiffConfig,
        trigger: String,
    ) {
        val rootsChanged =
            old.goldenPaths != updated.goldenPaths ||
                old.generatedPaths != updated.generatedPaths
        if (rootsChanged) restartPngWatcher()

        val associationsChanged =
            SnapshotIndexFingerprint.from(root, old) != SnapshotIndexFingerprint.from(root, updated)
        val generatedMatchingChanged =
            old.generatedPaths != updated.generatedPaths ||
                old.generatedFileRegex != updated.generatedFileRegex
        when {
            associationsChanged -> {
                clearCaches()
                refresh(trigger)
                fileIndex?.let { index -> scope.launch { rebuildSnapshotIndex(index) } }
            }
            generatedMatchingChanged -> {
                clearCaches()
                refresh(trigger)
                scope.launch { refreshSnapshotStatuses() }
            }
            old.trimTransparentPadding != updated.trimTransparentPadding -> {
                comparisonCache.clear()
                thumbnailCache.clear()
                pngRevision++
                selected?.let { select(it, "config_change") }
            }
        }
    }

    private fun restartPngWatcher() {
        pngWatcher?.close()
        pngWatcher = null
        val root = projectRoot ?: return
        val configuredRoots = if (config.goldenPaths.any { GoldenDiffConfig.goldenDirectoryRegex(it) != null }) {
            listOf(root)
        } else {
            config.resolvedGoldenPaths(root) + config.resolvedGeneratedPaths(root)
        }
        val watchRoots = configuredRoots.map { nearestExistingDirectory(it, root) }.distinctBy { it.absolutePath }
        pngWatcher = PngDirectoryWatcher(watchRoots) { events, overflow ->
            scope.launch { queuePngEvents(events, overflow) }
        }
    }

    private fun nearestExistingDirectory(path: File, projectRoot: File): File {
        var candidate: File? = path
        val normalizedRoot = projectRoot.normalize()
        while (candidate != null && !candidate.isDirectory && candidate.normalize() != normalizedRoot) {
            candidate = candidate.parentFile
        }
        return candidate?.takeIf { it.isDirectory } ?: normalizedRoot
    }

    private fun queuePngEvents(events: List<PngDirectoryWatcher.Event>, overflow: Boolean) {
        pendingPngEvents += events
        pendingPngOverflow = pendingPngOverflow || overflow
        pngDebounceJob?.cancel()
        pngDebounceJob = scope.launch {
            delay(PNG_DEBOUNCE_MS)
            val batch = pendingPngEvents.toList()
            val overflowed = pendingPngOverflow
            pendingPngEvents.clear()
            pendingPngOverflow = false
            applyPngEvents(batch, overflowed)
        }
    }

    private fun applyPngEvents(events: List<PngDirectoryWatcher.Event>, overflow: Boolean) {
        // Generate/Verify may write hundreds of files over several seconds. Their completion path
        // already performs one refresh, so intermediate watcher batches would only waste work.
        if (commandRunning) return
        val root = projectRoot ?: return
        val now = System.currentTimeMillis()
        suppressedPngPaths.entries.removeIf { it.value.expiresAt < now }
        val relevant = events
            .distinctBy { "${it.kind}:${it.file.absolutePath}" }
            .filterNot { event -> suppressesExpectedPngWrite(event.file, now) }
            .filter { event ->
                config.containsGoldenFile(root, event.file) ||
                    config.containsGeneratedFile(root, event.file)
            }
        val refreshMode = pngRefreshMode(events = relevant, overflow = overflow)
        if (refreshMode == PngRefreshMode.NONE) return

        relevant.forEach { invalidateCachesFor(it.file) }
        pngRevision++
        if (refreshMode == PngRefreshMode.RESCAN) {
            selectedSourcePath?.let { itemsCache.remove(selectionKey(it)) }
            refresh("filesystem_change")
            fileIndex?.let { index -> scope.launch { rebuildSnapshotIndex(index) } }
            return
        }

        val visibleByPath = items.associateBy { it.file.absolutePath }
        val affected = linkedSetOf<File>()
        relevant.forEach { event ->
            visibleByPath[event.file.absolutePath]?.file?.let(affected::add)
            if (config.containsGeneratedFile(root, event.file)) {
                generatedGoldenBase(event.file)?.let { base ->
                    items.asSequence()
                        .filter { it.file.nameWithoutExtension.equals(base, ignoreCase = true) }
                        .mapTo(affected) { it.file }
                }
            }
        }
        if (affected.isEmpty()) return
        refreshItemsInPlace(affected)
    }

    private fun refreshItemsInPlace(affected: Set<File>) {
        val root = projectRoot ?: return
        val generation = ++pngUpdateGeneration
        scope.launch {
            val (refreshedMerged, indexedStatuses) = withContext(Dispatchers.IO) {
                val changeScanner = scanner(root)
                val visible = changeScanner.mergedItemsFor(affected.toList()).associateBy { it.golden.absolutePath }
                val indexed = snapshotFileIndex?.files
                    ?.flatMap { it.goldenPaths }
                    ?.distinct()
                    ?.let { paths -> changeScanner.mergedItemsFor(paths.map(::File)) }
                    .orEmpty()
                visible to indexed
            }
            if (generation != pngUpdateGeneration) return@launch
            mergedItems = mergedItems.map { item -> refreshedMerged[item.golden.absolutePath] ?: item }
            items = mergedItems.toLegacyItems()
            selectedSourcePath?.let {
                val key = selectionKey(it)
                itemsCache[key] = items
                mergedItemsCache[key] = mergedItems
            }
            val selectedFile = selected
            if (selectedFile != null && selectedFile in affected) {
                invalidateCachesFor(selectedFile)
                select(selectedFile, "filesystem", keepCurrentPreview = true)
            }
            snapshotFileIndex?.let { current ->
                snapshotFileIndex = SnapshotFileIndex.refreshStatuses(current, indexedStatuses)
            }
        }
    }

    private fun generatedGoldenBase(file: File): String? {
        val regex = runCatching { Regex(config.normalizedForMatching().generatedFileRegex) }.getOrNull() ?: return null
        val match = regex.matchEntire(file.name) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun suppressPngEvent(file: File) {
        suppressedPngPaths[file.absolutePath] = SuppressedPngWrite(
            state = pngFileState(file),
            expiresAt = System.currentTimeMillis() + PNG_SUPPRESSION_MS,
        )
    }

    private fun suppressesExpectedPngWrite(file: File, now: Long): Boolean {
        val expected = suppressedPngPaths[file.absolutePath] ?: return false
        if (expected.expiresAt < now) {
            suppressedPngPaths.remove(file.absolutePath)
            return false
        }
        val unchanged = pngFileState(file) == expected.state
        if (!unchanged) suppressedPngPaths.remove(file.absolutePath)
        return unchanged
    }

    // region screenshot-test commands

    enum class CommandKind(val label: String) { GENERATE("Generate"), VERIFY("Verify") }

    fun commandFor(kind: CommandKind, perFile: Boolean): String = when (kind) {
        CommandKind.GENERATE -> if (perFile) config.generateFileCommand else config.generateAllCommand
        CommandKind.VERIFY -> if (perFile) config.verifyFileCommand else config.verifyAllCommand
    }

    /** Whether a command is available (so the toolbar can enable the option). */
    fun hasCommand(kind: CommandKind, perFile: Boolean): Boolean = commandFor(kind, perFile).isNotBlank()

    /**
     * Runs the Generate/Verify command for the whole project or the current file. The raw command is
     * expanded with the open file's names for the per-file variant, streamed line by line into
     * [commandLog], and followed by a rescan (automatically showing a failing Test output after Verify).
     */
    fun runCommand(kind: CommandKind, perFile: Boolean) {
        val path = selectedSourcePath
        if (kind == CommandKind.VERIFY && perFile && path != null) {
            runVerifyView(path)
        } else {
            runCommand(kind, perFile, path)
        }
    }

    /** Runs a per-file command against [relativePath], independently of the current selection. */
    fun runFileCommand(kind: CommandKind, relativePath: String) =
        if (kind == CommandKind.VERIFY) runVerifyView(relativePath) else
            runCommand(kind, perFile = true, relativePath = relativePath)

    fun hasVerifyCaseCommand(): Boolean =
        hasCaseCommand(CommandKind.VERIFY)

    fun hasCaseCommand(kind: CommandKind): Boolean = when (kind) {
        CommandKind.GENERATE -> config.generateFileCommand.isNotBlank()
        CommandKind.VERIFY -> config.verifyCaseCommand.isNotBlank() || config.verifyFileCommand.isNotBlank()
    }

    fun runVerifyCase(parentPath: String, caseId: String) =
        runCaseCommand(CommandKind.VERIFY, parentPath, caseId)

    fun runCaseCommand(kind: CommandKind, parentPath: String, caseId: String) {
        val case = snapshotFileIndex?.files
            ?.firstOrNull { it.relativePath == parentPath }
            ?.cases?.firstOrNull { it.id == caseId } ?: return
        val template = when (kind) {
            CommandKind.GENERATE -> config.generateFileCommand
            CommandKind.VERIFY -> config.verifyCaseCommand.ifBlank { config.verifyFileCommand }
        }
        if (template.isBlank()) return
        val command = expandSnapshotCommand(template, parentPath, case.generatorPath, case)
        startCommands(kind, parentPath, case.id, listOf(command), "${kind.label} (current case)")
    }

    fun runVerifyView(relativePath: String) {
        val template = config.verifyFileCommand
        if (template.isBlank()) return
        val view = snapshotFileIndex?.files?.firstOrNull { it.relativePath == relativePath }
        val commands = snapshotViewGeneratorPaths(view, relativePath).map { generator ->
            expandSnapshotCommand(template, relativePath, generator, null)
        }.let(::deduplicateSnapshotCommands)
        startCommands(CommandKind.VERIFY, relativePath, null, commands, "Verify (current file)")
    }

    private fun runCommand(kind: CommandKind, perFile: Boolean, relativePath: String?) {
        if (commandRunning) return
        val root = projectRoot ?: return
        val template = commandFor(kind, perFile)
        if (template.isBlank()) return
        val command = if (perFile) {
            val file = relativePath?.let { File(root, it) }?.takeIf { it.isFile } ?: return
            val screen = GenericScreenExtractor.extract(
                file.nameWithoutExtension,
                runCatching { file.readText() }.getOrDefault(""),
                config.normalizedForMatching().annotatedFunctionRegex,
            )
            CommandTemplate.expand(template, screen, relativePath.substringBeforeLast('.'))
        } else {
            template
        }

        startCommands(
            kind = kind,
            relativePath = relativePath.takeIf { perFile },
            caseId = null,
            commands = listOf(command),
            label = "${kind.label}${if (perFile) " (current file)" else ""}",
            perFile = perFile,
        )
    }

    private fun expandSnapshotCommand(
        template: String,
        parentPath: String,
        generatorPath: String,
        case: SnapshotCase?,
    ): String {
        val root = projectRoot ?: return template
        fun screen(path: String) = File(root, path).let { file ->
            GenericScreenExtractor.extract(
                file.nameWithoutExtension,
                runCatching { file.readText() }.getOrDefault(""),
                config.normalizedForMatching().annotatedFunctionRegex,
                includeTestFunctions = true,
            )
        }
        val parent = screen(parentPath)
        val generator = screen(generatorPath)
        return CommandTemplate.expand(
            template,
            parent,
            parentPath.substringBeforeLast('.', parentPath),
            CommandTemplate.SnapshotContext(
                fileName = File(generatorPath).nameWithoutExtension,
                className = case?.className ?: generator.classNames.firstOrNull(),
                filePath = generatorPath.substringBeforeLast('.', generatorPath),
                methodName = case?.methodName ?: CommandTemplate.SNAPSHOT_METHOD_NAME,
            ),
        )
    }

    private fun startCommands(
        kind: CommandKind,
        relativePath: String?,
        caseId: String?,
        commands: List<String>,
        label: String,
        perFile: Boolean = true,
    ) {
        if (commandRunning || commands.isEmpty()) return
        val root = projectRoot ?: return
        commandCancelled = false
        commandStatusDismissed = false
        commandRunning = true
        commandKind = kind
        commandPerFile = perFile
        commandSourcePath = relativePath
        commandCaseId = caseId
        commandLabel = label
        commandStatus = "Running…"
        commandLog.clear()
        featureUsed(if (kind == CommandKind.GENERATE) "generate_run" else "verify_run")

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                buildList {
                    commands.forEachIndexed { index, command ->
                        if (commandCancelled) return@forEachIndexed
                        scope.launch {
                            if (commands.size > 1) commandLog.add("— ${index + 1}/${commands.size} —")
                            commandLog.add("$ $command")
                        }
                        add(
                            CommandRunner.run(
                                command = command,
                                workingDir = root,
                                javaHome = config.commandJavaHome.ifBlank { null },
                                isCancelled = { commandCancelled },
                            ) { line ->
                                // The composition scope dispatches mutations onto the Compose UI thread.
                                scope.launch {
                                    commandLog.add(line)
                                    commandStatus = line
                                }
                            },
                        )
                    }
                }
            }
            commandRunning = false
            val succeeded = results.isNotEmpty() && results.all(CommandRunner.Result::succeeded)
            commandStatus = when {
                commandCancelled -> "${kind.label} cancelled."
                succeeded -> "${kind.label} finished (${results.size}/${commands.size})."
                else -> "${kind.label} finished with failures (${results.count(CommandRunner.Result::succeeded)}/${commands.size})."
            }
            commandLog.add(commandStatus.orEmpty())
            telemetry.commandCompleted(
                if (kind == CommandKind.GENERATE) {
                    TelemetryCommandKind.GENERATE
                } else {
                    TelemetryCommandKind.VERIFY
                },
                when {
                    !perFile -> TelemetryCommandScope.PROJECT
                    caseId != null -> TelemetryCommandScope.CASE
                    else -> TelemetryCommandScope.VIEW
                },
                when {
                    commandCancelled -> TelemetryCommandResult.CANCELLED
                    succeeded -> TelemetryCommandResult.SUCCESS
                    results.any(CommandRunner.Result::succeeded) -> TelemetryCommandResult.PARTIAL_FAILURE
                    results.isEmpty() || results.all { it.exitCode == null } ->
                        TelemetryCommandResult.COULD_NOT_START
                    else -> TelemetryCommandResult.FAILURE
                },
                commandCount = commands.size,
            )
            CompletionNotificationPolicy.decide(
                enabled = config.notifyWhenTasksFinish,
                hostForeground = MacNotifications.hostForeground(),
                kind = if (kind == CommandKind.GENERATE) {
                    CompletionCommandKind.GENERATE
                } else {
                    CompletionCommandKind.VERIFY
                },
                scope = if (perFile) {
                    CompletionCommandScope.File(relativePath?.let(::File)?.name ?: "Current file")
                } else {
                    CompletionCommandScope.Project(root.name)
                },
                result = when {
                    commandCancelled -> CompletionResult.CANCELLED
                    succeeded -> CompletionResult.SUCCESS
                    else -> CompletionResult.FAILURE
                },
            )?.let { MacNotifications.deliver(it.title, it.body) }
            // Show the freshly written output without a manual refresh.
            clearCaches()
            restartPngWatcher()
            refresh("command_finished")
            if (kind == CommandKind.GENERATE) {
                fileIndex?.let { rebuildSnapshotIndex(it) }
            } else {
                refreshSnapshotStatuses()
            }
        }
    }

    private suspend fun refreshSnapshotStatuses() {
        val root = projectRoot ?: return
        val current = snapshotFileIndex ?: return
        val statuses = withContext(Dispatchers.IO) {
            scanner(root).mergedItemsFor(current.files.flatMap { it.goldenPaths }.distinct().map(::File))
        }
        if (projectRoot == root && snapshotFileIndex?.fingerprint == current.fingerprint) {
            snapshotFileIndex = SnapshotFileIndex.refreshStatuses(current, statuses)
        }
    }

    fun cancelCommand() {
        if (commandRunning) commandCancelled = true
    }

    /**
     * Promotes the generated counterpart of [golden] to the committed golden (moves the generated
     * file onto it). Only meaningful while viewing Test output.
     *
     * Updates the list and preview in place rather than rescanning: the accepted row stays put and
     * loses its change bar, its thumbnail re-decodes to the freshly written golden, and the compare
     * pane switches to an "Accepted" state. A full [refresh] here used to blank the grid, wipe every
     * thumbnail and reset the selection — a jarring flash for a one-file change.
     */
    fun acceptGenerated(golden: File) {
        if (commandRunning) return
        val root = projectRoot
        suppressPngEvent(golden)
        scope.launch {
            val result = withContext(Dispatchers.IO) { GoldenAccept.accept(golden, config, root) }
            featureUsed(if (result.isSuccess) "accept_golden" else "accept_golden_failed")
            val failure = result.exceptionOrNull()
                ?.let { it as? GoldenAccept.AcceptException }
                ?.failure
            telemetry.acceptCompleted(
                TelemetryAcceptMode.SINGLE,
                if (result.isSuccess) TelemetryAcceptResult.SUCCESS else TelemetryAcceptResult.FAILURE,
                when {
                    result.isSuccess -> TelemetryAcceptFailure.NONE
                    failure == null -> TelemetryAcceptFailure.UNKNOWN
                    else -> when (failure) {
                    GoldenAccept.Failure.NoGeneratedCounterpart -> TelemetryAcceptFailure.MISSING
                    is GoldenAccept.Failure.Io -> TelemetryAcceptFailure.IO
                    }
                },
                candidates = 1,
                accepted = if (result.isSuccess) 1 else 0,
                failed = if (result.isSuccess) 0 else 1,
            )
            result.onFailure { error ->
                val failure = (error as? GoldenAccept.AcceptException)?.failure
                status = if (failure is GoldenAccept.Failure.NoGeneratedCounterpart) {
                    "No generated output found for ${golden.name}."
                } else {
                    "Could not accept ${golden.name}."
                }
                return@launch
            }
            result.getOrNull()?.let(::suppressPngEvent)
            // Drop only this golden's stale image/comparison; the rest of the grid keeps its decodes.
            invalidateCachesFor(golden)
            acceptedGoldens = acceptedGoldens + golden.absolutePath
            mergedItems = mergedItems.map { item ->
                if (item.golden == golden) {
                    item.copy(
                        workingCopy = item.workingCopy?.copy(status = ExtraComparisonItemStatus.MODIFIED),
                        testOutput = null,
                    )
                } else {
                    item
                }
            }
            items = mergedItems.toLegacyItems()
            status = "Accepted ${golden.name} as the golden."
            if (selected == golden) {
                // Repaint from the freshly written golden with an "Accepted" banner. Reloading the
                // comparison instead would read as "missing", since the test output it was promoted
                // from has just been consumed.
                val generation = ++comparisonGeneration
                val bitmap = withContext(Dispatchers.IO) { runCatching { loadGoldenBitmap(golden) }.getOrNull() }
                if (generation == comparisonGeneration && selected == golden) {
                    val accepted = Comparison(
                        old = null,
                        new = bitmap,
                        diff = null,
                        changedRatio = 0.0,
                        statusText = "Accepted as golden — ${golden.name}",
                        identical = true,
                        accepted = true,
                    )
                    // Cache it so clicking away and back to this golden keeps showing the accepted
                    // preview instead of reloading against the now-consumed test output.
                    comparisonCache[comparisonKey(golden, BuiltInSource.WORKING_COPY)] = accepted
                    comparison = accepted
                }
            }
            refreshSnapshotStatuses()
        }
    }

    /** Decodes [golden] (trimmed like the compare view) for the post-accept preview. IO only. */
    private fun loadGoldenBitmap(golden: File): ImageBitmap? =
        ImageBytes.decode(ImageBytes.workingBytes(golden))
            ?.let { trimIfEnabled(it, config.trimTransparentPadding) }
            ?.toComposeImageBitmap()

    /** Evicts the cached comparison and thumbnail entries for a single [golden] after it changes. */
    private fun invalidateCachesFor(golden: File) {
        comparisonCache.keys.removeAll { it.contains("|${golden.absolutePath}|") }
        thumbnailCache.keys.removeAll { it.startsWith("${golden.path}|") }
    }

    // endregion

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

    private fun scanner(root: File, scannerConfig: GoldenDiffConfig = config): ChangeScanner {
        return ChangeScanner.forGitProject(root, scannerConfig)
    }

    fun refresh(trigger: String = "automatic") {
        val root = projectRoot ?: return
        val started = System.nanoTime()
        val generation = ++refreshGeneration
        val requestedSourcePath = selectedSourcePath
        val requestedCaseId = selectedSnapshotCaseId
        busy = true
        status = "Scanning…"
        scope.launch {
            val span = telemetry.startSpan(
                "golden.scan",
                mapOf("scope" to CURRENT_FILE_SCOPE, "source" to "merged"),
            )
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val git = GitCli(root)
                    when {
                        !git.isAvailable() -> ScanOutcome(Blocker.NoGit, emptyList())
                        !git.isInsideWorkTree() -> ScanOutcome(Blocker.NotARepository, emptyList())
                        !config.isConfigured -> ScanOutcome(Blocker.NoGoldenDirectories, emptyList())
                        else -> {
                            val merged = requestedSourcePath
                                ?.let { findMergedForSourceFile(root, it, requestedCaseId) }
                                .orEmpty()
                            ScanOutcome(null, merged.toLegacyItems(), mergedItems = merged)
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
                        "scope" to CURRENT_FILE_SCOPE,
                        "source" to "merged",
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
                    "scope" to CURRENT_FILE_SCOPE,
                    "source" to "merged",
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
                    "failed_count_bucket" to TelemetryBuckets.count(
                        outcome.mergedItems.count { it.displayStatus == MergedGoldenStatus.FAILED },
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
            mergedItems = outcome.mergedItems
            acceptedGoldens = emptySet()
            busy = false
            status = when {
                outcome.error != null -> "Could not scan this project."
                outcome.blocker != null -> ""
                requestedSourcePath == null -> "Choose a project file to find its screenshots."
                outcome.items.isEmpty() -> "No screenshots match ${File(requestedSourcePath).name}."
                else -> "${outcome.items.size} screenshot(s) found."
            }
            if (requestedSourcePath != null && outcome.blocker == null) {
                val key = selectionKey(requestedSourcePath, requestedCaseId)
                itemsCache[key] = outcome.items
                mergedItemsCache[key] = outcome.mergedItems
                restoreSelectionFor(key, outcome.items)
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
        val alreadyOpen = selectedSourcePath == relativePath && selectedSnapshotCaseId == null
        selectedSourcePath = relativePath
        selectedSnapshotCaseId = null
        treeHighlight = relativePath
        treeHighlightCaseId = null
        val file = File(root, relativePath)
        telemetry.sourceFileSelected(
            memoryKey = file.absolutePath,
            trigger = if (file.extension.equals("png", true)) "direct_png" else trigger,
            fileFamily = fileFamily(file.extension),
            alreadyOpen = alreadyOpen,
        )
        if (!file.isFile) {
            showMissingSourceFile(relativePath)
            return
        }
        if (file.extension.equals("png", ignoreCase = true)) {
            val single = listOf(ExtraComparisonItem(file, file.name))
            applyCachedItems(relativePath, single, "1 screenshot selected.")
            return
        }
        // Reopening a tab shows its remembered result immediately; the images come from the caches.
        val cacheKey = selectionKey(relativePath)
        itemsCache[cacheKey]?.let { cached ->
            mergedItems = mergedItemsCache[cacheKey].orEmpty()
            val label = if (cached.isEmpty()) "No screenshots match ${file.name}." else "${cached.size} screenshot(s) found."
            applyCachedItems(cacheKey, cached, label)
            return
        }
        refresh("automatic")
    }

    /** Selects one method/preview under its visible view parent. */
    fun selectSnapshotCase(parentPath: String, caseId: String, trigger: String = "project_tree") {
        val root = projectRoot ?: return
        val case = snapshotFileIndex?.files
            ?.firstOrNull { it.relativePath == parentPath }
            ?.cases?.firstOrNull { it.id == caseId } ?: return
        selectedSourcePath = parentPath
        selectedSnapshotCaseId = case.id
        treeHighlight = parentPath
        treeHighlightCaseId = case.id
        val key = selectionKey(parentPath, case.id)
        telemetry.sourceFileSelected(
            memoryKey = "${File(root, parentPath).absolutePath}#${case.id}",
            trigger = trigger,
            fileFamily = "snapshot_case",
            alreadyOpen = false,
        )
        itemsCache[key]?.let { cached ->
            mergedItems = mergedItemsCache[key].orEmpty()
            applyCachedItems(key, cached, "${cached.size} screenshot(s) in ${case.methodName}.")
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
        acceptedGoldens = emptySet()
        status = label
        restoreSelectionFor(path, list)
    }

    private fun findMergedForSourceFile(
        root: File,
        relativePath: String,
        caseId: String?,
    ): List<MergedGoldenItem> = telemetry.measureSpan(
        "golden.match",
        mapOf("scope" to "current_file", "source" to "merged"),
    ) {
        val indexed = snapshotFileIndex?.files?.firstOrNull { it.relativePath == relativePath }
        val indexedPaths = caseId
            ?.let { id -> indexed?.cases?.firstOrNull { it.id == id }?.goldenPaths }
            ?: indexed?.goldenPaths
        if (indexedPaths != null) {
            return@measureSpan scanner(root).mergedItemsFor(indexedPaths.map(::File))
        }
        val file = File(root, relativePath)
        // In annotated mode the regex filters which functions count as screenshot candidates, exactly
        // like the plugin; other modes ignore function names, so the regex is only passed there.
        val annotationRegex = config.normalizedForMatching()
            .annotatedFunctionRegex.takeIf { config.matchMode == MatchMode.ANNOTATED_METHOD }
        val screen = GenericScreenExtractor.extract(
            file.nameWithoutExtension,
            runCatching { file.readText() }.getOrDefault(""),
            annotationRegex,
        )
        val goldens = GoldenFinder.find(
            config.resolvedGoldenPaths(root),
            screen,
            config.matchMode,
            config.excludedSuffixes,
            config.normalizedForMatching().goldenFilePatterns,
        )
        scanner(root).mergedItemsFor(goldens)
    }

    fun select(
        file: File?,
        selectionTrigger: String = "grid",
        keepCurrentPreview: Boolean = false,
    ) {
        selected = file
        val generation = ++comparisonGeneration
        val root = projectRoot
        if (file == null || root == null) {
            comparison = null
            return
        }
        val merged = mergedItems.firstOrNull { it.golden.normalize() == file.normalize() }
        val requestedSource = automaticGoldenSource(merged)
        // Picking a golden never touches the tree. The tree's highlight follows the tree's own
        // selection and the explicit reveal action; clearing it from here dropped the highlight of
        // the file whose goldens are on screen the moment one of them was clicked.
        // Remember the choice for the active source file so returning to it restores this golden.
        selectedSourcePath?.let { tabSelection[selectionKey(it)] = file }
        // A cached comparison shows at once — no null flash, no re-decode.
        comparisonCache[comparisonKey(file, requestedSource)]?.let {
            comparison = it
            comparisonViewed(it, requestedSource, cacheHit = true, selectionTrigger)
            return
        }
        if (!keepCurrentPreview) comparison = null
        scope.launch {
            val started = System.nanoTime()
            val span = telemetry.startSpan(
                "comparison.load",
                mapOf("scope" to CURRENT_FILE_SCOPE, "source" to requestedSource.wireValue(), "cache_hit" to "false"),
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
                        "scope" to CURRENT_FILE_SCOPE,
                        "source" to requestedSource.wireValue(),
                    ),
                )
                Comparison(null, null, null, 0.0, "Could not read image.", false)
            }
            if (generation == comparisonGeneration && selected == file && selectedSource == requestedSource) {
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
        if (next != current) {
            val file = items[next].file
            select(file, "keyboard")
            goldenScrollRequest = GoldenScrollRequest(file.normalize().path, ++goldenScrollSequence)
        }
    }

    internal fun consumeGoldenScrollRequest(sequence: Long) {
        if (goldenScrollRequest?.sequence == sequence) goldenScrollRequest = null
    }

    /** Locates the selected golden on demand without changing the active view/case filter. */
    fun revealSelectedGoldenInTree() {
        val golden = selected ?: return
        val location = goldenTreeLocation(snapshotFileIndex, selectedSourcePath, golden.path) ?: return
        revealInTree(location.parentPath, location.caseId)
    }

    internal fun openQuickOpenResult(result: QuickOpenResult) {
        quickOpenVisible = false
        if (result.caseId == null) {
            selectSourceFile(result.path, "quick_open")
        } else {
            selectSnapshotCase(result.path, result.caseId, "quick_open")
        }
        revealInTree(result.path, result.caseId)
    }

    private fun revealInTree(parentPath: String, caseId: String?) {
        treeHighlight = parentPath
        treeHighlightCaseId = caseId
        treeRevealRequest = TreeRevealRequest(
            parentPath,
            caseId,
            ++treeRevealSequence,
        )
    }

    private fun loadComparison(root: File, golden: File, requestedSource: BuiltInSource): Comparison {
        val git = GitCli(root)
        val headBytes = telemetry.measureSpan(
            "git.head_read",
            mapOf("scope" to CURRENT_FILE_SCOPE, "source" to requestedSource.wireValue()),
        ) { git.headBytes(golden) }
        val sourceFile = when (requestedSource) {
            BuiltInSource.WORKING_COPY -> golden
            BuiltInSource.GENERATED -> telemetry.measureSpan(
                "generated.lookup",
                mapOf("scope" to CURRENT_FILE_SCOPE, "source" to "test_output"),
            ) {
                com.github.dkwasniak.goldendiff.compare.GeneratedImageSource.findForGolden(
                    golden = golden,
                    goldenRoots = config.resolvedGoldenPaths(root),
                    generatedRoots = config.resolvedGeneratedPaths(root),
                    generatedFileRegex = config.normalizedForMatching().generatedFileRegex,
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
            mapOf("scope" to CURRENT_FILE_SCOPE, "source" to requestedSource.wireValue()),
        ) {
            ImageBytes.decode(headBytes)?.let { trimIfEnabled(it, trim) } to
                ImageBytes.decode(newBytes)?.let { trimIfEnabled(it, trim) }
        }

        val diff = if (!identical && oldImage != null && newImage != null) {
            telemetry.measureSpan(
                "pixel_diff.compute",
                mapOf("scope" to CURRENT_FILE_SCOPE, "source" to requestedSource.wireValue()),
            ) { PixelDiff.compute(oldImage.toArgbImage(), newImage.toArgbImage()) }
        } else {
            null
        }

        val text = when {
            identical -> "No changes vs HEAD — ${golden.name}"
            headBytes == null -> "New file (not in git HEAD) — ${golden.name}"
            newBytes == null -> "${sourceLabel(requestedSource)} missing — showing HEAD."
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
                "scope" to CURRENT_FILE_SCOPE,
            ),
        )
        telemetry.activationCompleted(CURRENT_FILE_SCOPE, requestedSource.wireValue())
    }

    override fun close() {
        pngDebounceJob?.cancel()
        pngWatcher?.close()
        telemetry.close()
    }

    private data class ScanOutcome(
        val blocker: Blocker?,
        val items: List<ExtraComparisonItem>,
        val error: Throwable? = null,
        val mergedItems: List<MergedGoldenItem> = emptyList(),
    )

    private companion object {
        const val MAX_CACHED_PROJECTS = 6
        const val PNG_DEBOUNCE_MS = 400L
        const val PNG_SUPPRESSION_MS = 2_000L
    }
}

private fun List<MergedGoldenItem>.toLegacyItems(): List<ExtraComparisonItem> = map { merged ->
    ExtraComparisonItem(
        file = merged.golden,
        title = merged.golden.name,
        isLoading = false,
        status = when (merged.displayStatus) {
            MergedGoldenStatus.FAILED, MergedGoldenStatus.MODIFIED -> ExtraComparisonItemStatus.MODIFIED
            MergedGoldenStatus.NEW -> ExtraComparisonItemStatus.NEW
            MergedGoldenStatus.UNCHANGED -> ExtraComparisonItemStatus.UNCHANGED
        },
    )
}

/**
 * The app dropped its Scope control - it only ever works from the opened file now. The telemetry
 * dimension stays, pinned to that one value, because the plugin still has both scopes and the events
 * are compared across surfaces.
 */
private const val CURRENT_FILE_SCOPE = "current_file"

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
