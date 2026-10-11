# DontFall multi-repo development workflow (A7 app + Zephyr M4 over OpenAMP)

Status: draft for review - 2026-10-11

## Intent

DontFall runs on the STM32MP157C-DK2: Linux on the Cortex-A7 and Zephyr on
the Cortex-M4, talking over OpenAMP (rpmsg). The Yocto workspace already
boots a signed `df-image-minimal`, and ST's CubeMP1 OpenAMP examples have
proven that the kernel, device tree and remoteproc side work.

What's missing is a setup that is both **professional** and **easy to
develop in**:

- **Professional:** one `bitbake df-image-minimal` builds everything that
  ships (A7 app + Zephyr M4 firmware) from pinned revisions. Release
  output can be reproduced from the `DontFall` repo alone.
- **Easy:** each part is developed in its own repo and pushed to a running
  board in seconds, without rebuilding or reflashing the image.

The first deliverable that proves the setup is an OpenAMP hello world: the
A7 app sends `hello from A7`, the M4 answers `hello from M4: ...`.

## Goals

- Three repos, each with one job:
  - `DontFall`: integration (repo manifest, layers, build/deploy scripts, docs)
  - `DontFall-app`: A7 Linux application (`github.com/LauriAlanen/DontFall-app`)
  - `DontFall-M4`: Zephyr M4 firmware (`github.com/LauriAlanen/DontFall-M4`)
- Yocto builds the Zephyr firmware itself (meta-zephyr, multiconfig) and
  puts it into the image with autostart at boot.
- Dev and release build the M4 firmware with the **same Zephyr revision,
  same modules, same SDK version**.
- Day-to-day loops:
  - A7 app: edit -> `./deploy.sh <ip> --run ...`
  - M4: edit -> `build` -> `deploy` (scp + remoteproc restart)
- The daily flow and the release flow are documented in the repos.
- Work is split into phases. Each phase leaves the board in a working
  state and has an on-board check.

## Non-goals (for now)

- Signing/authenticating the M4 firmware (the opteemin scheme loads it
  from Linux remoteproc, unsigned).
- Debugging the M4 over ST-Link/GDB, or engineering-mode boot.
- CI pipelines (the setup should make CI easy later, but none is built now).
- A Yocto SDK (`populate_sdk`) for the app; externalsrc + `deploy.sh`
  covers the app dev loop.
- Building the M4 firmware from a local checkout inside bitbake. Bitbake
  only ever builds pinned revisions of `DontFall-M4`.

## Anti-goals (failure even if it "works")

- A release build silently picking up a local, possibly dirty checkout.
- Zephyr/module/SDK versions drifting between the west dev build and the
  bitbake build.
- Any change to the signed boot chain (TF-A/FIP, keys, signing config).
- Needing a full image rebuild or reflash for an app or M4 change.
- Magic: hidden auto-edits of config files. Dev overrides must be visible
  in one obvious place.

## Constraints and pinned facts

- Yocto scarthgap, OpenSTLinux (`openstlinux-weston`), machine
  `stm32mp157c-dk2`. Layers are managed by `repo` (`default.xml`) and
  built in the `dontfall-yocto` Docker container (`build.sh`). The
  container bind-mounts paths at the **same absolute path** as on the host.
- meta-zephyr branch `scarthgap`, pinned at
  `f10a17c4b551f92a93ab6e8114b87c2816ed6f36`. Only the
  `meta-zephyr-core` layer is added. Do **not** add `meta-zephyr-bsp`: it
  ships its own `conf/machine/stm32mp157c-dk2.conf`, which would shadow or
  clash with our A7 machine of the same name.
- Zephyr: `PREFERRED_VERSION_zephyr-kernel = "3.7.0"`. meta-zephyr pins
  zephyr `v3.7-branch` at `36940db938a8f4a1e919496793ed439850a221c2` with:
  - cmsis `4b96cbb174678dcd3ca86e11e1f24bc5f8726da0` (`modules/hal/cmsis`)
  - hal_stm32 `f1317150eac951fdd8259337a47cbbc4c2e6d335` (`modules/hal/stm32`)
  - libmetal `a6851ba6dba8c9e87d00c42f171a822f7a29639b` (`modules/hal/libmetal`)
  - open-amp `76d2168bcdfcd23a9a7dce8c21f2083b90a1e60a` (`modules/lib/open-amp`)

  These match zephyr's own `west.yml` at that commit. `DontFall-M4`'s west
  manifest pins zephyr to the same SHA and imports only these modules.
