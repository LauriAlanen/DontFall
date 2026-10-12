# DontFall development workflow

How the three DontFall repos fit together, how to develop each part against
a running board, and how a release image is produced.

| Repo | Runs on | What it is |
|------|---------|------------|
| [DontFall](https://github.com/LauriAlanen/DontFall) | build host | Yocto/OpenSTLinux integration: `repo` manifest, `meta-df` layers, build + deploy scripts, these docs |
| [DontFall-app](https://github.com/LauriAlanen/DontFall-app) | Cortex-A7, Linux | The application (`/usr/local/bin/dontfall-app`) |
| [DontFall-M4](https://github.com/LauriAlanen/DontFall-M4) | Cortex-M4, Zephyr | Coprocessor firmware (`/lib/firmware/dontfall-m4.elf`) |

The A7 and M4 talk over OpenAMP: the M4 firmware announces an `rpmsg-tty`
channel, which Linux exposes as `/dev/ttyRPMSG0`.

**Principle:** a release image is built by `bitbake df-image-minimal` alone,
from revisions pinned in `meta-df`. Day to day, each part is built from your
working tree and pushed to a running board in seconds - no reflash.

## Disk layout

```
~/Projects/
  DontFall/                    integration repo (+ repo-synced layers, build dir)
    build-stm32mp157c-dk2/
      conf/dev.conf            your dev overrides (not in git)
  DontFall-app/                A7 app repo
  DontFall-M4-ws/              west workspace for the M4 (not a git repo)
    DontFall-M4/               M4 repo
    zephyr/, modules/          pinned by DontFall-M4/west.yml
```

The Yocto container (`DontFall/build.sh`) mounts `DontFall-app` and
`DontFall-M4-ws` at their host paths when they exist.

## One-time setup

```sh
cd ~/Projects
git clone git@github.com:LauriAlanen/DontFall.git
git clone git@github.com:LauriAlanen/DontFall-app.git
mkdir -p DontFall-M4-ws
git clone git@github.com:LauriAlanen/DontFall-M4.git DontFall-M4-ws/DontFall-M4

cd DontFall
./init.sh                                   # repo sync layers + yocto container
./build.sh true                             # creates build-stm32mp157c-dk2 from the template
cp meta-df/meta-df-common/conf/templates/default/dev.conf.sample \
   build-stm32mp157c-dk2/conf/dev.conf      # dev mode: build ../DontFall-app checkout

../DontFall-M4-ws/DontFall-M4/scripts/init.sh   # zephyr container + west update
```

The app and M4 repos are private and bitbake fetches them over ssh using
your ssh-agent (`build.sh` passes it into the container). Unlock your key
before builds that need to fetch:

```sh
eval "$(ssh-agent -s)" && ssh-add
```

Build the image and flash it once (the secure-boot key pair and signing tool
must be in `stm32mp15x-keys/` and `stm32mp15x-signingtool/`, see the
`SIGN_*` settings in `conf/local.conf`):

```sh
./build.sh                                  # bitbake df-image-minimal
```

The board boots with the M4 firmware running (`dontfall-m4.service`) and
`/dev/ttyRPMSG0` present. After that, everything below works over ssh.

## A7 app: edit -> run

```sh
cd ~/Projects/DontFall-app
# edit src/...
cmake -B build && cmake --build build   # host build, catches compile errors

cd ~/Projects/DontFall
./deploy.sh <board-ip> --run 'systemctl restart dontfall-app'
```

With `conf/dev.conf` in place bitbake builds your working tree (externalsrc,
uncommitted changes included) and `deploy.sh` rsyncs it to the board.

## M4 firmware: edit -> run

```sh
cd ~/Projects/DontFall-M4-ws/DontFall-M4
# edit app/...
scripts/build.sh                      # west build in the zephyr container
scripts/deploy.sh <board-ip> --log    # copy, restart M4, print its log
```

`deploy.sh` overwrites `/lib/firmware/dontfall-m4.elf` on the board (the file
the image ships) and restarts remoteproc0; `--log` prints the M4's `printk`
output. Your dev firmware stays until the next image deploy. On the board:

```sh
cat /sys/kernel/debug/remoteproc/remoteproc0/trace0    # M4 log
systemctl restart dontfall-m4                          # reload firmware
```

## End-to-end check

`dontfall-app.service` (started at boot) sends a `PING <seq>` heartbeat to
the M4 every 500 ms over `/dev/ttyRPMSG0`; the M4 answers `PONG <seq>`.
Each side declares the link lost after 1.5 s of silence:

| LED | Blinks while | Proves |
|-----|--------------|--------|
| Orange LD7 (M4, every 500 ms) | the M4 receives PINGs | remoteproc started the M4, rpmsg A7 -> M4 works, the app is alive |
| Blue LD8 (A7, every 1000 ms) | the A7 receives PONGs | rpmsg M4 -> A7 works, the M4 firmware is responsive |

Both blinking = both cores and the link work. Logs on each side:

```sh
journalctl -u dontfall-app         # "M4 link up" / "M4 link lost"
cat /sys/kernel/debug/remoteproc/remoteproc0/trace0   # "heartbeat: A7 link up/lost"
```

`systemctl stop dontfall-app` turns LD7 off within 1.5 s and gives LD8 back
to the kernel heartbeat trigger. Restarting the M4 is fine: the app reopens
`/dev/ttyRPMSG0` when it comes back.

## Release

1. Commit and push `DontFall-app` and/or `DontFall-M4`.
2. In `DontFall`, bump the pins to the pushed commits:
   - `meta-df/meta-df-common/recipes-df/dontfall-app/dontfall-app.bb` -> `SRCREV`
   - `meta-df/meta-df-common/recipes-df/dontfall-m4/dontfall-m4.bb` -> `SRCREV_dfm4`
3. Build without dev overrides (a build dir without `conf/dev.conf` is a
   release build):
   ```sh
   mv build-stm32mp157c-dk2/conf/dev.conf /tmp/dev.conf
   ./build.sh
   mv /tmp/dev.conf build-stm32mp157c-dk2/conf/dev.conf
   ```
   Restore `dev.conf` right after the build - if you forget, later "dev"
   builds silently run as release builds.
4. Flash the wic image, or update a running board:
   `./deploy.sh <board-ip> --image --no-build --reboot`. The image deploy is
   additive: files removed from the image stay on the board until you
   reflash (that's how the ST example firmware lingered).
5. Commit the SRCREV bump in `DontFall`.

The M4 firmware is always built from its pinned revision by bitbake (in the
`zephyr-m4` multiconfig, Zephyr SDK 0.16.9), with the same Zephyr commit,
modules and patches as `west` uses - so what you tested with
`scripts/build.sh` is what ships.

## Upgrading Zephyr

The Zephyr commit is pinned twice and both must match:

- `DontFall/default.xml` -> meta-zephyr revision (its `zephyr-kernel-src-<ver>.inc`)
- `DontFall-M4/west.yml` -> zephyr `revision`
- `zephyr-m4.conf` -> `PREFERRED_VERSION_zephyr-kernel` (must match the inc)

Bump all three together, `repo sync meta-zephyr`, then re-run
`DontFall-M4/scripts/init.sh`. If `west update` fails because our Zephyr
patches left the checkout dirty, reset it first
(`git -C ../zephyr checkout -- .`) - `init.sh` re-applies the patches.
Then check:

```sh
~/Projects/DontFall-M4-ws/DontFall-M4/scripts/check-zephyr-pin.sh
```

Zephyr patches live in `DontFall-M4/patches/zephyr/` and are applied by both
west (`scripts/apply-patches.sh`) and bitbake (`dontfall-m4.bb`); re-check
they still apply after an upgrade.

## Troubleshooting

| Symptom | Check |
|---------|-------|
| No `/dev/ttyRPMSG0` | `cat /sys/class/remoteproc/remoteproc0/state` (should be `running`), `dmesg \| grep -i rpmsg` (expect `creating channel rpmsg-tty`), M4 log in `trace0` |
| M4 `running` but empty `trace0` and no channel | Zephyr stuck before `main()` - make sure `patches/zephyr` were applied (`git -C ~/Projects/DontFall-M4-ws/zephyr status`) |
| Replies echo back endlessly with `cat`/`echo` | tty echo is on: `stty -F /dev/ttyRPMSG0 raw -echo` (the app does this itself) |
| bitbake fetch of DontFall-app/-M4 fails with `Permission denied (publickey)` | ssh-agent not running/unlocked on the host: `eval "$(ssh-agent -s)" && ssh-add` |
| bitbake fetch fails with `Host key verification failed` | github.com missing from `~/.ssh/known_hosts` (mounted into the container): `ssh-keyscan github.com >> ~/.ssh/known_hosts` |
| `/usr/local/bin/dontfall-app: not found` after boot | `/usr/local` (userfs) not mounted: `systemctl status mount-partitions` |
