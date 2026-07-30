package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import java.io.File

/** One stable entry in the shared Goldens grid. */
internal sealed interface GoldenGridItem {
    val id: String
    val file: File

    data class BuiltIn(val item: MergedGoldenItem) : GoldenGridItem {
        override val file: File
            get() = item.golden
        override val id: String
            get() = "built-in:${file.normalize().path}"
    }

    data class Extension(
        val source: ExtraComparisonSource,
        val item: ExtraComparisonItem,
        val screen: Screen,
    ) : GoldenGridItem {
        override val file: File
            get() = item.file
        override val id: String
            get() = "extension:${source.id}:${file.normalize().path}"
    }
}

internal fun mergedGoldenGridItems(
    builtIn: List<MergedGoldenItem>,
    extensions: List<GoldenGridItem.Extension>,
): List<GoldenGridItem> = builtIn.map(GoldenGridItem::BuiltIn) + extensions
