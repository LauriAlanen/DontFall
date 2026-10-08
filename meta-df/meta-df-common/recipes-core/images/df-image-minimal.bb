SUMMARY = "DontFall minimal image"
DESCRIPTION = "Minimal bootable DontFall image: kernel, machine essentials \
and a console. Extend with project packages via CORE_IMAGE_EXTRA_INSTALL."
LICENSE = "MIT"

inherit core-image

# ssh access for development; dropbear keeps the footprint small
IMAGE_FEATURES += "ssh-server-dropbear"
