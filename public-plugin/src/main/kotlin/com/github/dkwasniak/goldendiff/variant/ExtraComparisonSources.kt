package com.github.dkwasniak.goldendiff.variant

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.Disposable

object ExtraComparisonSources {
    // Contributed by dependent plugins (e.g. the internal Figma plugin) through this extension point.
    // Declared in the public plugin.xml as `<extensionPoint name="comparisonSource" .../>`, so the full
    // id is the plugin id plus the point name.
    private val EP: ExtensionPointName<ExtraComparisonSource> =
        ExtensionPointName.create("com.github.dkwasniak.goldendiff.comparisonSource")

    // Not cached: the extension list must reflect plugins loaded/unloaded at runtime (the point is
    // declared dynamic), so each read queries the point.
    val all: List<ExtraComparisonSource>
        get() = EP.extensionList

    fun addChangeListener(parentDisposable: Disposable, listener: () -> Unit) {
        EP.addExtensionPointListener(
            object : ExtensionPointListener<ExtraComparisonSource> {
                override fun extensionAdded(
                    extension: ExtraComparisonSource,
                    pluginDescriptor: PluginDescriptor,
                ) {
                    listener()
                }

                override fun extensionRemoved(
                    extension: ExtraComparisonSource,
                    pluginDescriptor: PluginDescriptor,
                ) {
                    listener()
                }
            },
            parentDisposable,
        )
    }
}
