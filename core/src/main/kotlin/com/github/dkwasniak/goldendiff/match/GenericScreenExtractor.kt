package com.github.dkwasniak.goldendiff.match

/**
 * Builds a [Screen] for files that are not Kotlin (TypeScript, JavaScript, Swift, Java,
 * plain text…). The plugin is tool-agnostic, so matching goldens to the open file must not depend on
 * Kotlin PSI. This extractor is deliberately language-neutral and text-based: it scans the document
 * for the handful of declaration shapes that end up in screenshot golden file names across web and
 * native ecosystems (component/function/class names and single-token test titles), plus the file base
 * name. It never touches any language plugin's PSI, so it adds no plugin dependencies and works even
 * when the file's language is unknown to the IDE.
 *
 * The result is intentionally caret-independent (there is no [Screen.caretName] here);
 * all downstream filtering and noise control still happens in [GoldenFinder].
 */
object GenericScreenExtractor {

    /**
     * @param annotationNameRegex when non-blank, named functions are filtered to screenshot
     * candidates the same way the plugin's Kotlin/PSI path does — kept only if preceded by an
     * annotation whose name matches the regex (e.g. `@Preview`).
     * When null/blank every named function is kept (the original, unfiltered behaviour).
     */
    fun extract(fileName: String, text: String, annotationNameRegex: String? = null): Screen {
        val classNames = CLASS_DECL.captures(text)
        val namedFunctions = if (annotationNameRegex.isNullOrBlank()) {
            NAMED_FUNCTION_DECL.captures(text)
        } else {
            annotatedOrTestFunctions(text, annotationNameRegex)
        }
        // Assigned components and test/story titles have no declaration annotation we can associate
        // reliably. They remain useful in the unfiltered extractor, but annotated-method mode must
        // not smuggle them in as if they were annotated functions.
        val otherCandidates = if (annotationNameRegex.isNullOrBlank()) {
            OTHER_DECLS.flatMap { it.captures(text) }
        } else {
            emptyList()
        }
        return Screen(
            functionNames = (namedFunctions + otherCandidates).distinct(),
            classNames = classNames,
            fileName = fileName,
            caretName = null,
        )
    }

    // Keeps only functions preceded by an annotation matching the regex. The annotated-method mode
    // is deliberately literal: naming a function `test…` does not make it annotated.
    private fun annotatedOrTestFunctions(text: String, annotationNameRegex: String): List<String> =
        NAMED_FUNCTION_DECL.findAll(text)
            .mapNotNull { match ->
                val name = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val annotated = precedingAnnotations(text, match.range.first)
                    .any { AnnotationNameMatcher.matches(it, annotationNameRegex) }
                name.takeIf { annotated }
            }
            .distinct()
            .toList()

    // Annotation names sitting directly above (or inline before) a declaration. Scans backwards over
    // the run of annotation / modifier / comment / blank lines and stops at the first line of real
    // code, so annotations belonging to an earlier declaration don't leak onto this one.
    private fun precedingAnnotations(text: String, declStart: Int): List<String> {
        val lines = text.substring(0, declStart).split('\n')
        val annotations = mutableListOf<String>()
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            when {
                line.isEmpty() -> continue
                line.startsWith("@") -> {
                    // An annotation sharing its line with another declaration belongs to that one.
                    if (DECL_KEYWORD.containsMatchIn(line)) break
                    ANNOTATION_NAME.findAll(line).forEach { annotations.add(it.groupValues[1]) }
                }
                line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") -> continue
                line.substringBefore(' ') in MODIFIERS -> continue
                else -> break
            }
        }
        return annotations
    }

    private fun Regex.captures(text: String): List<String> =
        findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.takeIf { name -> name.isNotBlank() } }.distinct().toList()

    /** JS/TS identifier (also valid for Java/Swift/Kotlin names). `$` is intentionally omitted. */
    private const val ID = "[A-Za-z_][A-Za-z0-9_]*"

    /** `class Foo`, `struct Foo`, `interface Foo`, `enum Foo`. */
    private val CLASS_DECL = Regex("""\b(?:class|struct|interface|enum)\s+($ID)""")

    /** `function Foo`, `func foo` (Swift), `fun foo`. */
    private val NAMED_FUNCTION_DECL = Regex("""\b(?:function|func|fun)\s+($ID)""")

    private val OTHER_DECLS = listOf(
        // Assigned function / arrow / React component / styled-component:
        //   const Foo = () => …, let bar = function …, const Baz = styled.div`…`, memo/forwardRef(…).
        Regex(
            """\b(?:const|let|var)\s+($ID)\s*(?::[^=\n]+?)?=\s*(?:async\s+)?""" +
                """(?:function\b|\([^)\n]*\)\s*(?::[^=\n]+?)?=>|$ID\s*=>|styled[.(]|(?:React\.)?(?:memo|forwardRef)\()""",
        ),
        // Single-token test / spec / story titles: it('primary'), test("Disabled"), describe(`Card`).
        Regex("""\b(?:describe|test|it|story)\s*\(\s*['"`]([^'"`\n]+)['"`]"""),
    )

    private val ANNOTATION_NAME = Regex("@($ID)")

    private val DECL_KEYWORD = Regex("""\b(?:fun|func|function|class|struct|interface|enum|val|var|let|const)\b""")

    private val MODIFIERS = setOf(
        "private", "public", "internal", "protected", "open", "override", "suspend", "inline",
        "abstract", "final", "static", "export", "default", "async",
    )
}
