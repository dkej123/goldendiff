package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFolderProjection
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.project.SnapshotTreeProjection
import com.github.dkwasniak.goldendiff.project.SnapshotViewProjection

/** One flat row of the Files list: a sticky folder header, a View (file), or one of its Cases. */
internal sealed interface FilesRow {
    val id: String

    data class Folder(
        val path: String,
        val failedCount: Int,
        val viewCount: Int,
        val collapsed: Boolean,
    ) : FilesRow {
        override val id: String get() = "folder:$path"
    }

    data class View(
        val entry: SnapshotSourceFile,
        val status: GoldenDisplayStatus,
        val hasCases: Boolean,
        val expanded: Boolean,
        val selected: Boolean,
    ) : FilesRow {
        val viewId: String get() = entry.relativePath
        override val id: String get() = "view:$viewId"
    }

    data class Case(
        val viewId: String,
        val entry: SnapshotCase,
        val status: GoldenDisplayStatus,
        val selected: Boolean,
    ) : FilesRow {
        override val id: String get() = "case:${entry.id}"
    }
}

internal data class FilesListPresentation(
    val rows: List<FilesRow>,
    /** Every visible View in row order, including Views inside collapsed folders. */
    val viewOrder: List<String>,
    /** Every folder header path, including collapsed ones. */
    val folderPaths: List<String>,
    val totalViewCount: Int,
) {
    val countLabel: String get() = "${viewOrder.size} of $totalViewCount files"

    fun viewStatus(viewId: String): GoldenDisplayStatus? =
        rows.firstNotNullOfOrNull { row -> (row as? FilesRow.View)?.takeIf { it.viewId == viewId }?.status }
}

/**
 * Flattens the core Folder -> View -> Case projection into folder headers that each own the Views
 * placed directly in them (intermediate folders without Views get no header). An active query
 * expands every folder and View so matches are never hidden behind a collapsed row.
 */
internal fun filesListPresentation(
    projection: SnapshotTreeProjection,
    totalViewCount: Int,
    queryActive: Boolean,
    collapsedFolders: Set<String>,
    expandedViews: Set<String>,
    selection: SnapshotSelection,
    statusOfGolden: (String) -> GoldenDisplayStatus?,
): FilesListPresentation {
    val rows = mutableListOf<FilesRow>()
    val viewOrder = mutableListOf<String>()
    val folderPaths = mutableListOf<String>()

    fun statusOf(paths: List<String>, fallback: GoldenDisplayStatus): GoldenDisplayStatus {
        val known = paths.mapNotNull(statusOfGolden)
        return if (known.isEmpty()) fallback else worstDisplayStatus(known)
    }

    fun appendView(view: SnapshotViewProjection, visible: Boolean) {
        val entry = view.entry
        viewOrder += entry.relativePath
        if (!visible) return
        val ownsSelectedCase = selection.viewId == entry.relativePath && selection.caseId != null
        val expanded = view.cases.isNotEmpty() &&
            (queryActive || ownsSelectedCase || entry.relativePath in expandedViews)
        rows += FilesRow.View(
            entry = entry,
            status = statusOf(entry.goldenPaths, entry.status.toDisplayStatus()),
            hasCases = view.cases.isNotEmpty(),
            expanded = expanded,
            selected = selection.viewId == entry.relativePath && selection.caseId == null,
        )
        if (!expanded) return
        view.cases.forEach { case ->
            rows += FilesRow.Case(
                viewId = entry.relativePath,
                entry = case.entry,
                status = statusOf(case.entry.goldenPaths, case.entry.status.toDisplayStatus()),
                selected = selection.viewId == entry.relativePath && selection.caseId == case.entry.id,
            )
        }
    }

    fun appendFolder(folder: SnapshotFolderProjection) {
        if (folder.views.isNotEmpty()) {
            val collapsed = !queryActive && folder.path in collapsedFolders
            val failed = folder.views.count { view ->
                statusOf(view.entry.goldenPaths, view.entry.status.toDisplayStatus()) == GoldenDisplayStatus.FAILED
            }
            folderPaths += folder.path
            rows += FilesRow.Folder(folder.path, failed, folder.views.size, collapsed)
            folder.views.forEach { appendView(it, visible = !collapsed) }
        }
        folder.folders.forEach(::appendFolder)
    }

    projection.rootViews.forEach { appendView(it, visible = true) }
    projection.folders.forEach(::appendFolder)
    return FilesListPresentation(rows, viewOrder, folderPaths, totalViewCount)
}