- Zephyr SDK **0.16.9** in both places: meta-zephyr's `zephyr-sdk-native`
  (`ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"`) and the M4 dev container (arm
  toolchain only).
- Zephyr board: `stm32mp157c_dk2`. Upstream sample
  `samples/subsys/ipc/openamp_rsc_table` officially supports it and is the
  starting point for the M4 app (rpmsg-tty channel -> `/dev/ttyRPMSG0` on
  Linux).
- M4 logs: Zephyr's resource table exposes the RAM console as a remoteproc
  trace buffer when `CONFIG_RAM_CONSOLE=y`. Read it on Linux at
  `/sys/kernel/debug/remoteproc/remoteproc0/trace0`.
- Firmware file on target: `/lib/firmware/dontfall-m4.elf` (the same name
  in dev and release), loaded via `/sys/class/remoteproc/remoteproc0`.
- App files keep installing under `/usr/local` (userfs partition).
- Commits: plain messages, no AI/tool attribution (`AGENTS.md`).

## Disk layout (decided)

```
~/Projects/
  DontFall/                 integration repo (unchanged location)
  DontFall-app/             A7 app repo
  DontFall-M4-ws/           west workspace (NOT a git repo)
    .west/
    DontFall-M4/            the M4 repo, moved here with `mv` (same git repo/remote)
    zephyr/                 west-managed, pinned
    modules/hal/{cmsis,stm32,libmetal}, modules/lib/open-amp
```

The Yocto container mounts `DontFall`, `DontFall-app` and `DontFall-M4-ws`
at their host paths. The internal `DontFall/dontfall-app` and
`DontFall/dontfall-app-build` are deleted, together with their
`EXTERNALSRC` lines in the build's `local.conf`.

## Design

### DontFall (integration)

- **Manifest:** add meta-zephyr (pinned SHA above) to `default.xml`. Add
  `meta-zephyr-core` to `bblayers.conf.sample` and to the existing
  build's `bblayers.conf`.
- **M4 machine:** `meta-df-stm32mp157c-dk2/conf/machine/stm32mp157c-dk2-m4.conf`:
  - Cortex-M4 tune (`require conf/machine/include/arm/armv7m/tune-cortexm4.inc`)
  - `ZEPHYR_BOARD = "stm32mp157c_dk2"`
- **Multiconfig** `zephyr-m4` (`meta-df-common/conf/multiconfig/zephyr-m4.conf`):
  - `MACHINE = "stm32mp157c-dk2-m4"`
  - `DISTRO = "zephyr"`
  - `TMPDIR = "${TOPDIR}/tmp-zephyr-m4"`
  - `PREFERRED_VERSION_zephyr-kernel = "3.7.0"`
  - `ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"`

  Add `BBMULTICONFIG = "zephyr-m4"` to `local.conf.sample` and to the
  existing `local.conf`.
- **Recipe `dontfall-m4`** (built in mc `zephyr-m4`):
  - `inherit zephyr-sample`
  - adds `DontFall-M4` to `SRC_URI` as a named git source at a pinned
    SRCREV
  - `ZEPHYR_SRC_DIR` points at the app dir inside it
  - `COMPATIBLE_MACHINE = "stm32mp157c-dk2-m4"`
- **Recipe `dontfall-m4-firmware`** (A7 side):
  - pulls the deployed ELF via
    `do_install[mcdepends] = "mc::zephyr-m4:dontfall-m4:do_deploy"`
  - installs it as `/lib/firmware/dontfall-m4.elf`
  - ships `dontfall-m4.service`, a systemd oneshot, enabled. Start: write
    the firmware name and `start` to remoteproc0 (stop first if it's
    running). Stop: `stop`.
- **Recipe `dontfall-app`:**
  - `SRC_URI` -> `git://github.com/LauriAlanen/DontFall-app.git;protocol=https;branch=main`
    with a real pinned SRCREV
  - keeps `inherit externalsrc cmake` and `prefix = "/usr/local"`
- **Image:** `df-image-minimal` installs `dontfall-app`,
  `dontfall-m4-firmware` and `rpmsg` userspace bits as needed. ST's
  `m4projects-stm32mp1` is kept until our firmware works, then dropped by
  overriding `M4COPRO_LIST` in our machine conf (`m4fwcoredump` stays).
