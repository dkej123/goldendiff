package com.github.dkwasniak.goldendiff.toolwindow

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.dkwasniak.goldendiff.compare.GeneratedImageSource
import com.github.dkwasniak.goldendiff.compare.GitImageSource
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.compare.ImageLayout
import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.compare.PixelDiff
import com.github.dkwasniak.goldendiff.compare.toArgbImage
import com.github.dkwasniak.goldendiff.compare.toBufferedImage
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.git.WorkingCopyStatus
import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.naming.shortGoldenName
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.settings.ScreenshotConfigurable
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.ui.CompareMode
import com.github.dkwasniak.goldendiff.ui.OnionSkinView
import com.github.dkwasniak.goldendiff.ui.SingleImageView
import com.github.dkwasniak.goldendiff.ui.SwipeView
import com.github.dkwasniak.goldendiff.ui.TwoUpView
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Timer
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text

/** Owns the IDE-facing state and renders the complete tool window in Compose/Jewel. */
class ScreenshotToolWindow(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {

    private val settings = ScreenshotSettings.getInstance(project)
    private val headBytesSource = GitImageSource(project)

    private var items by mutableStateOf(emptyList<GoldenItem>())
    private var selectedItem by mutableStateOf<ExtraComparisonItem?>(null)
    private var statusText by mutableStateOf("")
    private var comparison by mutableStateOf<ComparisonContent>(ComparisonContent.Empty)
    private var selectedScope by mutableStateOf(Scope.CURRENT_FILE)
    private var selectedSource by mutableStateOf(ComparisonSource.WORKING_COPY)
    private var comparisonSources by mutableStateOf(ComparisonSource.builtIns)
    private var thumbnailScaleIndex by mutableIntStateOf(0)

    private val refreshTimer = Timer(DEBOUNCE_MS) { refresh() }.apply { isRepeats = false }
    private var lastRefreshKey: List<String>? = null
    private var currentScreen: Screen? = null
    private var loadedFile: File? = null
    private var loadedSource = ComparisonSource.WORKING_COPY
    private var pendingForce = false
    private var wasVisible = toolWindow.isVisible

    init {
        subscribeToEditor()
        syncExtraComparisonSources()
        scheduleRefresh()
    }

    @Composable
    fun Content() {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
            Header()
            Status()
            HorizontalDivider()
            SplitContent(Modifier.weight(1f))
        }
    }

    @Composable
    private fun Header() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = ::onDirectoriesClicked) {
                    Text(if (settings.isConfigured) "Settings" else "Choose screenshots directory")
                }
                OutlinedButton(onClick = { scheduleRefresh(force = true) }) { Text("Refresh") }
                Spacer(Modifier.weight(1f))
                Text("Thumbnails")
                OutlinedButton(
                    onClick = { changeThumbnailScale(1) },
                    enabled = thumbnailScaleIndex < THUMBNAIL_SCALES.lastIndex,
                ) { Text("−") }
                Text("${(thumbnailScale() * 100).toInt()}%")
                OutlinedButton(
                    onClick = { changeThumbnailScale(-1) },
                    enabled = thumbnailScaleIndex > 0,
                ) { Text("+") }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Scope:")
                Scope.entries.forEach { scope ->
                    ChoiceButton(scope.title, selectedScope == scope) { selectScope(scope) }
                }
                Spacer(Modifier.width(8.dp))
                Text("Compare:")
                comparisonSources.forEach { source ->
                    ChoiceButton(
                        source.title,
                        selectedSource == source,
                        enabled = selectedScope == Scope.CURRENT_FILE || source.extra == null,
                    ) { selectSource(source) }
                }
            }
        }
    }

    @Composable
    private fun Status() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(statusText, color = JewelTheme.globalColors.text.info)
            if (items.any { it.item.status != ExtraComparisonItemStatus.UNCHANGED }) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(STATUS_MODIFIED, "Changed vs HEAD")
                    LegendDot(STATUS_NEW, "New in working copy")
                }
            }
        }
    }

    @Composable
    private fun LegendDot(color: Color, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = color)
            Spacer(Modifier.width(4.dp))
            Text(label, color = JewelTheme.globalColors.text.info)
        }
    }

    @Composable
    private fun SplitContent(modifier: Modifier) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val maximumListWidth = (availableWidth - 160.dp).coerceAtLeast(120.dp)
            var listWidth by remember(availableWidth) {
                mutableStateOf((availableWidth * 0.35f).coerceIn(120.dp, maximumListWidth))
            }
            Row(Modifier.fillMaxSize()) {
                GoldenGrid(Modifier.width(listWidth).fillMaxHeight())
                Box(
                    Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(JewelTheme.globalColors.borders.normal)
                        .pointerInput(availableWidth) {
                            detectHorizontalDragGestures { _, drag ->
                                listWidth = (listWidth + drag.toDp()).coerceIn(120.dp, maximumListWidth)
                            }
                        },
                )
                ComparePane(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    @Composable
    private fun GoldenGrid(modifier: Modifier) {
        val minCell = (BASE_THUMBNAIL_CELL_WIDTH * thumbnailScale()).dp.coerceAtLeast(80.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            modifier = modifier
                .padding(4.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight, Key.DirectionDown -> moveSelection(1)
                        Key.DirectionLeft, Key.DirectionUp -> moveSelection(-1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusable(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.item.file.path }) { golden -> GoldenCard(golden) }
        }
    }

    @Composable
    private fun GoldenCard(golden: GoldenItem) {
        val item = golden.item
        val selected = selectedItem?.file == item.file
        ContextMenuArea(
            items = {
                listOf(
                    ContextMenuItem("Show in ${RevealFileAction.getFileManagerName()}") {
                        if (item.file.isFile) RevealFileAction.openFile(item.file)
                    },
                    ContextMenuItem("Copy Absolute Path") {
                        CopyPasteManager.getInstance().setContents(StringSelection(item.file.absolutePath))
                    },
                    ContextMenuItem("Delete") { deleteGoldenFile(item.file) },
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(cardBackground(item.status, selected))
                    .clickable { selectItem(item) }
                    .padding(6.dp),
            ) {
                val bitmap = remember(golden.image) { golden.image?.toComposeImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.55f).background(JewelTheme.globalColors.borders.normal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (item.isLoading) "Loading Figma…" else "Missing image")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    shortGoldenName(item.title),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }

    @Composable
    private fun ComparePane(modifier: Modifier) {
        var mode by remember { mutableStateOf(CompareMode.TWO_UP) }
        var zoomIndex by remember { mutableIntStateOf(0) }
        var onionOpacity by remember { mutableStateOf(0.5f) }
        val content = comparison

        Column(modifier) {
            val title = content.title
            if (!title.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = {
                        CopyPasteManager.getInstance().setContents(StringSelection(title))
                    }) { Text("Copy") }
                }
                HorizontalDivider()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (content is ComparisonContent.Pair) {
                    CompareMode.entries.forEach { candidate ->
                        ChoiceButton(candidate.label, mode == candidate) { mode = candidate }
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { zoomIndex = (zoomIndex - 1).coerceAtLeast(0) }, enabled = zoomIndex > 0) {
                    Text("−")
                }
                Text(ZOOM_LABELS[zoomIndex])
                OutlinedButton(
                    onClick = { zoomIndex = (zoomIndex + 1).coerceAtMost(ZOOM_STEPS.lastIndex) },
                    enabled = zoomIndex < ZOOM_STEPS.lastIndex,
                ) { Text("+") }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ComparisonCanvas(content, mode, ZOOM_STEPS[zoomIndex], onionOpacity)
                if (content is ComparisonContent.Pair && mode == CompareMode.ONION) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).width(260.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("HEAD")
                        Slider(
                            value = onionOpacity,
                            onValueChange = { onionOpacity = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(content.newLabel)
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(content.status, color = JewelTheme.globalColors.text.info, modifier = Modifier.weight(1f))
                if (content is ComparisonContent.Retry) {
                    OutlinedButton(onClick = content.action) { Text("Retry") }
                }
            }
        }
    }

    @Composable
    private fun ComparisonCanvas(
        content: ComparisonContent,
        mode: CompareMode,
        zoom: Double,
        opacity: Float,
    ) {
        when (content) {
            ComparisonContent.Empty, is ComparisonContent.Retry -> Unit
            is ComparisonContent.Single -> {
                val image = remember(content.image) { content.image?.toComposeImageBitmap() }
                ScrollableCanvas(zoom, mode, image, null) { canvasModifier ->
                    SingleImageView(image, zoom, canvasModifier)
                }
            }
            is ComparisonContent.Pair -> {
                val old = remember(content.old) { content.old.toComposeImageBitmap() }
                val new = remember(content.new) { content.new.toComposeImageBitmap() }
                val diff = remember(content.diff) { content.diff.toComposeImageBitmap() }
                val shownOld = if (mode == CompareMode.DIFF) diff else old
                val shownNew = if (mode == CompareMode.DIFF) null else new
                ScrollableCanvas(zoom, mode, shownOld, shownNew) { canvasModifier ->
                    when (mode) {
                        CompareMode.TWO_UP -> TwoUpView(old, new, zoom, canvasModifier.padding(top = 28.dp))
                        CompareMode.SWIPE -> SwipeView(old, new, zoom, canvasModifier.padding(top = 28.dp))
                        CompareMode.ONION -> OnionSkinView(old, new, zoom, opacity, canvasModifier.padding(top = 28.dp))
                        CompareMode.DIFF -> SingleImageView(diff, zoom, canvasModifier.padding(top = 28.dp))
                    }
                    ComparisonLabels(content, mode)
                }
            }
        }
    }

    @Composable
    private fun ScrollableCanvas(
        zoom: Double,
        mode: CompareMode,
        old: ImageBitmap?,
        new: ImageBitmap?,
        content: @Composable (Modifier) -> Unit,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val canvasModifier = canvasSizeModifier(zoom, mode, old, new, maxWidth, maxHeight)
            Box(
                Modifier.fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(canvasModifier) { content(Modifier.fillMaxSize()) }
            }
        }
    }

    @Composable
    private fun ComparisonLabels(content: ComparisonContent.Pair, mode: CompareMode) {
        if (mode == CompareMode.DIFF) {
            Text(
                "%.2f%% pixels changed (highlighted)".format(content.changedRatio * 100),
                modifier = Modifier.padding(8.dp),
            )
            return
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(content.oldLabel, modifier = Modifier.weight(1f))
            Text(content.newLabel)
        }
    }

    @Composable
    private fun canvasSizeModifier(
        zoom: Double,
        mode: CompareMode,
        old: ImageBitmap?,
        new: ImageBitmap?,
        viewportWidth: Dp,
        viewportHeight: Dp,
    ): Modifier {
        if (zoom == ImageLayout.FIT) return Modifier.fillMaxSize()
        val density = LocalDensity.current
        val boundWidth = maxOf(old?.width ?: 0, new?.width ?: 0)
        val boundHeight = maxOf(old?.height ?: 0, new?.height ?: 0)
        val widthPx = if (mode == CompareMode.TWO_UP) boundWidth * 2 else boundWidth
        val requiredWidth = with(density) { (widthPx * zoom).toFloat().toDp() }.coerceAtLeast(viewportWidth)
        val requiredHeight = with(density) { (boundHeight * zoom).toFloat().toDp() }.coerceAtLeast(viewportHeight)
        return Modifier.requiredSize(requiredWidth, requiredHeight)
    }

    @Composable
    private fun ChoiceButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        if (selected) {
            DefaultButton(onClick = onClick, enabled = enabled) { Text(label) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
        }
    }

    @Composable
    private fun HorizontalDivider() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(JewelTheme.globalColors.borders.normal))
    }

    private fun cardBackground(status: ExtraComparisonItemStatus, selected: Boolean): Color =
        when {
            selected -> Color(0x553574F0)
            status == ExtraComparisonItemStatus.MODIFIED -> Color(0x333A2428)
            status == ExtraComparisonItemStatus.NEW -> Color(0x331F3326)
            else -> Color.Transparent
        }

    private fun changeScanner(): ChangeScanner {
        val root = project.basePath?.let(::File)
        return ChangeScanner(
            projectRoot = root,
            config = settings.toConfig(),
            headBytes = headBytesSource,
            workingCopyStatus = root?.let(::GitCli) ?: WorkingCopyStatus { emptyList() },
        )
    }

    private fun selectScope(scope: Scope) {
        if (selectedScope == scope) return
        selectedScope = scope
        if (scope == Scope.PROJECT_CHANGES && selectedSource.extra != null) {
            selectedSource = ComparisonSource.WORKING_COPY
        }
        loadedFile = null
        lastRefreshKey = null
        scheduleRefresh(force = true)
    }

    private fun selectSource(source: ComparisonSource) {
        if (selectedScope == Scope.PROJECT_CHANGES && source.extra != null) return
        if (selectedSource == source) return
        val previous = selectedSource
        selectedSource = source
        loadedFile = null
        loadedSource = source
        if (selectedScope == Scope.PROJECT_CHANGES || source.extra != null || previous.extra != null) {
            lastRefreshKey = null
            scheduleRefresh(force = selectedScope == Scope.PROJECT_CHANGES)
        } else {
            selectedItem?.let(::loadComparison)
        }
    }

    private fun selectItem(item: ExtraComparisonItem) {
        selectedItem = item
        loadComparison(item)
    }

    private fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.item.file == selectedItem?.file }
        val next = ((if (current >= 0) current else 0) + delta).coerceIn(0, items.lastIndex)
        selectItem(items[next].item)
    }

    private fun changeThumbnailScale(delta: Int) {
        thumbnailScaleIndex = (thumbnailScaleIndex + delta).coerceIn(0, THUMBNAIL_SCALES.lastIndex)
    }

    private fun onDirectoriesClicked() {
        if (settings.isConfigured) {
            ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
            scheduleRefresh(force = true)
        } else {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Screenshot Directory")
                .withDescription("Directory that contains screenshot golden files")
            val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
            settings.paths = settings.paths + storagePath(chosen.path)
            scheduleRefresh(force = true)
        }
    }

    private fun deleteGoldenFile(file: File) {
        if (!file.isFile) return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete ${file.name} from disk?",
            "Delete Golden File",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return
        if (!runCatching { file.delete() }.getOrDefault(false)) {
            Messages.showErrorDialog(project, "Could not delete:\n${file.path}", "Delete Golden File")
        }
        LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
        scheduleRefresh(force = true)
    }

    private fun storagePath(path: String): String {
        val basePath = project.basePath ?: return path
        val base = File(basePath).normalize()
        val file = File(path).normalize()
        return runCatching { file.relativeTo(base).path }.getOrNull()
            ?.takeIf { !it.startsWith("..") && it != "." }
            ?.replace(File.separatorChar, '/')
            ?: path
    }

    private fun subscribeToEditor() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) =
                    scheduleRefresh()
            },
        )
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    val visible = toolWindow.isVisible
                    if (visible && !wasVisible) scheduleRefresh()
                    wasVisible = visible
                }
            },
        )
    }

    private fun scheduleRefresh(force: Boolean = false) {
        if (force) pendingForce = true
        refreshTimer.restart()
    }

    private fun syncExtraComparisonSources() {
        val present = comparisonSources.map { it.id }.toSet()
        val additions = ExtraComparisonSources.all.filter { it.id !in present }.map(ComparisonSource::extra)
        if (additions.isNotEmpty()) comparisonSources = comparisonSources + additions
    }

    private fun refresh() {
        if (!toolWindow.isVisible) return
        syncExtraComparisonSources()
        val force = pendingForce
        pendingForce = false

        if (!settings.isConfigured) {
            statusText = "Choose a screenshots directory to begin."
            items = emptyList()
            selectedItem = null
            lastRefreshKey = null
            currentScreen = null
            clearComparison()
            return
        }
        val roots = settings.resolvedPaths(project)
        if (force) loadedFile = null
        if (selectedScope == Scope.PROJECT_CHANGES) {
            refreshProjectChanges(roots, force)
            return
        }

        val screen = CurrentScreen.compute(project, settings.annotatedFunctionRegex)
        if (screen == null || screen.names.isEmpty()) {
            statusText = "Open a screen or test file, or switch scope to Project changes."
            items = emptyList()
            selectedItem = null
            lastRefreshKey = null
            currentScreen = null
            clearComparison()
            return
        }
        val source = selectedSource
        val refreshKey = screen.names + source.id + (source.extra?.refreshKey(screen) ?: emptyList())
        if (!force && refreshKey == lastRefreshKey) return

        lastRefreshKey = refreshKey
        currentScreen = screen
        statusText = "Searching…"
        AppExecutorUtil.getAppExecutorService().execute {
            val found = source.extra?.findItems(project, screen, settings) ?: changeScanner().itemsFor(
                GoldenFinder.find(
                    roots,
                    screen,
                    settings.matchMode,
                    settings.excludedSuffixes,
                    settings.goldenFilePatterns,
                ),
                if (source == ComparisonSource.GENERATED) BuiltInSource.GENERATED else BuiltInSource.WORKING_COPY,
            )
            val uiItems = decodeThumbnails(found)
            val override = source.extra?.listStatusForItems(found)
            ApplicationManager.getApplication().invokeLater {
                populate(uiItems, if (source.extra == null) screen.caretName else null, override)
            }
        }
    }

    private fun refreshProjectChanges(roots: List<File>, force: Boolean) {
        val source = selectedSource.takeIf { it.extra == null } ?: ComparisonSource.WORKING_COPY
        val refreshKey = listOf("project-changes", source.id, settings.generatedFileRegex) +
            roots.map { it.path } + settings.resolvedGeneratedPaths(project).map { it.path }
        if (!force && refreshKey == lastRefreshKey) return

        lastRefreshKey = refreshKey
        currentScreen = null
        statusText = "Searching project changes…"
        AppExecutorUtil.getAppExecutorService().execute {
            val found = when (source) {
                ComparisonSource.GENERATED -> changeScanner().generatedChanges()
                else -> changeScanner().workingCopyChanges()
            }.filter { it.status != ExtraComparisonItemStatus.UNCHANGED }
            val uiItems = decodeThumbnails(found)
            val sourceName = if (source == ComparisonSource.GENERATED) "test output" else "working copy"
            ApplicationManager.getApplication().invokeLater {
                populate(
                    uiItems,
                    caretName = null,
                    statusOverride = if (found.isEmpty()) {
                        "No golden changes found in $sourceName."
                    } else {
                        "${found.size} changed screenshot(s) found in project $sourceName."
                    },
                )
            }
        }
    }

    private fun decodeThumbnails(found: List<ExtraComparisonItem>): List<GoldenItem> {
        val trim = settings.trimTransparentPadding
        return found.map { item ->
            val image = runCatching { ImageIO.read(item.file) }.getOrNull()
                ?.let { ImagePainting.trimTransparentBorder(it, trim) }
            GoldenItem(item, image)
        }
    }

    private fun populate(found: List<GoldenItem>, caretName: String?, statusOverride: String?) {
        val previouslyLoaded = loadedFile
        items = found
        statusText = statusOverride ?: if (found.isEmpty()) {
            "No screenshots found for this file. Check the directory or record screenshots."
        } else {
            "${found.size} screenshot(s) found."
        }
        if (found.isEmpty()) {
            selectedItem = null
            clearComparison()
            return
        }
        val index = when {
            previouslyLoaded != null -> found.indexOfFirst { it.item.file == previouslyLoaded }.takeIf { it >= 0 }
            else -> null
        } ?: caretName?.let { name ->
            found.indexOfFirst { it.item.file.name.contains(name, ignoreCase = true) }.takeIf { it >= 0 }
        } ?: 0
        selectItem(found[index].item)
    }

    private fun markItemLoaded(file: File) {
        items = items.map { golden ->
            if (golden.item.file == file && golden.item.isLoading) {
                val updated = golden.item.copy(isLoading = false)
                val image = runCatching { ImageIO.read(file) }.getOrNull()
                    ?.let { ImagePainting.trimTransparentBorder(it, settings.trimTransparentPadding) }
                GoldenItem(updated, image)
            } else {
                golden
            }
        }
    }

    private fun clearComparison() {
        loadedFile = null
        loadedSource = selectedSource
        comparison = ComparisonContent.Empty
    }

    private fun loadComparison(item: ExtraComparisonItem) {
        val file = item.file
        val source = selectedSource
        if (file == loadedFile && source == loadedSource) return
        loadedFile = file
        loadedSource = source
        comparison = ComparisonContent.Single(null, "Loading…", shortGoldenName(file.name))

        val extra = source.extra
        if (extra != null) {
            val screen = currentScreen
            if (screen == null) {
                comparison = ComparisonContent.Single(null, "Open a Kotlin screen or test file.", shortGoldenName(file.name))
                return
            }
            if (!file.isFile) {
                comparison = ComparisonContent.Single(
                    null,
                    "Fetching ${item.title} from Figma… (first load renders on Figma's servers)",
                    shortGoldenName(file.name),
                )
            }
            AppExecutorUtil.getAppExecutorService().execute {
                val result = extra.loadComparison(project, file, screen, settings)
                val rendered = toComparisonContent(result, item)
                ApplicationManager.getApplication().invokeLater {
                    if (loadedFile != file || loadedSource != source) return@invokeLater
                    comparison = rendered
                    if (result is ExtraComparisonResult.Comparison) markItemLoaded(file)
                }
            }
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            val headBytes = headBytesSource.headBytes(file)
            val workingFile = when (source) {
                ComparisonSource.WORKING_COPY -> file
                ComparisonSource.GENERATED -> GeneratedImageSource.findForGolden(
                    golden = file,
                    goldenRoots = settings.resolvedPaths(project),
                    generatedRoots = settings.resolvedGeneratedPaths(project),
                    generatedFileRegex = settings.generatedFileRegex,
                    excludedSuffixes = settings.excludedSuffixes,
                )
                else -> null
            }
            val workingBytes = workingFile?.let(ImageBytes::workingBytes)
            val trim = settings.trimTransparentPadding
            val head = ImagePainting.trimTransparentBorder(ImageBytes.decode(headBytes), trim)
            val working = ImagePainting.trimTransparentBorder(ImageBytes.decode(workingBytes), trim)
            val unchanged = headBytes != null && workingBytes != null && headBytes.contentEquals(workingBytes)
            val title = shortGoldenName(file.name)
            val rendered = when {
                source == ComparisonSource.GENERATED && !settings.hasGeneratedPaths ->
                    ComparisonContent.Single(head, "Configure generated test output directories in Settings.", title)
                source == ComparisonSource.GENERATED && workingFile == null ->
                    ComparisonContent.Single(head, "No generated test output found for ${file.name}.", title)
                unchanged && working != null ->
                    ComparisonContent.Single(working, "No changes vs HEAD — ${file.name}", title)
                head != null && working != null -> comparisonContent(
                    head,
                    working,
                    comparisonTitle(file, workingFile, source),
                    "HEAD",
                    source.compareLabel,
                    title,
                )
                head == null && working != null ->
                    ComparisonContent.Single(working, "New file (not in git HEAD) — ${workingFile?.name ?: file.name}", title)
                head != null -> ComparisonContent.Single(head, "Working copy missing — showing HEAD.", title)
                else -> ComparisonContent.Single(null, "Could not read image.", title)
            }
            ApplicationManager.getApplication().invokeLater {
                if (loadedFile == file && loadedSource == source) comparison = rendered
            }
        }
    }

    private fun toComparisonContent(result: ExtraComparisonResult, item: ExtraComparisonItem): ComparisonContent =
        when (result) {
            is ExtraComparisonResult.Single -> if (result.image == null) {
                ComparisonContent.Retry(result.statusText, shortGoldenName(item.title)) {
                    loadedFile = null
                    loadComparison(item)
                }
            } else {
                ComparisonContent.Single(result.image, result.statusText, shortGoldenName(item.title))
            }
            is ExtraComparisonResult.Comparison -> comparisonContent(
                result.old,
                result.new,
                result.statusText,
                result.oldLabel,
                result.newLabel,
                shortGoldenName(item.title),
            )
        }

    private fun comparisonContent(
        old: BufferedImage,
        new: BufferedImage,
        status: String,
        oldLabel: String,
        newLabel: String,
        title: String,
    ): ComparisonContent.Pair {
        val diff = requireNotNull(PixelDiff.compute(old.toArgbImage(), new.toArgbImage()))
        return ComparisonContent.Pair(
            old = old,
            new = new,
            diff = diff.image.toBufferedImage(),
            changedRatio = diff.changedRatio,
            status = status,
            oldLabel = oldLabel,
            newLabel = newLabel,
            title = title,
        )
    }

    private fun comparisonTitle(golden: File, workingFile: File?, source: ComparisonSource): String =
        when (source) {
            ComparisonSource.WORKING_COPY -> golden.name
            ComparisonSource.GENERATED -> "${golden.name} ↔ ${workingFile?.name ?: "generated output"}"
            else -> golden.name
        }

    private fun thumbnailScale(): Double = THUMBNAIL_SCALES[thumbnailScaleIndex]

    override fun dispose() {
        refreshTimer.stop()
    }

    private data class GoldenItem(val item: ExtraComparisonItem, val image: BufferedImage?)

    private data class ComparisonSource(
        val id: String,
        val title: String,
        val extra: ExtraComparisonSource? = null,
    ) {
        val compareLabel: String get() = extra?.compareLabel ?: title

        companion object {
            val WORKING_COPY = ComparisonSource("working-copy", "Working copy")
            val GENERATED = ComparisonSource("generated", "Test output")
            val builtIns = listOf(WORKING_COPY, GENERATED)
            fun extra(source: ExtraComparisonSource) = ComparisonSource(source.id, source.title, source)
        }
    }

    private enum class Scope(val title: String) {
        CURRENT_FILE("Current file"),
        PROJECT_CHANGES("Project changes"),
    }

    private sealed interface ComparisonContent {
        val status: String
        val title: String?

        data object Empty : ComparisonContent {
            override val status = ""
            override val title: String? = null
        }

        data class Single(
            val image: BufferedImage?,
            override val status: String,
            override val title: String?,
        ) : ComparisonContent

        data class Pair(
            val old: BufferedImage,
            val new: BufferedImage,
            val diff: BufferedImage,
            val changedRatio: Double,
            override val status: String,
            val oldLabel: String,
            val newLabel: String,
            override val title: String?,
        ) : ComparisonContent

        data class Retry(
            override val status: String,
            override val title: String?,
            val action: () -> Unit,
        ) : ComparisonContent
    }

    companion object {
        private const val DEBOUNCE_MS = 300
        private const val BASE_THUMBNAIL_CELL_WIDTH = 300
        private val THUMBNAIL_SCALES = listOf(1.0, 0.85, 0.70, 0.55, 0.40, 0.30, 0.22)
        private val ZOOM_STEPS = listOf(ImageLayout.FIT, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0)
        private val ZOOM_LABELS = listOf("Fit", "50%", "75%", "100%", "150%", "200%", "400%")
        private val STATUS_MODIFIED = Color(0xFFD36A75)
        private val STATUS_NEW = Color(0xFF57A869)
    }
}
