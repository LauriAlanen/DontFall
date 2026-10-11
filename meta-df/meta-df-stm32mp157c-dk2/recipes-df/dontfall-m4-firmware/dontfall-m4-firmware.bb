SUMMARY = "DontFall Cortex-M4 firmware + autostart"
DESCRIPTION = "Installs the Zephyr firmware built by the zephyr-m4 multiconfig \
(dontfall-m4) as /lib/firmware/dontfall-m4.elf and starts it on remoteproc0 \
at boot."
LICENSE = "CLOSED"

SRC_URI = "file://dontfall-m4-start file://dontfall-m4.service"
S = "${WORKDIR}"

inherit systemd

COMPATIBLE_MACHINE = "stm32mp157c-dk2"
PACKAGE_ARCH = "${MACHINE_ARCH}"

# Build the firmware in the zephyr-m4 multiconfig first. The mcdepends edge
# also carries its task hash, so a firmware change re-runs this install.
do_install[mcdepends] = "mc::${DF_M4_MC}:dontfall-m4:do_deploy"

DF_M4_ELF = "${DF_M4_TMPDIR}/deploy/images/${DF_M4_MACHINE}/dontfall-m4-${DF_M4_MACHINE}.elf"

do_install() {
    install -D -m 0644 ${DF_M4_ELF} ${D}${nonarch_base_libdir}/firmware/dontfall-m4.elf
    install -D -m 0755 ${WORKDIR}/dontfall-m4-start ${D}${sbindir}/dontfall-m4-start
    install -D -m 0644 ${WORKDIR}/dontfall-m4.service ${D}${systemd_system_unitdir}/dontfall-m4.service
}

SYSTEMD_SERVICE:${PN} = "dontfall-m4.service"
SYSTEMD_AUTO_ENABLE = "enable"

FILES:${PN} += "${nonarch_base_libdir}/firmware/dontfall-m4.elf"

# The ELF is Cortex-M4 bare-metal firmware loaded by remoteproc, not a Linux
# binary: don't strip/split it, and skip the Linux-userspace ELF QA checks.
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INHIBIT_SYSROOT_STRIP = "1"
INSANE_SKIP:${PN} += "arch"
