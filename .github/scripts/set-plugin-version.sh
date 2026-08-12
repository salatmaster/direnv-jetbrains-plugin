#!/usr/bin/env bash
#
# Records the released version in gradle.properties.
#
# A release builds with PLUGIN_VERSION from the environment, so this property is only the
# fallback for local builds - which is exactly why it rotted four releases behind without
# anyone noticing, and a local ./gradlew buildPlugin produced an artifact numbered 0.1.0
# carrying 0.1.0's change notes. The release workflow writes it in the same commit that
# cuts the changelog, so the two records of a version cannot disagree.
#
#   set-plugin-version.sh 0.1.5 [gradle.properties]
#
# Idempotent: a file that already records the version is left alone.
set -euo pipefail

version=${1:?usage: set-plugin-version.sh <version> [file]}
file=${2:-gradle.properties}

fail() {
    echo "$*" >&2
    exit 1
}

case "$version" in
    v*) fail "Pass the version without the leading v, got '$version'." ;;
esac
printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$' ||
    fail "'$version' is not a version; expected MAJOR.MINOR.PATCH."
[ -f "$file" ] || fail "No such file: $file"

grep -Eq '^pluginVersion[[:space:]]*=' "$file" ||
    fail "No 'pluginVersion' property in $file; nothing to update."

current=$(sed -nE 's/^pluginVersion[[:space:]]*=[[:space:]]*([^[:space:]]+).*$/\1/p' "$file" | head -1)
if [ "$current" = "$version" ]; then
    echo "$file already records $version; leaving it alone."
    exit 0
fi

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

awk -v version="$version" '
    /^pluginVersion[[:space:]]*=/ && !replaced {
        print "pluginVersion = " version
        replaced = 1
        next
    }
    { print }
' "$file" >"$tmp"

cat "$tmp" >"$file"
echo "Set pluginVersion to $version in $file, was $current."
