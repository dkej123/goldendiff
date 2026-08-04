package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.compare.GeneratedImageSource
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartIndex
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Promotes a freshly verified screenshot to its committed golden — the missing step for frameworks
 * whose verify run writes an `_actual` image next to (not over) the golden, such as Roborazzi.
 * (Generation frameworks overwrite the golden directly, so Accept is only meaningful for verify
 * output; hosts gate it on the "Test output" source.)
 *
 * "Accepting" **moves** the generated counterpart onto the golden: its bytes are written to the
 * golden's path (taking the golden's name) and the generated file is then removed, so the leftover
 * `_actual` no longer lingers in the working tree. The counterpart is resolved with the same
 * the same generated-counterpart rules as the comparison view, so Accept promotes the image shown
 * as "Test output".
 *
 * File I/O — never call on a UI thread.
 */
object GoldenAccept {

    /**
     * Immutable identity captured when a generated counterpart is resolved for comparison.
     *
     * The digest closes the size/mtime blind spot (for example, an editor replacing bytes while
     * preserving timestamps). [acceptResolved] validates all fields before writing the golden.
     */
    data class ResolvedSourceIdentity(
        val canonicalPath: String,
        val size: Long,
        val modifiedAtMillis: Long,
        val sha256: String,
    )

    sealed interface FrozenSource {
        data class Exact(
            val generated: File,
            val identity: ResolvedSourceIdentity,
        ) : FrozenSource

        data object Missing : FrozenSource

        data class Ambiguous(val candidates: List<File>) : FrozenSource
    }

    /** A golden and the generated-source resolution displayed to the user. */
    data class FrozenCandidate(
        val golden: File,
        val source: FrozenSource,
    )

    sealed interface ResolvedOutcome {
        data class Success(
            val golden: File,
            val generated: File,
        ) : ResolvedOutcome

        data class NothingToAccept(val golden: File) : ResolvedOutcome

        data class StaleSource(
            val golden: File,
            val generated: File,
        ) : ResolvedOutcome

        data class Io(
            val golden: File,
            val generated: File?,
            val cause: Throwable,
        ) : ResolvedOutcome

        data class Ambiguous(
            val golden: File,
            val candidates: List<File>,
        ) : ResolvedOutcome
    }

    data class ResolvedBulkItem(
        val candidate: FrozenCandidate,
        val outcome: ResolvedOutcome,
    )

    data class ResolvedBulkResult(val items: List<ResolvedBulkItem>) {
        val accepted: List<FrozenCandidate>
            get() = items.mapNotNull { item ->
                item.candidate.takeIf { item.outcome is ResolvedOutcome.Success }
            }

        /**
         * Frozen candidates that remain unresolved. Nothing-to-accept is intentionally not retried;
         * stale, ambiguous, and I/O failures require refresh/re-resolution or another attempt.
         */
        val retryCandidates: List<FrozenCandidate>
            get() = items.mapNotNull { item ->
                item.candidate.takeIf {
                    item.outcome is ResolvedOutcome.StaleSource ||
                        item.outcome is ResolvedOutcome.Ambiguous ||
                        item.outcome is ResolvedOutcome.Io
                }
            }
    }

