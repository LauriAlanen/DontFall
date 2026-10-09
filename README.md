# DontFall

Yocto/OpenSTLinux (scarthgap) workspace for the STM32MP157C-DK2,
managed with the `repo` tool (`default.xml`).

## Setup

```sh
./init.sh                    # repo init + sync all layers + docker image build
```

## Containerized build

`./init.sh` builds the `dontfall-yocto` image (skip with `SKIP_DOCKER=1`).
The compose service bind-mounts this workspace at the same absolute path,
so the container shares `downloads/`, `sstate-cache/` and the existing
build directory with the host - nothing is rebuilt.

```sh
./build.sh                          # bitbake df-image-minimal, fully automated
./build.sh bitbake st-image-weston  # any other target/command
./build.sh bash                     # interactive shell, env already loaded
```

The container's entrypoint sources `envsetup.sh` before every command, so
`docker compose run --rm yocto` also drops you straight into the build
environment - no manual `source` needed.

If the checkout lives at a different path, point `WORKSPACE_ROOT` at it:

```sh
WORKSPACE_ROOT=/path/to/DontFall ./build.sh
```

## Initialize a build

```sh
source ./envsetup.sh          # creates/enters ./build-stm32mp157c-dk2

bitbake df-image-minimal      # our minimal image (or st-image-weston)
```

`envsetup.sh` wraps `oe-init-build-env` with the project's
`TEMPLATECONF`; pass a different build dir as first argument or via
`BUILDDIR`.

## Custom layers

`meta-df/` holds the project layers — see `meta-df/README.md`.

- `meta-df-common` — machine-agnostic config/recipes + build template
- `meta-df-stm32mp157c-dk2` — `MACHINE = "stm32mp157c-dk2"` support
