SUMMARY = "micro-ROS Agent service for the DontFall M4"
DESCRIPTION = "Runs the micro-ROS Agent on /dev/ttyRPMSG1 whenever the M4's \
rpmsg tty exists, so the M4's micro-ROS node shows up on the ROS 2 graph \
without starting the Agent by hand."
LICENSE = "CLOSED"

SRC_URI = "file://dontfall-uros-agent.service"
S = "${WORKDIR}"

inherit systemd allarch

do_install() {
    install -D -m 0644 ${WORKDIR}/dontfall-uros-agent.service \
        ${D}${systemd_system_unitdir}/dontfall-uros-agent.service
}

SYSTEMD_SERVICE:${PN} = "dontfall-uros-agent.service"
SYSTEMD_AUTO_ENABLE = "enable"

RDEPENDS:${PN} = "micro-ros-agent"
