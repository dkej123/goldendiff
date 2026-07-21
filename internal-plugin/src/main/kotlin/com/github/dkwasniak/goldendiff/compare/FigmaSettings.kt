package com.github.dkwasniak.goldendiff.compare

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.File

/**
 * Project-level settings for the Figma variant. Only the directory that holds the committed Figma
 * reference PNGs is configurable; the token lives in the IDE password safe (see [FigmaTokenStore]).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GoldenDiffFigmaSettings",
    storages = [Storage("goldenDiffFigma.xml")],
)
class FigmaSettings : PersistentStateComponent<FigmaSettings.State> {

    class State {
        // Directory scanned for committed Figma reference PNGs. Relative paths resolve against the
        // project base directory; absolute paths are kept as-is.
        var goldenPath: String = DEFAULT_GOLDEN_PATH
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    var goldenPath: String
        get() = state.goldenPath.ifBlank { DEFAULT_GOLDEN_PATH }
        set(value) {
            state.goldenPath = value.trim().ifBlank { DEFAULT_GOLDEN_PATH }
        }

    fun resolvedGoldenDir(project: Project): File {
        val file = File(goldenPath)
        if (file.isAbsolute) return file.normalize()
        val basePath = project.basePath ?: return file.normalize()
        return File(basePath, goldenPath).normalize()
    }

    companion object {
        const val DEFAULT_GOLDEN_PATH = "screenshotTests/figma-goldens"

        fun getInstance(project: Project): FigmaSettings = project.service()
    }
}
