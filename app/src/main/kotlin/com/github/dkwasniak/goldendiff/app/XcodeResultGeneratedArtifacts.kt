package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartIndex
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

internal fun interface XcresultSummaryReader {
    fun read(resultBundle: File): String?
}

internal data class XcodeRunDevice(
    val platform: String,
    val name: String,
    val os: String,
)

internal data class XcodeTestRun(
    val id: String,
    val path: File,
    val modifiedAt: Long,
    val device: XcodeRunDevice?,
    val passedTests: Int?,
    val failedTests: Int?,
    val artifacts: List<GeneratedCounterpartIndex.ExplicitCandidate>,
    val partial: Boolean = false,
)

internal fun selectXcodeRunAfterVerify(
    before: List<XcodeTestRun>,
    after: List<XcodeTestRun>,
    currentPath: String?,
): String? {
    val oldTimes = before.associate { it.id to it.modifiedAt }
    return after.firstOrNull { oldTimes[it.id] == null || oldTimes[it.id] != it.modifiedAt }?.id
        ?: currentPath?.takeIf { selected -> after.any { it.id == selected } }
}

internal fun selectAvailableXcodeRun(runs: List<XcodeTestRun>, savedPath: String?): String? =
    savedPath?.takeIf { selected -> runs.any { it.id == selected } } ?: runs.firstOrNull()?.id

