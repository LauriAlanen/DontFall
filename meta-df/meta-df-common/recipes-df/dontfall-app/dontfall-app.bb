SUMMARY = "DontFall application"
DESCRIPTION = "DontFall C/C++ application for the A7. Release builds fetch \
the pinned SRCREV from git; during development conf/dev.conf points \
EXTERNALSRC at the sibling checkout (~/Projects/DontFall-app) so bitbake \
and ./deploy.sh build your working tree."
LICENSE = "CLOSED"

# Release build input: bump SRCREV to a pushed commit of DontFall-app.
# Private repo: fetched over ssh with the host ssh-agent (see build.sh).
SRC_URI = "git://git@github.com/LauriAlanen/DontFall-app.git;protocol=ssh;branch=main"
SRCREV = "6f53311213a99beb58b6bd98aebc3799921cae89"

S = "${WORKDIR}/git"

# externalsrc is a no-op until EXTERNALSRC:pn-dontfall-app is set (by
# conf/dev.conf), so the same recipe serves dev checkouts and release builds.
inherit externalsrc cmake

# Host-side tests (ctest + python pty) don't belong in the target build.
EXTRA_OECMAKE += "-DBUILD_TESTING=OFF"

# Install under /usr/local: app files land on the dedicated userfs partition
# (see meta-df-stm32mp157c-dk2/wic) and never touch the platform rootfs.
prefix = "/usr/local"
