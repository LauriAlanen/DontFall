SUMMARY = "DontFall Cortex-M4 Zephyr firmware"
DESCRIPTION = "OpenAMP remote for the STM32MP157 M4 (rpmsg-tty). Built in the \
zephyr-m4 multiconfig from a pinned DontFall-M4 revision; the dev loop is \
west in the DontFall-M4 repo, not bitbake."
LICENSE = "CLOSED"

inherit zephyr-sample

# Private repo: fetched over ssh with the host ssh-agent (see build.sh).
SRC_URI += "git://git@github.com/LauriAlanen/DontFall-M4.git;protocol=ssh;branch=main;name=dfm4;destsuffix=git/dontfall-m4"
SRCREV_dfm4 = "e0cb7172c559e2699735c9d420ab02f29e71b882"
SRCREV_FORMAT:append = "_dfm4"

ZEPHYR_SRC_DIR = "${S}/dontfall-m4/app"

COMPATIBLE_MACHINE = "${DF_M4_MACHINE}"

# DontFall-M4 carries its own Zephyr patches (patches/zephyr/), also applied
# by its west scripts - one source of truth for dev and release.
do_patch[postfuncs] += "dfm4_apply_zephyr_patches"
dfm4_apply_zephyr_patches() {
    for p in ${S}/dontfall-m4/patches/zephyr/*.patch; do
        [ -e "$p" ] || continue
        bbnote "applying $(basename $p) to zephyr"
        patch -p1 -d ${S}/zephyr < "$p"
    done
}