/** App-only `.xcresult` catalog. Each entry remains a separate run; artifacts are never merged. */
internal class XcodeResultGeneratedArtifacts(
    private val derivedDataRoot: File = File(
        System.getProperty("user.home"),
        "Library/Developer/Xcode/DerivedData",
    ),
    private val summaryReader: XcresultSummaryReader = XcresultSummaryReader(::readSummary),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(val path: String, val modifiedAt: Long, val projectPath: String)
    private data class CachedRun(val run: XcodeTestRun, val rawArtifactUrls: List<String>)
    private val parsedResults = linkedMapOf<CacheKey, CachedRun>()

    @Synchronized
    fun runs(projectRoot: File, config: GoldenDiffConfig): List<XcodeTestRun> {
        if (!config.usesXcresultSource) return emptyList()
        val override = config.xcresultDerivedDataPath.takeIf(String::isNotBlank)?.expandUserHome()
        val projectPath = projectRoot.normalize().absolutePath
        return resultBundles(projectRoot, override, config.xcresultScanWindowHours).map { bundle ->
            val key = CacheKey(bundle.absolutePath, bundle.lastModified(), projectPath)
            val cached = parsedResults[key] ?: parseRun(bundle, projectRoot).also {
                parsedResults[key] = it
                trimCache()
            }
            cached.run.copy(artifacts = candidates(cached.rawArtifactUrls))
        }
    }

    fun find(
        projectRoot: File,
        config: GoldenDiffConfig,
        selectedRunPath: String? = null,
    ): List<GeneratedCounterpartIndex.ExplicitCandidate> {
        val available = runs(projectRoot, config)
        return (available.firstOrNull { it.id == selectedRunPath } ?: available.firstOrNull())?.artifacts.orEmpty()
    }

    /** Compatibility helper used by focused adapter tests: newest bundle only. */
    @Synchronized
    fun find(projectRoot: File): List<GeneratedCounterpartIndex.ExplicitCandidate> {
        val config = GoldenDiffConfig(
            generatedSource = com.github.dkwasniak.goldendiff.settings.GeneratedSource.XCRESULT,
            xcresultScanWindowHours = 0,
        )
        val override = config.xcresultDerivedDataPath.takeIf(String::isNotBlank)?.expandUserHome()
        val bundle = resultBundles(projectRoot, override, 0).firstOrNull() ?: return emptyList()
        val key = CacheKey(bundle.absolutePath, bundle.lastModified(), projectRoot.normalize().absolutePath)
        val cached = parsedResults[key] ?: parseRun(bundle, projectRoot).also { parsedResults[key] = it }
        return candidates(cached.rawArtifactUrls)
    }

    private fun parseRun(bundle: File, projectRoot: File): CachedRun {
        val raw = summaryReader.read(bundle)
        if (raw == null) return CachedRun(unavailableRun(bundle), emptyList())
        return runCatching {
            val json = JSONObject(raw)
            val strings = extractStrings(json).toList()
            val projectPath = projectRoot.normalize().absolutePath
            val projectUri = projectRoot.normalize().toURI().toString()
            val urls = strings.asSequence()
                .filter { it.contains(projectPath) || it.contains(projectUri) }
                .flatMap { ACTUAL_FILE_URL.findAll(it).map { match -> match.groupValues[1] } }
                .distinct()
                .toList()
            CachedRun(XcodeTestRun(
                id = bundle.normalize().absolutePath,
                path = bundle,
                modifiedAt = bundle.lastModified(),
                device = metadataDevice(json),
                passedTests = findNumber(json, PASSED_KEYS),
                failedTests = findNumber(json, FAILED_KEYS),
                artifacts = candidates(urls),
            ), urls)
        }.getOrElse { CachedRun(unavailableRun(bundle), emptyList()) }
    }

    private fun candidates(urls: List<String>): List<GeneratedCounterpartIndex.ExplicitCandidate> = urls.asSequence()
        .mapNotNull(::existingPng)
        .distinctBy { it.normalize().absolutePath }
        .map { file ->
            GeneratedCounterpartIndex.ExplicitCandidate(
                file = file,
                relativeParent = file.parentFile?.name.orEmpty(),
                goldenBase = file.nameWithoutExtension,
            )
        }
        .distinctBy { "${it.relativeParent.lowercase()}/${it.file.name.lowercase()}" }
        .toList()

    private fun unavailableRun(bundle: File) = XcodeTestRun(
        id = bundle.normalize().absolutePath,
        path = bundle,
        modifiedAt = bundle.lastModified(),
        device = null,
        passedTests = null,
        failedTests = null,
        artifacts = emptyList(),
        partial = true,
    )

    private fun metadataDevice(json: JSONObject): XcodeRunDevice? {
        val objects = extractObjects(json).toList()
        val candidate = objects.firstOrNull { obj ->
            DEVICE_NAME_KEYS.any { obj.has(it) } && OS_KEYS.any { obj.has(it) }
        } ?: return null
        fun string(keys: Set<String>): String? = keys.firstNotNullOfOrNull { key ->
            candidate.optString(key).takeIf(String::isNotBlank)
        }
        val name = string(DEVICE_NAME_KEYS) ?: return null
        val os = string(OS_KEYS) ?: return null
        val rawPlatform = string(PLATFORM_KEYS).orEmpty()
        val platform = when {
            rawPlatform.contains("simulator", true) -> "iOS Simulator"
            rawPlatform.contains("ios", true) -> "iOS"
            else -> rawPlatform.ifBlank { "iOS" }
        }
        return XcodeRunDevice(platform, name, os.removePrefix("iOS "))
    }

    private fun findNumber(json: JSONObject, keys: Set<String>): Int? = extractObjects(json)
        .flatMap { obj -> keys.asSequence().filter(obj::has).map { obj.opt(it) } }
        .mapNotNull { value ->
            when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }.firstOrNull()

    private fun resultBundles(projectRoot: File, override: File?, scanWindowHours: Int): List<File> {
        val projectNames = projectRoot.listFiles().orEmpty()
            .filter { it.isDirectory && (it.extension == "xcodeproj" || it.extension == "xcworkspace") }
            .map { it.nameWithoutExtension }.toSet()
        if (projectNames.isEmpty()) return emptyList()
        val roots = override?.takeIf(File::isDirectory)?.let(::sequenceOf)
            ?: derivedDataRoot.listFiles().orEmpty().asSequence()
        val windowMillis = TimeUnit.HOURS.toMillis(scanWindowHours.coerceAtLeast(0).toLong())
        val currentTime = now()
        val cutoff = if (scanWindowHours <= 0 || windowMillis >= currentTime) Long.MIN_VALUE else
            currentTime - windowMillis
        return roots.filter(File::isDirectory)
            .filter { override != null || projectNames.any { name -> it.name == name || it.name.startsWith("$name-") } }
            .filter { it.belongsTo(projectRoot) }
            .flatMap { File(it, "Logs/Test").listFiles().orEmpty().asSequence() }
            .filter { it.isDirectory && it.extension == "xcresult" && it.lastModified() >= cutoff }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.absolutePath })
            .take(MAX_RESULT_BUNDLES).toList()
    }

    private fun trimCache() {
        val liveKeys = parsedResults.keys.groupBy { it.path }.values.flatMap { keys ->
            keys.sortedByDescending { it.modifiedAt }.take(1)
        }.toSet()
        parsedResults.keys.retainAll(liveKeys)
        while (parsedResults.size > MAX_CACHED_RESULTS) parsedResults.remove(parsedResults.keys.first())
    }

    private fun File.belongsTo(projectRoot: File): Boolean {
        val info = File(this, "info.plist")
        if (!info.isFile) return true
        val workspacePath = runCatching {
            WORKSPACE_PATH.find(info.readText())?.groupValues?.get(1)?.decodeXmlEntities()
        }.getOrNull() ?: return true
        return File(workspacePath).normalize().parentFile == projectRoot.normalize()
    }

    private fun existingPng(rawUrl: String): File? = runCatching {
        val uri = URI(rawUrl)
        if (!uri.scheme.equals("file", true)) return null
        File(uri).normalize()
    }.getOrNull()?.takeIf { it.isFile && it.extension.equals("png", true) }

    private fun String.expandUserHome(): File = when {
        this == "~" -> File(System.getProperty("user.home"))
        startsWith("~/") -> File(System.getProperty("user.home"), removePrefix("~/"))
        else -> File(this)
    }.normalize()

    private fun extractStrings(value: Any?): Sequence<String> = sequence {
        when (value) {
            is JSONObject -> value.keys().forEach { yieldAll(extractStrings(value.opt(it))) }
            is JSONArray -> repeat(value.length()) { yieldAll(extractStrings(value.opt(it))) }
            is String -> yield(value)
        }
    }

    private fun extractObjects(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> {
                yield(value)
                value.keys().forEach { yieldAll(extractObjects(value.opt(it))) }
            }
            is JSONArray -> repeat(value.length()) { yieldAll(extractObjects(value.opt(it))) }
        }
    }

    companion object {
        private const val MAX_RESULT_BUNDLES = 50
        private const val MAX_CACHED_RESULTS = 50
        private val DEVICE_NAME_KEYS = setOf("deviceName", "modelName", "name")
        private val OS_KEYS = setOf("osVersion", "operatingSystemVersion", "OS")
        private val PLATFORM_KEYS = setOf("platform", "platformName", "targetPlatform")
        private val PASSED_KEYS = setOf("passedTests", "testsPassedCount", "passedTestCount")
        private val FAILED_KEYS = setOf("failedTests", "testsFailedCount", "failedTestCount")
        private val WORKSPACE_PATH = Regex(
            """<key>\s*WorkspacePath\s*</key>\s*<string>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val ACTUAL_FILE_URL = Regex("""(?m)@\+\s*\R\s*\"(file:[^\"]+\.png)\"""", RegexOption.IGNORE_CASE)

        private fun String.decodeXmlEntities(): String = replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

        private fun readSummary(resultBundle: File): String? = runCatching {
            val process = ProcessBuilder(
                "xcrun", "xcresulttool", "get", "test-results", "summary", "--path", resultBundle.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.takeIf { process.waitFor() == 0 }
        }.getOrNull()
    }
}
