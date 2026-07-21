#!/usr/bin/env bash
# Publish the internal (Figma) plugin by hosting it straight from this public repo: build it, then
# copy the ZIP + updatePlugins.xml into distribution/ (served via raw.githubusercontent.com). Run
# after bumping pluginVersion in gradle.properties, then commit & push the distribution/ changes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

./gradlew :internal-plugin:buildPlugin :internal-plugin:generateUpdatePluginsXml

DIST="internal-plugin/build/distributions"
ZIP="$(ls -t "$DIST"/golden-diff-figma-*.zip | head -n1)"

# Keep only the current ZIP in the hosted folder (the descriptor references just the latest version).
rm -f distribution/golden-diff-figma-*.zip
cp "$ZIP" distribution/
cp "$DIST/updatePlugins.xml" distribution/

echo "Updated distribution/ with $(basename "$ZIP") + updatePlugins.xml."
echo "Now: git add distribution && git commit && git push"
