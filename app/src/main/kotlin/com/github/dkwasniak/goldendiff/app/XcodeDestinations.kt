package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class XcodeBuildContext(
    val scheme: String,
    val workspace: String? = null,
    val project: String? = null,
)

internal data class XcodeDestination(
    val platform: String,
    val name: String,
    val os: String? = null,
    val id: String? = null,
) {
    val isSimulator: Boolean get() = platform.equals("iOS Simulator", ignoreCase = true)
    val commandValue: String
        get() = if (isSimulator) {
            listOfNotNull("platform=iOS Simulator", "name=$name", os?.let { "OS=$it" }).joinToString(",")
        } else {
            listOfNotNull("platform=$platform", id?.let { "id=$it" }, "name=$name").joinToString(",")
        }
}

internal fun isSelectableXcodeDestination(
    destination: XcodeDestination,
    available: List<XcodeDestination>,
    authoritative: Boolean,
): Boolean = authoritative && validatedXcodeDestination(destination, available) != null

internal fun validatedXcodeDestination(
    requested: XcodeDestination,
    authoritative: List<XcodeDestination>,
): XcodeDestination? = authoritative.firstOrNull { it.commandValue == requested.commandValue }

internal data class XcodeDestinationPickerUpdate(
    val state: XcodeDestinationPickerState,
    val confirmedSelection: XcodeDestination? = null,
)

/** App-only presentation state for fast discovery followed by scheme-aware validation. */
internal data class XcodeDestinationPickerState(
    val destinations: List<XcodeDestination> = emptyList(),
    val loading: Boolean = false,
    val authoritative: Boolean = false,
    val error: String? = null,
    val pendingSelection: XcodeDestination? = null,
) {
    fun startLoading(): XcodeDestinationPickerState = copy(
        loading = true,
        authoritative = false,
        error = null,
        pendingSelection = null,
    )

    fun localLoaded(result: Result<List<XcodeDestination>>): XcodeDestinationPickerState {
        val local = result.getOrNull().orEmpty()
        return if (local.isEmpty()) this else copy(destinations = local, loading = false)
    }

    fun requestSelection(destination: XcodeDestination): XcodeDestinationPickerUpdate {
        if (!authoritative) {
            return XcodeDestinationPickerUpdate(copy(error = null, pendingSelection = destination))
        }
        val verified = validatedXcodeDestination(destination, destinations)
        return if (verified != null) {
            XcodeDestinationPickerUpdate(copy(error = null, pendingSelection = null), verified)
        } else {
            XcodeDestinationPickerUpdate(
                copy(error = "The selected destination is not available for the configured Xcode scheme."),
            )
        }
    }

    fun authoritativeLoaded(
        result: Result<List<XcodeDestination>>,
        fallbackError: String? = null,
    ): XcodeDestinationPickerUpdate = result.fold(
        onSuccess = { verified ->
            val confirmed = pendingSelection?.let { validatedXcodeDestination(it, verified) }
            val message = when {
                verified.isEmpty() -> "No destinations are available for the configured Xcode scheme."
                pendingSelection != null && confirmed == null ->
                    "The selected destination is not available for the configured Xcode scheme."
                else -> null
            }
            XcodeDestinationPickerUpdate(
                state = copy(
                    destinations = verified,
                    loading = false,
                    authoritative = verified.isNotEmpty(),
                    error = message,
                    pendingSelection = null,
                ),
                confirmedSelection = confirmed,
            )
        },
        onFailure = { failure ->
            XcodeDestinationPickerUpdate(
                copy(
                    loading = false,
                    authoritative = false,
                    error = failure.message ?: fallbackError ?: "Could not validate Xcode destinations.",
                    pendingSelection = null,
                ),
            )
        },
    )
}

internal sealed interface XcodeCommandAnalysis {
    data class Valid(
        val context: XcodeBuildContext,
        val destination: XcodeDestination?,
    ) : XcodeCommandAnalysis
    data class Invalid(val reason: String) : XcodeCommandAnalysis
}

internal object XcodeCommandDestination {
    private data class Token(val value: String, val start: Int, val end: Int)

