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

# Sibling repos (A7 app checkout for externalsrc, M4 west workspace) are
# mounted at their host paths when present. Done here rather than in
# docker-compose.yml so a missing dir isn't created as root on the host.
parent="$(cd .. && pwd)"
for d in "$parent/DontFall-app" "$parent/DontFall-M4-ws"; do
    [ -d "$d" ] && args+=(-v "$d:$d")
done

docker compose "${args[@]}" yocto "$@"
