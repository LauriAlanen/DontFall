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

## DontFall recipes and config

| Item | Layer | What it does |
|------|-------|--------------|
| `dontfall-app` | common | A7 app from `DontFall-app` (pinned `SRCREV`; `conf/dev.conf` switches it to the `../DontFall-app` checkout via externalsrc) |
| `dontfall-m4` | common | Zephyr M4 firmware from `DontFall-M4` (pinned `SRCREV_dfm4`, applies its `patches/zephyr/`); built only in the `zephyr-m4` multiconfig |
| `conf/multiconfig/zephyr-m4.conf` | common | Second configuration: `DISTRO = "zephyr"`, Zephyr 3.7, Zephyr SDK toolchain, own TMPDIR |
| `DF_M4_MC`, `DF_M4_MACHINE`, `DF_M4_TMPDIR` | common (`layer.conf`) | Names/paths shared by the multiconfig and the A7-side firmware package |
| `stm32mp157c-dk2-m4` machine | stm32mp157c-dk2 | Cortex-M4 machine for the multiconfig (`ZEPHYR_BOARD = "stm32mp157c_dk2"`) |
| `dontfall-m4-firmware` | stm32mp157c-dk2 | Installs the multiconfig's ELF as `/lib/firmware/dontfall-m4.elf` + `dontfall-m4.service` (starts the M4 at boot); pulled into images via `M4COPRO_LIST` in the machine conf |

Only `meta-zephyr/meta-zephyr-core` is enabled - `meta-zephyr-bsp` defines
its own `stm32mp157c-dk2` machine, which would clash with ours.

Workflow: [../docs/development.md](../docs/development.md).

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
