SUMMARY = "DontFall minimal image"
DESCRIPTION = "Minimal bootable DontFall image: kernel, machine essentials \
and a console. Extend with project packages via CORE_IMAGE_EXTRA_INSTALL."
LICENSE = "MIT"

inherit core-image

# ssh access for development; dropbear keeps the footprint small
IMAGE_FEATURES += "ssh-server-dropbear"

# Target-side tools for ./deploy.sh (live deploy over ssh) and for
# devtool deploy-target: rsync receives file trees, openssh-sftp-server +
# openssh-scp let dropbear accept sftp/scp (devtool deploys via scp).
IMAGE_INSTALL += "rsync openssh-sftp-server openssh-scp"

# The DontFall A7 application (/usr/local/bin/dontfall-app). It lives on the
# userfs partition, which ST's mount-partitions service mounts at /usr/local
# on boot (bootfs/vendorfs likewise) - the wic image has no fstab entries.
IMAGE_INSTALL += "dontfall-app systemd-mount-partitions"
