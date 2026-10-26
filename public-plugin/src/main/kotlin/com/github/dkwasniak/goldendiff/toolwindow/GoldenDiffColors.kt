package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Design tokens of the Files / Review tool window. Dark values come from the v3 handoff; light
 * values keep the v2 palette so both themes share one set of semantic names.
 */
internal object GoldenDiffColors {
    val background = JBColor(Color(0xFFFFFF), Color(0x1E1F22))
    val panel = JBColor(Color(0xF7F8FA), Color(0x242629))
    val header = JBColor(Color(0xF2F3F5), Color(0x2B2D30))
    val border = JBColor(Color(0xE1E3E8), Color(0x333438))
    val borderStrong = JBColor(Color(0xC9CCD2), Color(0x434447))
    val text = JBColor(Color(0x1E1F22), Color(0xDFE1E5))
    val dim = JBColor(Color(0x6C707E), Color(0x8A8D93))
    val faint = JBColor(Color(0x8C8F99), Color(0x6B6E74))
    val accent = JBColor(Color(0x3574F0), Color(0x548AF7))
    val accentBackground = JBColor(Color(53, 116, 240, 30), Color(84, 138, 247, 41))
    val failed = JBColor(Color(0xC9424A), Color(0xE0656B))
    val modified = JBColor(Color(0xA9761F), Color(0xD9A441))
    val new = JBColor(Color(0x2F8F52), Color(0x66B578))
    val brand = JBColor(Color(0xE0507E), Color(0xE0507E))
    val disabledFill = JBColor(Color(0, 0, 0, 15), Color(255, 255, 255, 15))
    val scrim = Color(0, 0, 0, 102)
    val stripeA = JBColor(Color(0xEDEEF1), Color(0x2A2C30))
    val stripeB = JBColor(Color(0xE6E7EB), Color(0x26282B))

    fun status(status: GoldenDisplayStatus): Color = when (status) {
        GoldenDisplayStatus.FAILED -> failed
        GoldenDisplayStatus.MODIFIED -> modified
        GoldenDisplayStatus.NEW -> new
        GoldenDisplayStatus.ACCEPTED -> accent
        GoldenDisplayStatus.UNCHANGED -> faint
    }

    /** 14% wash of a status colour, used behind badges and pills. */
    fun statusBackground(status: GoldenDisplayStatus): Color = status(status).withAlpha(36)
}

internal fun Color.withAlpha(alpha: Int): Color = Color(red, green, blue, alpha)
