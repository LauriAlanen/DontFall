# Lessons learned (for agents)

Only traps that cost a past agent real time. Keep each entry short; add one
only if it saves the next agent significant work.

## Board access

- **Non-login ssh has no ROS env.** `ssh root@<ip> 'ros2 ...'` fails; prefix
  the command with `. /etc/profile;` (it sources `/opt/ros/jazzy/setup.sh`).
- **Board shell is BusyBox.** Short option forms only (`head -n 30`, not
  `head -30`), no `devmem`. Read device tree / sysfs / debugfs instead.
- **Host key changed after a reflash** is expected, not an attack:
  `ssh-keygen -R <ip>`, then reconnect.
- Kill anything you started for a test (e.g. `micro_ros_agent`) before
  handing the board back.

## Yocto

- **Never hand-write patches.** Edit the unpacked source under git and use
  `git format-patch`/`git diff`; hand-made patches failed to apply.

## M4 (Zephyr)

- **Memory layout / resource table:** read `docs/ros-plan.md` step 4 before
  touching the linker or `zephyr,flash`/`zephyr,sram`. `CONFIG_XIP=n` looks
  simpler but the heap overwrites `.resource_table` (rpmsg fails with -2).
- **micro-ROS client does nothing until Linux writes first** on
  `/dev/ttyRPMSG1` (`printf '\n' > /dev/ttyRPMSG1` before the Agent).
