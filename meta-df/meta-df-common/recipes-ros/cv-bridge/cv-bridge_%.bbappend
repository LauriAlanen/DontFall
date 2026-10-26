# cv_bridge's package.xml asks for "opencv", which in Yocto is the meta
# package: all ~60 OpenCV modules, highgui with GTK3, python3-opencv and
# the samples. The C++ library only links core, imgproc and imgcodecs, and
# those come in through the shared-library dependencies anyway. numpy is
# only for the Python cv_bridge, which nothing on the board uses.
RDEPENDS:${PN}:remove = "opencv python3-numpy"
