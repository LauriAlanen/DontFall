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

## Deploying to a running board

`deploy.sh` pushes build output to a live board over SSH - no reflashing.
The board must run an image containing `rsync`/`openssh-sftp-server`
(df-image-minimal does) and be reachable on the LAN.

```sh
# clone the app repo into the workspace once; deploy.sh wires EXTERNALSRC
# into conf/local.conf so bitbake builds your checkout directly
git clone <app-repo> dontfall-app

./deploy.sh <board-ip>                       # build + push dontfall-app
./deploy.sh <board-ip> --run '/usr/local/bin/dontfall'
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
