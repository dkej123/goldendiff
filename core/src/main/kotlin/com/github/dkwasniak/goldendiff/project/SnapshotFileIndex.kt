package com.github.dkwasniak.goldendiff.project

import com.github.dkwasniak.goldendiff.match.GenericScreenExtractor
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import java.io.File

enum class SnapshotFileStatus {
    FAILED,
    MODIFIED,
    NEW,
    UNCHANGED,
}

data class SnapshotSourceFile(
    val relativePath: String,
    val goldenPaths: List<String>,
    val status: SnapshotFileStatus,
    val failedCount: Int,
    val cases: List<SnapshotCase> = emptyList(),
    /** Goldens retained on the view row because no function-level match was found. */
    val unmatchedGoldenPaths: List<String> = emptyList(),
    /** Every status represented by the associated goldens, used by multi-status filters. */
    val statuses: Set<SnapshotFileStatus> = setOf(status),
)

data class SnapshotCase(
    val id: String,
    val methodName: String,
    val generatorPath: String,
    val className: String?,
    val goldenPaths: List<String>,
    val status: SnapshotFileStatus,
    val failedCount: Int,
    /** Every status represented by this case's goldens, used by multi-status filters. */
    val statuses: Set<SnapshotFileStatus> = setOf(status),
)

/**
 * Reuses parsed source structure between background index refreshes. File size and mtime make the
 * common unchanged path allocation-only; a changed source is read and parsed again.
 */
class SnapshotSourceCache {
    data class Stats(val hits: Long, val misses: Long)

    private data class Key(
        val relativePath: String,
        val size: Long,
        val modifiedAt: Long,
        val annotationRegex: String?,
    )

    private data class Entry(
        val key: Key,
        val screen: com.github.dkwasniak.goldendiff.match.Screen,
    )

    private val entries = HashMap<String, Entry>()
    private var hitCount = 0L
    private var missCount = 0L

    @Synchronized
    internal fun screen(
        source: File,
        relativePath: String,
        annotationRegex: String?,
    ): com.github.dkwasniak.goldendiff.match.Screen {
        val key = Key(relativePath, source.length(), source.lastModified(), annotationRegex)
        entries[relativePath]?.takeIf { it.key == key }?.let {
            hitCount++
            return it.screen
        }
        val extracted = GenericScreenExtractor.extract(
            source.nameWithoutExtension,
            runCatching { source.readText() }.getOrDefault(""),
            annotationRegex,
            includeTestFunctions = true,
        )
        entries[relativePath] = Entry(key, extracted)
        missCount++
        return extracted
    }

    @Synchronized
    internal fun retain(paths: Set<String>) {
        entries.keys.retainAll(paths)
    }

    @Synchronized
    fun isCurrent(candidates: ProjectFileIndex, config: GoldenDiffConfig): Boolean {
        val normalized = config.normalizedForMatching()
        val annotationRegex = normalized.annotatedFunctionRegex
            .takeIf { normalized.matchMode == MatchMode.ANNOTATED_METHOD }
        val currentPaths = candidates.paths.filterTo(hashSetOf()) { relativePath ->
            val source = candidates.file(relativePath)
            source.isFile &&
                !source.extension.equals("png", ignoreCase = true) &&
                source.extension.lowercase() in CACHEABLE_SOURCE_EXTENSIONS
        }
        if (currentPaths != entries.keys) return false
        return currentPaths.all { relativePath ->
            val source = candidates.file(relativePath)
            entries[relativePath]?.key == Key(
                relativePath,
                source.length(),
                source.lastModified(),
                annotationRegex,
            )
        }
    }

    @Synchronized
    fun stats(): Stats = Stats(hitCount, missCount)

    private companion object {
        val CACHEABLE_SOURCE_EXTENSIONS = setOf(
            "c", "cc", "cpp", "cs", "dart", "go", "h", "hpp", "java", "js", "jsx", "kt", "kts",
            "m", "mm", "py", "rb", "rs", "scala", "swift", "ts", "tsx", "vue",
        )
    }
}

