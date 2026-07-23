package com.github.dkwasniak.goldendiff.telemetry

/**
 * The complete, privacy-reviewed product-event contract.
 *
 * Values are deliberately closed enums/buckets. Anything outside this allowlist is rejected before
 * it can reach a backend, which prevents paths, project names and source identifiers from being
 * added accidentally at call sites.
 */
object EventCatalog {
    private val countBuckets = setOf("0", "1", "2_5", "6_20", "21_100", "101_plus")
    private val durationBuckets = setOf("lt_100ms", "100_499ms", "500_1999ms", "2_9s", "10s_plus")
    private val sessionBuckets = setOf("lt_1m", "1_4m", "5_14m", "15_59m", "60m_plus")
    private val timeToValueBuckets = setOf("lt_1m", "1_4m", "5_14m", "15_59m", "1_23h", "1d_plus")
    private val diffBuckets = setOf("none", "zero", "lt_1pct", "1_5pct", "5_25pct", "gt_25pct")
    private val bool = setOf("true", "false")
    private val scope = setOf("current_file", "project_changes")
    private val source = setOf("working_copy", "test_output")
    private val installationAgeBuckets =
        setOf("first_day", "day_1", "days_2_6", "days_7_29", "days_30_89", "days_90_plus")

    private data class Definition(
        val required: Set<String>,
        val optional: Set<String> = emptySet(),
        val values: Map<String, Set<String>>,
    )

    private val definitions = mapOf(
        "product.installation_first_seen" to definition(),
        "product.session_started" to definition(
            "entry_point" to setOf("app_launch", "tool_window"),
            "project_restored" to bool,
            "analytics_enabled" to bool,
            "diagnostics_enabled" to bool,
            "installation_age_bucket" to installationAgeBuckets,
        ),
        "product.session_ended" to definition(
            "duration_bucket" to sessionBuckets,
            "scan_count_bucket" to countBuckets,
            "comparison_count_bucket" to countBuckets,
        ),
        "product.project_opened" to definition(
            "trigger" to setOf("manual", "restored"),
            "result" to setOf("success", "no_git", "not_repository", "io_error"),
            "configuration_present" to bool,
        ),
        "product.activation_completed" to definition(
            "time_to_value_bucket" to timeToValueBuckets,
            "sessions_to_activation" to setOf("1", "2", "3_5", "6_plus"),
            "scope" to scope,
            "source" to source,
        ),
        "product.configuration_saved" to definition(
            "match_mode" to setOf("annotated_method", "file_class_regex"),
            "golden_dir_count_bucket" to countBuckets,
            "generated_dir_count_bucket" to countBuckets,
            "generated_configured" to bool,
            "trim_enabled" to bool,
            "changed_golden_dirs" to bool,
            "changed_generated_dirs" to bool,
            "changed_matching" to bool,
            "changed_filtering" to bool,
            "changed_display" to bool,
        ),
        "product.browse_scope_selected" to definition("from" to scope, "to" to scope),
        "product.comparison_source_selected" to definition("from" to source, "to" to source),
        "product.source_file_selected" to definition(
            "trigger" to setOf("project_tree", "quick_open", "tab", "direct_png", "ide_editor"),
            "file_family" to setOf("kotlin", "java", "js_ts", "swift", "png", "other"),
            "already_open" to bool,
        ),
        "product.feature_used" to definition(
            "feature" to setOf(
                "quick_open", "detached_comparison", "copy_path", "reveal_in_file_manager",
                "left_pane_toggle", "tab_open", "tab_close",
            ),
        ),
        "product.scan_completed" to definition(
            "trigger" to setOf("automatic", "manual_refresh", "config_change", "scope_change", "source_change"),
            "scope" to scope,
            "source" to source,
            "result" to setOf("success_nonempty", "success_empty", "blocked", "failure"),
            "blocker" to setOf("none", "no_git", "not_repository", "no_configuration"),
            "duration_bucket" to durationBuckets,
            "item_count_bucket" to countBuckets,
            "modified_count_bucket" to countBuckets,
            "new_count_bucket" to countBuckets,
            "cache_hit" to bool,
        ),
        "product.operation_failed" to Definition(
            required = setOf("operation", "error_category", "retryable"),
            optional = setOf("scope", "source"),
            values = mapOf(
                "operation" to setOf(
                    "project_open", "scan", "match", "git_head", "generated_lookup", "image_decode",
                    "pixel_diff", "comparison_load", "config_save", "delete_file",
                ),
                "error_category" to setOf("io", "git", "decode", "invalid_config", "internal", "unknown"),
                "retryable" to bool,
                "scope" to scope,
                "source" to source,
            ),
        ),
        "product.comparison_viewed" to definition(
            "source" to source,
            "result" to setOf("identical", "modified", "new", "missing_counterpart", "decode_failed"),
            "load_duration_bucket" to durationBuckets,
            "diff_ratio_bucket" to diffBuckets,
            "dimensions" to setOf("same", "different", "one_missing"),
            "cache_hit" to bool,
            "selection_trigger" to setOf("grid", "keyboard", "automatic"),
        ),
        "product.compare_mode_selected" to definition(
            "mode" to setOf("side_by_side", "swipe", "onion", "diff"),
            "location" to setOf("main_pane", "detached_window"),
        ),
        "product.zoom_selected" to definition(
            "zoom" to setOf("fit", "lt_100", "equal_100", "gt_100"),
            "action" to setOf("fit", "zoom_in", "zoom_out", "hundred"),
            "location" to setOf("main_pane", "detached_window"),
        ),
        "product.golden_deleted" to definition(
            "result" to setOf("success", "failure"),
            "source" to setOf("working_copy"),
        ),
    )

