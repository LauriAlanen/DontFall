# ROS 2 + micro-ROS bring-up plan

Working plan for getting ROS 2 on the A7 and making the M4 a micro-ROS node
(see VISION.md, "The M4 is a ROS 2 node"). Each step is small and ends with
something you can see working. Update the status as steps land.

| # | Step | Status |
|---|------|--------|
| 1 | Pick the ROS 2 distro, add meta-ros to the build | done (`8a1290f`) |
| 2 | Plain ROS 2 on the A7: talker/listener on the board and from the PC | done |
| 3 | micro-ROS Agent on the A7 image (own recipe) | done |
| 4 | M4 hello world: micro-ROS publishes a counter over RPMsg (west build) | done |
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

## Step 3: micro-ROS Agent on the A7 (done)

- Two own recipes in `meta-df-common/recipes-ros/`:
  - `microxrcedds-agent_2.4.3.bb`: the Micro XRCE-DDS Agent library at the
    tag micro-ROS-Agent's `SuperBuild.cmake` pins (v2.4.3), superbuild off,
    against meta-ros's Fast DDS/Fast CDR and meta-oe's spdlog.
  - `micro-ros-agent_5.0.2.bb`: micro-ROS-Agent `jazzy` branch with
    `MICROROSAGENT_SUPERBUILD=OFF`. Adds `/opt/ros/jazzy/bin/micro-ros-agent`
    (symlink) so it runs without `ros2 run`.
- Hit and fixed: the Agent was written for spdlog 1.9 / fmt 8; meta-oe has
  fmt 10, which no longer formats types via `operator<<`. A patch adds
  `fmt::ostream_formatter` specializations for the logged types (endpoints,
  `TransportAddress`, Fast DDS GUID/entity/sample ids).
- Hit and fixed: `micro-ros-msgs` (meta-ros) didn't cross-compile, CMake
  picked the native 64-bit fastcdr config. A bbappend adds the target
  rosidl/fastcdr build deps (same pattern as meta-ros's msgs bbappends).
- Verified on the board: `micro-ros-agent udp4 --port 8888 -v4` starts
  and logs. A real client comes with step 4; run it then as
  `micro-ros-agent serial --dev /dev/ttyRPMSG1` (that endpoint doesn't
  exist yet).

## Step 4: M4 micro-ROS hello world (west build) (done)

Node `dontfall_m4` on the M4 publishes a `std_msgs/Int32` counter on
`/dontfall_m4/counter` once a second; verified with `ros2 topic echo` on
the PC. Code in DontFall-M4: `app/src/uros.c` (node),
`app/src/uros_transport.c` (transport).

- **Memory layout first.** The M4's "flash" was RETRAM (64 KB) and already
  half full; micro-ROS needs ~60 KB more code. Code now runs from MCU SRAM1
  (128 KB, `zephyr,flash`), data from SRAM2 (128 KB, `zephyr,sram`), and
  only the vector table stays at address 0 in RETRAM
  (`CONFIG_ROMSTART_RELOCATION_ROM`, made for remoteproc). Same split as
  ST's Cube examples; the MPU maps code read-only and data execute-never.
  With micro-ROS: code ~95 KB of 128 KB, static RAM ~45 KB, the rest of
  SRAM2 is the malloc arena micro-ROS allocates from.
  - Tried and rejected: `CONFIG_XIP=n` (everything in one RAM region). It
    puts `.resource_table` after `_end`, where the libc malloc arena starts,
    and the heap overwrites it: rpmsg setup fails and reading the table in
    debugfs oopses the kernel.
- `micro_ros_zephyr_module` (jazzy) pinned in DontFall-M4's `west.yml`. It
  builds fine against our Zephyr 3.7.0 (upstream tests 4.x), so no Zephyr
  bump. The container now has colcon, catkin_pkg, lark and empy (pinned).
  A small patch (`patches/micro_ros_zephyr_module/`) adds a "custom"
  transport choice, since the module only offers UART/USB/UDP.
  `CONFIG_STATIC_INIT_GNU=y` is needed (libmicroros has constructors).
- **Transport:** custom transport with framing on a **second** `rpmsg-tty`
  endpoint (`/dev/ttyRPMSG1`), stock Agent in serial mode. Revised from the
  original plan: with framing it is a byte stream, so the 496-byte rpmsg
  payload does not limit the XRCE MTU (left at 512).
- **The A7 must write first.** rpmsg learns Linux's endpoint address only
  from a first message, and an XRCE-DDS client always speaks first, so
  nothing moves until Linux writes once. Start the Agent with:
  `printf '\n' > /dev/ttyRPMSG1; micro-ros-agent serial --dev /dev/ttyRPMSG1`
  (bytes outside a frame are ignored). A future Agent service needs this
  as an `ExecStartPre`.
- The PING/PONG heartbeat on `/dev/ttyRPMSG0` still runs alongside.
- **Reconnect:** while connected the M4 pings the Agent once a second
  (`rmw_uros_ping_agent`); on a missed ping it destroys everything (session
  destroy timeout 0, the Agent is gone), flushes the rx pipe and goes back
  to waiting for the Agent. A restarted Agent has forgotten the session, so
  node/publisher are created again. Linux keeps the rpmsg endpoint across
  Agent restarts, so only the first Agent start after each M4 boot needs
  the `printf`.
  Verified on the board: three Agent kill/restart cycles, counter resumes
  each time without restarting the M4.
- Not done yet: micro-ROS liveness for the fail-safe (command timeouts,
  acting on "Agent lost").

## Step 5: M4 micro-ROS in bitbake

The Zephyr module builds libmicroros by running colcon during CMake, which
`git clone`s ~20 repos at their **branch heads** (`libmicroros.mk`), so
even the west build is not pinned below the module itself. bitbake blocks
network access there and it breaks reproducibility. The recipe also has to
apply `patches/micro_ros_zephyr_module/` and add the module path that
`app/CMakeLists.txt` assumes from the west layout. Pre-fetch pinned sources (or a separate library
recipe) so `bitbake mc:zephyr-m4:dontfall-m4` builds offline.
