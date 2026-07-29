package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.Chevron
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.Direction
import com.github.dkwasniak.goldendiff.app.ui.FileIcon
import com.github.dkwasniak.goldendiff.app.ui.FilterField
import com.github.dkwasniak.goldendiff.app.ui.FolderIcon
import com.github.dkwasniak.goldendiff.app.ui.IconButton
import com.github.dkwasniak.goldendiff.app.ui.Pane
import com.github.dkwasniak.goldendiff.app.ui.TargetIcon
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.WrappingPaneHeader
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.app.generated.resources.Res
import com.github.dkwasniak.goldendiff.app.generated.resources.refresh
import com.github.dkwasniak.goldendiff.project.ProjectFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import java.io.File

internal data class SnapshotFileGroup(
    val folder: String,
    val files: List<SnapshotSourceFile>,
    val failedFileCount: Int,
)

internal sealed interface SnapshotTreeRow {
    val depth: Int

    data class Folder(
        val path: String,
        val name: String,
        val failedFileCount: Int,
        val sourcePaths: List<String>,
        val expanded: Boolean,
        override val depth: Int,
    ) : SnapshotTreeRow

    data class File(
        val entry: SnapshotSourceFile,
        val expanded: Boolean = false,
        override val depth: Int,
    ) : SnapshotTreeRow

    data class Case(
        val parent: SnapshotSourceFile,
        val entry: SnapshotCase,
        override val depth: Int,
    ) : SnapshotTreeRow
}

private class SnapshotFolderBuilder(
    val name: String,
    val path: String,
) {
    val folders = linkedMapOf<String, SnapshotFolderBuilder>()
    val files = mutableListOf<SnapshotSourceFile>()
    val sourcePaths = linkedSetOf<String>()
}

internal fun projectSnapshotFiles(
    files: List<SnapshotSourceFile>,
    query: String,
    statusFilters: Set<SnapshotFileStatus>,
): Pair<List<SnapshotSourceFile>, List<SnapshotFileGroup>> {
    val admitted = files.filter { it.status.matches(statusFilters) }
    val needle = query.trim()
    val visible = admitted.filter {
        needle.isEmpty() ||
            it.relativePath.contains(needle, ignoreCase = true) ||
            it.cases.any { case ->
                case.methodName.contains(needle, ignoreCase = true) ||
                    case.generatorPath.contains(needle, ignoreCase = true)
            }
    }
    val flat = visible.sortedWith(
        compareBy<SnapshotSourceFile> { it.status.sortOrder }
            .thenBy(ProjectFileIndex.PATH_ORDER) { it.relativePath },
    )
    val groups = visible.groupBy { it.relativePath.substringBeforeLast('/', ".") }
        .toSortedMap(ProjectFileIndex.PATH_ORDER)
        .map { (folder, entries) ->
            SnapshotFileGroup(
                folder,
                entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { File(it.relativePath).name }),
                admitted.count {
                    it.status == SnapshotFileStatus.FAILED &&
                        it.relativePath.substringBeforeLast('/', ".") == folder
                },
            )
        }
    return flat to groups
}

private data class SnapshotNavigationPath(
    val entry: SnapshotSourceFile,
    val module: List<String>,
    val packagePath: List<String>,
    val originalFolder: String,
    val hasSourceRoot: Boolean,
)

/**
 * Source-root-aware grouped projection.
 *
 * Conventional `src/<source-set>/<language>` segments and the package prefix shared by every
 * indexed source are structural context rather than useful navigation. Module paths and actual
 * package branches remain visible. Full original paths stay attached to folder rows for tooltips.
 */
