# DontFall Multi-Repo Dev Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `bitbake df-image-minimal` builds and ships the A7 app and the
Zephyr M4 firmware from pinned revisions. Each part also has a fast,
documented dev loop that needs no reflash. An OpenAMP hello world proves
both work.

**Architecture:** `DontFall` stays the repo-managed integration workspace.
- meta-zephyr-core is added. A `zephyr-m4` multiconfig builds the
  `dontfall-m4` Zephyr app for a custom `stm32mp157c-dk2-m4` machine.
- An A7-side recipe pulls the ELF in via `mcdepends` and installs it plus
  a systemd autostart unit.
- `DontFall-app` is built through externalsrc only when `conf/dev.conf`
  exists.
- `DontFall-M4` is a west manifest repo inside `~/Projects/DontFall-M4-ws`,
  pinned to the exact Zephyr/module revisions meta-zephyr uses, and built
  in its own Zephyr SDK container.

**Tech Stack:** Yocto scarthgap / OpenSTLinux, meta-zephyr (scarthgap),
Zephyr 3.7 (v3.7-branch), Zephyr SDK 0.16.9, west, Docker/compose, CMake
C++17, OpenAMP rpmsg-tty, systemd, remoteproc.

**Spec:** `docs/superpowers/specs/2026-10-11-dontfall-dev-workflow-design.md`

## Global Constraints

- Commits: plain messages, NO AI/tool attribution or Co-Authored-By trailers (`DontFall/AGENTS.md`).
- Never touch the signed boot chain: `SIGN_*` settings, `stm32mp15x-keys/`, `stm32mp15x-signingtool/`, TF-A/FIP recipes.
- meta-zephyr: remote `https://git.yoctoproject.org`, project `meta-zephyr`, revision `f10a17c4b551f92a93ab6e8114b87c2816ed6f36`. Add ONLY `meta-zephyr/meta-zephyr-core`, never `meta-zephyr-bsp`.
- Zephyr: `PREFERRED_VERSION_zephyr-kernel = "3.7.0"`; zephyr SHA `36940db938a8f4a1e919496793ed439850a221c2`; modules cmsis `4b96cbb174678dcd3ca86e11e1f24bc5f8726da0`, hal_stm32 `f1317150eac951fdd8259337a47cbbc4c2e6d335`, libmetal `a6851ba6dba8c9e87d00c42f171a822f7a29639b`, open-amp `76d2168bcdfcd23a9a7dce8c21f2083b90a1e60a`.
- Zephyr SDK `0.16.9` in both bitbake (`ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"`) and the M4 container.
- Zephyr board `stm32mp157c_dk2`; M4 Yocto machine `stm32mp157c-dk2-m4`; multiconfig name `zephyr-m4`; mc TMPDIR `${TOPDIR}/tmp-zephyr-m4`.
- Target firmware path `/lib/firmware/dontfall-m4.elf` (same name in dev and release); remoteproc `/sys/class/remoteproc/remoteproc0`; M4 log `/sys/kernel/debug/remoteproc/remoteproc0/trace0`.
- App installs under prefix `/usr/local` → `/usr/local/bin/dontfall-app`.
- Repos: `git@github.com:LauriAlanen/DontFall-app.git`, `git@github.com:LauriAlanen/DontFall-M4.git` (fetch in recipes via `https://github.com/LauriAlanen/...`).
- Containers mount host paths at the SAME absolute path. Optional sibling repos must not break the build when absent.
- Release build = build dir without `conf/dev.conf`. No config file is ever auto-edited by scripts.
- Board-dependent checks need the board IP (`$BOARD`) from your human partner. The board must be on the LAN running a `df-image-minimal`. If the board is unavailable, stop at that step and hand the check over. Do not mark it done.

## Review Focus

1. **Release build with no `DontFall-app` checkout and no `conf/dev.conf`.** It must parse and fetch the app from GitHub at the pinned SRCREV. Covered in Task 2.3 Step 4.
2. **`build.sh` / `deploy.sh` when `../DontFall-app` or `../DontFall-M4-ws` doesn't exist.** The container must start without creating root-owned host dirs. Covered in Task 2.2 Step 3.
3. **M4 deploy while a firmware is already running (or crashed).** `deploy.sh` and the systemd unit must stop first, and must not fail if it's already `offline`. Covered in Task 3.3 Step 3 and Task 4.3 Step 5.
4. **`dontfall-app` when `/dev/ttyRPMSG0` is missing or the M4 is silent.** Clear message, non-zero exit, no hang. Covered in Task 5.1 tests.
5. **Zephyr version drift.** `west.yml` must pin the same zephyr SHA as meta-zephyr. Covered in Task 3.1 Step 4 (script check) and documented in Task 6.2.

