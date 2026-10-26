SUMMARY = "DontFall application"
DESCRIPTION = "DontFall C/C++ application for the A7. Release builds fetch \
the pinned SRCREV from git; during development conf/dev.conf points \
EXTERNALSRC at the sibling checkout (~/Projects/DontFall-app) so bitbake \
and ./deploy.sh build your working tree. The image starts it at boot via \
dontfall-app.service: the A7's ROS 2 node, with blue LD8 showing whether \
the M4's /diagnostics arrive, and dontfall-face.service: the robot's eyes on \
the DSI panel (ROS 2 node dontfall_face)."
LICENSE = "CLOSED"

# Release build input: bump SRCREV to a pushed commit of DontFall-app.
# Private repo: fetched over ssh with the host ssh-agent (see build.sh).
SRC_URI = "git://git@github.com/LauriAlanen/DontFall-app.git;protocol=ssh;branch=main \
    file://dontfall-app.service \
    file://dontfall-face.service \
"
SRCREV = "18d29989151511e073f72ad9b1cc55678c43cce0"

S = "${WORKDIR}/git"

# externalsrc is a no-op until EXTERNALSRC:pn-dontfall-app is set (by
# conf/dev.conf), so the same recipe serves dev checkouts and release builds.
inherit externalsrc cmake systemd

# Host-side tests don't belong in the target build.
EXTRA_OECMAKE += "-DBUILD_TESTING=OFF"

# A ROS 2 node (rclcpp, diagnostic_msgs from meta-ros). Plain CMake, not
# ros_ament_cmake: that class installs under /opt/ros. Instead point CMake
# and ament at the ROS prefix in the sysroots, like the class does.
# ament's CMake config runs Python at configure time, with its
# ament_package module from the native ROS prefix.
ROS_PREFIX = "/opt/ros/jazzy"
DEPENDS = "rclcpp diagnostic-msgs sensor-msgs ament-cmake-native"
# dontfall-face: faces seen by the PC vision node (/vision/faces)
DEPENDS += "vision-msgs"
# dontfall-face: SDL2 (KMS/DRM) and OpenGL ES 2.0 (ST's gcnano)
DEPENDS += "libsdl2 virtual/libgles2"
inherit python3native
export PYTHONPATH = "${STAGING_DIR_NATIVE}${ROS_PREFIX}/lib/${PYTHON_DIR}/site-packages"
export AMENT_PREFIX_PATH = "${STAGING_DIR_HOST}${ROS_PREFIX};${STAGING_DIR_NATIVE}${ROS_PREFIX}"
EXTRA_OECMAKE += "-DCMAKE_PREFIX_PATH='${STAGING_DIR_HOST}${ROS_PREFIX};${STAGING_DIR_NATIVE}${ROS_PREFIX}'"

# The unit goes on the rootfs: /usr/local (userfs) is mounted after systemd
# starts, so a unit installed next to the binary would never be enabled.
# prefix=/usr/local also shifts nonarch_base_libdir (root_prefix follows
# exec_prefix under usrmerge), dragging systemd_unitdir and the preset
# dir under /usr/local. Pin it back to the rootfs path.
nonarch_base_libdir = "${@bb.utils.contains('DISTRO_FEATURES', 'usrmerge', '/usr', '', d)}/lib"

do_install:append() {
    install -D -m 0644 ${WORKDIR}/dontfall-app.service ${D}${systemd_system_unitdir}/dontfall-app.service
    install -D -m 0644 ${WORKDIR}/dontfall-face.service ${D}${systemd_system_unitdir}/dontfall-face.service
}

SYSTEMD_SERVICE:${PN} = "dontfall-app.service dontfall-face.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Install under /usr/local: app files land on the dedicated userfs partition
# (see meta-df-stm32mp157c-dk2/wic) and never touch the platform rootfs.
prefix = "/usr/local"
