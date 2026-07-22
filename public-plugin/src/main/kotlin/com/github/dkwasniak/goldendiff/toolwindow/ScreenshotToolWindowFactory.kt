package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.jetbrains.jewel.bridge.JewelComposePanel

class ScreenshotToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val screen = ScreenshotToolWindow(project, toolWindow)
        val component = JewelComposePanel { screen.Content() }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        content.setDisposer(screen)
        toolWindow.contentManager.addContent(content)
    }
}
