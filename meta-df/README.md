# meta-df

DontFall custom layers.

This directory is a container for the project's own BitBake layers,
split by scope:

| Layer | Purpose |
|-------|---------|
| `meta-df-common` | Machine-agnostic configuration and recipes shared by all boards. |
| `meta-df-stm32mp157c-dk2` | Machine layer for the STM32MP157C-DK2 Discovery kit (`MACHINE = "stm32mp157c-dk2"`). |

`meta-df-common` also carries the build configuration template
(`conf/templates/default`) used by `oe-init-build-env`, and the
`df-image-minimal` image recipe.

## Usage

From the workspace root:

```sh
source ./envsetup.sh          # wraps oe-init-build-env with our TEMPLATECONF
bitbake df-image-minimal
```

Or without the helper:

```sh
TEMPLATECONF=$PWD/meta-df/meta-df-common/conf/templates/default \
    source openembedded-core/oe-init-build-env build-stm32mp157c-dk2

bitbake df-image-minimal
```

This generates `conf/bblayers.conf` and `conf/local.conf` in the build
directory with all required layers and `MACHINE = "stm32mp157c-dk2"`.
Re-sourcing later re-enters the same build directory without
regenerating the conf.

## Adding another machine

Create a new `meta-df-<machine>` sibling layer with its own
`conf/layer.conf` and `conf/machine/<machine>.conf`. Anything shared
between machines goes into `meta-df-common`.