    fun analyze(command: String): XcodeCommandAnalysis {
        val tokens = tokenize(command)
        val xcodeIndexes = tokens.indices.filter { tokens[it].value.substringAfterLast('/') == "xcodebuild" }
        if (xcodeIndexes.size != 1) return XcodeCommandAnalysis.Invalid(
            if (xcodeIndexes.isEmpty()) "Command does not contain xcodebuild." else "Command contains multiple xcodebuild invocations.",
        )
        val start = xcodeIndexes.single()
        val commandEnd = commandSegmentEnd(command, tokens[start].end)
        fun option(name: String): List<String> = buildList {
            for (index in start + 1 until tokens.size) {
                if (tokens[index].start >= commandEnd) break
                val value = tokens[index].value
                when {
                    value == name && index + 1 < tokens.size -> add(tokens[index + 1].value)
                    value.startsWith("$name=") -> add(value.substringAfter('='))
                }
            }
        }
        val schemes = option("-scheme")
        val workspaces = option("-workspace")
        val projects = option("-project")
        val destinations = option("-destination")
        if (schemes.size != 1) return XcodeCommandAnalysis.Invalid("Command must contain exactly one -scheme.")
        if (workspaces.size > 1 || projects.size > 1 || workspaces.isNotEmpty() && projects.isNotEmpty()) {
            return XcodeCommandAnalysis.Invalid("Command has an ambiguous workspace/project context.")
        }
        if (destinations.size > 1) return XcodeCommandAnalysis.Invalid("Command contains multiple destinations.")
        return XcodeCommandAnalysis.Valid(
            context = XcodeBuildContext(schemes.single(), workspaces.singleOrNull(), projects.singleOrNull()),
            destination = destinations.singleOrNull()?.let(::parseDestination),
        )
    }

    fun rewrite(command: String, destination: XcodeDestination): Result<String> = runCatching {
        val analysis = analyze(command)
        require(analysis is XcodeCommandAnalysis.Valid) {
            (analysis as XcodeCommandAnalysis.Invalid).reason
        }
        val tokens = tokenize(command)
        val xcodeIndex = tokens.indexOfFirst { it.value.substringAfterLast('/') == "xcodebuild" }
        val segmentEnd = commandSegmentEnd(command, tokens[xcodeIndex].end)
        val destinationIndex = (xcodeIndex + 1 until tokens.size).firstOrNull { index ->
            tokens[index].start < segmentEnd &&
                (tokens[index].value == "-destination" || tokens[index].value.startsWith("-destination="))
        }
        val quoted = shellQuote(destination.commandValue)
        if (destinationIndex == null) {
            val insertion = segmentEnd.let { end ->
                var trimmed = end
                while (trimmed > 0 && command[trimmed - 1].isWhitespace()) trimmed--
                trimmed
            }
            command.substring(0, insertion) + " -destination $quoted" + command.substring(insertion)
        } else {
            val option = tokens[destinationIndex]
            if (option.value.startsWith("-destination=")) {
                command.replaceRange(option.start, option.end, "-destination=$quoted")
            } else {
                val value = tokens.getOrNull(destinationIndex + 1)
                    ?: error("Destination has no value.")
                command.replaceRange(value.start, value.end, quoted)
            }
        }
    }

    fun rewriteAll(config: GoldenDiffConfig, destination: XcodeDestination): Result<GoldenDiffConfig> = runCatching {
        val commands = listOf(
            config.generateAllCommand,
            config.generateFileCommand,
            config.verifyAllCommand,
            config.verifyFileCommand,
            config.verifyCaseCommand,
        ).filter(String::isNotBlank)
        require(commands.isNotEmpty()) { "No configured Xcode commands." }
        val analyses = commands.map(::analyze)
        val invalid = analyses.filterIsInstance<XcodeCommandAnalysis.Invalid>().firstOrNull()
        require(invalid == null) { invalid?.reason.orEmpty() }
        val contexts = analyses.filterIsInstance<XcodeCommandAnalysis.Valid>().map { it.context }.distinct()
        require(contexts.size == 1) { "Configured commands use different Xcode schemes or projects." }
        fun changed(value: String) = if (value.isBlank()) value else rewrite(value, destination).getOrThrow()
        config.copy(
            generateAllCommand = changed(config.generateAllCommand),
            generateFileCommand = changed(config.generateFileCommand),
            verifyAllCommand = changed(config.verifyAllCommand),
            verifyFileCommand = changed(config.verifyFileCommand),
            verifyCaseCommand = changed(config.verifyCaseCommand),
        )
    }

    fun commonContext(config: GoldenDiffConfig): Result<XcodeBuildContext> = runCatching {
        val commands = listOf(
            config.generateAllCommand, config.generateFileCommand, config.verifyAllCommand,
            config.verifyFileCommand, config.verifyCaseCommand,
        ).filter(String::isNotBlank)
        require(commands.isNotEmpty()) { "Configure an xcodebuild command first." }
        val analyses = commands.map(::analyze)
        val invalid = analyses.filterIsInstance<XcodeCommandAnalysis.Invalid>().firstOrNull()
        require(invalid == null) { invalid?.reason.orEmpty() }
        analyses.filterIsInstance<XcodeCommandAnalysis.Valid>().map { it.context }.distinct()
            .singleOrNull() ?: error("Configured commands use different Xcode schemes or projects.")
    }