---

## Phase 1 — Cleanup

### Task 1.1: Remove the internal dontfall-app

**Files:**
- Delete (gitignored, not tracked): `DontFall/dontfall-app/`, `DontFall/dontfall-app-build/`
- Modify (untracked build conf): `DontFall/build-stm32mp157c-dk2/conf/local.conf`: remove the 2 `EXTERNALSRC*:pn-dontfall-app` lines and the comment above them
- Modify: `DontFall/.vscode/settings.json`: remove `cmake.sourceDirectory` and the `sources/poky` extraPaths. Keep `files.associations`.
- Modify: `DontFall/README.md`: drop the "clone the app repo into the workspace" paragraph in "Deploying to a running board". Phase 2 rewrites it.

- [ ] **Step 1: Confirm with your human partner, then delete.** Your human partner already asked for this, but `rm -rf` needs explicit confirmation of the exact command:
  `rm -rf /home/lauala-unix/Projects/DontFall/dontfall-app /home/lauala-unix/Projects/DontFall/dontfall-app-build`
- [ ] **Step 2: Edit local.conf, settings.json and README as listed.**
- [ ] **Step 3: Verify parse.**
  Run: `cd ~/Projects/DontFall && ./build.sh bitbake -p </dev/null`
  Expected: exits 0, no errors.
  Run: `./build.sh bitbake -e dontfall-app </dev/null | grep '^EXTERNALSRC='`
  Expected: `EXTERNALSRC=""`.
- [ ] **Step 4: Commit** (`.vscode/` is gitignored, so only README is tracked): `git add README.md && git commit -m "Remove in-workspace dontfall-app checkout references"`

---

## Phase 2 — App repo + dev overrides

### Task 2.1: Scaffold DontFall-app

**Files (repo `~/Projects/DontFall-app`):**
- Create: `CMakeLists.txt`: project `dontfall-app` VERSION `0.1.0`, LANGUAGES CXX, C++17, executable `dontfall-app` from `src/main.cpp`, `APP_VERSION` compile definition, `install(TARGETS ... RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})`. Same content as the deleted internal one.
- Create: `src/main.cpp`: prints `dontfall-app <version> on <uname>` and exits 0. No tick loop, so `--run` returns.
- Create: `.gitignore`: `/build*/`, `/oe-workdir`, `/oe-logs` (externalsrc drops these symlinks into the source dir).
- Modify: `README.md`: what the repo is; dev loop (`cd ../DontFall && ./deploy.sh $BOARD --run /usr/local/bin/dontfall-app`); host build (`cmake -B build && cmake --build build`); release = bump SRCREV in `DontFall/meta-df/meta-df-common/recipes-df/dontfall-app/dontfall-app.bb`.

- [ ] **Step 1: Host build check.**
  Run: `cd ~/Projects/DontFall-app && cmake -B build -G Ninja 2>/dev/null || cmake -B build; cmake --build build && ./build/dontfall-app`
  Expected: prints `dontfall-app 0.1.0 on Linux ...`, exit 0.
- [ ] **Step 2: Commit and push.** `git add -A && git commit -m "Scaffold dontfall-app CMake project" && git push origin main`. Record the commit SHA for Task 2.3.

### Task 2.2: Mount sibling repos into the Yocto container

**Files:**
- Modify: `DontFall/build.sh`: before `docker compose run`, append `-v "$d:$d"` to `args` for each of `"$(cd .. && pwd)/DontFall-app"` and `"$(cd .. && pwd)/DontFall-M4-ws"`, but only if the dir exists.
- Modify: `DontFall/docker-compose.yml`: header comment mentions the optional sibling mounts are added by `build.sh`. The `volumes:` list is unchanged (compose short syntax would create missing host dirs as root).

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Verify the mount when present.**
  Run: `./build.sh ls /home/lauala-unix/Projects/DontFall-app/CMakeLists.txt </dev/null`
  Expected: path printed, exit 0.
- [ ] **Step 3: Verify absence is harmless.**
  Run: `./build.sh true </dev/null && test ! -e /home/lauala-unix/Projects/DontFall-M4-ws && echo OK`
  Expected: `OK` (Phase 3 creates DontFall-M4-ws; until then it must stay absent).
