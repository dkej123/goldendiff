package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.compare.GeneratedImageSource
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import java.io.File
import java.io.IOException

/**
 * Promotes a freshly verified screenshot to its committed golden — the missing step for frameworks
 * whose verify run writes an `_actual` image next to (not over) the golden, such as Roborazzi.
 * (Generation frameworks overwrite the golden directly, so Accept is only meaningful for verify
 * output; hosts gate it on the "Test output" source.)
 *
 * "Accepting" **moves** the generated counterpart onto the golden: its bytes are written to the
 * golden's path (taking the golden's name) and the generated file is then removed, so the leftover
 * `_actual` no longer lingers in the working tree. The counterpart is resolved with the same
 * [GeneratedImageSource.findForGolden] the comparison view already uses, so Accept always promotes
 * the exact image the tool was showing as "Test output".
 *
 * File I/O — never call on a UI thread.
 */
object GoldenAccept {

    data class BulkResult(
        val accepted: List<File>,
        val failed: List<BulkFailure>,
    )

    data class BulkFailure(
        val golden: File,
        val failure: Failure,
    )

    sealed interface Failure {
        /** No generated file could be resolved for the golden (nothing to accept). */
        data object NoGeneratedCounterpart : Failure

        /** The generated file was found but could not be read or written. */
        data class Io(val cause: Throwable) : Failure
    }

    /**
     * Moves the generated counterpart of [golden] onto [golden] (writes its bytes to the golden's
     * path, then deletes the generated file). Returns the generated source file that was accepted on
     * success, or a typed [Failure]. [projectRoot] resolves the configured relative golden/generated
     * directories.
     */
    fun accept(golden: File, config: GoldenDiffConfig, projectRoot: File?): Result<File> {
        val normalized = config.normalizedForMatching()
        val generated = GeneratedImageSource.findForGolden(
            golden = golden,
            goldenRoots = config.resolvedGoldenPaths(projectRoot),
            generatedRoots = config.resolvedGeneratedPaths(projectRoot),
            generatedFileRegex = normalized.generatedFileRegex,
            excludedSuffixes = config.excludedSuffixes,
        ) ?: return Result.failure(AcceptException(Failure.NoGeneratedCounterpart))

        return runCatching {
            val bytes = generated.readBytes()
            golden.parentFile?.mkdirs()
            golden.writeBytes(bytes)
            // Move, not copy: remove the leftover generated file so it does not linger as a spurious
            // change. Only if it is a different file (a generated regex could resolve to the golden
            // itself for tools that write in place, which must not be deleted).
            if (generated.canonicalPath != golden.canonicalPath && !generated.delete()) {
                throw IOException("Could not remove accepted generated file: ${generated.path}")
            }
            generated
        }.recoverCatching { throw AcceptException(Failure.Io(it)) }
    }

    /** Whether a generated counterpart exists for [golden], so hosts can enable/disable Accept. */
    fun canAccept(golden: File, config: GoldenDiffConfig, projectRoot: File?): Boolean {
        val normalized = config.normalizedForMatching()
        return GeneratedImageSource.findForGolden(
            golden = golden,
            goldenRoots = config.resolvedGoldenPaths(projectRoot),
            generatedRoots = config.resolvedGeneratedPaths(projectRoot),
            generatedFileRegex = normalized.generatedFileRegex,
            excludedSuffixes = config.excludedSuffixes,
        ) != null
    }

    /**
     * Accepts every explicit golden and reports all outcomes. A failure never aborts the remaining
     * files, which lets hosts offer a retry for precisely the unresolved subset.
     */
    fun acceptAll(
        goldens: Collection<File>,
        config: GoldenDiffConfig,
        projectRoot: File?,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkResult {
        val distinct = goldens.distinctBy { it.normalize().path }
        val accepted = mutableListOf<File>()
        val failed = mutableListOf<BulkFailure>()
        distinct.forEachIndexed { index, golden ->
            accept(golden, config, projectRoot)
                .onSuccess { accepted += golden }
                .onFailure { error ->
                    failed += BulkFailure(
                        golden,
                        (error as? AcceptException)?.failure ?: Failure.Io(error),
                    )
                }
            onProgress(index + 1, distinct.size)
        }
        return BulkResult(accepted, failed)
    }

    /** Carries a typed [Failure] out of [accept] while still using [Result]. */
    class AcceptException(val failure: Failure) : Exception()
}