    val names: Set<String> get() = definitions.keys

    fun validate(name: String, properties: Map<String, String>) {
        val definition = requireNotNull(definitions[name]) { "Unknown telemetry event: $name" }
        require(properties.keys.containsAll(definition.required)) {
            "Missing properties for $name: ${definition.required - properties.keys}"
        }
        val allowed = definition.required + definition.optional
        require(properties.keys.all { it in allowed }) {
            "Unexpected properties for $name: ${properties.keys - allowed}"
        }
        properties.forEach { (key, value) ->
            require(value in requireNotNull(definition.values[key])) {
                "Disallowed value for $name.$key"
            }
        }
    }

    private fun definition(vararg fields: Pair<String, Set<String>>): Definition =
        Definition(fields.map(Pair<String, Set<String>>::first).toSet(), values = fields.toMap())
}

object TelemetryBuckets {
    fun count(value: Int): String = when {
        value <= 0 -> "0"
        value == 1 -> "1"
        value <= 5 -> "2_5"
        value <= 20 -> "6_20"
        value <= 100 -> "21_100"
        else -> "101_plus"
    }

    fun duration(milliseconds: Long): String = when {
        milliseconds < 100 -> "lt_100ms"
        milliseconds < 500 -> "100_499ms"
        milliseconds < 2_000 -> "500_1999ms"
        milliseconds < 10_000 -> "2_9s"
        else -> "10s_plus"
    }

    fun session(milliseconds: Long): String = when {
        milliseconds < 60_000 -> "lt_1m"
        milliseconds < 5 * 60_000 -> "1_4m"
        milliseconds < 15 * 60_000 -> "5_14m"
        milliseconds < 60 * 60_000 -> "15_59m"
        else -> "60m_plus"
    }

    fun timeToValue(milliseconds: Long): String = when {
        milliseconds < 60_000 -> "lt_1m"
        milliseconds < 5 * 60_000 -> "1_4m"
        milliseconds < 15 * 60_000 -> "5_14m"
        milliseconds < 60 * 60_000 -> "15_59m"
        milliseconds < 24 * 60 * 60_000 -> "1_23h"
        else -> "1d_plus"
    }

    fun diffRatio(ratio: Double?, hasImages: Boolean = true): String = when {
        !hasImages || ratio == null -> "none"
        ratio <= 0.0 -> "zero"
        ratio < 0.01 -> "lt_1pct"
        ratio <= 0.05 -> "1_5pct"
        ratio <= 0.25 -> "5_25pct"
        else -> "gt_25pct"
    }
}