- [ ] **Step 4: Commit:** `git commit -am "build.sh: mount DontFall-app / DontFall-M4-ws when present"`

### Task 2.3: dev.conf mechanism + real app SRC_URI

**Files:**
- Create: `DontFall/meta-df/meta-df-common/conf/templates/default/dev.conf.sample`. Header comment: dev-only overrides; a release build is a build dir without this file. Content:
  ```
  EXTERNALSRC:pn-dontfall-app = "${TOPDIR}/../../DontFall-app"
  ```
- Modify: `.../templates/default/local.conf.sample`: replace the commented `EXTERNALSRC` block with a comment plus `include conf/dev.conf`. `include` is optional and resolves via BBPATH, which starts at TOPDIR.
- Modify (untracked): `build-stm32mp157c-dk2/conf/local.conf`: add the same `include conf/dev.conf` block.
- Create (untracked): `build-stm32mp157c-dk2/conf/dev.conf`, copied from the sample.
- Modify: `DontFall/envsetup.sh`: after `oe-init-build-env`, if `$BUILDDIR/conf/dev.conf` is missing, echo a one-line hint to stderr with the `cp` command. Never create it.
- Modify: `.../recipes-df/dontfall-app/dontfall-app.bb`:
  - `SRC_URI = "git://github.com/LauriAlanen/DontFall-app.git;protocol=https;branch=main"`
  - `SRCREV` = SHA from Task 2.1
  - update the DESCRIPTION to mention `conf/dev.conf`
  - keep `inherit externalsrc cmake` and `prefix = "/usr/local"`
- Modify: `DontFall/deploy.sh`:
  - delete `wire_externalsrc()` and its call loop
  - delete the "Any recipe whose source is checked out..." note from the header
  - keep the default recipe `dontfall-app`
- Modify: `.../templates/default/conf-notes.txt`: one line about `conf/dev.conf`.

**Interfaces:**
- Produces: `conf/dev.conf` as THE place for dev overrides. Later docs (Task 6.2) refer to it.

- [ ] **Step 1: Implement all edits.**
- [ ] **Step 2: Verify the dev build uses the checkout.**
  Run: `./build.sh bitbake -e dontfall-app </dev/null | grep '^EXTERNALSRC='`
  Expected: `EXTERNALSRC="/home/lauala-unix/Projects/DontFall-app"` (path normalized or `${TOPDIR}/../..` form both acceptable).
  Run: `./build.sh bitbake dontfall-app </dev/null`
  Expected: success.
- [ ] **Step 3: Verify the on-board dev loop** (needs `$BOARD`).
  Run: `./deploy.sh $BOARD --run /usr/local/bin/dontfall-app`
  Expected: output contains `dontfall-app 0.1.0 on Linux`.
- [ ] **Step 4: Verify the release build path.**
  Run: `mv build-stm32mp157c-dk2/conf/dev.conf /tmp/dev.conf.bak && ./build.sh bitbake -c fetch dontfall-app </dev/null; rc=$?; mv /tmp/dev.conf.bak build-stm32mp157c-dk2/conf/dev.conf; echo rc=$rc`
  Expected: `rc=0`, and the fetch logs show the GitHub URL.
- [ ] **Step 5: Commit:** `git add meta-df envsetup.sh deploy.sh && git commit -m "Dev overrides via conf/dev.conf; dontfall-app fetches from GitHub"`

---

## Phase 3 — M4 repo standalone

### Task 3.1: West workspace + manifest

**Files:**
- Move: `~/Projects/DontFall-M4` → `~/Projects/DontFall-M4-ws/DontFall-M4` (plain `mv`; same git repo/remote).
- Modify: `~/Projects/dontfall.code-workspace`: M4 folder `path` → `DontFall-M4-ws/DontFall-M4`.
- Create (repo DontFall-M4): `west.yml`:
  ```yaml
  manifest:
    self:
      path: DontFall-M4
    remotes:
      - name: zephyrproject-rtos
        url-base: https://github.com/zephyrproject-rtos
    projects:
      - name: zephyr
        remote: zephyrproject-rtos
        revision: 36940db938a8f4a1e919496793ed439850a221c2
        import:
          name-allowlist: [cmsis, hal_stm32, libmetal, open-amp]
  ```
