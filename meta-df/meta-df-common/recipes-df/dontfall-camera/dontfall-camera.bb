SUMMARY = "Camera service for DontFall"
DESCRIPTION = "Runs gscam on the USB camera whenever it is plugged in: the \
camera's own MJPEG frames are published unchanged as \
sensor_msgs/CompressedImage, so the A7 never decodes or encodes an image \
(docs/archive/camera-plan.md)."
LICENSE = "CLOSED"

SRC_URI = "file://dontfall-camera.service \
    file://camera.yaml \
    file://70-dontfall-camera.rules \
"
S = "${WORKDIR}"

inherit systemd allarch

do_install() {
    install -D -m 0644 ${WORKDIR}/dontfall-camera.service \
        ${D}${systemd_system_unitdir}/dontfall-camera.service
    install -D -m 0644 ${WORKDIR}/camera.yaml ${D}${sysconfdir}/dontfall/camera.yaml
    install -D -m 0644 ${WORKDIR}/70-dontfall-camera.rules \
        ${D}${nonarch_base_libdir}/udev/rules.d/70-dontfall-camera.rules
}

FILES:${PN} += "${nonarch_base_libdir}/udev/rules.d"

SYSTEMD_SERVICE:${PN} = "dontfall-camera.service"
SYSTEMD_AUTO_ENABLE = "enable"

# gscam and the GStreamer elements its pipeline uses: v4l2src
# (plugins-good), videorate (camera.yaml) and appsink, which gscam adds at
# the end itself (plugins-base).
RDEPENDS:${PN} = "gscam gstreamer1.0-plugins-good-video4linux2 \
    gstreamer1.0-plugins-base-videorate gstreamer1.0-plugins-base-app"
