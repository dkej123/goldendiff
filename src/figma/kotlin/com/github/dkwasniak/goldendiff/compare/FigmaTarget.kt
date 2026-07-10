package com.github.dkwasniak.goldendiff.compare

import java.net.URI

data class FigmaTarget(
    val sourceUrl: String,
    val fileKey: String,
    val nodeId: String,
) {
    companion object {
        fun parse(sourceUrl: String): FigmaTarget? {
            val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
            val parts = uri.path.split('/').filter { it.isNotBlank() }
            if (parts.size < 2 || parts[0] != "design") return null

            var fileKey = parts[1]
            if (parts.size >= 4 && parts[2] == "branch") {
                fileKey = parts[3]
            }

            val nodeId = uri.rawQuery
                ?.split('&')
                ?.mapNotNull {
                    val separator = it.indexOf('=')
                    if (separator < 0) null else it.substring(0, separator) to it.substring(separator + 1)
                }
                ?.firstOrNull { it.first == "node-id" }
                ?.second
                ?.replace('-', ':')
                ?: return null

            return FigmaTarget(sourceUrl, fileKey, nodeId)
        }
    }
}
