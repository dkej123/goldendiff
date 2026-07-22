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

    fun extract(fileName: String, text: String): Screen {
        val classNames = CLASS_DECL.captures(text)
        val functionNames = FUNCTION_DECLS.flatMap { it.captures(text) }.distinct()
        return Screen(
            functionNames = functionNames,
            classNames = classNames,
            fileName = fileName,
            caretName = null,
        )
    }

    private fun Regex.captures(text: String): List<String> =
        findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.takeIf { name -> name.isNotBlank() } }.distinct().toList()

    /** JS/TS identifier (also valid for Java/Swift/Kotlin names). `$` is intentionally omitted. */
    private const val ID = "[A-Za-z_][A-Za-z0-9_]*"

    /** `class Foo`, `struct Foo`, `interface Foo`, `enum Foo`. */
    private val CLASS_DECL = Regex("""\b(?:class|struct|interface|enum)\s+($ID)""")

    private val FUNCTION_DECLS = listOf(
        // `function Foo`, `func foo` (Swift), `fun foo`.
        Regex("""\b(?:function|func|fun)\s+($ID)"""),
        // Assigned function / arrow / React component / styled-component:
        //   const Foo = () => …, let bar = function …, const Baz = styled.div`…`, memo/forwardRef(…).
        Regex(
            """\b(?:const|let|var)\s+($ID)\s*(?::[^=\n]+?)?=\s*(?:async\s+)?""" +
                """(?:function\b|\([^)\n]*\)\s*(?::[^=\n]+?)?=>|$ID\s*=>|styled[.(]|(?:React\.)?(?:memo|forwardRef)\()""",
        ),
        // Single-token test / spec / story titles: it('primary'), test("Disabled"), describe(`Card`).
        Regex("""\b(?:describe|test|it|story)\s*\(\s*['"`]([^'"`\n]+)['"`]"""),
    )
}
