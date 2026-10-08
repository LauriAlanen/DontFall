# DontFall

Yocto/OpenSTLinux (scarthgap) workspace for the STM32MP157C-DK2,
managed with the `repo` tool (`default.xml`).

## Setup

```sh
./init.sh                    # repo init + sync all layers
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
