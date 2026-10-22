# DontFall

Yocto/OpenSTLinux (scarthgap) workspace for the STM32MP157C-DK2,
managed with the `repo` tool (`default.xml`).

The image ships the A7 application ([DontFall-app](https://github.com/LauriAlanen/DontFall-app))
and the Zephyr Cortex-M4 firmware ([DontFall-M4](https://github.com/LauriAlanen/DontFall-M4)),
which talk ROS 2 over OpenAMP (micro-ROS on `/dev/ttyRPMSG0`).

**Daily development and release flow: [docs/development.md](docs/development.md).**

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

## Dev vs. release builds

Developer overrides live in `conf/dev.conf` (copy once from
`meta-df/meta-df-common/conf/templates/default/dev.conf.sample`); with it,
`dontfall-app` is built from the sibling checkout `../DontFall-app`. A build
dir **without** `conf/dev.conf` is a release build: every DontFall recipe
fetches its pinned SRCREV.

The DontFall-app/DontFall-M4 repos are private and fetched over ssh:
`build.sh` hands your host ssh-agent and `~/.ssh/known_hosts` to the
container, so unlock your key first (`eval "$(ssh-agent -s)" && ssh-add`).

## M4 firmware (Zephyr)

`BBMULTICONFIG = "zephyr-m4"` adds a second configuration that builds the
`dontfall-m4` Zephyr app (meta-zephyr, Zephyr 3.7, Zephyr SDK 0.16.9) for the
`stm32mp157c-dk2-m4` machine in `build-stm32mp157c-dk2/tmp-zephyr-m4`.
`bitbake df-image-minimal` pulls it in through `dontfall-m4-firmware`, which
installs `/lib/firmware/dontfall-m4.elf` and `dontfall-m4.service` (starts the
M4 at boot). Build it alone with `./build.sh bitbake mc:zephyr-m4:dontfall-m4`.

## ROS 2

ROS 2 **Jazzy** comes from [meta-ros](https://github.com/ros/meta-ros)
(scarthgap branch, pinned in `default.xml`; layers `meta-ros-common`,
`meta-ros2`, `meta-ros2-jazzy`). meta-ros enables Fortran in gcc globally;
`local.conf` turns that off with `ROS_WORLD_SKIP_GROUPS += "fortran"` so the
toolchain and every package aren't rebuilt for it. meta-ros has no
micro-ROS Agent recipe; that is ours to write.

`meta-df-common/classes/ros_ament_cmake.bbclass` overrides the meta-ros class
of the same name (meta-df-common is first in `BBPATH`). It inherits
`python3targetconfig` instead of `python3native`, otherwise the rosidl Python
bindings build against the host's x86_64 Python headers and fail on our
32-bit A7 with `LONG_BIT definition appears wrong for platform`
([meta-ros#1205](https://github.com/ros/meta-ros/issues/1205)). When bumping
meta-ros, re-copy the upstream class and re-apply that one line; drop the
override once upstream fixes the issue.

## Deploying to a running board

`deploy.sh` pushes build output to a live board over SSH - no reflashing.
The board must run an image containing `rsync`/`openssh-sftp-server`
(df-image-minimal does) and be reachable on the LAN.

```sh
./deploy.sh <board-ip>                       # build + push dontfall-app
./deploy.sh <board-ip> --run 'systemctl restart dontfall-app'
./deploy.sh <board-ip> <other-recipe>        # any recipe's installed files
./deploy.sh <board-ip> virtual/kernel --reboot   # kernel + dtbs + modules
./deploy.sh <board-ip> --image               # build image, unpack rootfs
                                             # on the running system
```

App files install under `/usr/local` (the dedicated userfs partition in the
wks layout), kernel/devicetree under `/boot` (bootfs partition) - `deploy.sh`
mounts both if they aren't already. Syncs are additive and never touch the
signed boot partitions (TF-A/FIP), so the secure-boot chain stays intact.

`devtool` also works over this connection, e.g.
`./build.sh devtool deploy-target <recipe> root@<board-ip>`.

## WiFi

Set `DF_WIFI_SSID`/`DF_WIFI_PASSWORD` in `conf/local.conf` to have the board
join a network on first boot. A `wpa-supplicant` bbappend in `meta-df-common`
generates `/etc/wpa_supplicant/wpa_supplicant-wlan0.conf` (passphrase stored
as hashed PSK, not plaintext) and enables `wpa_supplicant@wlan0.service`;
`systemd-networkd` DHCPs `wlan0` automatically. Unset `DF_WIFI_SSID` disables
it entirely.

Changing the vars + re-deploying works without reflashing:
`./deploy.sh <board-ip> wpa-supplicant --run 'systemctl restart wpa_supplicant@wlan0'`.

## Custom layers

`meta-df/` holds the project layers — see `meta-df/README.md`.

- `meta-df-common` — machine-agnostic config/recipes + build template
- `meta-df-stm32mp157c-dk2` — `MACHINE = "stm32mp157c-dk2"` support
