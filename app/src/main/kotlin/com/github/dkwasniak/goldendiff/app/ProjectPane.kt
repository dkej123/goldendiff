package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.Text
import com.github.dkwasniak.goldendiff.app.ui.Chevron
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.Direction
import com.github.dkwasniak.goldendiff.app.ui.FileIcon
import com.github.dkwasniak.goldendiff.app.ui.FilterField
import com.github.dkwasniak.goldendiff.app.ui.FolderIcon
import com.github.dkwasniak.goldendiff.app.ui.IconButton
import com.github.dkwasniak.goldendiff.app.ui.Pane
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import java.io.File

/** Left pane: the project file tree, its filter, and the quick-open affordance. */
@Composable
fun ProjectPane(state: AppState, modifier: Modifier) {
    val index = state.fileIndex
    val fullTree = remember(index) { index?.let { buildProjectTree(it.paths) }.orEmpty() }
    val filter = state.treeFilter
    val tree = remember(fullTree, filter) { filterProjectTree(fullTree, filter) }
    val expanded = remember(index) { mutableStateMapOf<String, Boolean>() }
    val selectedPath = state.selectedSourcePath

    LaunchedEffect(selectedPath) {
        // Reveal the selection by expanding every directory on the way down to it.
        val parts = selectedPath?.split('/').orEmpty()
        parts.dropLast(1).indices.forEach { depth ->
            expanded[parts.take(depth + 1).joinToString("/")] = true
        }
    }

    Pane(modifier) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "PROJECT",
                color = tokens.textFaint,
                fontSize = Type.thumbLabel,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                Shortcuts.quickOpenLabel,
                color = tokens.textFaint,
                fontSize = 10.sp,
                fontFamily = Type.Mono,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).hoverWash().clickable {
                    state.featureUsed("quick_open")
                    state.quickOpenQuery = ""
                    state.quickOpenVisible = true
                }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            IconButton(size = 18.dp, onClick = state::toggleLeftPane) {
                Chevron(Direction.LEFT, tokens.textFaint)
            }
        }
        FilterField(
            value = filter,
            placeholder = "Filter files…",
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        ) { state.treeFilter = it }

        when {
            state.projectRoot == null -> PaneHint("Open a project to browse its files.")
            index == null -> PaneHint("Indexing…")
            tree.isEmpty() -> PaneHint("No files match \"$filter\".")
            else -> {
                // While filtering, everything is expanded: a filtered tree the user still has to
                // unfold defeats the point of typing.
                val visible = remember(tree, filter, expanded.toMap()) {
                    flattenVisibleNodes(tree, expanded, expandAll = filter.isNotBlank())
                }
                LazyColumn(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                    items(visible, key = { it.node.path }) { row ->
                        TreeRow(
                            state = state,
                            row = row,
                            expanded = expanded[row.node.path] == true || filter.isNotBlank(),
                            selected = !row.node.isDirectory && row.node.path == selectedPath,
                            onToggle = { expanded[row.node.path] = expanded[row.node.path] != true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeRow(
    state: AppState,
    row: VisibleProjectNode,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val node = row.node
    val shape = RoundedCornerShape(Dimens.controlRadius)
    val file = File(state.projectRoot, node.path)
    FileContextMenu(state, file, canDelete = !node.isDirectory) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(Dimens.rowHeight).clip(shape)
                .background(if (selected) tokens.accentBg else Color.Transparent, shape)
                .border(1.dp, if (selected) tokens.accentBorder else Color.Transparent, shape)
                .hoverWash(!selected, shape)
                .clickable { if (node.isDirectory) onToggle() else state.selectSourceFile(node.path) }
                .padding(start = 8.dp + Dimens.treeIndent * row.depth, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.width(11.dp), contentAlignment = Alignment.Center) {
                if (node.isDirectory) {
                    Chevron(if (expanded) Direction.DOWN else Direction.RIGHT, tokens.textFaint)
                }
            }
            if (node.isDirectory) {
                FolderIcon(if (selected) tokens.accent else tokens.textFaint)
            } else {
                FileIcon(tokens.textFaint)
            }
            Text(
                node.name,
                color = if (selected) tokens.text else tokens.textDim,
                fontSize = Type.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The slim edge strip that brings the collapsed left pane back. */
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
    Text(text, color = tokens.textDim, fontSize = Type.small, modifier = Modifier.padding(10.dp))
}

internal data class VisibleProjectNode(val node: ProjectTreeNode, val depth: Int)

internal fun flattenVisibleNodes(
    roots: List<ProjectTreeNode>,
    expanded: Map<String, Boolean>,
    expandAll: Boolean = false,
): List<VisibleProjectNode> = buildList {
    fun append(nodes: List<ProjectTreeNode>, depth: Int) {
        nodes.forEach { node ->
            add(VisibleProjectNode(node, depth))
            if (node.isDirectory && (expandAll || expanded[node.path] == true)) append(node.children, depth + 1)
        }
    }
    append(roots, 0)
}
