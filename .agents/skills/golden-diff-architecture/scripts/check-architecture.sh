#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

failures=0

fail() {
    echo "ARCHITECTURE ERROR: $1" >&2
    failures=$((failures + 1))
}

require_pattern() {
    local pattern="$1"
    local file="$2"
    local message="$3"
    if ! rg --quiet "$pattern" "$file"; then
        fail "$message"
    fi
}

reject_pattern() {
    local pattern="$1"
    local path="$2"
    local message="$3"
    if rg --line-number "$pattern" "$path"; then
        fail "$message"
    fi
}

forbidden_core_imports='^import (com\.intellij|androidx\.compose|org\.jetbrains\.(compose|jewel|skia)|javax\.swing)'
forbidden_plugin_imports='^import (androidx\.compose|org\.jetbrains\.(compose|jewel|skia))'
forbidden_host_imports='^import (com\.intellij|androidx\.compose|org\.jetbrains\.(compose|jewel|skia)|javax\.swing)'

reject_pattern "$forbidden_core_imports" core/src/main \
    ":core imports a UI or IntelliJ API"
reject_pattern "$forbidden_host_imports" telemetry/src/main \
    ":telemetry imports a host UI or IntelliJ API"
reject_pattern "$forbidden_plugin_imports" public-plugin/src/main \
    ":public-plugin imports Compose, Jewel, or Skia"
reject_pattern "$forbidden_plugin_imports" internal-plugin/src/main \
    ":internal-plugin imports Compose, Jewel, or Skia"

reject_pattern '^[[:space:]]*(api|implementation|runtimeOnly)\(' core/build.gradle.kts \
    ":core gained a packaged production dependency"
require_pattern 'implementation\(project\(":core"\)\)' public-plugin/build.gradle.kts \
    ":public-plugin must package :core with implementation"
require_pattern 'compileOnly\(project\(":core"\)\)' internal-plugin/build.gradle.kts \
    ":internal-plugin must see :core through compileOnly"
require_pattern 'compileOnly\(project\(":core"\)\)' core-ui/build.gradle.kts \
    ":core-ui must keep its :core dependency compileOnly"

reject_pattern 'project\(":core-ui"\)' public-plugin/build.gradle.kts \
    ":public-plugin must not depend on :core-ui"
reject_pattern 'project\(":core-ui"\)' internal-plugin/build.gradle.kts \
    ":internal-plugin must not depend on :core-ui"
reject_pattern '^[[:space:]]*implementation\(compose\.' core-ui/build.gradle.kts \
    ":core-ui Compose dependencies must remain compileOnly"

if ((failures > 0)); then
    echo "Architecture checks failed: $failures problem(s)." >&2
    exit 1
fi

echo "Architecture checks passed."
