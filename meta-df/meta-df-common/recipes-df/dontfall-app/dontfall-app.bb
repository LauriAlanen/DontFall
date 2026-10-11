SUMMARY = "DontFall application"
DESCRIPTION = "DontFall C/C++ application for the A7. Release builds fetch \
the pinned SRCREV from git; during development conf/dev.conf points \
EXTERNALSRC at the sibling checkout (~/Projects/DontFall-app) so bitbake \
and ./deploy.sh build your working tree."
LICENSE = "CLOSED"

# Release build input: bump SRCREV to a pushed commit of DontFall-app.
SRC_URI = "git://github.com/LauriAlanen/DontFall-app.git;protocol=https;branch=main"
SRCREV = "cd832bc16217fe084fb6cbba3ab8b342f044d65a"

S = "${WORKDIR}/git"

# externalsrc is a no-op until EXTERNALSRC:pn-dontfall-app is set (by
# conf/dev.conf), so the same recipe serves dev checkouts and release builds.
inherit externalsrc cmake

# Install under /usr/local: app files land on the dedicated userfs partition
# (see meta-df-stm32mp157c-dk2/wic) and never touch the platform rootfs.
prefix = "/usr/local"
