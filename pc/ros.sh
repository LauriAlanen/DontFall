#!/usr/bin/env bash
# ROS 2 tools on the PC, talking to the board over the LAN (DDS, host
# network). GUI apps show up through WSLg (or X11 on a Linux desktop).
#
#   pc/ros.sh                    shell with ROS sourced
#   pc/ros.sh plotjuggler        live plots (Streaming: ROS 2 Topic Subscriber)
#   pc/ros.sh rviz2              3D view
#   pc/ros.sh teleop             drive with the keyboard (cmd_vel)
#   pc/ros.sh ros2 topic hz /dontfall_m4/imu
#
# Builds the image (pc/Dockerfile) on first use; after changing the
# Dockerfile, rebuild with: docker build -t dontfall-pc-ros pc
set -euo pipefail

IMAGE=dontfall-pc-ros
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -t "$IMAGE" "$DIR"
fi

args=(run --rm --init --network host -e DISPLAY="${DISPLAY:-:0}" -v /tmp/.X11-unix:/tmp/.X11-unix)
# WSLg: Wayland/PulseAudio sockets live under /mnt/wslg
[ -d /mnt/wslg ] && args+=(-v /mnt/wslg:/mnt/wslg -e XDG_RUNTIME_DIR=/mnt/wslg/runtime-dir)
[ -t 0 ] && args+=(-it)

case "${1:-}" in
    "")          set -- bash ;;
    plotjuggler) shift; set -- ros2 run plotjuggler plotjuggler "$@" ;;
    teleop)      shift; set -- ros2 run teleop_twist_keyboard teleop_twist_keyboard "$@" ;;
esac

# --init: ROS tools as PID 1 ignore SIGTERM/Ctrl-C. The ros2 daemon is
# started up front: when a CLI command spawns it on its own, the command
# hangs in this container. It dies with the container.
exec docker "${args[@]}" "$IMAGE" bash -c \
    'source /opt/ros/jazzy/setup.bash && ros2 daemon start >/dev/null && exec "$@"' _ "$@"
