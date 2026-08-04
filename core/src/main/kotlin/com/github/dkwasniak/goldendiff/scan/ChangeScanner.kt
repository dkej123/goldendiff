package com.github.dkwasniak.goldendiff.scan

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartIndex
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.compare.HeadBytesSource
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.git.WorkingCopyStatus
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.File

/** Which built-in side a golden is compared against. */
enum class BuiltInSource {
    /** The golden file as it currently sits on disk. */
    WORKING_COPY,

    /** The screenshot the verification tests just produced. */
    GENERATED,
}

/**
 * Decides which goldens changed and how, for both the "file I am looking at" and the
 * "everything in the project" views.
 *
 * Extracted out of the tool-window panel so the standalone app produces byte-for-byte the same lists
 * as the plugin — the two must agree, or the same repository would appear to have different changes
 * depending on which tool you opened.
 *
 * Every method does I/O and may spawn git — never call these on a UI thread.
 */
class ChangeScanner(
    private val projectRoot: File?,
    private val config: GoldenDiffConfig,
    private val headBytes: HeadBytesSource,
    private val workingCopyStatus: WorkingCopyStatus,
    private val explicitGeneratedCandidates: List<GeneratedCounterpartIndex.ExplicitCandidate> = emptyList(),
) {

    private val goldenRoots: List<File> get() = config.resolvedGoldenPaths(projectRoot)

    private val generatedRoots: List<File>
        get() = if (config.usesXcresultSource) emptyList() else config.resolvedGeneratedPaths(projectRoot)

    private val generatedIndex: GeneratedCounterpartIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GeneratedCounterpartIndex.build(
            generatedRoots = generatedRoots,
            generatedFileRegex = config.normalizedForMatching().generatedFileRegex,
            excludedSuffixes = config.excludedSuffixes,
            explicitCandidates = explicitGeneratedCandidates,
        )
    }

    /** Wraps an already-matched set of goldens with their status against [source]. */
    fun itemsFor(files: List<File>, source: BuiltInSource): List<ExtraComparisonItem> =
        buildItems(files) { statusOf(it, source) }

    /** Both built-in variants for an already matched current-file list. */
    fun mergedItemsFor(files: List<File>): List<MergedGoldenItem> {
        val roots = goldenRoots
        val normalizedFiles = files.distinctBy { it.normalize().path }
        // One batched HEAD lookup for every golden instead of one per golden: both variants below
        // compare against the same golden's committed bytes, and a project-wide refresh can easily
        // touch hundreds of files.
        val headByFile = headBytes.headBytes(normalizedFiles)
        return normalizedFiles.map { golden ->
            val resolution = generatedResolution(golden)
            val generated = (resolution as? GeneratedCounterpartResolution.Exact)?.file
            val head = headByFile[golden]
            MergedGoldenItem(
                golden = golden,
                workingCopy = GoldenSourceVariant(golden, statusOf(golden, BuiltInSource.WORKING_COPY, head)),
                testOutput = generated?.let { GoldenSourceVariant(it, generatedStatus(golden, it, head)) },
                testOutputResolution = resolution,
            )
        }.sortedWith(mergedComparator(roots))
    }

    /**
     * Project changes from both built-in sources, merged by normalized golden path.
     *
     * A golden changed in both places occurs once and retains both statuses. Unchanged generated
     * counterparts do not make an otherwise clean golden a project change.
     */
    fun mergedChanges(): List<MergedGoldenItem> {
        val roots = goldenRoots
        val working = workingCopyChanges().associateBy { it.file.normalize().path }
        val generated = generatedEntries().associateBy { it.golden.normalize().path }
        val relevantGenerated = generated.filterValues { entry ->
            entry.resolution is GeneratedCounterpartResolution.Ambiguous ||
                entry.status != null && entry.status != ExtraComparisonItemStatus.UNCHANGED
        }
        return (working.keys + relevantGenerated.keys).distinct().map { path ->
            val golden = working[path]?.file ?: relevantGenerated.getValue(path).golden
            val entry = generated[path]
            val output = (entry?.resolution as? GeneratedCounterpartResolution.Exact)?.file
            MergedGoldenItem(
                golden = golden,
                workingCopy = working[path]?.let { GoldenSourceVariant(golden, it.status) },
                testOutput = output?.let { GoldenSourceVariant(it, entry?.status ?: generatedStatus(golden, it)) },
                testOutputResolution = entry?.resolution ?: GeneratedCounterpartResolution.Missing,
            )
        }.sortedWith(mergedComparator(roots))
    }

    /**
     * Every changed golden in the project, taken from `git status`.
     *
     * The status comes straight from the porcelain code rather than a per-file HEAD fetch, which is
     * what keeps this usable on a large change set.
     */
    fun workingCopyChanges(): List<ExtraComparisonItem> {
        val roots = goldenRoots
        val statusByPath = workingCopyStatus.changedFiles()
            .filter { isGoldenCandidate(it.file, roots) }
            .associateBy({ it.file.normalize().path }, { it.status })
        val files = statusByPath.keys.map(::File)
            .sortedBy { invariantPathIn(it, roots).lowercase() }
        return buildItems(files) { statusByPath[it.normalize().path] ?: ExtraComparisonItemStatus.MODIFIED }
    }

    /** Every golden whose generated test output differs from the committed version. */
    fun generatedChanges(): List<ExtraComparisonItem> {
        val entries = generatedEntries().filter { it.status != null }
        val statusByGolden = entries.associate { it.golden.normalize().path to it.status!! }
        return buildItems(entries.map { it.golden }) {
            statusByGolden.getValue(it.normalize().path)
        }
    }

    fun statusOf(file: File, source: BuiltInSource): ExtraComparisonItemStatus =
        statusOf(file, source, headBytes.headBytes(file))

    /** Same as the public overload, but for a batched caller that already has [head] on hand. */
    private fun statusOf(file: File, source: BuiltInSource, head: ByteArray?): ExtraComparisonItemStatus {
        val sourceFile = when (source) {
            BuiltInSource.WORKING_COPY -> file
            BuiltInSource.GENERATED ->
                (generatedResolution(file) as? GeneratedCounterpartResolution.Exact)?.file
        }
        val sourceBytes = sourceFile?.let(ImageBytes::workingBytes)
        return when {
            sourceBytes == null -> ExtraComparisonItemStatus.UNCHANGED
            head == null -> ExtraComparisonItemStatus.NEW
            !head.contentEquals(sourceBytes) -> ExtraComparisonItemStatus.MODIFIED
            else -> ExtraComparisonItemStatus.UNCHANGED
        }
    }

    private fun generatedResolution(golden: File): GeneratedCounterpartResolution =
        generatedIndex.resolve(golden, goldenRoots)

    private data class GeneratedEntry(
        val golden: File,
        val resolution: GeneratedCounterpartResolution,
        val status: ExtraComparisonItemStatus?,
    )

    private fun generatedEntries(): List<GeneratedEntry> {
        if (generatedRoots.none(File::isDirectory) && explicitGeneratedCandidates.none { it.file.isFile }) {
            return emptyList()
        }
        return GoldenFinder.findAll(goldenRoots, config.excludedSuffixes).map { golden ->
            val resolution = generatedResolution(golden)
            val output = (resolution as? GeneratedCounterpartResolution.Exact)?.file
            GeneratedEntry(
                golden,
                resolution,
                output?.let { generatedStatus(golden, it) },
            )
        }
    }

    private fun mergedComparator(roots: List<File>): Comparator<MergedGoldenItem> =
        compareBy<MergedGoldenItem> { mergedStatusSortRank(it.displayStatus) }
            .thenBy(ProjectFileIndexPathComparator) { invariantPathIn(it.golden, roots) }

    private fun generatedStatus(golden: File, generated: File?): ExtraComparisonItemStatus =
        generatedStatus(golden, generated, headBytes.headBytes(golden))

    /** Same as the two-argument overload, but for a batched caller that already has [head] on hand. */
    private fun generatedStatus(golden: File, generated: File?, head: ByteArray?): ExtraComparisonItemStatus {
        val sourceBytes = generated?.let(ImageBytes::workingBytes) ?: return ExtraComparisonItemStatus.UNCHANGED
        if (head == null) return ExtraComparisonItemStatus.NEW
        return if (head.contentEquals(sourceBytes)) {
            ExtraComparisonItemStatus.UNCHANGED
        } else {
            ExtraComparisonItemStatus.MODIFIED
        }
    }

    /** Sorts changed first, then new, then unchanged, keeping the incoming order within each group. */
    private fun buildItems(
        files: List<File>,
        statusOf: (File) -> ExtraComparisonItemStatus,
    ): List<ExtraComparisonItem> =
        files.mapIndexed { index, file ->
            IndexedValue(
                index,
                ExtraComparisonItem(file = file, title = file.name, isLoading = false, status = statusOf(file)),
            )
        }
            .sortedWith(compareBy<IndexedValue<ExtraComparisonItem>> { statusSortRank(it.value.status) }.thenBy { it.index })
            .map { it.value }

    private fun isGoldenCandidate(file: File, roots: List<File>): Boolean =
        file.isFile &&
            file.extension.equals("png", ignoreCase = true) &&
            config.excludedSuffixes.none { it.isNotBlank() && file.nameWithoutExtension.endsWith(it) } &&
            roots.any { root -> file.isDescendantOf(root) }

    companion object {
        /**
         * Creates the canonical scanner used by every host for a Git working tree.
         *
         * Keeping both HEAD bytes and project-wide work-tree status on the same [GitCli] provider
         * prevents hosts from assigning different labels to the same golden when an IDE VCS mapping
         * cannot supply binary revision content.
         */
        fun forGitProject(
            projectRoot: File,
            config: GoldenDiffConfig,
            explicitGeneratedCandidates: List<GeneratedCounterpartIndex.ExplicitCandidate> = emptyList(),
        ): ChangeScanner {
            val git = GitCli(projectRoot)
            return ChangeScanner(
                projectRoot,
                config.normalizedForMatching(),
                git,
                git,
                explicitGeneratedCandidates,
            )
        }

        fun statusSortRank(status: ExtraComparisonItemStatus): Int =
            when (status) {
                ExtraComparisonItemStatus.MODIFIED -> 0
                ExtraComparisonItemStatus.NEW -> 1
                ExtraComparisonItemStatus.UNCHANGED -> 2
            }

        fun mergedStatusSortRank(status: MergedGoldenStatus): Int = when (status) {
            MergedGoldenStatus.FAILED -> 0
            MergedGoldenStatus.MODIFIED -> 1
            MergedGoldenStatus.NEW -> 2
            MergedGoldenStatus.UNCHANGED -> 3
        }

        private val ProjectFileIndexPathComparator =
            String.CASE_INSENSITIVE_ORDER.thenBy { it }

        private fun File.isDescendantOf(root: File): Boolean {
            val relative = runCatching { normalize().relativeTo(root.normalize()) }.getOrNull() ?: return false
            return !relative.path.startsWith("..")
        }

        /**
         * Path relative to whichever root contains [file], with `/` separators.
         *
         * Normalising the separator is what keeps sort order identical across platforms — on Windows
         * the raw path would sort by `\` and produce a different list for the same repository.
         */
        fun invariantPathIn(file: File, roots: List<File>): String {
            val root = roots.firstOrNull { file.isDescendantOf(it) }
            val relative = root?.let { runCatching { file.normalize().relativeTo(it.normalize()) }.getOrNull() }
            return (relative ?: file).path.replace(File.separatorChar, '/')
        }
    }
}
