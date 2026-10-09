#!/usr/bin/env bash
# Run a command inside the DontFall yocto container.
#
#   ./build.sh                            # bitbake df-image-minimal
#   ./build.sh bitbake st-image-weston    # any other target/command
#   ./build.sh bash                       # interactive shell (env loaded)
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [ $# -eq 0 ]; then
    set -- bitbake df-image-minimal
fi

# -T when there's no terminal (CI, scripts) - run allocates a TTY by default
args=(run --rm)
[ -t 0 ] || args+=(-T)

docker compose "${args[@]}" yocto "$@"