- Create: `scripts/check-zephyr-pin.sh`: greps the zephyr `revision:` from `west.yml`. If `../../DontFall/meta-zephyr` exists, it also greps `SRCREV_default` from `meta-zephyr-core/recipes-kernel/zephyr-kernel/zephyr-kernel-src-3.7.0.inc`. Prints both and exits non-zero on mismatch. If meta-zephyr is absent, it prints a note and exits 0.
- Create: `.gitignore`: `/build/`.

- [ ] **Step 1: Confirm the `mv` with your human partner, then move, then fix the code-workspace path.**
- [ ] **Step 2: Write `west.yml`, the check script and `.gitignore`.**
- [ ] **Step 3: Commit** (in DontFall-M4): `git commit -m "Add west manifest pinned to meta-zephyr scarthgap Zephyr 3.7"`. The workspace init is verified in Task 3.2.
- [ ] **Step 4: Run the pin check.** `scripts/check-zephyr-pin.sh` → expected: prints the west SHA, a "meta-zephyr not present" note, exit 0. Re-run after Task 4.1; it must then exit 0 with both SHAs equal.

### Task 3.2: Zephyr dev container + init/build scripts

**Files (repo DontFall-M4):**
- Create: `Dockerfile`:
  - base `ubuntu:22.04`; `ARG UID/GID`, user matching the host
  - apt deps per Zephyr's getting-started guide, minus GUI/SDL libs: git cmake ninja-build gperf ccache dfu-util device-tree-compiler wget python3-dev python3-pip python3-setuptools python3-wheel xz-utils file make gcc openssh-client rsync
  - `pip install west`
  - Zephyr SDK `0.16.9` minimal bundle (`zephyr-sdk-0.16.9_linux-x86_64_minimal.tar.xz`) to `/opt/zephyr-sdk-0.16.9`, then `./setup.sh -t arm-zephyr-eabi -c`
  - `ZEPHYR_SDK_INSTALL_DIR` exported
- Create: `docker-compose.yml`: service `zephyr`, image `dontfall-zephyr:latest`. It mounts `${WEST_TOPDIR}` (default: parent of the repo dir) at the same path; `working_dir` = the repo path.
- Create: `scripts/init.sh` (host). It:
  1. resolves `TOPDIR = <repo>/..`
  2. builds the image with host UID/GID
  3. runs in the container, if `$TOPDIR/.west` is missing: `west init -l <repo>`, then `west update --narrow -o=--depth=1`
  4. runs in the container: `pip install --user -r zephyr/scripts/requirements-base.txt`. Alternatively bake this into the Dockerfile layer; either is fine as long as `west build` works.
