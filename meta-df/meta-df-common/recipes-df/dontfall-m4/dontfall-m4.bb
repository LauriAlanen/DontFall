SUMMARY = "DontFall Cortex-M4 Zephyr firmware"
DESCRIPTION = "OpenAMP remote for the STM32MP157 M4 (rpmsg-tty). Built in the \
zephyr-m4 multiconfig from a pinned DontFall-M4 revision; the dev loop is \
west in the DontFall-M4 repo, not bitbake."
LICENSE = "CLOSED"

inherit zephyr-sample

# Private repo: fetched over ssh with the host ssh-agent (see build.sh).
SRC_URI += "git://git@github.com/LauriAlanen/DontFall-M4.git;protocol=ssh;branch=main;name=dfm4;destsuffix=git/dontfall-m4"
SRCREV_dfm4 = "9ba58b69109db7957d7af636c4bab89ed8fef237"
SRCREV_FORMAT:append = "_dfm4"

ZEPHYR_SRC_DIR = "${S}/dontfall-m4/app"

# micro-ROS: the module and the sources libmicroros is built from, at the
# paths west uses (app/CMakeLists.txt expects that layout). Fetched here,
# so the build itself needs no network.
require micro-ros-srcs.inc

# meta-zephyr passes this as -DZEPHYR_EXTRA_MODULES, and Zephyr prefers that
# cache value over the list app/CMakeLists.txt appends to - so repeat the
# module path here.
ZEPHYR_EXTRA_MODULES = "${S}/modules/lib/micro_ros_zephyr_module/modules/libmicroros"

# libmicroros.mk builds the sources with colcon (host tools; zephyr-m4.conf
# enables -native for the colcon extensions).
DEPENDS += "\
    python3-colcon-core-native \
    python3-colcon-cmake-native \
    python3-colcon-library-path-native \
    python3-colcon-metadata-native \
    python3-colcon-package-information-native \
    python3-colcon-package-selection-native \
    python3-colcon-parallel-executor-native \
    python3-colcon-pkg-config-native \
    python3-colcon-python-setup-py-native \
    python3-colcon-recursive-crawl-native \
    python3-colcon-ros-native \
    python3-colcon-test-result-native \
    python3-catkin-pkg-native \
    python3-pyparsing-native \
    python3-dateutil-native \
    python3-docutils-native \
    python3-empy-native \
    python3-lark-parser-native \
"

# libmicroros.mk builds inside the module's source tree and skips steps
# whose output dir exists, so a failed or older build would be reused.
do_configure:prepend() {
    cd ${S}/modules/lib/micro_ros_zephyr_module/modules/libmicroros
    rm -rf micro_ros_dev micro_ros_src include libmicroros.a
    cd -
}

# colcon configures the host-side ament tools (micro_ros_dev) with the
# environment's compiler, which OE sets to a target cross compiler. Zephyr's
# own build fixed its compilers at configure time, and libmicroros is
# cross-built with an explicit toolchain file, so only the host build sees this.
do_compile:prepend() {
    export CC="${BUILD_CC}" CXX="${BUILD_CXX}"
}

COMPATIBLE_MACHINE = "${DF_M4_MACHINE}"

# DontFall-M4 carries its own Zephyr and micro-ROS module patches
# (patches/zephyr/, patches/micro_ros_zephyr_module/), also applied by its
# west scripts - one source of truth for dev and release.
do_patch[postfuncs] += "dfm4_apply_zephyr_patches"
dfm4_apply_zephyr_patches() {
    for p in ${S}/dontfall-m4/patches/zephyr/*.patch; do
        [ -e "$p" ] || continue
        bbnote "applying $(basename $p) to zephyr"
        patch -p1 -d ${S}/zephyr < "$p"
    done
    for p in ${S}/dontfall-m4/patches/micro_ros_zephyr_module/*.patch; do
        [ -e "$p" ] || continue
        bbnote "applying $(basename $p) to micro_ros_zephyr_module"
        patch -p1 -d ${S}/modules/lib/micro_ros_zephyr_module < "$p"
    done
}
