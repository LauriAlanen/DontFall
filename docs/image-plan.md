# Image slimming plan

Working plan for trimming `df-image-minimal` down to what the robot needs.
KPIs: boot time, A7 CPU/RAM left for ROS and the app, free storage.
Update the status and numbers as steps land.

How to measure (board on the LAN, `<ip>`):

```sh
ssh root@<ip> reboot   # time until ssh answers again = "reboot -> ssh"
ssh root@<ip> 'journalctl -b | grep "systemd\[1\]: Startup finished"; df -h /; free'
```

| # | Step | Status |
|---|------|--------|
| 1 | Drop packagegroup-base-extended, syslog, resize InitRD, ST example DTs, extlinux menu | built, needs a reflash to measure |
| 2 | Install only the kernel modules the board uses | todo |
| 3 | Trim the kernel config (debug, unused drivers) | todo |
| 4 | Own distro config instead of `openstlinux-weston` (no wayland/x11/gstreamer/pulseaudio...) | todo, full rebuild |
| 5 | Optional: release image without the Python `ros2` CLI | todo, decide later |

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
  removed packages and the old extlinux.conf would stay on the board.
