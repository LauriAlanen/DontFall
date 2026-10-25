# Image slimming plan

Retired plan (steps 1-2 done, 3-5 not done) for trimming
`df-image-minimal` down to what the robot needs. KPIs: boot time, A7
CPU/RAM left for ROS and the app, free storage. Retired on 2026-10-25:
after step 2 the image is light enough (systemd ~14 s, 37 MB RAM after
boot, 249 MB rootfs). "Remaining" keeps the ideas if that changes.

How to measure (board on the LAN, `<ip>`):

```sh
ssh root@<ip> reboot   # time until ssh answers again = "reboot -> ssh"
ssh root@<ip> 'journalctl -b | grep "systemd\[1\]: Startup finished"; df -h /; free'
```

| # | Step | Status |
|---|------|--------|
| 1 | Drop packagegroup-base-extended, syslog, resize InitRD, ST example DTs, extlinux menu, `quiet` | done |
| 2 | Install only the kernel modules the board uses | done |
| 3 | Trim the kernel config (debug, unused drivers) | not done |
| 4 | Own distro config instead of `openstlinux-weston` (no wayland/x11/gstreamer/pulseaudio...) | not done |
| 5 | Optional: release image without the Python `ros2` CLI | not done |

Paused after step 2 (2026-10-16), retired 2026-10-25; see "Remaining" at the end.

## Baseline (2026-10-15, before step 1)

- reboot -> ssh: **43.9 s**; systemd: 11.8 s kernel + 14.7 s userspace
  = **26.5 s**. The kernel part includes unpacking and running the
  6.5 MB resize InitRD (~2 s unpack, root mounted from it at 7.7 s);
  U-Boot also waits 2 s in the extlinux menu (not in systemd's number).
- Rootfs: 301 MB used. 1300 packages, 761 of them kernel modules
  (28 MB), Python 108 MB (ROS CLI/launch).
- RAM: 43 MB used after boot.
- Running services the robot doesn't need: avahi, bluetooth (+ brcmfmac
  BT hooks), rpcbind, ALSA state restore, busybox syslogd/klogd next to
  journald.

## Step 1: image contents and boot config

- `df-image-minimal.bb` sets `IMAGE_INSTALL` from `packagegroup-core-boot`
  instead of core-image's default, which also adds
  `packagegroup-base-extended` (every DISTRO/MACHINE feature as packages).
  WiFi, M4 firmware and m4fwcoredump, which came in through it, are listed
  explicitly. `BAD_RECOMMENDATIONS += "busybox-syslog"`.
- Machine conf: `MACHINE_FEATURES:remove = "initrd autoresize"` (the wic
  layout already sizes the rootfs; MMC and ext4 are built into the kernel),
  `UBOOT_EXTLINUX_TIMEOUT = "0"`, no `bluetooth` machine feature, and no
  ST a7/m4-examples device trees or extlinux entries
  (`UBOOT_EXTLINUX_TARGETS_EXTRA_CONFIG[stm32mp157c-dk2] = ""`).
- Packages: 1300 -> 1246. Gone: bluez5, ALSA, avahi, rpcbind, pciutils,
  usbutils, glib, iw, hdparm, gdb-stm32mp-addons, busybox syslogd/klogd,
  the 6.5 MB InitRD. Still in (pulled by systemd/udev/e2fsprogs, later
  steps): util-linux, kbd + vconsole-setup, dbus, bluetooth-suspend (rdep
  of the WiFi/BT firmware), lttng.
- Boot chain settings (TF-A, FIP, U-Boot, `SIGN_*`) are untouched. TF-A
  and FIP still rebuild once, because their task signature includes
  `MACHINE_FEATURES`: same sources, config and key, freshly signed.
- Needs a full reflash: the rootfs deploy in `deploy.sh` is additive, so
  removed packages would stay on the board. (extlinux.conf alone:
  `./deploy.sh <ip> stm32mp-extlinux`.)
- First flash hung at U-Boot's "Enter choice:" prompt: any `MENU` line in
  extlinux.conf (here `MENU BACKGROUND`, from the `splashscreen` machine
  feature) makes U-Boot prompt, and `TIMEOUT 0` then waits forever.
  Dropping `splashscreen` removes the line; with no prompt and no DEFAULT,
  U-Boot boots the first label.
