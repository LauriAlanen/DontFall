SUMMARY = "micro-ROS Agent"
DESCRIPTION = "ROS 2 node that wraps eProsima's Micro XRCE-DDS Agent and exposes \
micro-ROS client entities on the ROS 2 graph. Built with MICROROSAGENT_SUPERBUILD=OFF \
against the standalone microxrcedds-agent recipe."
HOMEPAGE = "https://micro.ros.org/"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://package.xml;beginline=9;endline=9;md5=12c26a18c7f493fdc7e8a93b16b7c04f"

inherit ros_distro_jazzy
inherit ros_component

SRC_URI = "git://github.com/micro-ROS/micro-ROS-Agent.git;protocol=https;branch=jazzy"
SRCREV = "7c932329ad5591ef23942ef1962534258c16b000"
S = "${WORKDIR}/git/micro_ros_agent"

# CMakeLists.txt calls find_package(... REQUIRED) for ament_lint_auto and
# ament_cmake_gtest unconditionally (outside BUILD_TESTING), so include them
# alongside the build dependencies.
ROS_BUILD_DEPENDS = "\
    ament-cmake-gtest \
    ament-lint-auto \
    fastcdr \
    fastrtps \
    fastrtps-cmake-module \
    micro-ros-msgs \
    microxrcedds-agent \
    rcutils \
    rmw \
    rmw-dds-common \
    rmw-fastrtps-shared-cpp \
    rosidl-cmake \
    rosidl-runtime-cpp \
    rosidl-typesupport-cpp \
    rosidl-typesupport-fastrtps-cpp \
"

ROS_BUILDTOOL_DEPENDS = "\
    ament-cmake-native \
    rosidl-cmake-native \
"

ROS_EXEC_DEPENDS = "\
    fastcdr \
    fastrtps \
    micro-ros-msgs \
    microxrcedds-agent \
    rcutils \
    rmw \
    rmw-dds-common \
    rmw-fastrtps-shared-cpp \
    rosidl-runtime-cpp \
    rosidl-typesupport-cpp \
    rosidl-typesupport-fastrtps-cpp \
"

DEPENDS = "${ROS_BUILD_DEPENDS} ${ROS_BUILDTOOL_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

EXTRA_OECMAKE += "-DMICROROSAGENT_SUPERBUILD=OFF"

# ament_cmake installs the binary under ${ros_libdir}/micro_ros_agent/micro_ros_agent
# (for `ros2 run micro_ros_agent micro_ros_agent ...`). Also expose `micro-ros-agent`
# in ${ros_bindir} (which /etc/profile.d/ros.sh puts on PATH).
do_install:append() {
    install -d ${D}${ros_bindir}
    ln -s ../${baselib}/${ROS_BPN}/micro_ros_agent ${D}${ros_bindir}/micro-ros-agent
}

ROS_BUILD_TYPE = "ament_cmake"
inherit ros_${ROS_BUILD_TYPE}
