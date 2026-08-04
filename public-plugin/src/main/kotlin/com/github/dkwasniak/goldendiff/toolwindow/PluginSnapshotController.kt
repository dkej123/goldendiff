package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.watch.SnapshotLoadState

internal data class SnapshotSelection(
    val viewId: String? = null,
    val caseId: String? = null,
    val goldenId: String? = null,
)

internal data class PluginSnapshotState(
    val loadState: SnapshotLoadState = SnapshotLoadState.INITIAL_INDEXING,
    val index: SnapshotFileIndex? = null,
    val mergedItems: List<MergedGoldenItem> = emptyList(),
    val selection: SnapshotSelection = SnapshotSelection(),
    val activeEditorPath: String? = null,
    val activeFileHasSnapshots: Boolean = true,
    val errorMessage: String? = null,
    val generation: Long = 0,
    val extraGeneration: Long = 0,
    val extraViewId: String? = null,
    val extraItems: List<GoldenGridItem.Extension> = emptyList(),
    val selectedGridId: String? = null,
    val extraOnly: Boolean = false,
) {
    val selectedView: SnapshotSourceFile?
        get() = index?.files?.firstOrNull { it.relativePath == selection.viewId }

    val selectedCase: SnapshotCase?
        get() = selectedView?.cases?.firstOrNull { it.id == selection.caseId }

    val selectedItems: List<MergedGoldenItem>
        get() {
            val paths = (selectedCase?.goldenPaths ?: selectedView?.goldenPaths).orEmpty().toHashSet()
            return mergedItems.filter { it.golden.normalize().path in paths }
        }

    val selectedGolden: MergedGoldenItem?
        get() = selectedItems.firstOrNull { it.golden.normalize().path == selection.goldenId }

    val extensionsForSelection: List<GoldenGridItem.Extension>
        get() {
            if (selectedView?.relativePath != extraViewId) return emptyList()
            val method = selectedCase?.methodName ?: return extraItems
            return extraItems.filter { it.item.title == method }
        }

    val gridItems: List<GoldenGridItem>
        get() = mergedGoldenGridItems(selectedItems, extensionsForSelection)

    val visibleGridItems: List<GoldenGridItem>
        get() = if (extraOnly) extensionsForSelection else gridItems

    val selectedGridItem: GoldenGridItem?
        get() = visibleGridItems.firstOrNull { it.id == selectedGridId }

    val extraFilterSource
        get() = extensionsForSelection.firstOrNull()?.source
}

/**
 * Typed, Swing-independent state transitions for the plugin snapshot workflow. I/O is owned by the
 * panel, while this class prevents stale async results from replacing a newer refresh and restores
 * View/Case/golden selection by stable ids.
 */
