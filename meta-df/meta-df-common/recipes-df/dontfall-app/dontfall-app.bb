SUMMARY = "DontFall application"
DESCRIPTION = "DontFall C/C++ application. Release builds fetch from git; \
during development it builds straight out of a local checkout: clone the \
app repo into the workspace (<workspace>/dontfall-app) and ./deploy.sh \
wires EXTERNALSRC up in local.conf automatically."
LICENSE = "CLOSED"

# Release build inputs - fill in the real repo URL and a pinned revision.
# Keep SRCREV at 40 zeros (or a real sha): the git fetcher resolves any
# other value via ls-remote even under externalsrc, so a non-sha like "r0"
# breaks parsing of every recipe, not just this one.
SRC_URI = "git://github.com/YOURORG/dontfall-app.git;protocol=https;branch=main"
SRCREV = "0000000000000000000000000000000000000000"

S = "${WORKDIR}/git"

# externalsrc is a no-op until EXTERNALSRC:pn-dontfall-app is set, so the
# same recipe serves dev checkouts and release builds. If the app does not
# use cmake, swap the class (autotools, meson) or write do_compile yourself.
inherit externalsrc cmake

# Install under /usr/local: app files land on the dedicated userfs partition
# (see meta-df-stm32mp157c-dk2/wic) and never touch the platform rootfs.
prefix = "/usr/local"
