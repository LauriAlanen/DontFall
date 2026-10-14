# Source the ROS 2 workspace (/opt/ros/<distro>/setup.sh) in login shells so
# ros2 and installed ROS packages are on PATH without manual sourcing.
do_install:append:class-target() {
    install -d ${D}${sysconfdir}/profile.d
    echo "[ -f ${ros_prefix}/setup.sh ] && . ${ros_prefix}/setup.sh" \
        > ${D}${sysconfdir}/profile.d/ros.sh
}

FILES:${PN} += "${sysconfdir}/profile.d/ros.sh"
