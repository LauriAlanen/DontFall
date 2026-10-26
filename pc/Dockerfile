# syntax=docker/dockerfile:1.6
# ROS 2 tools for the PC: look at what the board publishes.
# Same distro as the board (Jazzy). Built and run by pc/ros.sh.
FROM ros:jazzy-ros-core

RUN apt-get update && apt-get install -y --no-install-recommends \
        ros-jazzy-foxglove-bridge \
        ros-jazzy-plotjuggler-ros \
        ros-jazzy-rviz2 \
        ros-jazzy-rviz-imu-plugin \
        ros-jazzy-teleop-twist-keyboard \
    && rm -rf /var/lib/apt/lists/*

# Vision on the PC (pc/vision, docs/camera-plan.md step 4): OpenCV to
# decode the camera's JPEGs, ONNX Runtime to run the detector, and the
# message types it publishes (vision_msgs, Foxglove's ImageAnnotations).
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3-opencv \
        python3-pip \
        ros-jazzy-foxglove-msgs \
        ros-jazzy-vision-msgs \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --no-cache-dir --break-system-packages onnxruntime==1.20.1

RUN echo 'source /opt/ros/jazzy/setup.bash' >> /root/.bashrc

# Person detector for pc/vision: YOLOX-Tiny (Apache-2.0, COCO, 416x416),
# pinned by checksum.
ADD --checksum=sha256:427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7 \
    https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx \
    /opt/models/yolox_tiny.onnx
