#!/usr/bin/env bash
# DontFall workspace initialization.
#
# Uses repo(1) with this repository as the manifest source (local manifest
# workflow): `repo init` clones this repo into .repo/manifests, `repo sync`
# clones every <project> in the manifest at its pinned revision.
#
# Afterwards the docker image for the containerized build environment is
# built (see Dockerfile / docker-compose.yml).
#
# Usage:
#   ./init.sh [repo-sync-args...]
#
# Environment overrides:
#   MANIFEST     manifest file to use       (default: default.xml)
#   BRANCH       manifest branch to track   (default: current branch)
#   JOBS         parallel sync jobs         (default: nproc)
#   SKIP_DOCKER  set to 1 to skip the docker image build
#   IMAGE        docker image name          (default: dontfall-yocto)
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

IMAGE="${IMAGE:-dontfall-yocto}"

if [ "${SKIP_DOCKER:-0}" = "1" ]; then
    echo "==> SKIP_DOCKER=1 - skipping docker image build"
elif command -v docker >/dev/null 2>&1; then
    echo "==> Building docker image: $IMAGE (UID=$(id -u), GID=$(id -g))"
    docker build -t "$IMAGE" \
        --build-arg "UID=$(id -u)" \
        --build-arg "GID=$(id -g)" \
        "$REPO_ROOT"
else
    echo "==> docker not found - skipping image build (builds on host still work)"
fi

echo "==> Done. Workspace initialized."