/**
 * Snapshot-aware projection of [ProjectFileIndex].
 *
 * Building it reads source files and scans configured golden roots, so callers must run [build] away
 * from UI threads. Matched paths are retained to make later watcher/status refreshes pure and cheap.
 */
data class SnapshotFileIndex(
    val root: File,
    val files: List<SnapshotSourceFile>,
    val failedFilesByDirectory: Map<String, Int>,
    val fingerprint: SnapshotIndexFingerprint,
    /** Complete inventory, including goldens that could not be associated with any source file. */
    val goldenInventory: Set<String>,
) {
    val paths: List<String> get() = files.map { it.relativePath }

    fun file(path: String): File = File(root, path)

    companion object {
        fun build(
            candidates: ProjectFileIndex,
            config: GoldenDiffConfig,
            mergedItems: Collection<MergedGoldenItem>,
            sourceCache: SnapshotSourceCache = SnapshotSourceCache(),
        ): SnapshotFileIndex {
            val normalized = config.normalizedForMatching()
            val goldenRoots = normalized.resolvedGoldenPaths(candidates.root)
            val mergedByPath = mergedItems.associateBy { it.golden.normalize().path }
            val allGoldens = GoldenFinder.findAll(goldenRoots, normalized.excludedSuffixes)
            val sources = candidates.paths.mapNotNull { relativePath ->
                val source = candidates.file(relativePath)
                if (!source.isFile || source.extension.equals("png", ignoreCase = true)) return@mapNotNull null
                if (source.extension.lowercase() !in SOURCE_EXTENSIONS) return@mapNotNull null
                val annotationRegex = normalized.annotatedFunctionRegex
                    .takeIf { normalized.matchMode == MatchMode.ANNOTATED_METHOD }
                val screen = sourceCache.screen(source, relativePath, annotationRegex)
                val matches = GoldenFinder.matchFiles(
                    goldens = allGoldens,
                    roots = goldenRoots,
                    screen = screen,
                    mode = normalized.matchMode,
                    generalPatterns = normalized.goldenFilePatterns,
                ).distinctBy { it.normalize().path }
                SourceEvidence(
                    relativePath = relativePath,
                    screen = screen,
                    broadGoldenPaths = matches.map { it.normalize().path }.toSet(),
                    functionMatches = GoldenFinder.functionMatches(allGoldens, screen.functionNames, goldenRoots)
                        .groupBy { it.functionName }
                        .mapValues { (_, evidence) -> evidence.map { it.golden.normalize().path }.toSet() },
                )
            }
            sourceCache.retain(sources.mapTo(hashSetOf(), SourceEvidence::relativePath))

            val cases = sources.flatMap { generator ->
                generator.functionMatches.map { (method, paths) ->
                    CaseEvidence(generator, method, paths)
                }
            }
            // A golden may contain more than one extracted name. Prefer the longest method and then a
            // stable generator path so it is never duplicated between cases.
            val caseByGolden = cases
                .flatMap { case -> case.goldenPaths.map { it to case } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, matches) ->
                    matches.sortedWith(
                        compareByDescending<CaseEvidence> { it.methodName.length }
                            .thenBy(ProjectFileIndex.PATH_ORDER) { it.generator.relativePath },
                    ).first()
                }
            val ownedCaseGoldens = cases.associateWith { case ->
                case.goldenPaths.filterTo(linkedSetOf()) { caseByGolden[it] == case }
            }.filterValues { it.isNotEmpty() }

            val parentByCase = ownedCaseGoldens.keys.associateWith { case ->
                selectParent(case, sources)
            }
            val unmatched = allGoldens.map { it.normalize().path }
                .filter { it !in caseByGolden }
                .mapNotNull { goldenPath ->
                    selectParentForUnmatched(goldenPath, sources)?.let { it to goldenPath }
                }
            val parentPaths = (parentByCase.values.map { it.relativePath } + unmatched.map { it.first.relativePath }).toSet()
            val files = parentPaths.mapNotNull { parentPath ->
                val parent = sources.firstOrNull { it.relativePath == parentPath } ?: return@mapNotNull null
                val parentCases = parentByCase.filterValues { it.relativePath == parentPath }.keys
                    .map { case ->
                        val paths = ownedCaseGoldens.getValue(case).sorted()
                        val statuses = statusesFor(paths, mergedByPath)
                        SnapshotCase(
                            id = "$parentPath\u0000${case.generator.relativePath}\u0000${case.methodName}",
                            methodName = case.methodName,
                            generatorPath = case.generator.relativePath,
                            className = case.generator.screen.classNames.firstOrNull(),
                            goldenPaths = paths,
                            status = primaryStatus(statuses),
                            failedCount = failedCount(paths, mergedByPath),
                            statuses = statuses,
                        )
                    }
                    .sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER, SnapshotCase::methodName)
                            .thenBy(ProjectFileIndex.PATH_ORDER, SnapshotCase::generatorPath),
                    )
                val unmatchedPaths = unmatched.filter { it.first.relativePath == parentPath }.map { it.second }.sorted()
                val paths = (parentCases.flatMap { it.goldenPaths } + unmatchedPaths).distinct()
                val statuses = statusesFor(paths, mergedByPath)
                SnapshotSourceFile(
                    relativePath = parentPath,
                    goldenPaths = paths,
                    status = primaryStatus(statuses),
                    failedCount = failedCount(paths, mergedByPath),
                    cases = parentCases,
                    unmatchedGoldenPaths = unmatchedPaths,
                    statuses = statuses,
                )
            }.sortedWith(compareBy(ProjectFileIndex.PATH_ORDER) { it.relativePath })

            return SnapshotFileIndex(
                root = candidates.root,
                files = files,
                failedFilesByDirectory = aggregateFailedDirectories(files),
                fingerprint = SnapshotIndexFingerprint.from(candidates.root, normalized),
                goldenInventory = allGoldens.mapTo(linkedSetOf()) { it.normalize().path },
            )
        }

        private data class SourceEvidence(
            val relativePath: String,
            val screen: com.github.dkwasniak.goldendiff.match.Screen,
            val broadGoldenPaths: Set<String>,
            val functionMatches: Map<String, Set<String>>,
        )

        private data class CaseEvidence(
            val generator: SourceEvidence,
            val methodName: String,
            val goldenPaths: Set<String>,
        )

        private fun selectParent(case: CaseEvidence, sources: List<SourceEvidence>): SourceEvidence {
            if (!case.generator.isTestContainer()) return case.generator
            val production = sources.filter { !it.isTestContainer() && it.broadGoldenPaths.any(case.goldenPaths::contains) }
            val expectedOwners = case.generator.testOwnerNames()
            val credible = production.filter { source ->
                source.ownerNames().any { owner ->
                    owner.hasViewSuffix() ||
                        expectedOwners.any { expected ->
                            owner.equals(expected, ignoreCase = true) ||
                                owner.endsWith(expected, ignoreCase = true) ||
                                expected.endsWith(owner, ignoreCase = true)
                        }
                }
            }
            return credible.sortedWith(parentComparator(case.goldenPaths, expectedOwners)).firstOrNull()
                ?: case.generator
        }

        private fun selectParentForUnmatched(
            goldenPath: String,
            sources: List<SourceEvidence>,
        ): SourceEvidence? {
            val matching = sources.filter {
                goldenPath in it.broadGoldenPaths || it.ownerNames().any { owner -> ownerMatches(goldenPath, owner) }
            }
            return matching.filterNot { it.isTestContainer() }
                .ifEmpty { matching }
                .sortedWith(parentComparator(setOf(goldenPath)))
                .firstOrNull()
        }

        private fun parentComparator(
            goldens: Set<String>,
            expectedOwners: Set<String> = emptySet(),
        ): Comparator<SourceEvidence> =
            compareByDescending<SourceEvidence> { source ->
                source.ownerNames().any { owner ->
                    expectedOwners.any { expected ->
                        owner.equals(expected, ignoreCase = true) ||
                            owner.endsWith(expected, ignoreCase = true) ||
                            expected.endsWith(owner, ignoreCase = true)
                    }
                }
            }.thenByDescending { source ->
                goldens.count { golden -> source.ownerNames().any { owner -> ownerMatches(golden, owner) } }
            }.thenByDescending { source ->
                source.broadGoldenPaths.count(goldens::contains)
            }.thenByDescending { source ->
                source.ownerNames().filter { owner -> goldens.any { ownerMatches(it, owner) } }
                    .maxOfOrNull(String::length) ?: 0
            }.thenBy(ProjectFileIndex.PATH_ORDER) { it.relativePath }

        private fun SourceEvidence.ownerNames(): List<String> =
            (screen.classNames + screen.fileName)
                .filter { it.length >= 3 && it.lowercase() !in GENERIC_OWNER_NAMES }
                .distinct()

        private fun SourceEvidence.testOwnerNames(): Set<String> =
            (screen.classNames + screen.fileName).mapNotNull { name ->
                name.replace(TEST_CONTAINER_SUFFIX, "").takeIf { it.length >= 3 && it != name }
            }.toSet()

        private fun String.hasViewSuffix(): Boolean =
            VIEW_OWNER_SUFFIXES.any { endsWith(it, ignoreCase = true) }

        private fun SourceEvidence.isTestContainer(): Boolean {
            val segments = relativePath.replace('\\', '/').split('/')
            val stem = File(relativePath).nameWithoutExtension
            return functionMatches.keys.any { it.startsWith("test", ignoreCase = true) } ||
                segments.any { it.lowercase() in setOf("test", "tests", "__tests__") } ||
                TEST_CONTAINER_SUFFIX.containsMatchIn(stem)
        }

        private fun ownerMatches(path: String, owner: String): Boolean {
            var start = path.indexOf(owner, ignoreCase = true)
            while (start >= 0) {
                val end = start + owner.length
                val before = start == 0 || !path[start - 1].isLetterOrDigit() ||
                    path[start - 1].isLowerCase() && path[start].isUpperCase()
                val after = end == path.length || !path[end].isLetterOrDigit() ||
                    path[end].isUpperCase()
                if (before && after) return true
                start = path.indexOf(owner, start + 1, ignoreCase = true)
            }
            return false
        }

        private fun statusesFor(
            paths: Collection<String>,
            mergedByPath: Map<String, MergedGoldenItem>,
        ): Set<SnapshotFileStatus> =
            paths.mapTo(linkedSetOf()) { path ->
                mergedByPath[path]?.displayStatus.toSnapshotStatus()
            }.ifEmpty { setOf(SnapshotFileStatus.UNCHANGED) }

        private fun primaryStatus(statuses: Set<SnapshotFileStatus>): SnapshotFileStatus = when {
            SnapshotFileStatus.FAILED in statuses -> SnapshotFileStatus.FAILED
            SnapshotFileStatus.MODIFIED in statuses -> SnapshotFileStatus.MODIFIED
            SnapshotFileStatus.NEW in statuses -> SnapshotFileStatus.NEW
            else -> SnapshotFileStatus.UNCHANGED
        }

        private fun MergedGoldenStatus?.toSnapshotStatus(): SnapshotFileStatus = when (this) {
            MergedGoldenStatus.FAILED -> SnapshotFileStatus.FAILED
            MergedGoldenStatus.MODIFIED -> SnapshotFileStatus.MODIFIED
            MergedGoldenStatus.NEW -> SnapshotFileStatus.NEW
            MergedGoldenStatus.UNCHANGED, null -> SnapshotFileStatus.UNCHANGED
        }

        private fun failedCount(
            paths: Collection<String>,
            mergedByPath: Map<String, MergedGoldenItem>,
        ): Int = paths.count { mergedByPath[it]?.displayStatus == MergedGoldenStatus.FAILED }

        fun aggregateFailedDirectories(files: Collection<SnapshotSourceFile>): Map<String, Int> {
            val counts = linkedMapOf<String, Int>()
            files.asSequence().filter { it.failedCount > 0 }.forEach { entry ->
                var directory = entry.relativePath.substringBeforeLast('/', "")
                while (directory.isNotEmpty()) {
                    counts[directory] = counts.getOrDefault(directory, 0) + 1
                    directory = directory.substringBeforeLast('/', "")
                }
            }
            return counts
        }

        /** Refreshes failure/change badges without re-reading source files or rebuilding associations. */
        fun refreshStatuses(
            index: SnapshotFileIndex,
            mergedItems: Collection<MergedGoldenItem>,
        ): SnapshotFileIndex {
            val updates = mergedItems.associateBy { it.golden.normalize().path }
            val files = index.files.map { file ->
                val cases = file.cases.map { case ->
                    val statuses = statusesFor(case.goldenPaths, updates, case.statuses)
                    case.copy(
                        status = primaryStatus(statuses),
                        failedCount = failedCount(case.goldenPaths, updates, case.failedCount),
                        statuses = statuses,
                    )
                }
                val statuses = statusesFor(file.goldenPaths, updates, file.statuses)
                file.copy(
                    cases = cases,
                    status = primaryStatus(statuses),
                    failedCount = failedCount(file.goldenPaths, updates, file.failedCount),
                    statuses = statuses,
                )
            }
            return index.copy(files = files, failedFilesByDirectory = aggregateFailedDirectories(files))
        }

        private fun statusesFor(
            paths: Collection<String>,
            updates: Map<String, MergedGoldenItem>,
            fallback: Set<SnapshotFileStatus>,
        ): Set<SnapshotFileStatus> {
            if (paths.none(updates::containsKey)) return fallback
            return paths.mapTo(linkedSetOf()) { path ->
                updates[path]?.displayStatus.toSnapshotStatus()
            }.ifEmpty { setOf(SnapshotFileStatus.UNCHANGED) }
        }

        private fun failedCount(
            paths: Collection<String>,
            updates: Map<String, MergedGoldenItem>,
            fallback: Int,
        ): Int {
            if (paths.none(updates::containsKey)) return fallback
            return paths.count { updates[it]?.displayStatus == MergedGoldenStatus.FAILED }
        }

        private val TEST_CONTAINER_SUFFIX =
            Regex("""(?:SnapshotTests?|Tests?|Specs?)$""", RegexOption.IGNORE_CASE)

        private val SOURCE_EXTENSIONS = setOf(
            "c", "cc", "cpp", "cs", "dart", "go", "h", "hpp", "java", "js", "jsx", "kt", "kts",
            "m", "mm", "py", "rb", "rs", "scala", "swift", "ts", "tsx", "vue",
        )

        private val GENERIC_OWNER_NAMES = setOf(
            "connector", "constants", "content", "destination", "entity", "info", "model", "preview",
            "root", "screen", "state", "uistate", "view",
        )

        private val VIEW_OWNER_SUFFIXES = setOf(
            "Banner", "BottomSheet", "Card", "Component", "Dialog", "Page", "Row", "Screen", "Style",
            "Tile", "View", "Widget",
        )
    }
}

/** The settings that affect source-to-golden associations. */
data class SnapshotIndexFingerprint(
    val projectPath: String,
    val goldenPaths: List<String>,
    val matchMode: MatchMode,
    val annotatedFunctionRegex: String,
    val goldenFilePatterns: List<String>,
    val excludedSuffixes: List<String>,
) {
    companion object {
        fun from(projectRoot: File, config: GoldenDiffConfig): SnapshotIndexFingerprint =
            SnapshotIndexFingerprint(
                projectPath = projectRoot.normalize().path,
                goldenPaths = config.goldenPaths,
                matchMode = config.matchMode,
                annotatedFunctionRegex = config.annotatedFunctionRegex,
                goldenFilePatterns = config.goldenFilePatterns,
                excludedSuffixes = config.excludedSuffixes,
            )
    }
}
