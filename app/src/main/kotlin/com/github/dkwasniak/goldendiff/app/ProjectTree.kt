package com.github.dkwasniak.goldendiff.app

/** One immutable node in the standalone app's project tree. */
internal data class ProjectTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<ProjectTreeNode> = emptyList(),
)

/** Builds a directory-first tree from the flat, already indexed project-relative file paths. */
internal fun buildProjectTree(paths: List<String>): List<ProjectTreeNode> {
    val root = MutableProjectTreeNode("", "", true)
    paths.forEach { path ->
        val parts = path.split('/').filter(String::isNotEmpty)
        var parent = root
        parts.forEachIndexed { index, name ->
            val nodePath = parts.take(index + 1).joinToString("/")
            val isDirectory = index < parts.lastIndex
            parent = parent.children.getOrPut(name) {
                MutableProjectTreeNode(name, nodePath, isDirectory)
            }
        }
    }
    return root.freezeChildren()
}

/**
 * Keeps only files whose name contains [query], plus the directories leading to them.
 *
 * A directory that matches by its own name is kept whole: typing a module name is how you narrow to
 * a module, and pruning its contents because the leaf names do not repeat the module name would
 * make the filter useless for exactly that case.
 */
internal fun filterProjectTree(roots: List<ProjectTreeNode>, query: String): List<ProjectTreeNode> {
    val needle = query.trim()
    if (needle.isEmpty()) return roots

    fun keep(node: ProjectTreeNode): ProjectTreeNode? {
        val selfMatches = node.name.contains(needle, ignoreCase = true)
        if (!node.isDirectory) return node.takeIf { selfMatches }
        if (selfMatches) return node
        val children = node.children.mapNotNull(::keep)
        return if (children.isEmpty()) null else node.copy(children = children)
    }

    return roots.mapNotNull(::keep)
}

private class MutableProjectTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
) {
    val children = linkedMapOf<String, MutableProjectTreeNode>()

    fun freezeChildren(): List<ProjectTreeNode> = children.values
        .sortedWith(compareBy<MutableProjectTreeNode> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { node ->
            ProjectTreeNode(node.name, node.path, node.isDirectory, node.freezeChildren())
        }
}
