package com.github.dkwasniak.goldendiff.match

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Extracts the names used to match screenshot golden files against the file the user is currently
 * editing. The [Screen.names] set includes class names, preview/test function names, and the file
 * base name. It does NOT depend on the caret position, so it stays stable while the user clicks
 * around the file. [Screen.caretName] is separate and only used to preselect the best-matching golden
 * when the list is first built for a file.
 */
object CurrentScreen {

    data class Screen(
        /** Candidate names to match against golden file names. Stable for a given file. */
        val names: List<String>,
        /** Name of the function under the caret, used only for the initial selection. */
        val caretName: String?,
    )

    fun compute(project: Project): Screen? =
        ReadAction.compute<Screen?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? KtFile
                ?: return@compute null

            val names = LinkedHashSet<String>()

            // All classes declared in the file (the screen / test class).
            PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
                .mapNotNull { it.name }
                .forEach(names::add)

            // Preview / test functions in the file. Plain @Composable helpers and PreviewParameter
            // arguments are intentionally ignored because small helper names create noisy false
            // positives.
            PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
                .filter { isPreviewOrTest(it) }
                .mapNotNull { it.name }
                .forEach(names::add)

            // The file base name (e.g. LoginScreen.kt -> LoginScreen).
            names.add(psiFile.name.substringBeforeLast('.'))

            // Caret function — separate, for initial selection only (not part of the match set).
            val caretName = psiFile.findElementAt(editor.caretModel.offset)?.let { enclosingFunction(it) }?.name

            Screen(names.filter { it.isNotBlank() }, caretName)
        }

    private fun isPreviewOrTest(function: KtNamedFunction): Boolean {
        val annotated = function.annotationEntries.any { entry ->
            val annotationName = entry.shortName?.asString()
                ?: entry.typeReference?.text?.substringAfterLast('.')
                ?: return@any false
            annotationName != "PreviewParameter" &&
                (annotationName.contains("Preview") || annotationName.contains("Previews") || annotationName == "Test")
        }
        return annotated || function.name?.startsWith("test") == true
    }

    private fun enclosingFunction(element: com.intellij.psi.PsiElement): KtNamedFunction? {
        var function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        while (function != null && function.isLocal) {
            function = PsiTreeUtil.getParentOfType(function, KtNamedFunction::class.java)
        }
        return function
    }
}
