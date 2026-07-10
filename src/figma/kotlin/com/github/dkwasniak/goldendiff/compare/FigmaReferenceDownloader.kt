package com.github.dkwasniak.goldendiff.compare

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FigmaReferenceDownloader(
    private val project: Project,
) {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun download(preview: FigmaPreview, target: FigmaTarget, output: File): FigmaDownloadResult {
        val token = resolveToken() ?: return FigmaDownloadResult.Failure(
            "Missing Figma token. Add it in Golden Diff settings.",
        )
        return try {
            val frame = fetchFrame(target, token) ?: return FigmaDownloadResult.Failure(
                "Figma node ${target.nodeId} was not found.",
            )
            val frameBounds = frame.bounds() ?: return FigmaDownloadResult.Failure(
                "Figma node ${target.nodeId} has no frame bounds.",
            )
            val imageUrl = fetchImageUrl(target, token) ?: return FigmaDownloadResult.Failure(
                "Figma did not return an image URL for node ${target.nodeId}.",
            )
            val fullImage = downloadImage(imageUrl) ?: return FigmaDownloadResult.Failure(
                "Downloaded Figma image could not be decoded.",
            )
            val crop = computeCrop(frame, frameBounds, fullImage)
            val cropped = cropImage(fullImage, crop)
            output.parentFile.mkdirs()
            ImageIO.write(cropped, "png", output)
            writePngTextChunks(
                output,
                mapOf(
                    "sourceUrl" to preview.sourceUrl,
                    "functionName" to preview.functionName,
                    "widthDp" to crop.width.toString(),
                    "heightDp" to crop.height.toString(),
                    "frameHeightDp" to frameBounds.height.roundToInt().toString(),
                    "theme" to detectTheme(cropped),
                    "nodeId" to target.nodeId,
                    "frameSize" to "${frameBounds.width.trimmed()}x${frameBounds.height.trimmed()}",
                    "crop" to "${crop.width}x${crop.height}+${crop.x}+${crop.y}",
                ),
            )
            FigmaDownloadResult.Success
        } catch (e: Exception) {
            thisLogger().warn("Failed to download Figma reference for ${preview.functionName}", e)
            FigmaDownloadResult.Failure(e.message ?: "Failed to download Figma reference.")
        }
    }

    private fun fetchFrame(target: FigmaTarget, token: String): JsonObject? {
        val nodeId = urlEncode(target.nodeId)
        val response = requestJson("https://api.figma.com/v1/files/${target.fileKey}/nodes?ids=$nodeId", token)
        return response.getAsJsonObject("nodes")
            ?.getAsJsonObject(target.nodeId)
            ?.getAsJsonObject("document")
    }

    private fun fetchImageUrl(target: FigmaTarget, token: String): String? {
        val nodeId = urlEncode(target.nodeId)
        val response = requestJson(
            "https://api.figma.com/v1/images/${target.fileKey}?ids=$nodeId&format=png&scale=1",
            token,
        )
        return response.getAsJsonObject("images")?.get(target.nodeId)?.asString
    }

    private fun requestJson(url: String, token: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url))
            .header("X-Figma-Token", token)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Figma API returned HTTP ${response.statusCode()}: ${response.body().take(240)}")
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun downloadImage(url: String): BufferedImage? {
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("Figma image download returned HTTP ${response.statusCode()}")
        }
        return ImageIO.read(ByteArrayInputStream(response.body()))
    }

    private fun computeCrop(frame: JsonObject, frameBounds: Bounds, image: BufferedImage): Crop {
        val bars = detectBars(frame, frameBounds)
        var topCut = bars.filter { it.side == Side.TOP }.maxOfOrNull { it.bounds.bottom - frameBounds.y } ?: 0.0
        var bottomCut = bars.filter { it.side == Side.BOTTOM }.minOfOrNull { it.bounds.y - frameBounds.y } ?: frameBounds.height

        val barNodeIds = bars.map { it.nodeId }.toSet()
        frame.children().forEach { child ->
            if (child.string("id") in barNodeIds || child.boolean("visible") == false) return@forEach
            val content = visibleContentBounds(child) ?: return@forEach
            val childTop = content.first - frameBounds.y
            val childBottom = content.second - frameBounds.y
            if (childTop < topCut) topCut = childTop
            if (childBottom > bottomCut) bottomCut = childBottom
        }

        if (bottomCut <= topCut) error("Detected Figma bars produce an empty crop.")

        val scaleY = image.height.toDouble() / frameBounds.height
        val y = (topCut * scaleY).roundToInt().coerceIn(0, image.height - 1)
        val height = ((bottomCut - topCut) * scaleY).roundToInt().coerceIn(1, image.height - y)
        return Crop(0, y, image.width, height)
    }

    private fun detectBars(frame: JsonObject, frameBounds: Bounds): List<Bar> {
        val minBarWidth = frameBounds.width * 0.55
        val topRegionBottom = frameBounds.y + frameBounds.height * 0.35
        val bottomRegionTop = frameBounds.bottom - frameBounds.height * 0.35
        val seen = mutableSetOf<String>()
        return walkVisibleNodes(frame)
            .filter { it !== frame }
            .mapNotNull { node ->
                val bounds = node.bounds() ?: return@mapNotNull null
                if (bounds.width < minBarWidth) return@mapNotNull null
                val name = node.string("name").orEmpty()
                val side = when {
                    bounds.y <= topRegionBottom && isStatusBarName(name) -> Side.TOP
                    bounds.y >= bottomRegionTop && isSystemNavigationBarName(name) -> Side.BOTTOM
                    else -> return@mapNotNull null
                }
                val key = "$side:${(bounds.x - frameBounds.x).roundToInt()}:" +
                    "${(bounds.y - frameBounds.y).roundToInt()}:${bounds.width.roundToInt()}:${bounds.height.roundToInt()}"
                if (!seen.add(key)) return@mapNotNull null
                Bar(node.string("id").orEmpty(), bounds, side)
            }
    }

    private fun visibleContentBounds(node: JsonObject): Pair<Double, Double>? {
        var top: Double? = null
        var bottom: Double? = null
        walkVisibleNodes(node)
            .filter { it.children().isEmpty() }
            .forEach {
                val bounds = it.bounds() ?: return@forEach
                top = min(top ?: bounds.y, bounds.y)
                bottom = max(bottom ?: bounds.bottom, bounds.bottom)
            }
        return if (top != null && bottom != null) top!! to bottom!! else null
    }

    private fun cropImage(image: BufferedImage, crop: Crop): BufferedImage {
        val subimage = image.getSubimage(crop.x, crop.y, crop.width, crop.height)
        val copy = BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().use { graphics ->
            graphics.drawImage(subimage, 0, 0, null)
        }
        return copy
    }

    private fun detectTheme(image: BufferedImage): String {
        var total = 0.0
        var count = 0
        for (x in 1..5) {
            for (y in 1..5) {
                val rgb = image.getRGB(image.width * x / 6, image.height * y / 6)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                total += 0.299 * r + 0.587 * g + 0.114 * b
                count++
            }
        }
        return if (total / count < 128) "dark" else "light"
    }

    private fun writePngTextChunks(file: File, chunks: Map<String, String>) {
        val encoded = ByteArrayOutputStream()
        chunks.forEach { (key, value) ->
            val payload = key.toByteArray(StandardCharsets.ISO_8859_1) +
                byteArrayOf(0) +
                value.toByteArray(StandardCharsets.ISO_8859_1)
            encoded.write(pngChunk("tEXt", payload))
        }

        val data = file.readBytes()
        var pos = 8
        while (pos < data.size - 8) {
            val length = ByteBuffer.wrap(data, pos, 4).int
            if (String(data, pos + 4, 4, StandardCharsets.ISO_8859_1) == "IDAT") break
            pos += 4 + 4 + length + 4
        }
        file.writeBytes(data.copyOfRange(0, pos) + encoded.toByteArray() + data.copyOfRange(pos, data.size))
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        return ByteBuffer.allocate(4 + 4 + payload.size + 4)
            .putInt(payload.size)
            .put(typeBytes)
            .put(payload)
            .putInt(crc.value.toInt())
            .array()
    }

    private fun resolveToken(): String? {
        return FigmaTokenStore.get(project)
    }

    private fun walkVisibleNodes(node: JsonObject, inheritedVisible: Boolean = true): List<JsonObject> {
        val visible = inheritedVisible && node.boolean("visible") != false
        if (!visible) return emptyList()
        return listOf(node) + node.children().flatMap { walkVisibleNodes(it, visible) }
    }

    private fun JsonObject.bounds(): Bounds? {
        val box = getAsJsonObject("absoluteBoundingBox") ?: return null
        return Bounds(
            x = box.double("x") ?: return null,
            y = box.double("y") ?: return null,
            width = box.double("width") ?: return null,
            height = box.double("height") ?: return null,
        )
    }

    private fun JsonObject.children(): List<JsonObject> =
        getAsJsonArray("children")?.mapNotNull { it.asJsonObject } ?: emptyList()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean

    private fun JsonObject.double(name: String): Double? =
        get(name)?.takeUnless { it.isJsonNull }?.asDouble

    private fun normalizedName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isStatusBarName(name: String): Boolean {
        val normalized = normalizedName(name)
        return "status bar" in normalized || "statusbar" in normalized
    }

    private fun isSystemNavigationBarName(name: String): Boolean {
        val normalized = normalizedName(name)
        return normalized in setOf("navigation", "navigation bar", "system navigation") ||
            "system nav" in normalized ||
            "android nav bar" in normalized ||
            "android navigation bar" in normalized
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun Double.trimmed(): String =
        if (this % 1.0 == 0.0) toInt().toString() else toString()

    private data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
        val bottom: Double get() = y + height
    }

    private data class Bar(val nodeId: String, val bounds: Bounds, val side: Side)

    private data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

    private enum class Side { TOP, BOTTOM }
}

sealed class FigmaDownloadResult {
    data object Success : FigmaDownloadResult()

    data class Failure(val message: String) : FigmaDownloadResult()
}

private fun java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
