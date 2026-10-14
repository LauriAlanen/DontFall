# ROS 2 + micro-ROS bring-up plan

Working plan for getting ROS 2 on the A7 and making the M4 a micro-ROS node
(see VISION.md, "The M4 is a ROS 2 node"). Each step is small and ends with
something you can see working. Update the status as steps land.

| # | Step | Status |
|---|------|--------|
| 1 | Pick the ROS 2 distro, add meta-ros to the build | done (`8a1290f`) |
| 2 | Plain ROS 2 on the A7: talker/listener on the board and from the PC | done |
| 3 | micro-ROS Agent on the A7 image (own recipe) | todo |
| 4 | M4 hello world: micro-ROS publishes a counter over RPMsg (west build) | todo |
| 5 | Build the M4 micro-ROS firmware in bitbake from pinned sources | todo |

## Step 1 findings (done)

- Distro: **Jazzy** (LTS). meta-ros scarthgap supports it until Apr 2028;
  micro-ROS has a Jazzy branch. Lyrical is newer but micro-ROS support for
  it is uncertain.
- meta-ros pinned in `default.xml`; layers `meta-ros-common`, `meta-ros2`,
  `meta-ros2-jazzy`. Dependencies (core, meta-oe, meta-python) were already
  in the build.
- meta-ros turns on Fortran in gcc globally, which would rebuild the
  toolchain and everything. `local.conf` sets
  `ROS_WORLD_SKIP_GROUPS += "fortran"`; only lapack-based maths needs it.
- meta-ros has **no micro-ROS Agent recipe** (only `micro-ros-msgs` and
  diagnostics), so step 3 means writing one.
- meta-ros bbappends some existing recipes (python3, bash, libffi, boost,
  ...), so the first build after adding it rebuilds more than expected.

## Step 2: plain ROS 2 on the A7 (done)

Goal: prove the image, the ROS install, DDS and the network before the M4
is involved, so later failures are clearly on the micro-ROS side.

**2a. Put ROS into the image (done).** In `df-image-minimal.bb`:
`IMAGE_INSTALL += "ros-core demo-nodes-cpp"`. `ros-core` is the smallest
standard ROS set (rclcpp, rclpy, the `ros2` CLI, launch, Fast DDS as
default middleware); `demo-nodes-cpp` has `talker`/`listener`. Build and
flash. First build is long.
- Hit and fixed: rosidl Python bindings compiled against the host's x86_64
  Python headers (`LONG_BIT definition appears wrong`, meta-ros#1205).
  Fixed by overriding `ros_ament_cmake.bbclass` in meta-df-common (see
  README, "ROS 2").
- Hit and fixed: stale git `alternates` in old work dirs pointing at the
  former `build-stm32mp157c-dk2/downloads/` (downloads moved to the
  workspace root). If it recurs: `bitbake -c clean <recipe>`.

**2b. On the board alone** (done):
`ros2 run demo_nodes_cpp talker` / `ros2 run demo_nodes_cpp listener`,
`ros2 topic list`, `ros2 node list` all verified on the board.
- meta-ros installs under `/opt/ros/jazzy` (`ros_opt_prefix.bbclass`), not
  `/usr`. `recipes-ros/ros-workspace/ros-workspace_%.bbappend` installs
  `/etc/profile.d/ros.sh` so login shells source `/opt/ros/jazzy/setup.sh`
  automatically.
- Rootfs usage with `ros-core` + `demo-nodes-cpp`: ~293 MB of 1.6 GB (19%).

**2c. From the PC** (done):
- WSL2 is in mirrored networking mode (`networkingMode=mirrored`), putting
  `eth0` directly on the LAN subnet. Windows enables the Hyper-V firewall
  with `DefaultInboundAction = Block` by default, which drops inbound DDS
  UDP packets from the board; allow it in an Admin PowerShell on Windows:
  `Set-NetFirewallHyperVVMSetting -Name '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -DefaultInboundAction Allow`
- On the PC, use the `ros:jazzy-ros-core` Docker image with `--network host`
  (pass `--no-daemon` for one-shot `docker run` commands so a spawned
  `_ros2_daemon` doesn't hold the container open):
  `docker run --rm --network host ros:jazzy-ros-core bash -c "source /opt/ros/jazzy/setup.bash && ros2 topic echo /chatter std_msgs/msg/String --no-daemon"`
- Verified: `/talker` on the board is discovered on the PC and `/chatter`
  messages arrive over the LAN.

## Step 3: micro-ROS Agent on the A7

- Write a recipe for `micro-ros-agent` (Jazzy branch). Its CMake
  "superbuild" downloads Micro-XRCE-DDS-Agent at build time, which bitbake
  forbids: build with the superbuild off and provide
  `microxrcedds-agent` as its own pinned recipe.
- Run it as `micro-ros-agent serial --dev /dev/ttyRPMSG1`.

## Step 4: M4 micro-ROS hello world (west build)

- Use the `micro_ros_zephyr_module` (Jazzy). Check it builds against our
  pinned Zephyr 3.7.0; don't bump Zephyr for it unless forced (three pins
  move together, see `docs/development.md`).
- No ready-made RPMsg transport: write a micro-ROS **custom transport**
  (framing on) on top of `app/src/ipc.c`, on a **second** `rpmsg-tty`
  endpoint (Linux side `/dev/ttyRPMSG1`). The stock Agent needs no custom
  code that way.
- Keep the PING/PONG heartbeat on the first endpoint during bring-up as a
  known-good reference. Replace it later with micro-ROS liveness
  (`rmw_uros_ping_agent` / command timeout) for the fail-safe.
- MTU: RPMsg buffers are 512 bytes incl. header (~496 payload); keep the
  transport MTU below that.
- Memory: `CONFIG_HEAP_MEM_POOL_SIZE=1024` and `MAIN_STACK_SIZE=1024` are far
  too small; micro-ROS needs tens of KB. Tune stacks, heap and micro-ROS
  static limits.
- Done when: a `std_msgs/Int32` counter from the M4 shows up in
  `ros2 topic echo` on the PC.

## Step 5: M4 micro-ROS in bitbake

The Zephyr module builds libmicroros by running colcon during CMake, which
fetches sources from the network. bitbake blocks network access there and
it breaks reproducibility. Pre-fetch pinned sources (or a separate library
recipe) so `bitbake mc:zephyr-m4:dontfall-m4` builds offline.
