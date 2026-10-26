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
# decode the camera's JPEGs and run YuNet, ONNX Runtime to run YOLOX, and
# the message types it publishes (vision_msgs, Foxglove's
# ImageAnnotations). OpenCV from pip, not Ubuntu's 4.6: the current YuNet
# model needs 4.8+. numpy stays at Ubuntu's 1.x, which ROS is built with.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3-pip \
        ros-jazzy-foxglove-msgs \
        ros-jazzy-vision-msgs \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --no-cache-dir --break-system-packages \
        onnxruntime==1.20.1 opencv-python-headless==4.10.0.84 'numpy<2'

RUN echo 'source /opt/ros/jazzy/setup.bash' >> /root/.bashrc

# Person detector for pc/vision: YOLOX-Tiny (Apache-2.0, COCO, 416x416),
# pinned by checksum.
ADD --checksum=sha256:427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7 \
    https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx \
    /opt/models/yolox_tiny.onnx
# Face detector for pc/vision: YuNet 2023mar (MIT, OpenCV Zoo), pinned to
# a Hugging Face revision and by checksum.
ADD --checksum=sha256:8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4 \
    https://huggingface.co/opencv/face_detection_yunet/resolve/3cc26e7f1014a5ee5d74a42acee58bafc9d0a310/face_detection_yunet_2023mar.onnx \
    /opt/models/face_detection_yunet_2023mar.onnx
