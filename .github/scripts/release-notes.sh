#!/usr/bin/env bash
#
# Prints the release notes for one version: its section of the changelog, followed by
# the compare link the changelog already records for it.
#
# GitHub's --generate-notes lists merged pull request titles. That says what landed but
# not what changed for anyone installing the plugin, and it appends a "New Contributors"
# block that is noise on a single-author repository. The changelog already describes the
# change properly, so the release quotes it instead of telling a second, thinner story.
#
#   release-notes.sh 0.1.3 [CHANGELOG.md]
#
# Writes nothing and fails when the section is missing or empty: a release whose notes
# say nothing is worse than one with no notes, because it looks answered.
set -euo pipefail

version=${1:?usage: release-notes.sh <version> [file]}
file=${2:-CHANGELOG.md}

fail() {
    echo "$*" >&2
    exit 1
}

case "$version" in
    v*) fail "Pass the version without the leading v, got '$version'." ;;
esac
[ -f "$file" ] || fail "No such file: $file"

# Matched as a literal prefix rather than a regex: the heading contains brackets and
# dots, and escaping those for every caller is a bug waiting to happen.
body=$(awk -v want="## [$version]" '
    index($0, want) == 1 { inside = 1; next }
    inside && /^## / { exit }
    inside { print }
' "$file")

[ -n "$(printf '%s' "$body" | tr -d '[:space:]')" ] ||
    fail "No '## [$version]' section in $file, or it is empty."

# The section is bounded by blank lines on both sides; keep the ones inside it.
printf '%s\n' "$body" | awk '
    { line[NR] = $0 }
    END {
        first = 1
        while (first <= NR && line[first] ~ /^[[:space:]]*$/) first++
        last = NR
        while (last >= first && line[last] ~ /^[[:space:]]*$/) last--
        for (i = first; i <= last; i++) print line[i]
    }
'

# Taken from the file rather than composed from the version, so it cannot disagree with
# the links the changelog already publishes.
link=$(grep -E "^\[$version\]: " "$file" | head -1 | sed -E "s#^\[$version\]: ##" || true)
[ -n "$link" ] && printf '\n**Full changelog**: %s\n' "$link"

exit 0