- **Dev overrides:** live in one file, `conf/dev.conf` in the build dir.
  - `local.conf` pulls it in with an optional `include conf/dev.conf`.
  - Template: `meta-df-common/conf/templates/default/dev.conf.sample`,
    copied once by hand (README says so; `envsetup.sh` prints a hint if
    it's missing).
  - It holds `EXTERNALSRC:pn-dontfall-app = "<path>/DontFall-app"`.
  - **A release build is a build without `conf/dev.conf`.**
- **`deploy.sh`:** drop the auto-`EXTERNALSRC` wiring (replaced by
  `dev.conf`). Default recipe stays `dontfall-app`. Everything else stays.
- **Docker:** `docker-compose.yml` also mounts `../DontFall-app` and
  `../DontFall-M4-ws` at the same absolute paths. They're optional: the
  build must still work if they're absent.
- **`.vscode/settings.json`:** drop the stale `cmake.sourceDirectory` and
  `sources/poky` paths.

### DontFall-app

- CMake, C++17, single executable `dontfall-app`, installed to `bin`
  (lands at `/usr/local/bin/dontfall-app` through the recipe's prefix).
- `.gitignore`, `README.md` (dev loop and release notes).
- Hello world behaviour (phase 5):
  - opens `/dev/ttyRPMSG0` (path overridable by argv)
  - sets raw mode
  - writes `hello from A7\n`
  - waits up to 2 s for a line and prints it
  - exit code 0 on reply, non-zero on timeout or open error, with a clear
    message

### DontFall-M4

- West manifest repo (`west.yml`):
  - zephyr pinned to `36940db938a8f4a1e919496793ed439850a221c2`
  - `import: name-allowlist: [cmsis, hal_stm32, libmetal, open-amp]`
  - `self.path: DontFall-M4`
- `app/`: Zephyr application for board `stm32mp157c_dk2`, derived from
  upstream `openamp_rsc_table`, trimmed to what we need:
  - resource table
  - an rpmsg-tty endpoint that replies `hello from M4: <received line>`
  - `CONFIG_RAM_CONSOLE=y`, so `printk` shows up in remoteproc `trace0`
  - default output name `zephyr.elf`
- Dev container (`Dockerfile` + `docker-compose.yml`):
  - Ubuntu 22.04, `west`, Zephyr's Python requirements
  - Zephyr SDK 0.16.9 minimal + `arm-zephyr-eabi`
  - UID/GID passthrough
  - mounts `DontFall-M4-ws` at its host path, so `compile_commands.json`
    works for host IDEs
- Scripts:
  - `scripts/init.sh`: create the workspace + `west update` + build the
    container
  - `scripts/build.sh`: `west build -b stm32mp157c_dk2 app` in the
    container; extra args pass through
  - `scripts/deploy.sh <ip> [--log]`: scp the ELF to
    `/lib/firmware/dontfall-m4.elf`, restart through remoteproc, and with
    `--log` print `trace0`
- `README.md`: workspace setup, dev loop, release notes.

## Daily flow (what gets documented)

Main docs: `DontFall/docs/development.md`. Each repo's README links to it
and covers its own commands.

One-time setup:
1. Run `./init.sh` in `DontFall`.
2. Clone `DontFall-app` next to it.
3. Run `scripts/init.sh` from `DontFall-M4` (it creates `DontFall-M4-ws`).
4. `cp` `dev.conf.sample` -> `build-stm32mp157c-dk2/conf/dev.conf`.
5. Build `df-image-minimal` and flash it once.

A7 app change:
1. Edit in `~/Projects/DontFall-app`.
2. From `DontFall`: `./deploy.sh <ip> --run /usr/local/bin/dontfall-app`.
   Bitbake builds your working tree through externalsrc and rsyncs it.

M4 change:
1. Edit in `~/Projects/DontFall-M4-ws/DontFall-M4/app`.
2. Run `scripts/build.sh`.
3. Run `scripts/deploy.sh <ip> --log`. It overwrites
   `/lib/firmware/dontfall-m4.elf` and restarts the M4 in seconds. The
   override lasts until the next image deploy.

End-to-end check: on the board, run `dontfall-app`, which prints the M4's
reply.

Release:
1. Commit and push in `DontFall-app` / `DontFall-M4`.
2. In `DontFall`, bump `SRCREV` in `dontfall-app.bb` / `dontfall-m4.bb`.
3. Build without `conf/dev.conf`: `./build.sh`.
4. Flash the wic, or `./deploy.sh <ip> --image`.
5. Commit the SRCREV bump.

Zephyr upgrade: bump meta-zephyr in `default.xml` and the zephyr SHA in
`west.yml` **together**. The two must match.

## Phases

Each phase is its own set of commits and ends with a check on real
hardware.

1. **Cleanup.**
   - Delete internal `dontfall-app` and `dontfall-app-build`, and the
     `EXTERNALSRC` lines in `local.conf`.
   - Fix the README and `.vscode`.
   - Check: `bitbake df-image-minimal` still parses and builds.
2. **App repo + dev overrides.**
   - Set up `DontFall-app`.
   - Add the compose mounts, `dev.conf` mechanism and recipe SRC_URI, and
     slim down `deploy.sh`.
   - Check: `./deploy.sh <ip> --run /usr/local/bin/dontfall-app` prints
     the hello/version line.
3. **M4 repo standalone.**
   - Move the repo into `DontFall-M4-ws`.
   - Add the west manifest, app, container and scripts.
   - Check: after `scripts/deploy.sh <ip> --log`:
     - remoteproc0 state is `running`
     - `trace0` shows the M4 banner
     - `/dev/ttyRPMSG0` exists
     - `echo hi > /dev/ttyRPMSG0; cat /dev/ttyRPMSG0` shows
       `hello from M4: hi`
4. **Yocto builds Zephyr.**
   - Add meta-zephyr to the manifest and layers.
   - Add the M4 machine, multiconfig, `dontfall-m4`,
     `dontfall-m4-firmware` + service, and the image install.
   - Check: `bitbake df-image-minimal` builds the firmware via mc. After
     flashing or `--image` deploy and a reboot, the M4 is `running` with
     `dontfall-m4.elf`, without manual steps.
5. **OpenAMP hello world.**
   - The A7 app talks to `/dev/ttyRPMSG0`.
   - Check: `dontfall-app` on the board prints `hello from M4: hello from A7`.
6. **Tidy up + docs.**
   - Drop ST's `m4projects-stm32mp1` from the image.
   - Write `docs/development.md` and the per-repo READMEs.
   - Update `AGENTS.md` with build/verify commands.
   - Check: a fresh image boots straight into our firmware, and the
     documented commands work as written.

## Calls made by the designer (review these)

- Only `meta-zephyr-core` is added, plus our own M4 machine named
  `stm32mp157c-dk2-m4`, to avoid the machine-name clash.
- Zephyr SDK toolchain (0.16.9) in bitbake rather than meta-zephyr's
  Yocto-built cross gcc, so dev and release use the same compiler.
- Bitbake never builds a local M4 checkout; the M4 dev loop is west-only.
  Reason: the M4 needs nothing from the Linux sysroot, while the app does.
- Dev overrides live in an optional `conf/dev.conf`. Release = no dev.conf.
- Same firmware filename in dev and release, so dev deploys transparently
  override the packaged firmware until the next image deploy.
- Hello world goes over rpmsg-tty (`/dev/ttyRPMSG0`), not rpmsg-char.
- The M4 starts at boot via our own systemd unit. ST's loader service stays
  disabled and is removed with `m4projects` in phase 6.

## Left to the builder

- Exact content of the trimmed Zephyr app and its Kconfig/overlay. Start
  from the upstream `openamp_rsc_table` `stm32mp157c_dk2` overlay/conf.
- `SRCREV_FORMAT` and source naming in `dontfall-m4.bb`.
- Where `dontfall-m4-firmware` finds the mc deploy dir (derive it from
  the mc `TMPDIR` and machine; no hardcoded host paths).
- Dockerfile details for the M4 container, as long as the SDK version and
  same-path mount hold.

## Risks to watch

- Linux DT memory regions (vdev0vring0/1, vdev0buffer, retram/mcuram)
  must match what Zephyr's `stm32mp157c_dk2` board/overlay expects.
  ST's examples working suggests they do, but phase 3 proves it.
- meta-zephyr scarthgap + OpenSTLinux in one build: distro, TCLIBC and
  layer priority interactions inside the multiconfig. Phase 4 proves it.
- The first mc build downloads the Zephyr SDK and all zephyr modules that
  meta-zephyr fetches (a large download).