    fun selectedDestination(config: GoldenDiffConfig): Result<XcodeDestination?> = runCatching {
        val commands = listOf(
            config.generateAllCommand, config.generateFileCommand, config.verifyAllCommand,
            config.verifyFileCommand, config.verifyCaseCommand,
        ).filter(String::isNotBlank)
        if (commands.isEmpty()) return@runCatching null
        val analyses = commands.map(::analyze)
        val invalid = analyses.filterIsInstance<XcodeCommandAnalysis.Invalid>().firstOrNull()
        require(invalid == null) { invalid?.reason.orEmpty() }
        val destinations = analyses.filterIsInstance<XcodeCommandAnalysis.Valid>().map { it.destination }.distinct()
        require(destinations.size == 1) { "Configured commands use different destinations." }
        destinations.single()
    }

    fun parseShowDestinations(output: String): List<XcodeDestination> = BRACED.findAll(output)
        .mapNotNull { match ->
            val values = match.groupValues[1].split(',').mapNotNull { part ->
                val separator = part.indexOf(':')
                if (separator < 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
            }.toMap()
            val platform = values["platform"] ?: return@mapNotNull null
            val name = values["name"] ?: return@mapNotNull null
            if (!platform.equals("iOS", true) && !platform.equals("iOS Simulator", true)) return@mapNotNull null
            if (values["error"] != null || name.startsWith("Any iOS")) return@mapNotNull null
            XcodeDestination(platform, name, values["OS"], values["id"])
        }
        .toList()
        .sortedDestinations()

    fun parseSimctlDevices(output: String): List<XcodeDestination> = runCatching {
        val devices = JSONObject(output).getJSONObject("devices")
        devices.keySet().asSequence().flatMap { runtime ->
            val os = runtime.substringAfter(".iOS-", missingDelimiterValue = "")
                .replace('-', '.')
                .takeIf(String::isNotBlank)
            val entries = devices.getJSONArray(runtime)
            (0 until entries.length()).asSequence().mapNotNull { index ->
                val device = entries.getJSONObject(index)
                if (!device.optBoolean("isAvailable", true)) return@mapNotNull null
                val name = device.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = device.optString("udid").takeIf(String::isNotBlank)
                XcodeDestination("iOS Simulator", name, os, id)
            }
        }.toList().sortedDestinations()
    }.getOrDefault(emptyList())

    fun parseDevicectlDevices(output: String): List<XcodeDestination> = runCatching {
        val devices = JSONObject(output).getJSONObject("result").getJSONArray("devices")
        (0 until devices.length()).mapNotNull { index ->
            val device = devices.getJSONObject(index)
            val hardware = device.optJSONObject("hardwareProperties") ?: return@mapNotNull null
            if (!hardware.optString("platform").equals("iOS", ignoreCase = true)) return@mapNotNull null
            if (!hardware.optString("reality").equals("physical", ignoreCase = true)) return@mapNotNull null
            val properties = device.optJSONObject("deviceProperties") ?: return@mapNotNull null
            val name = properties.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = hardware.optString("udid").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val os = properties.optString("osVersionNumber").takeIf(String::isNotBlank)
            XcodeDestination("iOS", name, os, id)
        }.sortedDestinations()
    }.getOrDefault(emptyList())

    fun combineDestinations(vararg groups: List<XcodeDestination>): List<XcodeDestination> =
        groups.flatMap { it }.sortedDestinations()

    private fun parseDestination(value: String): XcodeDestination? {
        val values = value.split(',').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) null else part.substring(0, separator).trim().lowercase() to part.substring(separator + 1).trim()
        }.toMap()
        val platform = values["platform"] ?: return null
        return XcodeDestination(platform, values["name"].orEmpty(), values["os"], values["id"])
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun commandSegmentEnd(command: String, from: Int): Int {
        var index = from
        var quote: Char? = null
        while (index < command.length) {
            val char = command[index]
            when {
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == char -> quote = null
                char == '\\' && quote != '\'' -> index++
                quote == null && (char == ';' || char == '\n' || char == '|') -> return index
                quote == null && char == '&' && command.getOrNull(index + 1) == '&' -> return index
            }
            index++
        }
        return command.length
    }

    private fun tokenize(command: String): List<Token> {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < command.length) {
            while (index < command.length && command[index].isWhitespace()) index++
            if (index >= command.length) break
            val start = index
            val value = StringBuilder()
            var quote: Char? = null
            while (index < command.length) {
                val char = command[index]
                if (quote == null && char.isWhitespace()) break
                when {
                    quote == null && (char == '\'' || char == '"') -> quote = char
                    quote == char -> quote = null
                    char == '\\' && quote != '\'' && index + 1 < command.length -> value.append(command[++index])
                    else -> value.append(char)
                }
                index++
            }
            result += Token(value.toString(), start, index)
        }
        return result
    }

    private val BRACED = Regex("""\{([^{}]+)}""")

    private fun List<XcodeDestination>.sortedDestinations(): List<XcodeDestination> =
        distinctBy { it.commandValue }
            .sortedWith(compareBy<XcodeDestination> { !it.isSimulator }.thenBy { it.name }.thenBy { it.os.orEmpty() })
}

