package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.CellRendererPane
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/**
 * Horizontal goldens strip of the Review tab: 104px thumbnails whose width follows each image's own
 * aspect, a mono label, a 6px status bar and a source glyph for extension items (e.g. Figma).
 *
 * A JList cannot lay out cells of different widths in one row, so this is a single focusable canvas
 * with its own hit testing, arrow-key navigation and tooltips.
 */
internal class GoldenStripPanel(
    private val trimTransparentPadding: () -> Boolean,
    private val statusOf: (GoldenGridItem) -> GoldenDisplayStatus,
    private val onSelect: (gridId: String, trigger: String) -> Unit,
    private val onContextMenu: (GoldenGridItem, Component, Int, Int) -> Unit,
) : JPanel(BorderLayout()) {
    private val renderer = Renderer()
    private val canvas = StripCanvas()
    private val scroll = JBScrollPane(
        canvas,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    )

    init {
        isOpaque = true
        background = GoldenDiffColors.background
        border = JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0)
        scroll.border = JBUI.Borders.empty()
        scroll.viewport.background = GoldenDiffColors.background
        add(scroll, BorderLayout.CENTER)
    }

    /** Programmatic rendering; it never reports [onSelect]. */
    fun render(items: List<GoldenGridItem>, selectedId: String?) {
        val builtIn = items.filterIsInstance<GoldenGridItem.BuiltIn>()
        renderer.labels = builtIn.map { it.id }
            .zip(stripLabels(builtIn.map { it.item.golden.nameWithoutExtension }))
            .toMap()
        canvas.items = items
        canvas.selectedIndex = items.indexOfFirst { it.id == selectedId }
        canvas.revalidate()
        canvas.repaint()
        SwingUtilities.invokeLater { canvas.revealSelected() }
    }

    /** Drops cached thumbnails of [file], e.g. after an extension finished downloading it. */
    fun invalidate(file: File) = renderer.clear(file)

    fun focusList() {
        canvas.requestFocusInWindow()
    }

    override fun getPreferredSize(): Dimension =
        Dimension(super.getPreferredSize().width, canvas.preferredSize.height + JBUI.scale(8))

    private inner class StripCanvas : JComponent() {
        var items: List<GoldenGridItem> = emptyList()
        var selectedIndex = -1
        private val pane = CellRendererPane()

        init {
            isOpaque = true
            background = GoldenDiffColors.background
            isFocusable = true
            add(pane)
            getAccessibleContext()?.accessibleName = "Goldens"
            ToolTipManager.sharedInstance().registerComponent(this)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    val index = indexAt(e.x, e.y) ?: return
                    if (e.isPopupTrigger) {
                        onContextMenu(items[index], this@StripCanvas, e.x, e.y)
                        return
                    }
                    if (SwingUtilities.isLeftMouseButton(e)) select(index, "grid")
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val index = indexAt(e.x, e.y) ?: return
                    onContextMenu(items[index], this@StripCanvas, e.x, e.y)
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })
            bindArrow(KeyEvent.VK_LEFT, -1)
            bindArrow(KeyEvent.VK_RIGHT, 1)
        }

        override fun getAccessibleContext(): AccessibleContext {
            if (accessibleContext == null) {
                accessibleContext = object : AccessibleJComponent() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LIST
                }
            }
            return accessibleContext
        }

        fun revealSelected() {
            cellBounds().getOrNull(selectedIndex)?.let(::scrollRectToVisible)
        }

        private fun select(index: Int, trigger: String) {
            if (index !in items.indices || index == selectedIndex) return
            selectedIndex = index
            repaint()
            revealSelected()
            getAccessibleContext()?.accessibleDescription = renderer.accessibleName(items[index])
            onSelect(items[index].id, trigger)
        }

        private fun bindArrow(keyCode: Int, delta: Int) {
            val key = "golden.step.$delta"
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), key)
            actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    if (items.isEmpty()) return
                    select((selectedIndex.coerceAtLeast(0) + delta).coerceIn(0, items.size - 1), "keyboard")
                }
            })
        }

        private fun indexAt(x: Int, y: Int): Int? =
            cellBounds().indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }

        private fun cellBounds(): List<Rectangle> {
            var x = JBUI.scale(PADDING_X)
            val y = JBUI.scale(PADDING_Y)
            return items.map { item ->
                val size = renderer.cellSize(item)
                Rectangle(x, y, size.width, size.height).also { x += size.width + JBUI.scale(GAP) }
            }
        }

        override fun getPreferredSize(): Dimension {
            val bounds = cellBounds()
            val height = JBUI.scale(THUMB_HEIGHT + 4 + LABEL_HEIGHT + PADDING_Y * 2)
            val width = (bounds.lastOrNull()?.let { it.x + it.width } ?: 0) + JBUI.scale(PADDING_X)
            return Dimension(width, height)
        }

        override fun getToolTipText(event: MouseEvent): String? =
            indexAt(event.x, event.y)?.let { renderer.tooltip(items[it]) }

        override fun paintComponent(graphics: Graphics) {
            graphics.color = background
            graphics.fillRect(0, 0, width, height)
            if (items.isEmpty()) {
                graphics.font = font
                graphics.color = GoldenDiffColors.faint
                graphics.drawString("No goldens", JBUI.scale(PADDING_X), JBUI.scale(PADDING_Y) + graphics.fontMetrics.ascent)
                return
            }
            val clip = graphics.clipBounds
            cellBounds().forEachIndexed { index, rect ->
                if (clip != null && !clip.intersects(rect)) return@forEachIndexed
                val component = renderer.component(items[index], index == selectedIndex, hasFocus() && index == selectedIndex)
                pane.paintComponent(graphics, component, this, rect.x, rect.y, rect.width, rect.height, true)
            }
        }
    }

    private data class ImageKey(val path: String, val modified: Long, val trim: Boolean)

    private inner class Renderer {
        /** Short built-in labels for the current strip, keyed by grid id. */
        var labels: Map<String, String> = emptyMap()
        private val images = HashMap<ImageKey, BufferedImage?>()
        private val cell = ThumbnailCell()

        fun clear(file: File) {
            images.keys.removeIf { it.path == file.path }
        }

        fun cellSize(item: GoldenGridItem): Dimension {
            val image = fileOf(item)?.let(::image)
            return ThumbnailCell.cellSize(image?.width, image?.height)
        }

        fun component(value: GoldenGridItem, selected: Boolean, focused: Boolean): Component {
            cell.bind(
                image = fileOf(value)?.let(::image),
                label = when (value) {
                    is GoldenGridItem.BuiltIn -> labels[value.id] ?: value.item.golden.nameWithoutExtension
                    is GoldenGridItem.Extension -> value.item.title
                },
                status = statusOf(value),
                selected = selected,
                focused = focused,
                sourceIcon = (value as? GoldenGridItem.Extension)?.source?.filterIcon,
                extension = value is GoldenGridItem.Extension,
                loading = value is GoldenGridItem.Extension && value.item.isLoading,
            )
            return cell
        }

        fun tooltip(value: GoldenGridItem): String = when (value) {
            is GoldenGridItem.BuiltIn -> "${value.item.golden.name} · ${sourceSummary(value.item)}"
            is GoldenGridItem.Extension ->
                "${value.source.title}: ${value.item.title}" + if (value.item.isLoading) " · Loading reference…" else ""
        }

        fun accessibleName(value: GoldenGridItem): String =
            "${value.file.name}, ${statusOf(value).badgeLabel ?: "Unchanged"}"

        private fun fileOf(value: GoldenGridItem): File? = when (value) {
            is GoldenGridItem.BuiltIn -> displayedVariant(value.item)?.file
            is GoldenGridItem.Extension -> value.item.file.takeIf(File::isFile)
        }

        private fun image(file: File): BufferedImage? {
            val key = ImageKey(file.path, file.lastModified(), trimTransparentPadding())
            return images.getOrPut(key) {
                runCatching { ImageIO.read(file) }.getOrNull()
                    ?.let { ImagePainting.trimTransparentBorder(it, key.trim) }
            }
        }

        private fun sourceSummary(item: MergedGoldenItem): String = buildList {
            item.workingCopy?.let { add("Working: ${it.status.readable}") }
            item.testOutput?.let {
                add("Test: ${if (it.status == ExtraComparisonItemStatus.UNCHANGED) "Unchanged" else "Failed"}")
            }
            if (item.testOutputResolution is GeneratedCounterpartResolution.Ambiguous) add("Test: Ambiguous")
        }.joinToString(" · ").ifBlank { "No source" }

        private fun displayedVariant(item: MergedGoldenItem): GoldenSourceVariant? =
            item.testOutput?.takeIf { item.hasAcceptableTestOutput } ?: item.workingCopy ?: item.testOutput
    }

    private class ThumbnailCell : JComponent() {
        private var image: BufferedImage? = null
        private var label = ""
        private var status = GoldenDisplayStatus.UNCHANGED
        private var selected = false
        private var focused = false
        private var sourceIcon: Icon? = null
        private var extension = false
        private var loading = false
        private val spinner = AnimatedIcon.Default()
        private val labelFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(9f))

        fun bind(
            image: BufferedImage?,
            label: String,
            status: GoldenDisplayStatus,
            selected: Boolean,
            focused: Boolean,
            sourceIcon: Icon?,
            extension: Boolean,
            loading: Boolean,
        ) {
            this.image = image
            this.label = label
            this.status = status
            this.selected = selected
            this.focused = focused
            this.sourceIcon = sourceIcon
            this.extension = extension
            this.loading = loading
        }

        private fun thumbSize(): Dimension = thumbnailSize(image?.width, image?.height, JBUI.scale(THUMB_HEIGHT))

        override fun getPreferredSize(): Dimension = cellSize(image?.width, image?.height)

        companion object {
            /** Thumbnail plus label; the label never gets narrower than 48px. */
            fun cellSize(imageWidth: Int?, imageHeight: Int?): Dimension {
                val thumb = thumbnailSize(imageWidth, imageHeight, JBUI.scale(THUMB_HEIGHT))
                return Dimension(maxOf(thumb.width, JBUI.scale(48)), thumb.height + JBUI.scale(4 + LABEL_HEIGHT))
            }
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val thumb = thumbSize()
                val arc = JBUI.scale(4)
                val clip = java.awt.geom.RoundRectangle2D.Float(
                    0f, 0f, thumb.width.toFloat(), thumb.height.toFloat(), arc.toFloat(), arc.toFloat(),
                )
                val inner = g.create() as Graphics2D
                try {
                    inner.clip(clip)
                    paintStripes(inner, thumb)
                    image?.let {
                        val target = fittedImageRect(it.width, it.height, thumb)
                        inner.drawImage(it, target.x, target.y, target.width, target.height, null)
                    }
                    if (status != GoldenDisplayStatus.UNCHANGED) {
                        inner.color = GoldenDiffColors.status(status)
                        inner.fillRect(0, thumb.height - JBUI.scale(6), thumb.width, JBUI.scale(6))
                    }
                } finally {
                    inner.dispose()
                }
                if (loading) {
                    spinner.paintIcon(
                        this,
                        g,
                        (thumb.width - spinner.iconWidth) / 2,
                        (thumb.height - spinner.iconHeight) / 2,
                    )
                }
                if (extension) paintSourceGlyph(g)
                g.color = if (selected) GoldenDiffColors.accent else GoldenDiffColors.border
                val stroke = if (selected) JBUI.scale(2) else 1
                g.stroke = BasicStroke(stroke.toFloat())
                val offset = stroke / 2f
                g.draw(
                    java.awt.geom.RoundRectangle2D.Float(
                        offset, offset, thumb.width - stroke.toFloat(), thumb.height - stroke.toFloat(),
                        arc.toFloat(), arc.toFloat(),
                    ),
                )
                if (focused && !selected) {
                    g.color = GoldenDiffColors.accent.withAlpha(128)
                    g.drawRoundRect(0, 0, thumb.width - 1, thumb.height - 1, arc, arc)
                }
                g.font = labelFont
                g.color = if (selected) GoldenDiffColors.text else GoldenDiffColors.faint
                val metrics = g.fontMetrics
                val labelWidth = maxOf(thumb.width, JBUI.scale(48))
                val text = ImagePainting.ellipsize(label, metrics, labelWidth)
                g.drawString(text, 0, thumb.height + JBUI.scale(4) + metrics.ascent)
            } finally {
                g.dispose()
            }
        }

        private fun paintStripes(g: Graphics2D, size: Dimension) {
            g.color = GoldenDiffColors.stripeA
            g.fillRect(0, 0, size.width, size.height)
            g.color = GoldenDiffColors.stripeB
            g.stroke = BasicStroke(JBUI.scale(8).toFloat())
            val step = JBUI.scale(16)
            var x = -size.height
            while (x < size.width + size.height) {
                g.drawLine(x, size.height, x + size.height, 0)
                x += step
            }
        }

        private fun paintSourceGlyph(g: Graphics2D) {
            val inset = JBUI.scale(4)
            val icon = sourceIcon
            if (icon != null) {
                icon.paintIcon(this, g, inset, inset)
                return
            }
            val size = JBUI.scale(9)
            val colors = listOf(Color(0x0ACF83), Color(0xA259FF), Color(0xF24E1E), Color(0x1ABCFE))
            colors.forEachIndexed { index, color ->
                g.color = color
                g.fillArc(inset, inset, size, size, 45 + index * 90, 90)
            }
        }
    }

    internal companion object {
        const val THUMB_HEIGHT = 104
        const val LABEL_HEIGHT = 12
        const val GAP = 8
        const val PADDING_X = 10
        const val PADDING_Y = 10
        private const val MAX_ASPECT = 2.4

        /**
         * Short labels for dotted golden names (Roborazzi "package.Class.method.config"): segments shared
         * by every golden in the strip are dropped from both ends, leaving what tells them apart. A lone
         * golden keeps its last segment. The tooltip always shows the full name.
         */
        fun stripLabels(names: List<String>): List<String> {
            val segments = names.map { it.split('.') }
            if (segments.size <= 1) return names.map { it.substringAfterLast('.').ifBlank { it } }
            val shortest = segments.minOf { it.size }
            var prefix = 0
            while (prefix < shortest - 1 && segments.all { it[prefix] == segments[0][prefix] }) prefix++
            var suffix = 0
            while (
                suffix < shortest - prefix - 1 &&
                segments.all { it[it.size - 1 - suffix] == segments[0][segments[0].size - 1 - suffix] }
            ) {
                suffix++
            }
            return segments.mapIndexed { index, parts ->
                parts.subList(prefix, parts.size - suffix).joinToString(".").ifBlank { names[index] }
            }
        }

        /** Contain-fits the image into the thumbnail box; only images wider than the aspect cap are letterboxed. */
        fun fittedImageRect(imageWidth: Int, imageHeight: Int, box: Dimension): Rectangle {
            if (imageWidth <= 0 || imageHeight <= 0) return Rectangle(0, 0, box.width, box.height)
            val scale = minOf(box.width.toDouble() / imageWidth, box.height.toDouble() / imageHeight)
            val width = (imageWidth * scale).toInt().coerceIn(1, box.width)
            val height = (imageHeight * scale).toInt().coerceIn(1, box.height)
            return Rectangle((box.width - width) / 2, (box.height - height) / 2, width, height)
        }

        /** Height-driven thumbnail size; missing images use a portrait placeholder. */
        fun thumbnailSize(imageWidth: Int?, imageHeight: Int?, height: Int): Dimension {
            val aspect = if (imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
                0.47
            } else {
                imageWidth.toDouble() / imageHeight
            }
            return Dimension((height * aspect.coerceAtMost(MAX_ASPECT)).toInt().coerceAtLeast(1), height)
        }
    }
}

private val ExtraComparisonItemStatus.readable: String
    get() = when (this) {
        ExtraComparisonItemStatus.MODIFIED -> "Modified"
        ExtraComparisonItemStatus.NEW -> "New"
        ExtraComparisonItemStatus.UNCHANGED -> "Unchanged"
    }