    data class BulkResult(
        val accepted: List<File>,
        val failed: List<BulkFailure>,
        /** The generated counterpart consumed (moved/deleted) for each accepted golden, in the same
         * order as [accepted] — lets hosts suppress their own watcher events for both files. */
        val consumedGenerated: List<File> = emptyList(),
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

    /** Captures the exact source identity to carry from comparison state into Accept. */
    fun resolvedSourceIdentity(generated: File): Result<ResolvedSourceIdentity> =
        runCatching { generated.captureIdentity() }

    /** Convenience factory for freezing an exact resolved source. */
    fun freeze(golden: File, generated: File): Result<FrozenCandidate> =
        resolvedSourceIdentity(generated).map { identity ->
            FrozenCandidate(golden, FrozenSource.Exact(generated, identity))
        }

    /**
     * Accepts the exact generated file previously resolved for the comparison.
     *
     * No counterpart lookup is performed here. A moved, removed, or changed source is reported as
     * [ResolvedOutcome.StaleSource] before the golden is written.
     */
    fun acceptResolved(
        golden: File,
        generated: File,
        identity: ResolvedSourceIdentity,
    ): ResolvedOutcome {
        val currentBytes = try {
            generated.readBytesWithIdentity(identity)
        } catch (stale: StaleResolvedSourceException) {
            return ResolvedOutcome.StaleSource(golden, generated)
        } catch (error: Throwable) {
            return if (!generated.exists()) {
                ResolvedOutcome.StaleSource(golden, generated)
            } else {
                ResolvedOutcome.Io(golden, generated, error)
            }
        }

        val sameFile = runCatching { generated.canonicalPath == golden.canonicalPath }
            .getOrElse { return ResolvedOutcome.Io(golden, generated, it) }
        if (sameFile) return ResolvedOutcome.NothingToAccept(golden)

        try {
            golden.parentFile?.mkdirs()
            golden.writeBytes(currentBytes)
        } catch (error: Throwable) {
            // The resolved source remains untouched and can be retried.
            return ResolvedOutcome.Io(golden, generated, error)
        }

        return try {
            if (!generated.delete()) {
                ResolvedOutcome.Io(
                    golden,
                    generated,
                    IOException("Could not remove accepted generated file: ${generated.path}"),
                )
            } else {
                ResolvedOutcome.Success(golden, generated)
            }
        } catch (error: Throwable) {
            ResolvedOutcome.Io(golden, generated, error)
        }
    }

    fun acceptFrozen(candidate: FrozenCandidate): ResolvedOutcome = when (val source = candidate.source) {
        is FrozenSource.Exact -> acceptResolved(candidate.golden, source.generated, source.identity)
        FrozenSource.Missing -> ResolvedOutcome.NothingToAccept(candidate.golden)
        is FrozenSource.Ambiguous -> ResolvedOutcome.Ambiguous(
            candidate.golden,
            source.candidates.distinctBy { it.normalize().path },
        )
    }

    /**
     * Accepts every frozen candidate without resolving any source again and retains every per-item
     * outcome. One failure never aborts later candidates.
     */
    fun acceptAllResolved(
        candidates: Collection<FrozenCandidate>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ResolvedBulkResult {
        val items = candidates.mapIndexed { index, candidate ->
            ResolvedBulkItem(candidate, acceptFrozen(candidate)).also {
                onProgress(index + 1, candidates.size)
            }
        }
        return ResolvedBulkResult(items)
    }

    /**
     * Moves the generated counterpart of [golden] onto [golden] (writes its bytes to the golden's
     * path, then deletes the generated file). Returns the generated source file that was accepted on
     * success, or a typed [Failure]. [projectRoot] resolves the configured relative golden/generated
     * directories.
     */
    fun accept(
        golden: File,
        config: GoldenDiffConfig,
        projectRoot: File?,
        explicitGeneratedCandidates: List<GeneratedCounterpartIndex.ExplicitCandidate> = emptyList(),
    ): Result<File> {
        val normalized = config.normalizedForMatching()
        val goldenRoots = config.resolvedGoldenPaths(projectRoot)
        val generatedRoots = config.resolvedGeneratedPaths(projectRoot)
        val generated = if (explicitGeneratedCandidates.isEmpty()) {
            GeneratedImageSource.findForGolden(
                golden = golden,
                goldenRoots = goldenRoots,
                generatedRoots = generatedRoots,
                generatedFileRegex = normalized.generatedFileRegex,
                excludedSuffixes = config.excludedSuffixes,
            )
        } else {
            when (
                val resolution = GeneratedCounterpartIndex.build(
                    generatedRoots = generatedRoots,
                    generatedFileRegex = normalized.generatedFileRegex,
                    excludedSuffixes = config.excludedSuffixes,
                    explicitCandidates = explicitGeneratedCandidates,
                ).resolve(golden, goldenRoots)
            ) {
                is GeneratedCounterpartResolution.Exact -> resolution.file
                GeneratedCounterpartResolution.Missing,
                is GeneratedCounterpartResolution.Ambiguous,
                -> null
            }
        } ?: return Result.failure(AcceptException(Failure.NoGeneratedCounterpart))

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
        explicitGeneratedCandidates: List<GeneratedCounterpartIndex.ExplicitCandidate> = emptyList(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkResult {
        val distinct = goldens.distinctBy { it.normalize().path }
        val accepted = mutableListOf<File>()
        val consumedGenerated = mutableListOf<File>()
        val failed = mutableListOf<BulkFailure>()
        distinct.forEachIndexed { index, golden ->
            accept(golden, config, projectRoot, explicitGeneratedCandidates)
                .onSuccess { generated ->
                    accepted += golden
                    consumedGenerated += generated
                }
                .onFailure { error ->
                    failed += BulkFailure(
                        golden,
                        (error as? AcceptException)?.failure ?: Failure.Io(error),
                    )
                }
            onProgress(index + 1, distinct.size)
        }
        return BulkResult(accepted, failed, consumedGenerated)
    }

    /** Carries a typed [Failure] out of [accept] while still using [Result]. */
    class AcceptException(val failure: Failure) : Exception()

    private fun File.captureIdentity(bytes: ByteArray = readBytes()): ResolvedSourceIdentity {
        if (!isFile) throw IOException("Resolved generated source is not a file: $path")
        return ResolvedSourceIdentity(
            canonicalPath = canonicalPath,
            size = bytes.size.toLong(),
            modifiedAtMillis = lastModified(),
            sha256 = bytes.sha256(),
        )
    }

    private fun File.readBytesWithIdentity(expected: ResolvedSourceIdentity): ByteArray {
        if (!isFile) throw StaleResolvedSourceException()
        val beforeSize = length()
        val beforeModified = lastModified()
        val currentPath = runCatching { canonicalPath }.getOrElse { throw StaleResolvedSourceException() }
        if (
            currentPath != expected.canonicalPath ||
            beforeSize != expected.size ||
            beforeModified != expected.modifiedAtMillis
        ) {
            throw StaleResolvedSourceException()
        }
        val bytes = readBytes()
        if (
            length() != beforeSize ||
            lastModified() != beforeModified ||
            bytes.size.toLong() != expected.size ||
            bytes.sha256() != expected.sha256
        ) {
            throw StaleResolvedSourceException()
        }
        return bytes
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private class StaleResolvedSourceException : Exception()
}
