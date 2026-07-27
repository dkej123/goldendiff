package com.github.dkwasniak.goldendiff.app

import java.io.File

/**
 * The copy-ready instruction an AI CLI agent runs to configure Golden Diff for a project.
 *
 * Kept out of the Compose layer so the exact wording is easy to read and adjust in one place. It
 * deliberately teaches the agent *what to look for* in the repository — the conventions of the common
 * screenshot-testing tools — rather than assuming it already knows, and finishes with the exact
 * `configure … --signal` command so the open setup window can react.
 */

/** Shown in the prompt box before the user has picked a project directory. */
const val CLI_AGENT_PROMPT_PATH_PLACEHOLDER = "<absolute path to your project>"

/** The only launcher a configuration agent may use. Development/build copies are never exposed. */
const val INSTALLED_CLI_PATH = "/Applications/Golden Diff.app/Contents/MacOS/Golden Diff"

/**
 * Always point the agent at the installed application. In particular, do not derive this from the
 * current process: onboarding may itself be running from `app/build`, an IDE, or a Gradle dev run,
 * and none of those copies is the user-facing CLI authority.
 */
fun resolveCliBinaryPath(): String = INSTALLED_CLI_PATH

fun isInstalledCliAvailable(cliPath: String = resolveCliBinaryPath()): Boolean =
    File(cliPath).let { it.isFile && it.canExecute() }

