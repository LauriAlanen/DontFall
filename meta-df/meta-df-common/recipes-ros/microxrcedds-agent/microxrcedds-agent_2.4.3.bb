SUMMARY = "eProsima Micro XRCE-DDS Agent library"
DESCRIPTION = "C++ implementation of the DDS-XRCE Agent (server side), bridging \
Micro XRCE-DDS clients to Fast DDS. Built with the CMake superbuild disabled \
and against meta-ros's fastcdr/fastrtps and meta-oe's spdlog so bitbake fetches \
everything up front."
HOMEPAGE = "https://micro-xrce-dds.docs.eprosima.com/"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=3b83ef96387f14655fc854ddc3c6bd57"

inherit ros_distro_jazzy
inherit ros_component
inherit ros_insane_dev_so

# v2.4.3 is the tag pinned by micro-ROS-Agent (jazzy branch) in its SuperBuild.cmake
SRC_URI = "git://github.com/eProsima/Micro-XRCE-DDS-Agent.git;protocol=https;branch=master \
    file://0001-Logger-support-fmt-9-ostream-formatter.patch \
"
SRCREV = "73622810d984349b80bbac0ef55fc0b694d62222"
S = "${WORKDIR}/git"

DEPENDS = "\
    asio \
    fastcdr \
    fastrtps \
    spdlog \
"

RDEPENDS:${PN} += "\
    fastcdr \
    fastrtps \
    spdlog \
"

# Match the flags micro-ROS-Agent's SuperBuild.cmake passes to xrceagent,
# plus UAGENT_USE_SYSTEM_LOGGER=ON so spdlog is not fetched during do_compile.
EXTRA_OECMAKE += "\
    -DUAGENT_SUPERBUILD=OFF \
    -DUAGENT_USE_SYSTEM_FASTDDS=ON \
    -DUAGENT_USE_SYSTEM_FASTCDR=ON \
    -DUAGENT_USE_SYSTEM_LOGGER=ON \
    -DUAGENT_CED_PROFILE=OFF \
    -DUAGENT_P2P_PROFILE=OFF \
    -DUAGENT_BUILD_EXECUTABLE=OFF \
    -DUAGENT_ISOLATED_INSTALL=OFF \
"

ROS_BUILD_TYPE = "cmake"
inherit ros_${ROS_BUILD_TYPE}
