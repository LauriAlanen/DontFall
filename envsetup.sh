#!/usr/bin/env bash
# DontFall build environment setup.
#
# This script must be SOURCED (it exports the bitbake environment):
#
#   source ./envsetup.sh [builddir]
#
# Works when sourced from bash and zsh.
#
# Environment overrides:
#   BUILDDIR      build directory          (default: <workspace>/build-stm32mp157c-dk2)
#   TEMPLATECONF  conf template directory  (default: meta-df-common template)

# ------------------------------------------------------------------------
# Detect this script's path and whether it is being sourced, portably.
# ------------------------------------------------------------------------
if [ -n "${ZSH_VERSION:-}" ]; then
    # zsh: %x expands to the current (sourced or executed) file;
    # a sourced file adds a ':file' frame to the eval context.
    _df_self="${(%):-%x}"
    case "$ZSH_EVAL_CONTEXT" in
        *:file) _df_sourced=1 ;;
        *)      _df_sourced=0 ;;
    esac
elif [ -n "${BASH_VERSION:-}" ]; then
    # bash: sourced -> $0 is the shell name, BASH_SOURCE is this file
    _df_self="${BASH_SOURCE[0]}"
    if [ "$_df_self" = "$0" ]; then _df_sourced=0; else _df_sourced=1; fi
else
    # Unknown shell: can't reliably detect sourcing; proceed anyway.
    _df_self="$0"
    _df_sourced=1
fi

if [ "$_df_sourced" != 1 ]; then
    echo "error: this script must be sourced, e.g.:  . ./envsetup.sh" >&2
    exit 1
fi

DF_ROOT="$(cd "$(dirname "$_df_self")" && pwd)"
unset _df_self _df_sourced

if [ ! -f "$DF_ROOT/openembedded-core/oe-init-build-env" ]; then
    echo "error: openembedded-core not found - run ./init.sh first" >&2
    return 1
fi

BUILDDIR="${1:-${BUILDDIR:-$DF_ROOT/build-stm32mp157c-dk2}}"
export TEMPLATECONF="${TEMPLATECONF:-$DF_ROOT/meta-df/meta-df-common/conf/templates/default}"

echo "==> Build dir:    $BUILDDIR"
echo "==> TEMPLATECONF: $TEMPLATECONF"

. "$DF_ROOT/openembedded-core/oe-init-build-env" "$BUILDDIR"
