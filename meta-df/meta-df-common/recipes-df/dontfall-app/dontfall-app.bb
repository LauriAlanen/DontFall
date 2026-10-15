SUMMARY = "DontFall application"
DESCRIPTION = "DontFall C/C++ application for the A7. Release builds fetch \
the pinned SRCREV from git; during development conf/dev.conf points \
EXTERNALSRC at the sibling checkout (~/Projects/DontFall-app) so bitbake \
and ./deploy.sh build your working tree. The image starts it at boot via \
dontfall-app.service, which runs it as the A7 <-> M4 heartbeat with blue LD8 as link status."
LICENSE = "CLOSED"

# Release build input: bump SRCREV to a pushed commit of DontFall-app.
# Private repo: fetched over ssh with the host ssh-agent (see build.sh).
SRC_URI = "git://git@github.com/LauriAlanen/DontFall-app.git;protocol=ssh;branch=main \
    file://dontfall-app.service \
"
SRCREV = "a3bf6dae0d437ce0fb43858cb0758ea0e7680a94"

S = "${WORKDIR}/git"

# externalsrc is a no-op until EXTERNALSRC:pn-dontfall-app is set (by
# conf/dev.conf), so the same recipe serves dev checkouts and release builds.
inherit externalsrc cmake systemd

# Host-side tests (ctest + python pty) don't belong in the target build.
EXTRA_OECMAKE += "-DBUILD_TESTING=OFF"

# The unit goes on the rootfs: /usr/local (userfs) is mounted after systemd
# starts, so a unit installed next to the binary would never be enabled.
# prefix=/usr/local also shifts nonarch_base_libdir (root_prefix follows
# exec_prefix under usrmerge), dragging systemd_unitdir and the preset
# dir under /usr/local. Pin it back to the rootfs path.
nonarch_base_libdir = "${@bb.utils.contains('DISTRO_FEATURES', 'usrmerge', '/usr', '', d)}/lib"

do_install:append() {
    install -D -m 0644 ${WORKDIR}/dontfall-app.service ${D}${systemd_system_unitdir}/dontfall-app.service
}

SYSTEMD_SERVICE:${PN} = "dontfall-app.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Install under /usr/local: app files land on the dedicated userfs partition
# (see meta-df-stm32mp157c-dk2/wic) and never touch the platform rootfs.
prefix = "/usr/local"
