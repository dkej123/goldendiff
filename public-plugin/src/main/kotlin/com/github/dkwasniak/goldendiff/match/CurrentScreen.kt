package com.github.dkwasniak.goldendiff.match

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.Callable

/**
 * Builds a [Screen] from the file the user is currently editing.
 *
 * This is the IDE-only half of candidate extraction: it needs an open editor, a caret, and Kotlin PSI,
 * none of which exist outside the IDE. The [Screen] type itself and the language-neutral
 * [GenericScreenExtractor] fallback live in the platform-independent core module, so the standalone
 * app can produce the same candidates from a file it was handed and feed the same [GoldenFinder].
 */
object CurrentScreen {

    /**
     * Computes the current screen from a background thread. The non-blocking read action waits for
     * pending writes instead of holding up the EDT, and works on every supported 241+ platform.
     */
    fun compute(
        project: Project,
        annotationNameRegex: String = MatchingDefaults.ANNOTATION_NAME_REGEX,
    ): Screen? =
        ReadAction.nonBlocking(
            Callable<Screen?> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@Callable null
                val document = editor.document

                // Non-Kotlin files (TS/JS/Swift/Java/…) get a language-neutral, text-based extraction so the
                // plugin stays tool-agnostic instead of only matching Kotlin screens.
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) as? KtFile
                    ?: run {
                        val fileName = FileDocumentManager.getInstance().getFile(document)
                            ?.name?.substringBeforeLast('.')
                            ?: return@Callable null
                        return@Callable GenericScreenExtractor.extract(fileName, document.text, annotationNameRegex)
                    }

                val annotationPattern = runCatching { Regex(annotationNameRegex) }
                    .getOrElse { Regex(MatchingDefaults.ANNOTATION_NAME_REGEX) }

                // All classes declared in the file (the screen / test class).
                val classNames = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
                    .mapNotNull { it.name }
                    .filter { it.isNotBlank() }
                    .distinct()

                // Annotated screenshot functions in the file. Plain @Composable helpers and PreviewParameter
                // arguments are intentionally ignored because small helper names create noisy false
                // positives.
                val functionNames = PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
                    .filter { isScreenshotCandidate(it, annotationPattern) }
                    .mapNotNull { it.name }
                    .filter { it.isNotBlank() }
                    .distinct()

                // The file base name (e.g. LoginScreen.kt -> LoginScreen).
                val fileName = psiFile.name.substringBeforeLast('.')

                // Caret function — separate, for initial selection only (not part of the match set).
                val caretName = psiFile.findElementAt(editor.caretModel.offset)?.let { enclosingFunction(it) }?.name

                Screen(functionNames, classNames, fileName, caretName)
            },
        )
            .withDocumentsCommitted(project)
            .expireWhen { project.isDisposed }
            .executeSynchronously()

    private fun isScreenshotCandidate(function: KtNamedFunction, annotationPattern: Regex): Boolean {
        val annotated = function.annotationEntries.any { entry ->
            val annotationName = entry.shortName?.asString()
                ?: entry.typeReference?.text?.substringAfterLast('.')
                ?: return@any false
            AnnotationNameMatcher.matches(annotationName, annotationPattern.pattern)
        }
        return annotated
    }

    private fun enclosingFunction(element: com.intellij.psi.PsiElement): KtNamedFunction? {
        var function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        while (function != null && function.isLocal) {
            function = PsiTreeUtil.getParentOfType(function, KtNamedFunction::class.java)
        }
        return function
    }
}
