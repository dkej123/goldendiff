package com.github.dkwasniak.goldendiff.project

import java.io.File

/**
 * Host-neutral Folder -> View -> Snapshot case navigation model.
 *
 * IDs are derived only from normalized project-relative paths and case evidence, so they remain
 * stable when a host rebuilds its UI rows or refreshes statuses.
 */
data class SnapshotTreeProjection(
    val folders: List<SnapshotFolderProjection>,
    val rootViews: List<SnapshotViewProjection>,
) {
    val views: List<SnapshotViewProjection>
        get() = buildList {
            addAll(rootViews)
            fun append(folder: SnapshotFolderProjection) {
                addAll(folder.views)
                folder.folders.forEach(::append)
            }
            folders.forEach(::append)
        }
}

data class SnapshotFolderProjection(
    val id: String,
    val name: String,
    val path: String,
    val sourcePaths: List<String>,
    /** Number of failed Views below this folder; each View is counted at most once. */
    val failedViewCount: Int,
    val folders: List<SnapshotFolderProjection>,
    val views: List<SnapshotViewProjection>,
)

data class SnapshotViewProjection(
    val id: String,
    val entry: SnapshotSourceFile,
    val cases: List<SnapshotCaseProjection>,
)

data class SnapshotCaseProjection(
    val id: String,
    val entry: SnapshotCase,
)

/**
 * Projects this index using case-insensitive search and OR-combined status filters.
 *
 * Search covers the View name/path, generator path, and case name. A matching descendant retains
 * all of its ancestors. A matching View retains its matching cases, while unmatched goldens remain
 * represented by the View itself and never create a synthetic Case.
 */
fun SnapshotFileIndex.projectTree(
    query: String = "",
    statusFilters: Set<SnapshotFileStatus> = emptySet(),
): SnapshotTreeProjection = projectSnapshotTree(files, query, statusFilters)

fun projectSnapshotTree(
    files: Collection<SnapshotSourceFile>,
    query: String = "",
    statusFilters: Set<SnapshotFileStatus> = emptySet(),
): SnapshotTreeProjection {
    val admitted = files.filter { it.matches(statusFilters) }
    val needle = query.trim().replace('\\', '/')
    val navigation = admitted.associateWith(::navigationPath)
    val packagePaths = navigation.values.filter { it.hasSourceRoot }.map { it.packagePath }
    val commonPackagePrefix = commonPathPrefix(packagePaths).let { prefix ->
        val keepAtLeastOne = (packagePaths.minOfOrNull(List<String>::size) ?: 0) - 1
        prefix.take(keepAtLeastOne.coerceAtLeast(0))
    }

    fun logicalSegments(entry: SnapshotSourceFile): List<String> {
        val path = navigation.getValue(entry)
        if (!path.hasSourceRoot) return path.packagePath
        var packageTail = path.packagePath.drop(commonPackagePrefix.size)
        if (packageTail.isNotEmpty() && path.module.any { it.equals(packageTail.first(), ignoreCase = true) }) {
            packageTail = packageTail.drop(1)
        }
        return (path.module + packageTail).ifEmpty { path.packagePath.takeLast(1) }
    }

    val failedViewCounts = mutableMapOf<String, Int>()
    admitted.filter { it.status == SnapshotFileStatus.FAILED }.forEach { entry ->
        var path = ""
        logicalSegments(entry).forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            failedViewCounts[path] = failedViewCounts.getOrDefault(path, 0) + 1
        }
    }

    val visible = admitted.mapNotNull { entry ->
        val viewMatches = needle.isEmpty() ||
            entry.relativePath.normalizedProjectPath().contains(needle, ignoreCase = true) ||
            File(entry.relativePath).name.contains(needle, ignoreCase = true)
        val cases = entry.cases
            .filter { it.matches(statusFilters) }
            .filter { case ->
                needle.isEmpty() || viewMatches ||
                    case.methodName.contains(needle, ignoreCase = true) ||
                    case.generatorPath.normalizedProjectPath().contains(needle, ignoreCase = true)
            }
            .sortedWith(CASE_ORDER)
        if (!viewMatches && cases.isEmpty()) return@mapNotNull null
        VisibleView(entry, cases)
    }

    val root = FolderBuilder("", "")
    visible.forEach { visibleView ->
        var folder = root
        var path = ""
        logicalSegments(visibleView.entry).forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            folder = folder.folders.getOrPut(segment) { FolderBuilder(segment, path) }
            folder.sourcePaths += navigation.getValue(visibleView.entry).originalFolder
        }
        folder.views += visibleView
    }

    fun buildFolder(builder: FolderBuilder): SnapshotFolderProjection {
        val childFolders = builder.folders.values.map(::buildFolder).sortedWith(FOLDER_ORDER)
        val views = builder.views.sortedWith(VISIBLE_VIEW_ORDER).map { visibleView ->
            SnapshotViewProjection(
                id = visibleView.entry.stableId,
                entry = visibleView.entry,
                cases = visibleView.cases.map { case ->
                    SnapshotCaseProjection(case.stableId, case)
                },
            )
        }
        return SnapshotFolderProjection(
            id = builder.path,
            name = builder.name,
            path = builder.path,
            sourcePaths = builder.sourcePaths.sortedWith(ProjectFileIndex.PATH_ORDER),
            failedViewCount = failedViewCounts.getOrDefault(builder.path, 0),
            folders = childFolders,
            views = views,
        )
    }

    val folders = root.folders.values.map(::buildFolder).sortedWith(FOLDER_ORDER)
    val rootViews = root.views.sortedWith(VISIBLE_VIEW_ORDER).map { visibleView ->
        SnapshotViewProjection(
            id = visibleView.entry.stableId,
            entry = visibleView.entry,
            cases = visibleView.cases.map { SnapshotCaseProjection(it.stableId, it) },
        )
    }
    return SnapshotTreeProjection(folders, rootViews)
}

