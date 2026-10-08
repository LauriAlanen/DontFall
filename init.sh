#!/usr/bin/env bash
# DontFall workspace initialization.
#
# Uses repo(1) with this repository as the manifest source (local manifest
# workflow): `repo init` clones this repo into .repo/manifests, `repo sync`
# clones every <project> in the manifest at its pinned revision.
#
# Usage:
#   ./init.sh [repo-sync-args...]
#
# Environment overrides:
#   MANIFEST   manifest file to use       (default: default.xml)
#   BRANCH     manifest branch to track   (default: current branch)
#   JOBS       parallel sync jobs         (default: nproc)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

MANIFEST="${MANIFEST:-default.xml}"
BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)}"
JOBS="${JOBS:-$(nproc)}"

if ! command -v repo >/dev/null 2>&1; then
    echo "error: 'repo' tool not found in PATH" >&2
    echo "       install: https://gerrit.googlesource.com/git-repo#install" >&2
    exit 1
fi

echo "==> Initializing manifest: $MANIFEST (branch: $BRANCH)"
repo init -u "$REPO_ROOT" -b "$BRANCH" -m "$MANIFEST" --depth=1

echo "==> Syncing projects (-j$JOBS)"
repo sync -j"$JOBS" "$@"

echo "==> Done. Workspace initialized."
