package com.github.dkwasniak.goldendiff.command

enum class CompletionCommandKind(val label: String) {
    GENERATE("Generate"),
    VERIFY("Verify"),
}

sealed interface CompletionCommandScope {
    data class Project(val projectName: String) : CompletionCommandScope
    data class File(val fileName: String) : CompletionCommandScope
}

enum class CompletionResult {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class CompletionNotification(
    val title: String,
    val body: String,
)

/** Pure shared policy; hosts only provide focus state and deliver the resulting native notification. */
object CompletionNotificationPolicy {
    fun decide(
        enabled: Boolean,
        hostForeground: Boolean,
        kind: CompletionCommandKind,
        scope: CompletionCommandScope,
        result: CompletionResult,
    ): CompletionNotification? {
        if (!enabled || hostForeground || result == CompletionResult.CANCELLED) return null
        val resultText = if (result == CompletionResult.SUCCESS) "finished" else "failed"
        val target = when (scope) {
            is CompletionCommandScope.Project -> scope.projectName
            is CompletionCommandScope.File -> scope.fileName
        }
        val scopeText = when (scope) {
            is CompletionCommandScope.Project -> "whole project"
            is CompletionCommandScope.File -> "current file"
        }
        return CompletionNotification(
            title = "${kind.label} $resultText",
            body = "$target · $scopeText",
        )
    }
}
