#!/usr/bin/env bash
#
# Turns the [Unreleased] section of a Keep a Changelog file into a released version.
#
# A release used to mean editing this by hand and then remembering to tag; the two
# drifted apart the moment anyone forgot. The release workflow runs this before it
# builds, so the tag it creates always contains its own changelog entry.
#
#   cut-changelog.sh 0.1.3 2026-08-10 [CHANGELOG.md]
#
# Idempotent: a file that already has the section is left alone, so a re-run cannot
# duplicate it.
set -euo pipefail

version=${1:?usage: cut-changelog.sh <version> <date> [file]}
date=${2:?usage: cut-changelog.sh <version> <date> [file]}
file=${3:-CHANGELOG.md}

fail() {
    echo "$*" >&2
    exit 1
}

case "$version" in
    v*) fail "Pass the version without the leading v, got '$version'." ;;
esac
printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$' ||
    fail "'$version' is not a version; expected MAJOR.MINOR.PATCH."
printf '%s' "$date" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' ||
    fail "'$date' is not a date; expected YYYY-MM-DD."
[ -f "$file" ] || fail "No such file: $file"

if grep -Fq "## [$version]" "$file"; then
    echo "$file already has a [$version] section; leaving it alone."
    exit 0
fi

grep -q '^## \[Unreleased\]' "$file" || fail "No '## [Unreleased]' heading in $file."

# Everything between [Unreleased] and the next heading. Releasing an empty section
# would publish a version whose changelog says nothing, which is worse than no entry
# at all: it looks answered.
body=$(awk '/^## \[Unreleased\]/ { inside = 1; next } inside && /^## / { exit } inside { print }' "$file")
[ -n "$(printf '%s' "$body" | tr -d '[:space:]')" ] ||
    fail "Nothing is recorded under [Unreleased] in $file; a release needs an entry."

# The previous version is read off the compare link rather than from the tags, so
# this works on a shallow clone and cannot disagree with what the file already says.
link=$(grep -E '^\[unreleased\]: .+/compare/v.+\.\.\.HEAD$' "$file" || true)

if [ -z "$link" ]; then
    # First release: there is no earlier tag to compare against, so the link block does
    # not exist yet. Build it from the remote, and point the version at its tag rather
    # than at a comparison with a version that never existed.
    repo=${REPO_URL:-$(git config --get remote.origin.url 2>/dev/null || true)}
    [ -n "$repo" ] ||
        fail "No '[unreleased]' link in $file and no git remote to derive one; set REPO_URL."
    repo=$(printf '%s' "$repo" | sed -E 's#^git@github\.com:#https://github.com/#; s#\.git$##')

    printf '\n[unreleased]: %s/compare/v%s...HEAD\n[%s]: %s/releases/tag/v%s\n' \
        "$repo" "$version" "$version" "$repo" "$version" >>"$file"

    tmp=$(mktemp)
    trap 'rm -f "$tmp"' EXIT
    awk -v version="$version" -v date="$date" '
        /^## \[Unreleased\]/ && !cut {
            print
            print ""
            print "## [" version "] - " date
            cut = 1
            next
        }
        { print }
    ' "$file" >"$tmp"
    cat "$tmp" >"$file"
    echo "Cut $version ($date) in $file as the first release."
    exit 0
fi

base=$(printf '%s' "$link" | sed -E 's#^\[unreleased\]: (.+/compare/)v.+\.\.\.HEAD$#\1#')
previous=$(printf '%s' "$link" | sed -E 's#^\[unreleased\]: .+/compare/v(.+)\.\.\.HEAD$#\1#')

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

awk -v version="$version" -v date="$date" -v base="$base" -v previous="$previous" '
    /^## \[Unreleased\]/ && !cut {
        print
        print ""
        print "## [" version "] - " date
        cut = 1
        next
    }
    /^\[unreleased\]: / {
        print "[unreleased]: " base "v" version "...HEAD"
        print "[" version "]: " base "v" previous "...v" version
        next
    }
    { print }
' "$file" >"$tmp"

cat "$tmp" >"$file"
echo "Cut $version ($date) in $file, after $previous."
