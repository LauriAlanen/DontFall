# Without the target fastcdr and rosidl-typesupport-* packages staged in
# recipe-sysroot, CMake finds the 64-bit fastcdr-config.cmake in
# recipe-sysroot-native and rejects it when cross-compiling for 32-bit ARM
# (same pattern as meta-ros's builtin-interfaces / rmf-*-msgs bbappends).
ROS_BUILD_DEPENDS += "\
    fastcdr \
    fastrtps \
    fastrtps-cmake-module \
    python-cmake-module \
    rosidl-default-runtime \
    rosidl-generator-c \
    rosidl-generator-cpp \
    rosidl-generator-py \
    rosidl-runtime-c \
    rosidl-runtime-cpp \
    rosidl-typesupport-c \
    rosidl-typesupport-cpp \
    rosidl-typesupport-fastrtps-c \
    rosidl-typesupport-fastrtps-c-native \
    rosidl-typesupport-fastrtps-cpp \
    rosidl-typesupport-fastrtps-cpp-native \
    rosidl-typesupport-introspection-c \
    rosidl-typesupport-introspection-cpp \
"

ROS_BUILDTOOL_DEPENDS += "\
    python3-lark-parser-native \
    python3-numpy-native \
    rosidl-adapter-native \
    rosidl-parser-native \
"