internal fun projectSnapshotTree(
    files: List<SnapshotSourceFile>,
    query: String,
    statusFilters: Set<SnapshotFileStatus>,
    collapsedFolders: Set<String> = emptySet(),
    expandedViews: Set<String> = emptySet(),
): List<SnapshotTreeRow> {
    val admitted = files.filter { it.status.matches(statusFilters) }
    val needle = query.trim()
    val visible = admitted.filter {
        needle.isEmpty() ||
            it.relativePath.contains(needle, ignoreCase = true) ||
            it.cases.any { case ->
                case.methodName.contains(needle, ignoreCase = true) ||
                    case.generatorPath.contains(needle, ignoreCase = true)
            }
    }
    val parsed = admitted.associateWith(::snapshotNavigationPath)
    val packagePaths = parsed.values.filter { it.hasSourceRoot }.map { it.packagePath }
    val commonPackagePrefix = commonPathPrefix(packagePaths).let { prefix ->
        val keepAtLeastOne = (packagePaths.minOfOrNull(List<String>::size) ?: 0) - 1
        prefix.take(keepAtLeastOne.coerceAtLeast(0))
    }
    fun logicalSegments(entry: SnapshotSourceFile): List<String> {
        val navigation = parsed.getValue(entry)
        if (!navigation.hasSourceRoot) return navigation.packagePath
        var packageTail = navigation.packagePath.drop(commonPackagePrefix.size)
        if (
            packageTail.isNotEmpty() &&
            navigation.module.any { it.equals(packageTail.first(), ignoreCase = true) }
        ) {
            packageTail = packageTail.drop(1)
        }
        return (navigation.module + packageTail).ifEmpty {
            navigation.packagePath.takeLast(1)
        }
    }

    val failedCounts = mutableMapOf<String, Int>()
    admitted.filter { it.status == SnapshotFileStatus.FAILED }.forEach { entry ->
        var path = ""
        logicalSegments(entry).forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            failedCounts[path] = failedCounts.getOrDefault(path, 0) + 1
        }
    }

    val root = SnapshotFolderBuilder("", "")
    visible.forEach { entry ->
        var node = root
        var path = ""
        logicalSegments(entry).forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            node = node.folders.getOrPut(segment) { SnapshotFolderBuilder(segment, path) }
            node.sourcePaths += parsed.getValue(entry).originalFolder
        }
        node.files += entry
    }

    return buildList {
        fun appendContents(node: SnapshotFolderBuilder, depth: Int) {
            node.files
                .sortedWith(
                    compareBy<SnapshotSourceFile> { it.status.sortOrder }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { File(it.relativePath).name },
                )
                .forEach { entry ->
                    val parentMatches = needle.isNotEmpty() &&
                        entry.relativePath.contains(needle, ignoreCase = true)
                    val cases = entry.cases.filter { case ->
                        case.status.matches(statusFilters) &&
                            (
                                needle.isEmpty() ||
                                    parentMatches ||
                                    case.methodName.contains(needle, ignoreCase = true) ||
                                    case.generatorPath.contains(needle, ignoreCase = true)
                                )
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SnapshotCase::methodName))
                    val expanded = entry.relativePath in expandedViews ||
                        needle.isNotEmpty() && cases.isNotEmpty()
                    add(SnapshotTreeRow.File(entry, expanded, depth))
                    if (expanded) {
                        cases.forEach { case ->
                            add(SnapshotTreeRow.Case(entry, case, depth + 1))
                        }
                    }
                }
            node.folders.values
                .sortedWith(
                    compareBy<SnapshotFolderBuilder> { !it.hasLabeledStatus() }
                        .thenBy(ProjectFileIndex.PATH_ORDER) { it.path },
                )
                .forEach { folder ->
                    add(
                        SnapshotTreeRow.Folder(
                            path = folder.path,
                            name = folder.name,
                            failedFileCount = failedCounts.getOrDefault(folder.path, 0),
                            sourcePaths = folder.sourcePaths.sortedWith(ProjectFileIndex.PATH_ORDER),
                            expanded = folder.path !in collapsedFolders,
                            depth = depth,
                        ),
                    )
                    if (folder.path !in collapsedFolders) appendContents(folder, depth + 1)
                }
        }
        appendContents(root, 0)
    }
}