fun buildCliAgentPrompt(projectPath: String): String {
    val cliPath = INSTALLED_CLI_PATH
    return """
    Configure Golden Diff — a screenshot-diff tool — for the project at $projectPath.
    Golden Diff compares committed "golden" screenshot PNGs against the Git working copy and freshly
    generated test output. Configure it non-interactively with its CLI; do NOT launch the app UI.

    CLI (run this exact installed binary by its full path, quotes included): "$cliPath"
    Use that exact path for EVERY Golden Diff command. Never use a Golden Diff binary from app/build,
    the project checkout, Gradle, another application bundle, or PATH.

    PROJECT ACCESS CONTRACT: inspect the project read-only. You may use read-only shell commands such
    as git rev-parse, git status, rg, find, sed, and file listing to understand its configuration.
    Do not create, edit, move, or delete project files; do not run formatters, generators, snapshot
    record/update commands, or tests that can write project/build output. Do not edit shell profiles
    or environment configuration. The only writes allowed in this task are Golden Diff's own settings
    and completion signal, performed by the installed CLI above.

    Before the generic steps, check for a framework-specific guide: run `"$cliPath" guides`. If the
    project matches one of the listed frameworks (each row says how to detect it), run
    `"$cliPath" guide <name>` and follow it. A guide gives the exact PROJECT-side setup — e.g.
    environment variables the test run must export so freshly generated output lands in a stable
    directory GoldenDiff can read — AND the exact GoldenDiff options for that framework. Both take
    precedence over the generic guidance below. Analyze project-side setup read-only. If the guide
    requires a project, shell, CI, or environment change, report the exact required change to the user
    but do not apply it. Do NOT ask the user to choose between options unless the guide explicitly
    leaves a decision to the user.

    1. Confirm the project. cd into $projectPath and verify it is a Git repository
       (git rev-parse --is-inside-work-tree). Use paths RELATIVE to this root everywhere below.

    2. Identify how this project does screenshot testing. Don't assume any platform, language, build
       system or tool — inspect whatever this project actually uses (its dependency/build/config
       files, test targets and sources, CI setup) to determine the screenshot-testing framework and
       how it is configured. Then find where that framework keeps its COMMITTED golden/reference
       images: prefer an explicitly configured output/reference directory, otherwise its default
       location for this project's layout. If that is inconclusive, search the repo for directories
       under the test sources that hold many committed image files (typically PNG), ignoring build
       output and generated/diff artifacts. Confirm by listing a few. Collect EVERY golden directory —
       a project with several modules/targets usually has one each. When many directories share a
       stable project-relative layout, prefer one precise directory regex over a long list of paths.
       The regex is matched against the complete directory path using "/" separators.

       Treat every matched directory as a MATCHING ROOT, not merely a directory that happens to
       contain PNGs. Golden Diff matches each golden's path RELATIVE to that root. Choose the root so
       the key required by the selected mode remains in the relative path:
         • annotated mode — the annotated producer function name must remain, usually in the filename;
         • file-class mode — the source file or class name must remain in a directory or filename.
       Therefore, if a layout is <container>/<ClassName>/<images>, match <container>, not each
       <ClassName> leaf. Derive this boundary from the repository's actual source-to-golden pairs;
       do not assume framework-specific folder names. Avoid roots broad enough to include unrelated
       generated output. This is the only required setting.

    3. Find the test-output directory (optional). From the same framework's configuration or defaults,
       locate where a verification run WRITES the freshly generated images; those files are usually
       named after the golden they correspond to (often with a suffix like _actual). Skip this if you
       cannot identify it with confidence.

    4. Decide the matching mode — how Golden Diff maps the file being edited to its goldens:
         • annotated (default) — producer functions carry annotations such as @Preview or @Test, and
           goldens are named after those functions. Default annotation-name regex: .*Preview.*|Test
         • file-class — goldens are named after the file or class under test.
       A function merely named test… without a matching annotation is NOT an annotated-method
       candidate. XCTest projects commonly use such unannotated methods; choose file-class when their
       snapshot paths contain the source file or test class name. Inspect a handful of golden
       filenames against the test source to choose instead of assuming annotated.

    5. Apply the configuration:
       "$cliPath" configure \
         --project "$projectPath" \
         [--golden-dir <relative golden dir> ...] \
         [--golden-dir-regex '<full project-relative directory regex>' ...] \
         [--generated-dir <relative test-output dir>] \
         --match-mode <annotated|file-class>
       Use one --golden-dir per literal directory or --golden-dir-regex per repeated layout; they may
       be combined. Leave Filtering, Display and Privacy at their defaults.

    6. Configure the record/verify commands (optional but recommended). Using the same framework you
       identified, determine how this project RECORDS (generates) its golden screenshots and how it
       VERIFIES them, including how to scope a run to a SINGLE test file. Store those as command
       templates so Golden Diff can offer them later:
       "$cliPath" configure \
         --project "$projectPath" \
         [--generate-all-cmd '<command that regenerates all goldens>'] \
         [--generate-file-cmd '<command scoped to one file>'] \
         [--verify-all-cmd '<command that verifies all goldens>'] \
         [--verify-file-cmd '<command scoped to one file>']
       In the per-file variants use {file_name}, {class_name} and {file_path} where the framework needs
       the edited file's name, its declaring class, or its project-relative path. If a framework guide
       matched, use exactly the command examples it gives; otherwise derive them from the project's own
       build/test configuration. Do NOT run these commands yourself — the project stays read-only; you
       only WRITE them into Golden Diff's settings. If you cannot determine a command with confidence,
       leave it unset rather than guessing.

    7. Verify what Golden Diff will actually use. Select representative source/test files whose
       goldens you confirmed manually — at least one for every distinct module, target, or directory
       layout — and pass each one separately:
       "$cliPath" validate \
         --project "$projectPath" \
         --source-file <relative representative source file> \
         [--source-file <representative from another layout> ...]
       Validation expands directory regexes, checks that resolved roots contain PNGs, then runs the
       same source extraction and golden matcher as the app for every supplied file. Every source file
       must report at least one matching golden. If any reports zero, inspect its expected golden path
       relative to the resolved root, correct the root regex or matching mode/patterns, and validate
       again. Do not consider directory-only validation sufficient, and do not consider setup complete
       until all representative source files pass.

    8. After successful validation, notify the open setup window:
       "$cliPath" configure --project "$projectPath" --signal
       This preserves the settings you already applied and only writes the completion signal. Finally
       run "$cliPath" show --project "$projectPath" --json and share that configuration summary with me.
    """.trimIndent()
}
