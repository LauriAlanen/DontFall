# ROS 2 tools for the PC: look at what the board publishes.
# Same distro as the board (Jazzy). Built and run by pc/ros.sh.
FROM ros:jazzy-ros-core

RUN apt-get update && apt-get install -y --no-install-recommends \
        ros-jazzy-plotjuggler-ros \
        ros-jazzy-rviz2 \
        ros-jazzy-rviz-imu-plugin \
    && rm -rf /var/lib/apt/lists/*

RUN echo 'source /opt/ros/jazzy/setup.bash' >> /root/.bashrc
