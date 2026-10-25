# SDL2 for dontfall-face (docs/face-plan.md): fullscreen on DRM/KMS with
# OpenGL ES 2.0 from ST's gcnano, no compositor. The default follows the
# distro features (wayland, x11, desktop GL, pulseaudio/pipewire/alsa);
# none of those are used on the robot's screen. Everything here is for the
# board only (class-target), not SDL's native/nativesdk variants.
PACKAGECONFIG:class-target = "kmsdrm gles2 ${@bb.utils.contains('TUNE_FEATURES', 'neon', 'arm-neon', '', d)}"

# udev: SDL finds input devices (the touch panel, step 6) through libudev.
# It dlopen()s libudev.so.1, so the runtime dependency isn't automatic;
# without the library SDL 2.30 crashes on start (segfault after "Failed
# loading libudev.so.1").
DEPENDS:append:class-target = " udev"
RDEPENDS:${PN}:append:class-target = " libudev"

# Link libdrm and libgbm normally instead of dlopen()ing them. SDL's build
# records the file name behind libgbm.so (libgbm.so.1.0.0) as the name to
# dlopen; ST's gcnano has that file only on /vendor/lib, where the loader
# finds libraries by SONAME (libgbm.so.1), so KMS/DRM failed to start and
# SDL fell back to its offscreen driver.
EXTRA_OECMAKE:append:class-target = " -DSDL_KMSDRM_SHARED=OFF"
