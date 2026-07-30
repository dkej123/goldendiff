package com.github.dkwasniak.goldendiff.telemetry

enum class TelemetryCommandKind(val wireValue: String) {
    GENERATE("generate"),
    VERIFY("verify"),
}

enum class TelemetryCommandScope(val wireValue: String) {
    PROJECT("project"),
    VIEW("view"),
    CASE("case"),
}

enum class TelemetryCommandResult(val wireValue: String) {
    SUCCESS("success"),
    PARTIAL_FAILURE("partial_failure"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    COULD_NOT_START("could_not_start"),
}

enum class TelemetryAcceptMode(val wireValue: String) {
    SINGLE("single"),
    BULK("bulk"),
}

enum class TelemetryAcceptResult(val wireValue: String) {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILURE("failure"),
    NOTHING_TO_ACCEPT("nothing_to_accept"),
}

enum class TelemetryAcceptFailure(val wireValue: String) {
    NONE("none"),
    MISSING("missing"),
    AMBIGUOUS("ambiguous"),
    STALE("stale"),
    IO("io"),
    UNKNOWN("unknown"),
}

fun TelemetryClient.commandCompleted(
    kind: TelemetryCommandKind,
    scope: TelemetryCommandScope,
    result: TelemetryCommandResult,
    commandCount: Int,
): Boolean =
    event(
        "product.command_completed",
        mapOf(
            "kind" to kind.wireValue,
            "scope" to scope.wireValue,
            "result" to result.wireValue,
            "command_count_bucket" to TelemetryBuckets.count(commandCount),
        ),
    )

fun TelemetryClient.acceptCompleted(
    mode: TelemetryAcceptMode,
    result: TelemetryAcceptResult,
    failure: TelemetryAcceptFailure,
    candidates: Int,
    accepted: Int,
    failed: Int,
    retry: Boolean = false,
): Boolean =
    event(
        "product.accept_completed",
        mapOf(
            "mode" to mode.wireValue,
            "result" to result.wireValue,
            "failure_category" to failure.wireValue,
            "candidate_count_bucket" to TelemetryBuckets.count(candidates),
            "accepted_count_bucket" to TelemetryBuckets.count(accepted),
            "failed_count_bucket" to TelemetryBuckets.count(failed),
            "retry" to retry.toString(),
        ),
    )