internal fun projectSnapshotFlatRows(
    files: List<SnapshotSourceFile>,
    query: String,
    statusFilters: Set<SnapshotFileStatus>,
    expandedViews: Set<String> = emptySet(),
): List<SnapshotTreeRow> {
    val (visible, _) = projectSnapshotFiles(files, query, statusFilters)
    val needle = query.trim()
    return buildList {
        visible.forEach { entry ->
            val parentMatches = needle.isNotEmpty() && entry.relativePath.contains(needle, ignoreCase = true)
            val cases = entry.cases.filter { case ->
                case.status.matches(statusFilters) &&
                    (
                        needle.isEmpty() ||
                            parentMatches ||
                            case.methodName.contains(needle, ignoreCase = true) ||
                            case.generatorPath.contains(needle, ignoreCase = true)
                        )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SnapshotCase::methodName))
            val expanded = entry.relativePath in expandedViews || needle.isNotEmpty() && cases.isNotEmpty()
            add(SnapshotTreeRow.File(entry, expanded, 0))
            if (expanded) cases.forEach { add(SnapshotTreeRow.Case(entry, it, 1)) }
        }
    }
}

private val SnapshotFileStatus.sortOrder: Int
    get() = when (this) {
        SnapshotFileStatus.FAILED -> 0
        SnapshotFileStatus.MODIFIED -> 1
        SnapshotFileStatus.NEW -> 2
        SnapshotFileStatus.UNCHANGED -> 3
    }

private fun SnapshotFileStatus.matches(filters: Set<SnapshotFileStatus>): Boolean =
    filters.isEmpty() || this in filters

private fun SnapshotFolderBuilder.hasLabeledStatus(): Boolean =
    files.any { it.status != SnapshotFileStatus.UNCHANGED } ||
        folders.values.any(SnapshotFolderBuilder::hasLabeledStatus)

