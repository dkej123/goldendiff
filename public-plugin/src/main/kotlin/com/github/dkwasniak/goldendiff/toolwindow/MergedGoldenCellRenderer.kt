package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.JLabel

/** Snapshot-aware image card with merged status detail exposed through its tooltip. */
internal class MergedGoldenCellRenderer(
    private val project: Project,
    private val cardWidth: () -> Int,
) : ListCellRenderer<GoldenGridItem> {

    private data class ImageKey(val path: String, val modified: Long, val trim: Boolean)
    private data class IconKey(val image: ImageKey, val width: Int)

    private val images = HashMap<ImageKey, BufferedImage?>()
    private val icons = HashMap<IconKey, Icon>()
    private val failed = JBColor(Color(0xC9424A), Color(0xE0656B))
    private val modified = JBColor(Color(0xA9761F), Color(0xD9A441))
    private val added = JBColor(Color(0x2F8F52), Color(0x66B578))

    fun clearScaledCache() = icons.clear()

    fun clear(file: File) {
        images.keys.removeIf { it.path == file.path }
        icons.keys.removeIf { it.image.path == file.path }
    }

    override fun getListCellRendererComponent(
        list: JList<out GoldenGridItem>,
        value: GoldenGridItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val width = cardWidth().coerceAtLeast(JBUI.scale(36))
        val thumbnailWidth = width
        val thumbnail = when (value) {
            is GoldenGridItem.BuiltIn -> displayedVariant(value.item)?.file?.let { iconFor(it, thumbnailWidth) }
            is GoldenGridItem.Extension -> value.item.file.takeIf(File::isFile)?.let { iconFor(it, thumbnailWidth) }
        } ?: PlaceholderIcon(thumbnailWidth, placeholderHeight(thumbnailWidth))
        val accent = when (value) {
            is GoldenGridItem.BuiltIn -> accent(value.item.displayStatus)
            is GoldenGridItem.Extension -> accent(value.item.status)
        }
        val statusTooltip = when (value) {
            is GoldenGridItem.BuiltIn -> statusTooltip(value.item)
            is GoldenGridItem.Extension ->
                "${value.source.title}: ${value.item.title}" +
                    if (value.item.isLoading) " · Loading reference…" else ""
        }
        val panel = ThumbnailCard(
            thumbnail = thumbnail,
            accent = accent,
            selected = isSelected,
            sourceBadge = (value as? GoldenGridItem.Extension)?.let {
                SourceBadge(it.source.title, it.source.filterIcon)
            },
        ).apply {
            background = list.background
            toolTipText = statusTooltip
            accessibleContext.accessibleName = "${value.file.name}, $statusTooltip"
        }
        if (value is GoldenGridItem.Extension && value.item.isLoading) {
            panel.layout = java.awt.BorderLayout()
            panel.add(
                JLabel("Loading ${value.source.title}…", SwingConstants.CENTER).apply {
                    icon = AnimatedIcon.Default()
                    iconTextGap = JBUI.scale(5)
                    foreground = UIUtil.getContextHelpForeground()
                    font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
                    isOpaque = false
                },
                java.awt.BorderLayout.CENTER,
            )
        }
        return JPanel(GridBagLayout()).apply {
            isOpaque = true
            background = list.background
            border = JBUI.Borders.empty(JBUI.scale(4), 0)
            add(panel)
        }
    }

    private fun statusTooltip(item: MergedGoldenItem): String = buildList {
        item.workingCopy?.let { add("Working: ${it.status.readable}") }
        item.testOutput?.let {
            add("Test: ${if (it.status == ExtraComparisonItemStatus.UNCHANGED) "Unchanged" else "Failed"}")
        }
        if (item.testOutputResolution is GeneratedCounterpartResolution.Ambiguous) add("Test: Ambiguous")
    }.joinToString(" \u00b7 ").ifBlank { item.golden.name }

    private fun displayedVariant(item: MergedGoldenItem): GoldenSourceVariant? =
        item.testOutput?.takeIf {
            it.status == ExtraComparisonItemStatus.MODIFIED || it.status == ExtraComparisonItemStatus.NEW
        } ?: item.workingCopy ?: item.testOutput

    private fun accent(status: MergedGoldenStatus): Color = when (status) {
        MergedGoldenStatus.FAILED -> failed
        MergedGoldenStatus.MODIFIED -> modified
        MergedGoldenStatus.NEW -> added
        MergedGoldenStatus.UNCHANGED -> UIUtil.getContextHelpForeground()
    }

    private fun accent(status: ExtraComparisonItemStatus): Color = when (status) {
        ExtraComparisonItemStatus.MODIFIED -> modified
        ExtraComparisonItemStatus.NEW -> added
        ExtraComparisonItemStatus.UNCHANGED -> UIUtil.getContextHelpForeground()
    }

    private fun iconFor(file: File, width: Int): Icon {
        val key = imageKey(file)
        return icons.getOrPut(IconKey(key, width)) {
            val image = images.getOrPut(key) {
                runCatching { ImageIO.read(file) }.getOrNull()
                    ?.let { ImagePainting.trimTransparentBorder(it, key.trim) }
            }
            if (image == null) {
                PlaceholderIcon(width, placeholderHeight(width))
            } else {
                ImageIcon(image, width)
            }
        }
    }

    private fun imageKey(file: File) = ImageKey(
        file.path,
        file.lastModified(),
        ScreenshotSettings.getInstance(project).trimTransparentPadding,
    )

    private fun placeholderHeight(width: Int): Int = (width * 0.72).toInt().coerceAtLeast(1)

    private class ThumbnailCard(
        private val thumbnail: Icon,
        private val accent: Color,
        private val selected: Boolean,
        private val sourceBadge: SourceBadge?,
    ) : JPanel() {
        private val size = Dimension(thumbnail.iconWidth, thumbnail.iconHeight)

        init {
            isOpaque = true
            preferredSize = size
            minimumSize = size
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            thumbnail.paintIcon(this, graphics, 0, 0)
            graphics.color = accent
            graphics.fillRect(0, height - JBUI.scale(4), width, JBUI.scale(4))
            sourceBadge?.let { badge ->
                val g = graphics.create() as Graphics2D
                try {
                    paintSourceBadge(g, badge, width)
                } finally {
                    g.dispose()
                }
            }
            if (selected) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = SelectionBorder
                    g.stroke = java.awt.BasicStroke(JBUI.scale(1).toFloat())
                    g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(4), JBUI.scale(4))
                } finally {
                    g.dispose()
                }
            }
        }

        private companion object {
            val SelectionBorder = JBColor(Color(0x5B8DEF), Color(0x6B9BFF))

            fun paintSourceBadge(graphics: Graphics2D, badge: SourceBadge, cardWidth: Int) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val font = UIUtil.getLabelFont().deriveFont(
                    java.awt.Font.BOLD,
                    JBUI.scaleFontSize(9f).toFloat(),
                )
                graphics.font = font
                val geometry = sourceBadgeGeometry(
                    cardWidth = cardWidth,
                    textWidth = graphics.fontMetrics.stringWidth(badge.title),
                    iconWidth = badge.icon?.iconWidth ?: 0,
                )
                graphics.color = JBColor(Color(0xF7F8FA), Color(0x303236))
                graphics.fillRoundRect(
                    geometry.x,
                    geometry.y,
                    geometry.width,
                    geometry.height,
                    geometry.height,
                    geometry.height,
                )
                graphics.color = JBColor(Color(0xC9CCD2), Color(0x55585E))
                graphics.drawRoundRect(
                    geometry.x,
                    geometry.y,
                    geometry.width - 1,
                    geometry.height - 1,
                    geometry.height - 1,
                    geometry.height - 1,
                )
                var contentX = geometry.x + JBUI.scale(4)
                badge.icon?.let { icon ->
                    val iconY = geometry.y + (geometry.height - icon.iconHeight) / 2
                    icon.paintIcon(null, graphics, contentX, iconY)
                    contentX += icon.iconWidth + JBUI.scale(3)
                }
                graphics.color = UIUtil.getLabelForeground()
                val baseline = geometry.y +
                    (geometry.height - graphics.fontMetrics.height) / 2 +
                    graphics.fontMetrics.ascent
                graphics.drawString(badge.title, contentX, baseline)
            }
        }
    }

    private class ImageIcon(
        private val image: BufferedImage,
        width: Int,
    ) : Icon {
        private val size = widthConstrainedSize(image.width, image.height, width)

        override fun getIconWidth(): Int = size.width
        override fun getIconHeight(): Int = size.height

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g2.drawImage(image, x, y, iconWidth, iconHeight, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private class PlaceholderIcon(
        private val width: Int,
        private val height: Int,
    ) : Icon {
        override fun getIconWidth(): Int = width
        override fun getIconHeight(): Int = height

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(0xF2F3F5), Color(0x242629))
                g2.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(5), JBUI.scale(5))
                g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                for (offset in -iconHeight until iconWidth step JBUI.scale(12)) {
                    g2.drawLine(x + offset, y + iconHeight, x + offset + iconHeight, y)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        fun widthConstrainedSize(
            sourceWidth: Int,
            sourceHeight: Int,
            width: Int,
        ): Dimension {
            val safeSourceWidth = sourceWidth.coerceAtLeast(1)
            val scale = width.coerceAtLeast(1).toDouble() / safeSourceWidth
            return Dimension(
                width.coerceAtLeast(1),
                (sourceHeight.coerceAtLeast(1) * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}

private data class SourceBadge(val title: String, val icon: Icon?)

internal data class SourceBadgeGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun sourceBadgeGeometry(
    cardWidth: Int,
    textWidth: Int,
    iconWidth: Int,
): SourceBadgeGeometry {
    val margin = JBUI.scale(4)
    val horizontalPadding = JBUI.scale(8)
    val gap = if (iconWidth > 0) JBUI.scale(3) else 0
    val width = (horizontalPadding + iconWidth + gap + textWidth)
        .coerceAtMost((cardWidth - margin * 2).coerceAtLeast(1))
    return SourceBadgeGeometry(
        x = (cardWidth - margin - width).coerceAtLeast(0),
        y = margin,
        width = width,
        height = JBUI.scale(18),
    )
}

private val ExtraComparisonItemStatus.readable: String
    get() = when (this) {
        ExtraComparisonItemStatus.MODIFIED -> "Modified"
        ExtraComparisonItemStatus.NEW -> "New"
        ExtraComparisonItemStatus.UNCHANGED -> "Unchanged"
    }