- `quiet` on the kernel command line: kernel and systemd status lines no
  longer go synchronously to the 115200 baud serial console.

Result (two reboots each; first boot after a flash is slower: ldconfig,
journal catalog, dropbear host keys):

| | Baseline | Step 1 |
|---|---|---|
| reboot -> ssh | 43.9 s | 26.4-29.1 s |
| systemd kernel + userspace | 11.8 + 14.7 = 26.5 s | 2.7-3.2 + 11.6-11.8 = 14.3-15.0 s |
| rootfs used | 301 MB | 271 MB |
| RAM used after boot | 43 MB | 40 MB |
| running services | 18 | 13 |

Left after step 1: udev coldplug still loaded the bluetooth, audio and
crypto drivers (~3 s around 12-16 s in the boot log), fixed in step 2.

## Step 2: kernel modules

- Machine conf: `MACHINE_ESSENTIAL_EXTRA_RDEPENDS:remove = "kernel-modules"`
  and an explicit list: rpmsg (M4 link), stm32-dbg-bus, brcmfmac (WiFi),
  ipv6/fq_codel/iptables (systemd-networkd), DSI display + panel, touch,
  GPU (the robot's screen), uvcvideo (USB camera). Dependencies come along
  automatically. Watchdog, USB host/storage, MMC, ext4 and remoteproc are
  built into the kernel.
- Dropped: bluetooth, audio, HDMI bridge/CEC, ADC and timer triggers,
  USB-C controller, HW crypto. Also `BAD_RECOMMENDATIONS` for
  `lttng-modules` (LTTng kernel tracer; ROS 2 tracing is userspace,
  lttng-ust) and `cryptodev-module` (OpenSSL /dev/crypto, unused).
- Verified on the board: M4 link up (dontfall-app), `/dev/ttyRPMSG0/1`,
  WiFi IP, `/dev/dri/card*` (that was only `simpledrm`: the real display
  driver needed `sii902x` too, fixed in face plan step 0), no failed
  units, micro-ROS Agent shows
  `/dontfall_m4/counter`.

| | Baseline | Step 1 | Step 2 |
|---|---|---|---|
| reboot -> ssh | 43.9 s | 26.4-29.1 s | 24.9-26.5 s |
| systemd kernel + userspace | 26.5 s | 14.3-15.0 s | 2.6-3.2 + 10.7-11.0 = 13.3-14.2 s |
| rootfs used | 301 MB | 271 MB | 249 MB |
| RAM used after boot | 43 MB | 40 MB | 37 MB |
| packages (kernel modules) | 1300 (761) | 1246 (761) | 513 (30) |
| /usr/lib/modules | 28 MB | 28 MB | 3.7 MB |

## Remaining

State after step 2: systemd ~13.8 s (kernel ~3 s, userspace ~11 s),
rootfs 249 MB, 37 MB RAM after boot. Userspace is now the bulk.

- **Step 3, kernel config:** `CONFIG_DEBUG_INFO`, `CONFIG_FTRACE` and
  other debug options are on; drivers for hardware the robot doesn't use
  are built in (e.g. ethernet probing takes ~1.8 s in the kernel log).
  Use a config fragment in meta-df, compare `dmesg` timings before/after.
- **Step 4, own distro:** `openstlinux-weston` sets DISTRO_FEATURES
  wayland, opengl, gstreamer, pulseaudio/pipewire, polkit, kde,
  gobject-introspection, bluetooth, nfs, zeroconf... These still shape
  packages through PACKAGECONFIG (systemd, dbus, util-linux, kbd +
  vconsole-setup, bluetooth-suspend from the WiFi/BT firmware). A
  `df` distro based on `openstlinux` with a trimmed feature list rebuilds
  every package (hours) and must keep `optee`, `usrmerge`, `systemd`.
  Also: getty on tty1 and vconsole setup for a VT nobody types on.
  If this is ever done, keep what `docs/archive/face-plan.md` needs: the
  `opengl` distro feature, the GPU (gcnano), libdrm, SDL2 and the
  display/touch kernel modules (DRM/KMS directly, no wayland).
- **Step 5, Python:** ~108 MB of the rootfs is Python for the `ros2` CLI
  and launch. Decide whether a release image drops it (C++ nodes only) and
  a dev image keeps it.