private fun snapshotNavigationPath(entry: SnapshotSourceFile): SnapshotNavigationPath {
    val originalFolder = entry.relativePath.substringBeforeLast('/', "")
    val segments = originalFolder.split('/').filter(String::isNotBlank)
    val sourceRoot = segments.indices.firstOrNull { index ->
        segments[index] == "src" &&
            index + 2 < segments.size &&
            segments[index + 2] in setOf("kotlin", "java")
    }
    return if (sourceRoot == null) {
        SnapshotNavigationPath(entry, emptyList(), segments, originalFolder, hasSourceRoot = false)
    } else {
        SnapshotNavigationPath(
            entry = entry,
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

/** Left pane: only source files that current matching associates with at least one existing golden. */
@Composable
fun ProjectPane(state: AppState, modifier: Modifier) {
    var grouped by remember { mutableStateOf(true) }
    var statusFilters by remember { mutableStateOf(emptySet<SnapshotFileStatus>()) }
    var collapsedFolders by remember { mutableStateOf(emptySet<String>()) }
    var expandedViews by remember { mutableStateOf(emptySet<String>()) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val index = state.snapshotFileIndex
    val (flat, _) = projectSnapshotFiles(index?.files.orEmpty(), state.treeFilter, statusFilters)
    val treeRows = projectSnapshotTree(
        index?.files.orEmpty(),
        state.treeFilter,
        statusFilters,
        collapsedFolders = collapsedFolders.takeIf { state.treeFilter.isBlank() }.orEmpty(),
        expandedViews = expandedViews,
    )
    val flatRows = projectSnapshotFlatRows(index?.files.orEmpty(), state.treeFilter, statusFilters, expandedViews)
    val displayedRows = if (grouped) treeRows else flatRows
    val revealRequest = state.treeRevealRequest
    val revealTargetIndex = revealRequest?.let { request ->
        displayedRows.indexOfFirst { row ->
            when {
                request.caseId != null -> (row as? SnapshotTreeRow.Case)?.entry?.id == request.caseId
                else -> (row as? SnapshotTreeRow.File)?.entry?.relativePath == request.sourcePath
            }
        }.takeIf { it >= 0 }
    }

    LaunchedEffect(revealRequest?.sequence) {
        val target = revealRequest?.sourcePath ?: return@LaunchedEffect
        val targetFile = index?.files?.firstOrNull { it.relativePath == target } ?: return@LaunchedEffect
        val targetCase = revealRequest.caseId?.let { id -> targetFile.cases.firstOrNull { it.id == id } }
        if (
            !targetFile.status.matches(statusFilters) ||
            targetCase != null && !targetCase.status.matches(statusFilters)
        ) {
            statusFilters = emptySet()
        }
        val filter = state.treeFilter.trim()
        val filterMatches = target.contains(filter, ignoreCase = true) ||
            targetCase?.methodName?.contains(filter, ignoreCase = true) == true ||
            targetCase?.generatorPath?.contains(filter, ignoreCase = true) == true
        if (
            filter.isNotBlank() &&
            !filterMatches
        ) {
            state.treeFilter = ""
        }
        // A reveal must win over manual collapsing; the follow-up composition computes the target's
        // lazy-list index and the effect below performs the actual scroll.
        if (grouped && collapsedFolders.isNotEmpty()) collapsedFolders = emptySet()
        revealRequest.caseId?.let { expandedViews = expandedViews + target }
    }
    // The index has to stay in the key, because a reveal that had to clear a filter or expand a folder
    // only resolves to a row one composition later. But it also moves whenever the user expands
    // anything above the revealed row, which used to replay the last reveal as a scroll on every
    // toggle - hence scrolling once per request and ignoring later index shifts.
    var scrolledRevealSequence by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(revealRequest?.sequence, revealTargetIndex) {
        val sequence = revealRequest?.sequence ?: return@LaunchedEffect
        if (sequence == scrolledRevealSequence) return@LaunchedEffect
        val targetIndex = revealTargetIndex ?: return@LaunchedEffect
        scrolledRevealSequence = sequence
        listState.animateScrollToItem(targetIndex)
    }

    Pane(modifier) {
        WrappingPaneHeader(
            minHeight = 32.dp,
            horizontalPadding = 10.dp,
            verticalPadding = 6.dp,
            spacing = 8.dp,
            leading = {
                Text(
                    "FILES",
                    color = tokens.textFaint,
                    fontSize = Type.thumbLabel,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
            },
            actions = {
                Text("Group", color = tokens.textDim, fontSize = Type.small)
                Toggle(grouped) { grouped = !grouped }
                IconButton(size = 18.dp, onClick = state::revealSelectedGoldenInTree) {
                    TargetIcon(
                        if (state.selected != null) tokens.textDim else tokens.textFaint.copy(alpha = 0.35f),
                        size = 13.dp,
                    )
                }
                IconButton(size = 18.dp, onClick = state::toggleLeftPane) {
                    Chevron(Direction.LEFT, tokens.textFaint)
                }
            },
        )
        FilterField(
            value = state.treeFilter,
            placeholder = "Filter files…",
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        ) { state.treeFilter = it }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(
                SnapshotFileStatus.FAILED to "Failed",
                SnapshotFileStatus.MODIFIED to "Modified",
                SnapshotFileStatus.NEW to "New",
            ).forEach { (status, label) ->
                FilterChip(label, status, status in statusFilters) {
                    statusFilters = if (status in statusFilters) statusFilters - status else statusFilters + status
                }
            }
        }
        if (state.snapshotIndexing && index != null) {
            Row(
                Modifier.fillMaxWidth().background(tokens.accentBg).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CircularProgressIndicator(Modifier.width(11.dp).height(11.dp), color = tokens.accent, strokeWidth = 1.5.dp)
                Text("Indexing snapshots…", color = tokens.textDim, fontSize = Type.small)
            }
        }
        when {
            state.projectRoot == null -> PaneHint("Open a project to browse snapshot files.")
            index == null -> PaneHint("Scanning for screenshots…")
            index.files.isEmpty() -> PaneHint("No files with snapshots")
            flat.isEmpty() && statusFilters.isNotEmpty() && state.treeFilter.isBlank() ->
                PaneActionHint("No files match the selected statuses", "Show all files") {
                    statusFilters = emptySet()
                }
            flat.isEmpty() ->
                PaneActionHint("No files match the active filters", "Clear search") { state.treeFilter = "" }
            else -> LazyColumn(
                Modifier.fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        nextSnapshotRow(
                            displayedRows,
                            state.treeHighlight,
                            state.treeHighlightCaseId,
                            delta,
                        )?.let { target ->
                            when (target) {
                                is SnapshotTreeRow.File ->
                                    state.selectSourceFile(target.entry.relativePath, "keyboard")
                                is SnapshotTreeRow.Case ->
                                    state.selectSnapshotCase(target.parent.relativePath, target.entry.id, "keyboard")
                                is SnapshotTreeRow.Folder -> Unit
                            }
                            val targetIndex = displayedRows.indexOf(target)
                            val targetVisible = listState.layoutInfo.visibleItemsInfo.any { visible ->
                                visible.index == targetIndex
                            }
                            if (targetIndex >= 0 && !targetVisible) {
                                scope.launch { listState.animateScrollToItem(targetIndex) }
                            }
                        }
                        true
                    }
                    .padding(bottom = 8.dp),
                state = listState,
            ) {
                if (grouped) {
                    items(
                        treeRows,
                        key = { row ->
                            when (row) {
                                is SnapshotTreeRow.Folder -> "folder:${row.path}"
                                is SnapshotTreeRow.File -> "file:${row.entry.relativePath}"
                                is SnapshotTreeRow.Case -> "case:${row.entry.id}"
                            }
                        },
                    ) { row ->
                        when (row) {
                            is SnapshotTreeRow.Folder -> FolderHeader(row) {
                                collapsedFolders = if (row.expanded) {
                                    collapsedFolders + row.path
                                } else {
                                    collapsedFolders - row.path
                                }
                            }
                            is SnapshotTreeRow.File -> SnapshotFileRow(
                                state,
                                row.entry,
                                grouped = true,
                                depth = row.depth,
                                expanded = row.expanded,
                                onToggle = {
                                    expandedViews = if (row.expanded) {
                                        expandedViews - row.entry.relativePath
                                    } else {
                                        expandedViews + row.entry.relativePath
                                    }
                                },
                            ) {
                                focusRequester.requestFocus()
                                state.selectSourceFile(row.entry.relativePath)
                            }
                            is SnapshotTreeRow.Case -> SnapshotCaseRow(state, row) {
                                focusRequester.requestFocus()
                                state.selectSnapshotCase(row.parent.relativePath, row.entry.id)
                            }
                        }
                    }
                } else {
                    items(
                        flatRows,
                        key = { row ->
                            when (row) {
                                is SnapshotTreeRow.File -> "flat-file:${row.entry.relativePath}"
                                is SnapshotTreeRow.Case -> "flat-case:${row.entry.id}"
                                is SnapshotTreeRow.Folder -> "flat-folder:${row.path}"
                            }
                        },
                    ) { row ->
                        when (row) {
                            is SnapshotTreeRow.File -> SnapshotFileRow(
                                state,
                                row.entry,
                                grouped = false,
                                depth = row.depth,
                                expanded = row.expanded,
                                onToggle = {
                                    expandedViews = if (row.expanded) {
                                        expandedViews - row.entry.relativePath
                                    } else {
                                        expandedViews + row.entry.relativePath
                                    }
                                },
                            ) {
                                focusRequester.requestFocus()
                                state.selectSourceFile(row.entry.relativePath)
                            }
                            is SnapshotTreeRow.Case -> SnapshotCaseRow(state, row) {
                                focusRequester.requestFocus()
                                state.selectSnapshotCase(row.parent.relativePath, row.entry.id)
                            }
                            is SnapshotTreeRow.Folder -> Unit
                        }
                    }
                }
            }
        }
    }
}

internal fun nextSnapshotRow(
    rows: List<SnapshotTreeRow>,
    selectedPath: String?,
    selectedCaseId: String?,
    delta: Int,
): SnapshotTreeRow? {
    val selectable = rows.filter { it is SnapshotTreeRow.File || it is SnapshotTreeRow.Case }
    if (selectable.isEmpty()) return null
    var current = selectable.indexOfFirst { row ->
        when (row) {
            is SnapshotTreeRow.File ->
                selectedCaseId == null && row.entry.relativePath == selectedPath
            is SnapshotTreeRow.Case ->
                row.parent.relativePath == selectedPath && row.entry.id == selectedCaseId
            is SnapshotTreeRow.Folder -> false
        }
    }
    // A selected case can become hidden when its parent is collapsed. Continue from the visible
    // parent instead of jumping to the beginning/end of the tree.
    if (current < 0 && selectedPath != null) {
        current = selectable.indexOfFirst { row ->
            row is SnapshotTreeRow.File && row.entry.relativePath == selectedPath
        }
    }
    val start = current.takeIf { it >= 0 } ?: if (delta > 0) -1 else selectable.size
    return selectable[(start + delta).coerceIn(0, selectable.lastIndex)]
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FolderHeader(folder: SnapshotTreeRow.Folder, onToggle: () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).background(tokens.panel)
                    .border(1.dp, tokens.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    folder.sourcePaths.joinToString("\n"),
                    color = tokens.textDim,
                    fontSize = Type.small,
                    fontFamily = Type.Mono,
                )
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(
                    start = (10 + folder.depth * 14).dp,
                    end = 10.dp,
                    top = 7.dp,
                    bottom = 3.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Chevron(
                if (folder.expanded) Direction.DOWN else Direction.RIGHT,
                tokens.textFaint,
                size = 7.dp,
            )
            FolderIcon(tokens.textFaint)
            Text(
                folder.name,
                color = tokens.textFaint,
                fontSize = Type.thumbLabel,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (folder.failedFileCount > 0) {
                StatusBadge(folder.failedFileCount.toString(), SnapshotFileStatus.FAILED)
            }
        }
    }
}

@Composable
private fun SnapshotFileRow(
    state: AppState,
    entry: SnapshotSourceFile,
    grouped: Boolean,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val selected = state.treeHighlight == entry.relativePath
    val shape = RoundedCornerShape(Dimens.controlRadius)
    SnapshotFileContextMenu(state, entry.relativePath) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(Dimens.rowHeight).clip(shape)
                .background(if (selected) tokens.accentBg else Color.Transparent)
                .border(1.dp, if (selected) tokens.accentBorder else Color.Transparent, shape)
                .hoverWash(!selected, shape)
                .clickable(onClick = onSelect)
                .padding(start = if (grouped) (10 + depth * 14).dp else 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (entry.cases.isNotEmpty()) {
                Box(
                    Modifier.width(14.dp).height(20.dp).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Chevron(if (expanded) Direction.DOWN else Direction.RIGHT, tokens.textFaint, size = 7.dp)
                }
            } else {
                Spacer(Modifier.width(14.dp))
            }
            FileIcon(tokens.textFaint)
            Column(Modifier.weight(1f)) {
                Text(
                    File(entry.relativePath).name,
                    color = if (selected) tokens.text else tokens.textDim,
                    fontSize = Type.body,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!grouped && entry.relativePath.contains('/')) {
                    Text(
                        entry.relativePath.substringBeforeLast('/'),
                        color = tokens.textFaint,
                        fontSize = 9.5.sp,
                        fontFamily = Type.Mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.status != SnapshotFileStatus.UNCHANGED) {
                StatusBadge(entry.status.name.lowercase().replaceFirstChar(Char::uppercase), entry.status)
            }
            if (entry.status != SnapshotFileStatus.UNCHANGED) {
                VerifySnapshotFileButton(state, entry.relativePath)
            }
        }
    }
}

@Composable
private fun SnapshotCaseRow(
    state: AppState,
    row: SnapshotTreeRow.Case,
    onSelect: () -> Unit,
) {
    val selected = state.treeHighlightCaseId == row.entry.id
    val shape = RoundedCornerShape(Dimens.controlRadius)
    SnapshotCaseContextMenu(state, row.parent, row.entry) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(Dimens.rowHeight).clip(shape)
                .background(if (selected) tokens.accentBg else Color.Transparent)
                .border(1.dp, if (selected) tokens.accentBorder else Color.Transparent, shape)
                .hoverWash(!selected, shape)
                .clickable(onClick = onSelect)
                .padding(start = (10 + row.depth * 14).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Spacer(Modifier.width(14.dp))
            Text(
                row.entry.methodName,
                color = if (selected) tokens.text else tokens.textDim,
                fontSize = Type.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.entry.status != SnapshotFileStatus.UNCHANGED) {
                StatusBadge(
                    row.entry.status.name.lowercase().replaceFirstChar(Char::uppercase),
                    row.entry.status,
                )
            }
            if (row.entry.status != SnapshotFileStatus.UNCHANGED) {
                VerifySnapshotCaseButton(state, row.parent, row.entry)
            }
        }
    }
}

@Composable
private fun SnapshotFileContextMenu(
    state: AppState,
    relativePath: String,
    content: @Composable () -> Unit,
) {
    val root = state.projectRoot
    if (root == null) {
        content()
        return
    }
    FileContextMenu(
        state = state,
        file = File(root, relativePath),
        canDelete = false,
        title = File(relativePath).name,
        additionalItems = {
            commandMenuItems(state) { kind -> state.runFileCommand(kind, relativePath) }
        },
        content = content,
    )
}

@Composable
private fun SnapshotCaseContextMenu(
    state: AppState,
    parent: SnapshotSourceFile,
    case: SnapshotCase,
    content: @Composable () -> Unit,
) {
    val root = state.projectRoot
    if (root == null) {
        content()
        return
    }
    FileContextMenu(
        state = state,
        file = File(root, case.generatorPath),
        canDelete = false,
        title = case.methodName,
        additionalItems = {
            commandMenuItems(state, method = true) { kind ->
                state.runCaseCommand(kind, parent.relativePath, case.id)
            }
        },
        content = content,
    )
}

private fun commandMenuItems(
    state: AppState,
    method: Boolean = false,
    run: (AppState.CommandKind) -> Unit,
): List<ContextMenuItem> {
    if (state.commandRunning) {
        return listOf(
            ActionContextMenuItem("Show command log", ContextActionIcon.LOG) {
                state.commandLogVisible = true
            },
        )
    }
    val scope = if (method) "method" else "file"
    return buildList {
        AppState.CommandKind.entries.forEach { kind ->
            val available = if (method) state.hasCaseCommand(kind) else state.hasCommand(kind, perFile = true)
            if (available) {
                add(
                    ActionContextMenuItem(
                        "${kind.label} for $scope",
                        if (kind == AppState.CommandKind.GENERATE) {
                            ContextActionIcon.GENERATE
                        } else {
                            ContextActionIcon.VERIFY
                        },
                    ) { run(kind) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VerifySnapshotFileButton(state: AppState, relativePath: String) {
    val hasCommand = state.hasCommand(AppState.CommandKind.VERIFY, perFile = true)
    val running = state.commandRunning &&
        state.commandKind == AppState.CommandKind.VERIFY &&
        state.commandPerFile &&
        state.commandSourcePath == relativePath
    val enabled = hasCommand && (!state.commandRunning || running)
    val tip = when {
        !hasCommand -> "Set a Verify current file command in Settings → Commands."
        running -> "Verify is running — click to view the log."
        state.commandRunning -> "A screenshot command is already running."
        else -> "Verify this file"
    }
    TooltipArea(
        tooltip = {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).background(tokens.panel)
                    .border(1.dp, tokens.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(tip, color = tokens.textDim, fontSize = Type.small)
            }
        },
    ) {
        Box(
            Modifier.width(20.dp).height(20.dp)
                .clip(RoundedCornerShape(Dimens.controlRadius))
                .hoverWash(enabled = enabled)
                .clickable(enabled = enabled) {
                    if (running) {
                        state.commandLogVisible = true
                    } else {
                        state.runFileCommand(AppState.CommandKind.VERIFY, relativePath)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    Modifier.width(12.dp).height(12.dp),
                    color = tokens.accent,
                    strokeWidth = 1.6.dp,
                )
            } else {
                Icon(
                    painter = painterResource(Res.drawable.refresh),
                    contentDescription = "Verify ${File(relativePath).name}",
                    tint = tokens.accent.copy(alpha = if (enabled) 1f else 0.35f),
                    modifier = Modifier.width(15.dp).height(15.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VerifySnapshotCaseButton(
    state: AppState,
    parent: SnapshotSourceFile,
    case: SnapshotCase,
) {
    val hasCommand = state.hasVerifyCaseCommand()
    val running = state.commandRunning &&
        state.commandKind == AppState.CommandKind.VERIFY &&
        state.commandCaseId == case.id
    val enabled = hasCommand && (!state.commandRunning || running)
    val fallback = state.config.verifyCaseCommand.isBlank() && state.config.verifyFileCommand.isNotBlank()
    val tip = when {
        !hasCommand -> "Set Verify current case or Verify current file in Settings → Commands."
        running -> "Verify is running — click to view the log."
        state.commandRunning -> "A screenshot command is already running."
        fallback -> "Verify this case using the generator's whole-file command."
        else -> "Verify this snapshot case"
    }
    TooltipArea(
        tooltip = {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).background(tokens.panel)
                    .border(1.dp, tokens.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(tip, color = tokens.textDim, fontSize = Type.small)
            }
        },
    ) {
        Box(
            Modifier.width(20.dp).height(20.dp)
                .clip(RoundedCornerShape(Dimens.controlRadius))
                .hoverWash(enabled = enabled)
                .clickable(enabled = enabled) {
                    if (running) state.commandLogVisible = true
                    else state.runVerifyCase(parent.relativePath, case.id)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    Modifier.width(12.dp).height(12.dp),
                    color = tokens.accent,
                    strokeWidth = 1.6.dp,
                )
            } else {
                Icon(
                    painter = painterResource(Res.drawable.refresh),
                    contentDescription = "Verify ${case.methodName}",
                    tint = tokens.accent.copy(alpha = if (enabled) 1f else 0.35f),
                    modifier = Modifier.width(15.dp).height(15.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, status: SnapshotFileStatus) {
    val color = when (status) {
        SnapshotFileStatus.FAILED -> tokens.changed
        SnapshotFileStatus.MODIFIED -> tokens.modified
        SnapshotFileStatus.NEW -> tokens.new
        SnapshotFileStatus.UNCHANGED -> tokens.textFaint
    }
    Text(
        label,
        color = color,
        fontSize = 8.5.sp,
        // Pinned rather than left to the font's own leading: the default line height padded the badge
        // out to most of the 26dp row, which is what made it crowd the rows above and below.
        lineHeight = 9.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun FilterChip(
    label: String,
    status: SnapshotFileStatus,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val statusColor = when (status) {
        SnapshotFileStatus.FAILED -> tokens.changed
        SnapshotFileStatus.MODIFIED -> tokens.modified
        SnapshotFileStatus.NEW -> tokens.new
        SnapshotFileStatus.UNCHANGED -> tokens.textFaint
    }
    val color = if (selected) statusColor else tokens.textDim
    Text(
        label,
        color = color,
        fontSize = Type.small,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) statusColor.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) statusColor.copy(alpha = 0.5f) else tokens.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun CollapsedProjectEdge(state: AppState, modifier: Modifier) {
    Box(
        modifier.width(14.dp).clip(RoundedCornerShape(Dimens.controlRadius))
            .background(tokens.panel).border(1.dp, tokens.border, RoundedCornerShape(Dimens.controlRadius))
            .hoverWash().clickable { state.toggleLeftPane() },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(Direction.RIGHT, tokens.textFaint)
    }
}

@Composable
private fun PaneHint(text: String) {
    Text(text, color = tokens.textDim, fontSize = Type.small, modifier = Modifier.padding(12.dp))
}

@Composable
private fun PaneActionHint(title: String, action: String, onClick: () -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = tokens.textDim, fontSize = Type.small)
        Text(action, color = tokens.accent, fontSize = Type.small, modifier = Modifier.clickable(onClick = onClick))
    }
}
