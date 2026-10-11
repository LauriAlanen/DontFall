#!/usr/bin/env bash
# deploy.sh - push built output to a running DontFall board over SSH.
#
#   ./deploy.sh <board-ip> [recipe...]         build recipe(s), rsync their
#                                              install trees to the target /
#   ./deploy.sh <board-ip>                     same, defaults to dontfall-app
#   ./deploy.sh <board-ip> virtual/kernel --reboot
#                                              kernel + dtbs + modules, reboot
#   ./deploy.sh <board-ip> --image             build df-image-minimal and
#                                              unpack its rootfs on the target
# Options:
#   --image[=name]   deploy an image rootfs instead of recipe(s)
#   --run '<cmd>'    run a command on the target after syncing
#                    (e.g. 'systemctl restart dontfall-m4' or
#                    '/usr/local/bin/dontfall-app')
#   --reboot         reboot the target after syncing
#   --no-build       skip the bitbake step, deploy whatever is already built
#
# Notes:
#  - With conf/dev.conf in the build dir, dontfall-app is built from the
#    sibling checkout ../DontFall-app (externalsrc); without it, from the
#    recipe's pinned SRCREV.
#  - File syncs are additive: files deleted from a build stay on the board.
#  - The board must run an image with rsync + openssh-sftp-server
#    (df-image-minimal carries them since this script was introduced).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
BUILDDIR="${BUILDDIR:-$PWD/build-stm32mp157c-dk2}"

# Dev boards on a LAN: no host key pinning, no password prompts.
SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
          -o ConnectTimeout=5 -o NumberOfPasswordPrompts=0 -o LogLevel=ERROR)

die()  { echo "deploy.sh: $*" >&2; exit 1; }
info() { echo "==> $*"; }

usage() { sed -n '2,24p' "$0" >&2; exit "${1:-0}"; }

# --- argument parsing ------------------------------------------------------
TARGET=""; RECIPES=(); IMAGE=""; RUN=""; REBOOT=0; NOBUILD=0
while [ $# -gt 0 ]; do
    case "$1" in
        --image)     IMAGE=df-image-minimal ;;
        --image=*)   IMAGE=${1#*=} ;;
        --run)       [ $# -ge 2 ] || die "--run needs a command"; RUN=$2; shift ;;
        --run=*)     RUN=${1#*=} ;;
        --reboot)    REBOOT=1 ;;
        --no-build)  NOBUILD=1 ;;
        -h|--help)   usage 0 ;;
        -*)          die "unknown option: $1" ;;
        *)           if [ -z "$TARGET" ]; then TARGET=$1; else RECIPES+=("$1"); fi ;;
    esac
    shift
done
[ -n "$TARGET" ] || usage 1
[ -n "$IMAGE" ] || [ ${#RECIPES[@]} -gt 0 ] || RECIPES=(dontfall-app)
[ -z "$IMAGE" ] || [ ${#RECIPES[@]} -eq 0 ] || die "--image and recipes are mutually exclusive"

# --- helpers ---------------------------------------------------------------
dc() { ./build.sh "$@"; }
# Non-interactive container calls: </dev/null makes build.sh pass -T to
# docker compose run so no TTY mangles the I/O.
dcni() { ./build.sh "$@" </dev/null; }

# Run a command on the target from inside the container (it has the ssh client).
tsh() { dcni ssh "${SSH_OPTS[@]}" "root@$TARGET" "$@"; }

# Resolve a bitbake variable for a recipe inside the build environment.
getvar() { # <recipe> <var>
    dcni bitbake -e "$1" 2>/dev/null | sed -n 's/^'"$2"'="\(.*\)"$/\1/p' | tail -n1
}

# Make sure the separate bootfs/userfs partitions are mounted on the target
# so files routed to /boot and /usr/local land in the right partition
# (the wic image is built with --no-fstab-update).
ensure_mounts() {
    tsh 'mountpoint -q /boot || mount LABEL=bootfs /boot;
         mountpoint -q /usr/local || mount LABEL=userfs /usr/local;
         true' >/dev/null 2>&1 || true
}

# --- main ------------------------------------------------------------------
if [ $NOBUILD -eq 0 ]; then
    if [ -n "$IMAGE" ]; then
        info "bitbake $IMAGE"
        dc bitbake "$IMAGE"
    else
        info "bitbake ${RECIPES[*]}"
        dc bitbake "${RECIPES[@]}"
    fi
fi

info "checking ssh to root@$TARGET"
tsh true 2>/dev/null || die "cannot reach root@$TARGET - board up/on the LAN? container rebuilt (docker compose build)?"
ensure_mounts

if [ -n "$IMAGE" ]; then
    deploy_dir=$(getvar "$IMAGE" DEPLOY_DIR_IMAGE)
    tarball=$(ls -t "$deploy_dir"/"$IMAGE"-*.rootfs.tar.* 2>/dev/null | head -n1 || true)
    [ -n "$tarball" ] || die "no rootfs tarball for $IMAGE in $deploy_dir"
    info "streaming $(basename "$tarball") -> $TARGET:/"
    dcni bash -c '
        case "$1" in
            *.tar.xz)       d=xzcat ;;
            *.tar.bz2)      d=bzcat ;;
            *.tar.zst)      d=zstdcat ;;
            *.tar.gz|*.tgz) d=zcat ;;
            *) echo "unsupported tarball: $1" >&2; exit 1 ;;
        esac
        "$d" "$1" | ssh $3 "root@$2" "tar -xf - -C /"
    ' _ "$tarball" "$TARGET" "${SSH_OPTS[*]}"
else
    for r in "${RECIPES[@]}"; do
        d=$(getvar "$r" D)
        { [ -n "$d" ] && [ -d "$d" ]; } || die "cannot resolve D for $r (build it first)"
        # --chown: pseudo only virtualizes root ownership, so files in D are
        # physically owned by the build user - fix ownership on the way in.
        info "rsync $r -> $TARGET:/"
        dcni rsync -az --chown=root:root --info=progress2 \
            -e "ssh ${SSH_OPTS[*]}" "$d/" "root@$TARGET:/"
        if [ -d "$d/boot" ] && ! tsh 'mountpoint -q /boot'; then
            info "warning: /boot not mounted on target - kernel files went to the rootfs copy"
        fi
    done
fi

if [ -n "$RUN" ]; then
    info "run: $RUN"
    dc ssh "${SSH_OPTS[@]}" "root@$TARGET" "$RUN"
fi
if [ "$REBOOT" = 1 ]; then
    info "rebooting $TARGET"
    tsh reboot >/dev/null 2>&1 || true
fi
info "done"