- Create: `scripts/build.sh`: runs `west build -b stm32mp157c_dk2 app -d build "$@"` in the container (`-T` when no TTY, as in DontFall's `build.sh`).

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Verify init.**
  Run: `cd ~/Projects/DontFall-M4-ws/DontFall-M4 && scripts/init.sh`
  Expected: `~/Projects/DontFall-M4-ws/{.west,zephyr,modules/hal/cmsis,modules/hal/stm32,modules/hal/libmetal,modules/lib/open-amp}` exist; `git -C ../zephyr rev-parse HEAD` = `36940db938a8f4a1e919496793ed439850a221c2`.
- [ ] **Step 3: Commit:** `git add -A && git commit -m "Add Zephyr SDK 0.16.9 dev container and init/build scripts"`. Building the app is verified in Task 3.3.

### Task 3.3: Hello firmware + deploy script

**Files (repo DontFall-M4):**
- Create: `app/CMakeLists.txt`, `app/prj.conf`, `app/boards/stm32mp157c_dk2.overlay`, `app/boards/stm32mp157c_dk2.conf`, `app/src/main.c`. Derive these from zephyr `samples/subsys/ipc/openamp_rsc_table` at the pinned SHA. Copy its overlay and board conf verbatim, then trim:
  - `prj.conf`:
    - keep `CONFIG_IPM=y`, `CONFIG_OPENAMP=y`, `CONFIG_OPENAMP_RSC_TABLE=y`, `CONFIG_OPENAMP_RSC_TABLE_NUM_RPMSG_BUFF=8`, `CONFIG_OPENAMP_MASTER=n`, `CONFIG_HEAP_MEM_POOL_SIZE=1024`, `CONFIG_MAIN_STACK_SIZE=1024`
    - add `CONFIG_PRINTK=y`, `CONFIG_RAM_CONSOLE=y`
    - drop `CONFIG_SHELL*` and `CONFIG_KERNEL_BIN_NAME`, so the output is `zephyr.elf`
  - `main.c`:
    - keep the IPM/virtio/rpmsg setup and the `rpmsg-tty` endpoint
    - drop the `rpmsg-client-sample` thread and the shell backend
    - on each received message, send back `"hello from M4: "` + the received bytes
    - `printk("dontfall-m4 up\n")` at start
- Create: `scripts/deploy.sh <ip> [--log]`:
  - scp `build/zephyr/zephyr.elf` → `root@<ip>:/lib/firmware/dontfall-m4.elf`
  - then over ssh: if state is not `offline`, `echo stop >` state; `echo -n dontfall-m4.elf > .../firmware`; `echo start > .../state`; print the state
  - with `--log`: `sleep 1; cat .../trace0`
  - SSH options as in `DontFall/deploy.sh` (`StrictHostKeyChecking=no`, `UserKnownHostsFile=/dev/null`, `ConnectTimeout=5`, `LogLevel=ERROR`); runs scp/ssh from the host
- Modify: `README.md`: workspace layout, `scripts/init.sh`, the build/deploy loop, how to read logs, the release step (push, then bump `SRCREV` in `DontFall/.../dontfall-m4.bb`), and the pin rule (`scripts/check-zephyr-pin.sh`).

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build.** `scripts/build.sh` → expected: `build/zephyr/zephyr.elf` exists; `file` reports `ELF 32-bit LSB executable, ARM`.
- [ ] **Step 3: On-board check** (needs `$BOARD`). Run `scripts/deploy.sh $BOARD --log` twice in a row (the second run exercises stop-while-running). Expected each time: state `running`, and the log contains `dontfall-m4 up`. Then:
  `ssh root@$BOARD 'ls /dev/ttyRPMSG0 && (timeout 2 cat /dev/ttyRPMSG0 & sleep 0.3; echo hi > /dev/ttyRPMSG0; wait)'`
  Expected: `hello from M4: hi`.
  If `/dev/ttyRPMSG0` is missing, check `dmesg | grep -i rpmsg` and `lsmod | grep rpmsg_tty` before changing code. Use superpowers:systematic-debugging; the likely culprit is a DT memory region mismatch (see the spec's Risks).
- [ ] **Step 4: Commit and push:** `git add -A && git commit -m "Hello firmware: rpmsg-tty echo with RAM console trace; deploy script" && git push origin main`. Record the SHA for Task 4.2.

---

## Phase 4 — Yocto builds Zephyr

### Task 4.1: Add meta-zephyr-core

**Files:**
- Modify: `DontFall/default.xml`:
  - add `<remote name="yocto" fetch="https://git.yoctoproject.org"/>`
  - add `<project name="meta-zephyr" path="meta-zephyr" remote="yocto" revision="f10a17c4b551f92a93ab6e8114b87c2816ed6f36"/>`
- Modify: `.../templates/default/bblayers.conf.sample` and (untracked) `build-stm32mp157c-dk2/conf/bblayers.conf`: add `${WSROOT}/meta-zephyr/meta-zephyr-core` after `meta-st-openstlinux`.

- [ ] **Step 1: Edit `default.xml` and commit it first.** `repo init -u` reads the committed manifest: `git commit -am "Manifest: add meta-zephyr (scarthgap)"`.
- [ ] **Step 2: Sync.** `cd ~/Projects/DontFall && repo init -u "$PWD" -b main -m default.xml --depth=1 && repo sync meta-zephyr` → expected: `meta-zephyr/meta-zephyr-core/conf/layer.conf` exists.
- [ ] **Step 3: Add the layer to both bblayers files.**
- [ ] **Step 4: Verify.** `./build.sh bitbake-layers show-layers </dev/null | grep zephyrcore` → one line. `./build.sh bitbake -p </dev/null` → exit 0. Then `../DontFall-M4-ws/DontFall-M4/scripts/check-zephyr-pin.sh` → exit 0, both SHAs equal.
- [ ] **Step 5: Commit:** `git add meta-df && git commit -m "Enable meta-zephyr-core layer"`

### Task 4.2: M4 machine, multiconfig and dontfall-m4 recipe

**Files:**
- Create: `meta-df/meta-df-stm32mp157c-dk2/conf/machine/stm32mp157c-dk2-m4.conf`:
  - `#@TYPE/#@NAME/#@DESCRIPTION` header
  - `require conf/machine/include/arm/armv7m/tune-cortexm4.inc`
  - `TUNE_FEATURES = "armv7em cortexm4"`
  - `ARCH:stm32mp157c-dk2-m4 = "arm"`
  - `ZEPHYR_BOARD = "stm32mp157c_dk2"`
- Modify: `meta-df/meta-df-common/conf/layer.conf`: add `DF_M4_MC = "zephyr-m4"`, `DF_M4_MACHINE = "stm32mp157c-dk2-m4"`, `DF_M4_TMPDIR = "${TOPDIR}/tmp-zephyr-m4"`.
- Create: `meta-df/meta-df-common/conf/multiconfig/zephyr-m4.conf`:
  - `MACHINE = "${DF_M4_MACHINE}"`
  - `DISTRO = "zephyr"`
  - `TMPDIR = "${DF_M4_TMPDIR}"`
  - `PREFERRED_VERSION_zephyr-kernel = "3.7.0"`
  - `ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"`
- Modify: `local.conf.sample` and (untracked) `local.conf`: `BBMULTICONFIG = "zephyr-m4"`, with a comment.
- Create: `meta-df/meta-df-common/recipes-df/dontfall-m4/dontfall-m4.bb`:
  - SUMMARY; `LICENSE = "CLOSED"`
  - `inherit zephyr-sample`
  - `SRC_URI += "git://github.com/LauriAlanen/DontFall-M4.git;protocol=https;branch=main;name=dfm4;destsuffix=git/dontfall-m4"`
  - `SRCREV_dfm4` = SHA from Task 3.3
  - `SRCREV_FORMAT:append = "_dfm4"`
  - `ZEPHYR_SRC_DIR = "${S}/dontfall-m4/app"`
  - `COMPATIBLE_MACHINE = "${DF_M4_MACHINE}"`
  - a comment that the dev loop is west, not bitbake

**Interfaces:**
- Produces: deploy artifact `${DF_M4_TMPDIR}/deploy/images/${DF_M4_MACHINE}/dontfall-m4-${DF_M4_MACHINE}.elf` (zephyr-image.inc `ZEPHYR_IMAGE_LINK_NAME`). Task 4.3 consumes it.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Verify the mc build.** `./build.sh bitbake mc:zephyr-m4:dontfall-m4 </dev/null` → success. `ls build-stm32mp157c-dk2/tmp-zephyr-m4/deploy/images/stm32mp157c-dk2-m4/dontfall-m4-stm32mp157c-dk2-m4.elf` exists.
  If it fails on distro/layer interaction (ST bbappends or global settings leaking into the zephyr mc), use superpowers:systematic-debugging. Scope fixes to the mc conf, and never weaken the A7 config.
- [ ] **Step 3: Verify the A7 side is unaffected.** `./build.sh bitbake -e virtual/kernel </dev/null | grep -E '^(MACHINE|DISTRO)='` → `stm32mp157c-dk2` / `openstlinux-weston`.
- [ ] **Step 4: Commit:** `git commit -m "Build dontfall-m4 Zephyr firmware via zephyr-m4 multiconfig"` (stage `meta-df`).

### Task 4.3: Firmware package, autostart unit, image install

**Files:**
- Create: `meta-df/meta-df-stm32mp157c-dk2/recipes-df/dontfall-m4-firmware/dontfall-m4-firmware.bb`. It:
  - has `LICENSE = "CLOSED"`, `inherit systemd`
  - sets `do_install[mcdepends] = "mc::zephyr-m4:dontfall-m4:do_deploy"` (written literally, not via `${DF_M4_MC}`; the mcdepends edge also carries the firmware's task hash, so a firmware change re-runs this install)
  - installs `${DF_M4_TMPDIR}/deploy/images/${DF_M4_MACHINE}/dontfall-m4-${DF_M4_MACHINE}.elf` → `${nonarch_base_libdir}/firmware/dontfall-m4.elf` (mode 0644)
  - installs `files/dontfall-m4-start` → `${sbindir}/` (0755) and `files/dontfall-m4.service` → `${systemd_system_unitdir}/`
  - sets `SYSTEMD_SERVICE:${PN} = "dontfall-m4.service"`, `SYSTEMD_AUTO_ENABLE = "enable"`, `PACKAGE_ARCH = "${MACHINE_ARCH}"`, `INHIBIT_PACKAGE_STRIP = "1"`, `INHIBIT_PACKAGE_DEBUG_SPLIT = "1"`, plus whatever QA skips are necessary (expect `arch`; justify each in a comment)
  - adds `COMPATIBLE_MACHINE = "stm32mp157c-dk2"`
  - does NOT use `file-checksums` on the ELF (it doesn't exist at parse time and would cause taskhash mismatches)
- Create: `.../files/dontfall-m4-start` (sh). It:
  - sets `R=/sys/class/remoteproc/remoteproc0`
  - if `$(cat $R/state)` is not `offline`, runs `echo stop > $R/state`
  - runs `echo -n dontfall-m4.elf > $R/firmware`, then `echo start > $R/state`
  - exits non-zero if the state is not `running` after the start
- Create: `.../files/dontfall-m4.service`:
  - `Type=oneshot`, `RemainAfterExit=yes`
  - `ExecStart=/usr/sbin/dontfall-m4-start`
  - `ExecStop=/bin/sh -c 'echo stop > /sys/class/remoteproc/remoteproc0/state'`
  - `WantedBy=multi-user.target`
- Modify: `meta-df/meta-df-common/recipes-core/images/df-image-minimal.bb`: `IMAGE_INSTALL += "dontfall-app"`. The machine-specific package goes in `meta-df-stm32mp157c-dk2` via `IMAGE_INSTALL:append:stm32mp157c-dk2 = " dontfall-m4-firmware"` in the machine conf or in a `df-image-minimal.bbappend` there (builder's choice; it must not be in the machine-agnostic layer unconditionally).

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Verify the package contents.** `./build.sh bitbake dontfall-m4-firmware </dev/null && ./build.sh oe-pkgdata-util list-pkg-files dontfall-m4-firmware </dev/null` → lists `/lib/firmware/dontfall-m4.elf`, `/usr/sbin/dontfall-m4-start`, `.../system/dontfall-m4.service`.
- [ ] **Step 3: Build the image.** `./build.sh </dev/null` (= `bitbake df-image-minimal`) → success. The manifest (`tmp-glibc/deploy/images/stm32mp157c-dk2/df-image-minimal-*.rootfs.manifest`) contains `dontfall-app` and `dontfall-m4-firmware`.
- [ ] **Step 4: On-board check** (needs `$BOARD`). `./deploy.sh $BOARD --image --reboot`, wait for boot, then run `ssh root@$BOARD 'systemctl is-active dontfall-m4; cat /sys/class/remoteproc/remoteproc0/{firmware,state}; ls /dev/ttyRPMSG0'`. Expected: `active`, `dontfall-m4.elf`, `running`, and the device listed.
- [ ] **Step 5: Restart robustness.** `ssh root@$BOARD 'systemctl restart dontfall-m4 && systemctl is-active dontfall-m4'` → `active`.
- [ ] **Step 6: Commit:** `git commit -m "Ship dontfall-m4 firmware with systemd autostart; install app in image"` (stage `meta-df`).

---

## Phase 5 — OpenAMP hello world

### Task 5.1: A7 app talks to the M4

**Files (repo DontFall-app):**
- Modify: `src/main.cpp`: `int main(int argc, char** argv)`. It:
  - uses device `argv[1]`, or `/dev/ttyRPMSG0` if not given
  - opens it `O_RDWR|O_NOCTTY` and applies `cfmakeraw`
  - writes `hello from A7\n`
  - `poll`s up to 2000 ms (accumulating until `\n` or the deadline) and prints the received line to stdout
  - exit codes: `0` on reply; `1` on open failure (message `cannot open <dev>: <strerror>` to stderr); `2` on timeout (message `no reply from M4 within 2s` to stderr)
  - keeps the version banner as the first line
- Create: `tests/test_rpmsg_hello.py` (python3 stdlib only: `pty`, `subprocess`, `os`, `select`). It takes the binary path as argv[1] and runs three cases:
  - `test_reply`: open a pty pair, run the app with the slave path, read `hello from A7\n` from the master, write `hello from M4: hello from A7\n`. Assert exit 0 and stdout contains `hello from M4: hello from A7`.
  - `test_timeout`: never reply. Assert exit 2 within 4 s, and stderr contains `no reply from M4`.
  - `test_missing_device`: path `/nonexistent/tty`. Assert exit 1, and stderr contains `cannot open /nonexistent/tty`.
- Modify: `CMakeLists.txt`:
  - `include(CTest)`
  - `if(BUILD_TESTING)`, find Python3 (Interpreter) and `add_test(NAME rpmsg_hello COMMAND Python3::Interpreter ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_rpmsg_hello.py $<TARGET_FILE:dontfall-app>)`
- Modify: `DontFall/meta-df/meta-df-common/recipes-df/dontfall-app/dontfall-app.bb`: `EXTRA_OECMAKE += "-DBUILD_TESTING=OFF"`.

- [ ] **Step 1: Write the test and the CTest wiring.**
- [ ] **Step 2: Watch it fail.** `cmake -B build && cmake --build build && ctest --test-dir build --output-on-failure` → FAIL (the app doesn't open a device yet).
- [ ] **Step 3: Implement `main.cpp`.**
- [ ] **Step 4: Watch it pass.** Same command → `100% tests passed`.
- [ ] **Step 5: On-board end-to-end** (needs `$BOARD`, image from Task 4.3). `cd ~/Projects/DontFall && ./deploy.sh $BOARD --run /usr/local/bin/dontfall-app` → output contains `hello from M4: hello from A7`.
- [ ] **Step 6: Commit and push the app.** `git commit -am "OpenAMP hello: talk to M4 over /dev/ttyRPMSG0" && git push`. In DontFall, bump `SRCREV` in `dontfall-app.bb` to the new SHA and commit: `git commit -am "dontfall-app: OpenAMP hello world; tests off in recipe"`.

---

## Phase 6 — Tidy up + docs

### Task 6.1: Drop ST example firmware

**Files:**
- Modify: `meta-df/meta-df-stm32mp157c-dk2/conf/machine/stm32mp157c-dk2.conf`: in the M4 copro section, add `M4COPRO_LIST = "m4fwcoredump"` with a comment that `m4projects-stm32mp1` is replaced by `dontfall-m4-firmware`.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Verify.** `./build.sh </dev/null` → success. The new rootfs manifest has no `m4projects-stm32mp1`, and still has `m4fwcoredump`, `dontfall-m4-firmware` and `rpmsg-tools`.
- [ ] **Step 3: On-board check** (needs `$BOARD`). Run `./deploy.sh $BOARD --image --reboot`; the Task 4.3 Step 4 checks must still pass. NOTE: `--image` is additive, so the old `/usr/local/Cube-M4-examples` stay on a board deployed that way. Mention reflashing for a clean check.
- [ ] **Step 4: Commit:** `git commit -am "Drop ST M4 example firmware from the image"`

### Task 6.2: Document the workflow

**Files:**
- Create: `DontFall/docs/development.md`. Sections, matching the spec's "Daily flow":
  - Repos & disk layout (tree)
  - One-time setup (exact commands)
  - A7 app loop
  - M4 loop (incl. `--log` / `trace0`)
  - End-to-end check
  - Release flow (push → bump SRCREVs → build without `conf/dev.conf` → flash or `--image` → commit)
  - Upgrading Zephyr (bump meta-zephyr in `default.xml` and the zephyr SHA in `west.yml` together; run `check-zephyr-pin.sh`)
  - Troubleshooting (no `/dev/ttyRPMSG0` → `dmesg | grep -i rpmsg`, remoteproc state, `trace0`)
- Modify: `DontFall/README.md`: link to `docs/development.md`, remove anything it contradicts, and describe `conf/dev.conf` and the multiconfig in short form.
- Modify: `DontFall/meta-df/README.md`: list the M4 machine, the multiconfig, `dontfall-m4`/`dontfall-m4-firmware`, and the `DF_M4_*` vars.
- Modify: `DontFall-app/README.md`, `DontFall-M4/README.md`: link to `DontFall/docs/development.md` (relative GitHub URL or path note).
- Modify: `DontFall/AGENTS.md`: append a "Build & verify" section:
  - `./build.sh bitbake -p`
  - `./build.sh bitbake mc:zephyr-m4:dontfall-m4`
  - `./build.sh`
  - the app's `ctest`
  - the M4's `scripts/build.sh`
  - board checks need an IP from the human

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Verify the docs against reality.** Run every non-board command in `docs/development.md` verbatim (copy and paste) and confirm each succeeds. Fix the docs, not the commands, unless a command is genuinely wrong.
- [ ] **Step 3: Commit** in each repo: `git commit -m "Document dev and release workflow"`. Push the app and M4 repos; DontFall is pushed only when your human partner asks.
