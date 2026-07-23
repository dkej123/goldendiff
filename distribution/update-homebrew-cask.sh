#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <version> <sha256> [stable|beta]" >&2
  exit 1
fi

version="$1"
sha256="$2"
channel="${3:-stable}"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Invalid version: $version" >&2
  exit 1
fi

if [[ ! "$sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid SHA-256: $sha256" >&2
  exit 1
fi

case "$channel" in
  stable)
    cask_name="golden-diff.rb"
    ;;
  beta)
    cask_name="golden-diff@beta.rb"
    ;;
  *)
    echo "Invalid channel: $channel" >&2
    exit 1
    ;;
esac

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cask="$repo_root/Casks/$cask_name"
temporary="$(mktemp)"
trap 'rm -f "$temporary"' EXIT

awk -v version="$version" -v sha256="$sha256" '
  /^  version / { print "  version \"" version "\""; next }
  /^  sha256 /  { print "  sha256 \"" sha256 "\""; next }
  { print }
' "$cask" > "$temporary"

mv "$temporary" "$cask"
trap - EXIT