internal fun interface XcodeDestinationReader {
    fun read(projectRoot: File, context: XcodeBuildContext): Result<String>
}

internal fun interface LocalXcodeDeviceReader {
    fun read(): Result<List<XcodeDestination>>
}

internal class XcodeDestinationCatalog(
    private val localReader: LocalXcodeDeviceReader = LocalXcodeDeviceReader(::readLocalDevices),
    private val fallbackReader: XcodeDestinationReader = XcodeDestinationReader(::readDestinations),
) {
    fun load(projectRoot: File, config: GoldenDiffConfig): Result<List<XcodeDestination>> =
        XcodeCommandDestination.commonContext(config).mapCatching {
            localReader.read().getOrThrow().also { devices ->
                check(devices.isNotEmpty()) { "No local iOS devices were reported." }
            }
        }

    fun loadAuthoritative(projectRoot: File, config: GoldenDiffConfig): Result<List<XcodeDestination>> =
        XcodeCommandDestination.commonContext(config).mapCatching { context ->
            XcodeCommandDestination.parseShowDestinations(
                fallbackReader.read(projectRoot, context).getOrThrow(),
            )
        }

    private companion object {
        fun readLocalDevices(): Result<List<XcodeDestination>> = runCatching {
            val simulators = runJsonCommand(
                listOf("xcrun", "simctl", "list", "devices", "available", "--json"),
                XcodeCommandDestination::parseSimctlDevices,
            ).getOrDefault(emptyList())
            val physical = runDevicectl().getOrDefault(emptyList())
            XcodeCommandDestination.combineDestinations(simulators, physical).also {
                check(it.isNotEmpty()) { "No local iOS devices were reported." }
            }
        }

        fun readDestinations(projectRoot: File, context: XcodeBuildContext): Result<String> = runCatching {
            val args = buildList {
                add("xcodebuild")
                context.workspace?.let { addAll(listOf("-workspace", it)) }
                context.project?.let { addAll(listOf("-project", it)) }
                addAll(
                    listOf(
                        "-scheme", context.scheme,
                        "-skipPackageUpdates",
                        "-disableAutomaticPackageResolution",
                        "-destination-timeout", "3",
                        "-showdestinations",
                    ),
                )
            }
            runTextCommand(args, projectRoot, XCODEBUILD_TIMEOUT_SECONDS).getOrThrow()
        }

        private fun runDevicectl(): Result<List<XcodeDestination>> = runCatching {
            val output = Files.createTempFile("golden-diff-devices-", ".json").toFile()
            try {
                val process = ProcessBuilder(
                    "xcrun", "devicectl", "list", "devices", "--json-output", output.absolutePath,
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                check(process.waitFor(DEVICE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "devicectl timed out."
                }
                check(process.exitValue() == 0) { "devicectl failed." }
                XcodeCommandDestination.parseDevicectlDevices(output.readText())
            } finally {
                output.delete()
            }
        }

        private fun runJsonCommand(
            command: List<String>,
            parse: (String) -> List<XcodeDestination>,
        ): Result<List<XcodeDestination>> = runCatching {
            val output = Files.createTempFile("golden-diff-simulators-", ".json").toFile()
            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .start()
                check(process.waitFor(DEVICE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "Device catalog command timed out."
                }
                check(process.exitValue() == 0) { "Device catalog command failed." }
                parse(output.readText())
            } finally {
                output.delete()
            }
        }

        private const val DEVICE_COMMAND_TIMEOUT_SECONDS = 8L
        private const val XCODEBUILD_TIMEOUT_SECONDS = 45L

        private fun runTextCommand(command: List<String>, directory: File, timeoutSeconds: Long): Result<String> =
            runCatching {
                val output = Files.createTempFile("golden-diff-xcodebuild-", ".log").toFile()
                try {
                    val process = ProcessBuilder(command)
                        .directory(directory)
                        .redirectErrorStream(true)
                        .redirectOutput(output)
                        .start()
                    check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        "xcodebuild -showdestinations timed out."
                    }
                    val text = output.readText()
                    check(process.exitValue() == 0) {
                        text.trim().ifEmpty { "xcodebuild -showdestinations failed." }
                    }
                    text
                } finally {
                    output.delete()
                }
            }
    }
}
