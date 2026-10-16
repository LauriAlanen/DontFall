SUMMARY = "DontFall minimal image"
DESCRIPTION = "Minimal bootable DontFall image: kernel, machine essentials, \
WiFi, ssh, the DontFall app and M4 firmware, and ROS 2."
LICENSE = "MIT"

inherit core-image

# Start from packagegroup-core-boot only (kernel, systemd, udev, busybox).
# core-image's default also pulls packagegroup-base-extended, which turns
# every DISTRO/MACHINE feature into packages and services: bluetooth, ALSA,
# avahi, NFS/rpcbind, PCI, usbutils, ... The robot needs
# none of them, and each costs boot time, RAM and rootfs space. What we do
# need from it is listed explicitly below.
IMAGE_INSTALL = "packagegroup-core-boot"

# /etc/build (build info); ST's image class warns when it is missing.
IMAGE_INSTALL += "build-info-openstlinux"

# journald already collects all logs; busybox syslogd/klogd only duplicate
# them (core-boot recommends them).
BAD_RECOMMENDATIONS += "busybox-syslog"

# ssh access for development; dropbear keeps the footprint small
IMAGE_FEATURES += "ssh-server-dropbear"

# Target-side tools for ./deploy.sh (live deploy over ssh) and for
# devtool deploy-target: rsync receives file trees, openssh-sftp-server +
# openssh-scp let dropbear accept sftp/scp (devtool deploys via scp).
IMAGE_INSTALL += "rsync openssh-sftp-server openssh-scp"

# WiFi (on-board BCM43430, brcmfmac): firmware, regulatory db and
# wpa_supplicant, which auto-connects (wpa-supplicant bbappend).
IMAGE_INSTALL += "linux-firmware-addons-bcm43xx wireless-regdb-static wpa-supplicant"

# M4 firmware + its autostart service, and M4 crash dumps. These used to
# come in through packagegroup-base-extended (MACHINE_EXTRA_RRECOMMENDS).
IMAGE_INSTALL += "dontfall-m4-firmware m4fwcoredump"

# The DontFall A7 application (/usr/local/bin/dontfall-app). It lives on the
# userfs partition, which ST's mount-partitions service mounts at /usr/local
# on boot (bootfs/vendorfs likewise) - the wic image has no fstab entries.
IMAGE_INSTALL += "dontfall-app systemd-mount-partitions"

# ROS core, demo nodes, and micro-ROS Agent (bridges M4 over RPMsg to ROS 2)
IMAGE_INSTALL += "ros-core demo-nodes-cpp micro-ros-agent"