internal class PluginSnapshotController(
    private val onStateChanged: (PluginSnapshotState) -> Unit,
) {
    internal data class MergedUpdateRequest(val refreshGeneration: Long, val sequence: Long)

    private var mergedUpdateSequence = 0L
    var state: PluginSnapshotState = PluginSnapshotState()
        private set

    fun setConfigured(configured: Boolean) {
        if (configured) return
        publish(
            state.copy(
                loadState = SnapshotLoadState.UNCONFIGURED,
                index = null,
                mergedItems = emptyList(),
                selection = SnapshotSelection(),
                selectedGridId = null,
                extraItems = emptyList(),
                extraViewId = null,
                extraOnly = false,
                errorMessage = null,
                generation = state.generation + 1,
                extraGeneration = state.extraGeneration + 1,
            ),
        )
    }

    fun beginRefresh(): Long {
        mergedUpdateSequence++
        val generation = state.generation + 1
        publish(
            state.copy(
                loadState = if (state.index == null) {
                    SnapshotLoadState.INITIAL_INDEXING
                } else {
                    SnapshotLoadState.REFRESHING
                },
                errorMessage = null,
                generation = generation,
            ),
        )
        return generation
    }

    fun completeRefresh(
        generation: Long,
        index: SnapshotFileIndex,
        mergedItems: List<MergedGoldenItem>,
        activeEditorPath: String?,
    ) {
        if (generation != state.generation) return
        mergedUpdateSequence++
        val previous = state.selection
        val activeView = activeEditorPath?.let { path -> index.files.firstOrNull { it.relativePath == path } }
        val selectedView = index.files.firstOrNull { it.relativePath == previous.viewId }
            ?: activeView
            ?: index.files.firstOrNull()
        val selectedCase = selectedView?.cases?.firstOrNull { it.id == previous.caseId }
        val visiblePaths = (selectedCase?.goldenPaths ?: selectedView?.goldenPaths).orEmpty()
        val selectedGolden = previous.goldenId?.takeIf(visiblePaths::contains)
            ?: visiblePaths.firstOrNull()
        val selectedGridId = state.selectedGridId
            ?.takeIf { id -> id.startsWith("extension:") || id == selectedGolden?.let(::builtInGridId) }
            ?: selectedGolden?.let(::builtInGridId)
        publish(
            state.copy(
                loadState = if (index.files.isEmpty()) SnapshotLoadState.EMPTY else SnapshotLoadState.READY,
                index = index,
                mergedItems = mergedItems,
                selection = SnapshotSelection(selectedView?.relativePath, selectedCase?.id, selectedGolden),
                selectedGridId = selectedGridId,
                activeEditorPath = activeEditorPath,
                activeFileHasSnapshots = activeEditorPath == null || activeView != null,
                errorMessage = null,
            ),
        )
    }

    /**
     * Patches known-affected goldens in place without a full rescan — used both for watcher-driven
     * in-place PNG updates and for Accept/Accept All, which already know the exact outcome of their
     * file operation. Leaves [PluginSnapshotState.loadState] and generation untouched.
     */
    fun beginMergedItemUpdate(): MergedUpdateRequest =
        MergedUpdateRequest(state.generation, ++mergedUpdateSequence)

    fun applyMergedItemUpdates(request: MergedUpdateRequest, updated: List<MergedGoldenItem>) {
        if (request.refreshGeneration != state.generation || request.sequence != mergedUpdateSequence) return
        if (updated.isEmpty()) return
        val byPath = updated.associateBy { it.golden.normalize().path }
        val merged = state.mergedItems.map { item -> byPath[item.golden.normalize().path] ?: item }
        if (merged == state.mergedItems) return
        val index = state.index?.let { SnapshotFileIndex.refreshStatuses(it, updated) }
        publish(state.copy(mergedItems = merged, index = index))
    }

    fun failRefresh(generation: Long, error: Throwable) {
        if (generation != state.generation) return
        publish(
            state.copy(
                loadState = SnapshotLoadState.ERROR,
                errorMessage = error.message ?: "Snapshot indexing failed.",
            ),
        )
    }

    fun editorChanged(relativePath: String?) {
        if (relativePath == state.activeEditorPath) return
        val view = relativePath?.let { path -> state.index?.files?.firstOrNull { it.relativePath == path } }
        if (view == null) {
            publish(
                state.copy(
                    activeEditorPath = relativePath,
                    activeFileHasSnapshots = relativePath == null,
                    selectedGridId = null,
                ),
            )
            return
        }
        val golden = view.goldenPaths.firstOrNull()
        publish(
            state.copy(
                selection = SnapshotSelection(view.relativePath, goldenId = golden),
                selectedGridId = golden?.let(::builtInGridId),
                activeEditorPath = relativePath,
                activeFileHasSnapshots = true,
            ),
        )
    }

    fun selectView(viewId: String) {
        val view = state.index?.files?.firstOrNull { it.relativePath == viewId } ?: return
        val rememberedGolden = state.selection.goldenId?.takeIf(view.goldenPaths::contains)
        publish(
            normalizeGridSelection(
                state.copy(
                selection = SnapshotSelection(
                    viewId = view.relativePath,
                    goldenId = rememberedGolden ?: view.goldenPaths.firstOrNull(),
                ),
                selectedGridId = (rememberedGolden ?: view.goldenPaths.firstOrNull())?.let(::builtInGridId),
                ),
            ),
        )
    }

    fun selectCase(viewId: String, caseId: String) {
        val view = state.index?.files?.firstOrNull { it.relativePath == viewId } ?: return
        val case = view.cases.firstOrNull { it.id == caseId } ?: return
        val rememberedGolden = state.selection.goldenId?.takeIf(case.goldenPaths::contains)
        publish(
            normalizeGridSelection(
                state.copy(
                selection = SnapshotSelection(
                    viewId = view.relativePath,
                    caseId = case.id,
                    goldenId = rememberedGolden ?: case.goldenPaths.firstOrNull(),
                ),
                selectedGridId = (rememberedGolden ?: case.goldenPaths.firstOrNull())?.let(::builtInGridId),
                ),
            ),
        )
    }

    fun selectGolden(goldenId: String) {
        if (state.selectedItems.none { it.golden.normalize().path == goldenId }) return
        publish(
            state.copy(
                selection = state.selection.copy(goldenId = goldenId),
                selectedGridId = builtInGridId(goldenId),
            ),
        )
    }

    fun selectGridItem(gridId: String) {
        val item = state.visibleGridItems.firstOrNull { it.id == gridId } ?: return
        when (item) {
            is GoldenGridItem.BuiltIn -> selectGolden(item.item.golden.normalize().path)
            is GoldenGridItem.Extension -> publish(state.copy(selectedGridId = item.id))
        }
    }

    fun beginExtraRefresh(viewId: String?): Long {
        val generation = state.extraGeneration + 1
        publish(
            state.copy(
                extraGeneration = generation,
                extraViewId = viewId,
                extraItems = emptyList(),
                extraOnly = false,
                selectedGridId = state.selection.goldenId?.let(::builtInGridId),
            ),
        )
        return generation
    }

    fun completeExtraRefresh(
        generation: Long,
        viewId: String?,
        items: List<GoldenGridItem.Extension>,
    ) {
        if (generation != state.extraGeneration || viewId != state.extraViewId) return
        val candidates = mergedGoldenGridItems(state.selectedItems, itemsForSelection(items))
        val selected = state.selectedGridId?.takeIf { id -> candidates.any { it.id == id } }
            ?: state.selection.goldenId?.let(::builtInGridId)?.takeIf { id -> candidates.any { it.id == id } }
            ?: candidates.firstOrNull()?.id
        publish(
            state.copy(
                extraItems = items,
                selectedGridId = selected,
                extraOnly = state.extraOnly && itemsForSelection(items).isNotEmpty(),
            ),
        )
    }

    fun setExtraOnly(enabled: Boolean) {
        val effective = enabled && state.extensionsForSelection.isNotEmpty()
        val visible = if (effective) state.extensionsForSelection else state.gridItems
        val selected = state.selectedGridId?.takeIf { id -> visible.any { it.id == id } }
            ?: if (!effective) {
                state.selection.goldenId?.let(::builtInGridId)?.takeIf { id -> visible.any { it.id == id } }
            } else {
                null
            }
            ?: visible.firstOrNull()?.id
        publish(state.copy(extraOnly = effective, selectedGridId = selected))
    }

    fun markExtensionLoaded(gridId: String) {
        val index = state.extraItems.indexOfFirst { it.id == gridId }
        if (index < 0) return
        val current = state.extraItems[index]
        if (current.item.isLoading == !current.item.file.isFile) return
        val updated = state.extraItems.toMutableList()
        updated[index] = current.copy(item = current.item.copy(isLoading = !current.item.file.isFile))
        publish(state.copy(extraItems = updated))
    }

    private fun publish(next: PluginSnapshotState) {
        if (next == state) return
        state = next
        onStateChanged(next)
    }

    private fun itemsForSelection(items: List<GoldenGridItem.Extension>): List<GoldenGridItem.Extension> {
        val method = state.selectedCase?.methodName ?: return items
        return items.filter { it.item.title == method }
    }

    private fun normalizeGridSelection(next: PluginSnapshotState): PluginSnapshotState {
        val extraOnly = next.extraOnly && next.extensionsForSelection.isNotEmpty()
        val visible = if (extraOnly) next.extensionsForSelection else next.gridItems
        val selected = next.selectedGridId?.takeIf { id -> visible.any { it.id == id } }
            ?: next.selection.goldenId?.let(::builtInGridId)?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id
        return next.copy(extraOnly = extraOnly, selectedGridId = selected)
    }
}

private fun builtInGridId(path: String): String = "built-in:${java.io.File(path).normalize().path}"