/** Stable project-relative View identity, independent of host UI instances. */
val SnapshotSourceFile.stableId: String
    get() = relativePath.normalizedProjectPath()

/** Stable Case identity produced by [SnapshotFileIndex], independent of host UI instances. */
val SnapshotCase.stableId: String
    get() = id

private data class VisibleView(
    val entry: SnapshotSourceFile,
    val cases: List<SnapshotCase>,
)

private class FolderBuilder(
    val name: String,
    val path: String,
) {
    val folders = linkedMapOf<String, FolderBuilder>()
    val views = mutableListOf<VisibleView>()
    val sourcePaths = linkedSetOf<String>()
}

private data class NavigationPath(
    val module: List<String>,
    val packagePath: List<String>,
    val originalFolder: String,
    val hasSourceRoot: Boolean,
)

private fun navigationPath(entry: SnapshotSourceFile): NavigationPath {
    val normalized = entry.relativePath.normalizedProjectPath()
    val originalFolder = normalized.substringBeforeLast('/', "")
    val segments = originalFolder.split('/').filter(String::isNotBlank)
    val sourceRoot = segments.indices.firstOrNull { index ->
        segments[index].equals("src", ignoreCase = true) &&
            index + 2 < segments.size &&
            segments[index + 2].lowercase() in setOf("kotlin", "java")
    }
    return if (sourceRoot == null) {
        NavigationPath(emptyList(), segments, originalFolder, hasSourceRoot = false)
    } else {
        NavigationPath(
            module = segments.take(sourceRoot),
            packagePath = segments.drop(sourceRoot + 3),
            originalFolder = originalFolder,
            hasSourceRoot = true,
        )
    }
}

private fun commonPathPrefix(paths: List<List<String>>): List<String> {
    val first = paths.firstOrNull() ?: return emptyList()
    return first.takeWhileIndexed { index, segment ->
        paths.all { path -> path.getOrNull(index)?.equals(segment, ignoreCase = true) == true }
    }
}

private inline fun <T> List<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
    val result = ArrayList<T>()
    forEachIndexed { index, value ->
        if (!predicate(index, value)) return result
        result += value
    }
    return result
}

private fun SnapshotSourceFile.matches(filters: Set<SnapshotFileStatus>): Boolean =
    filters.isEmpty() || statuses.any(filters::contains)

private fun SnapshotCase.matches(filters: Set<SnapshotFileStatus>): Boolean =
    filters.isEmpty() || statuses.any(filters::contains)

private val SnapshotFileStatus.sortOrder: Int
    get() = when (this) {
        SnapshotFileStatus.FAILED -> 0
        SnapshotFileStatus.MODIFIED -> 1
        SnapshotFileStatus.NEW -> 2
        SnapshotFileStatus.UNCHANGED -> 3
    }

private val CASE_ORDER =
    compareBy<SnapshotCase> { it.status.sortOrder }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.methodName }
        .thenBy(ProjectFileIndex.PATH_ORDER) { it.generatorPath }
        .thenBy(SnapshotCase::id)

private val VISIBLE_VIEW_ORDER =
    compareBy<VisibleView> { it.entry.status.sortOrder }
        .thenBy(ProjectFileIndex.PATH_ORDER) { it.entry.relativePath.normalizedProjectPath() }

private val FOLDER_ORDER =
    compareBy<SnapshotFolderProjection> { it.statusSortOrder() }
        .thenBy(ProjectFileIndex.PATH_ORDER) { it.path }

private fun SnapshotFolderProjection.statusSortOrder(): Int =
    (views.map { it.entry.status.sortOrder } + folders.map { it.statusSortOrder() })
        .minOrNull() ?: SnapshotFileStatus.UNCHANGED.sortOrder

private fun String.normalizedProjectPath(): String =
    replace('\\', '/').removePrefix("./").trimStart('/')
