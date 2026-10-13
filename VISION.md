# DontFall vision

What this project is heading towards, and how agents should work on it.
Read this before proposing or building anything. For how to build and
deploy, see `README.md` and `docs/development.md`.

## The robot

DontFall is a two-wheeled self-balancing robot built on the STM32MP157
heterogeneous SoC: a Cortex-M4 running Zephyr for the hard real-time work,
Cortex-A7s running Linux (Yocto/OpenSTLinux) for everything else, linked
by OpenAMP. The robot uses two BLDC motors driven through SimpleFOC
modules, has a USB camera and a screen, and runs ROS 2.

The aim is to use the heterogeneous architecture the way it is meant to be
used and to follow industry practice throughout, not hobby shortcuts.

## Milestones

1. **Stand still.** The robot balances in place without help. This is the
   first major milestone and the hardest part.
2. **After that, open-ended.** Likely directions, in no fixed order:
   - drive it remotely from a web page served by the A7
   - show eyes and fun animations on the screen (personality, not a
     status display)
   - follow a person it detects with the camera (the long-term headline
     goal)

## Architecture

| Part | Runs on | Responsibility |
|------|---------|----------------|
| `DontFall-M4` | Cortex-M4, Zephyr | FOC for both motors, the balance controller, reading the IMU and encoders. Keeps the robot upright on its own. |
| `DontFall-app` and other A7 software | Cortex-A7, Linux | ROS 2, perception and person-following, web UI, screen, high-level behaviour. |
| `DontFall` | build host | Yocto integration: layers, image, build and deploy scripts. |

Principles:

- **Hard real-time lives only on the M4.** FOC (kHz) and balancing (hundreds
  of Hz) never depend on Linux scheduling. Nothing timing-critical runs on
  the A7.
- **The A7 sends only high-level commands**, such as desired forward speed,
  turn rate and mode changes (arm/disarm). It never closes a fast control
  loop over the inter-core link.
- **The M4 is a ROS 2 node.** It shows up on the ROS 2 graph like any other
  node and the A7 talks to it with topics and services. The intended route
  is micro-ROS on Zephyr with an RPMsg transport and the micro-ROS Agent on
  the A7. This replaces today's PING/PONG line protocol over
  `/dev/ttyRPMSG0` and is not built yet.
- **Use standard ROS 2 message types where they fit** (`geometry_msgs/Twist`,
  `sensor_msgs/Imu`, `sensor_msgs/JointState`, diagnostics), so stock
  tooling (`ros2 topic`, rosbag, RViz) works without custom glue. Only
  low-rate telemetry crosses to the A7.
- **Fail safe without the A7.** If commands stop arriving (crash, reboot,
  dead node), the M4 does not keep driving on stale commands. It falls back
  to balancing in place, or disarms safely if it can't balance.
- **Release images stay reproducible**: built by bitbake from pinned
  revisions, as described in `docs/development.md`.

## Where things stand

- **Working:** STM32MP157C-DK2 running the Yocto image with signed boot,
  Zephyr M4 firmware loaded by remoteproc, and an OpenAMP heartbeat
  between the A7 app and the M4 (LD7/LD8 show the link status).
- **On hand, not used yet:** USB camera, and the DK2's own 480x800 DSI
  display (MB1407), which is the robot's screen.
- **Coming soon:** one SimpleFOC board and one BLDC motor, so the first
  motor work is a single motor on the bench.
- **ROS 2 distribution:** Jazzy (LTS), from meta-ros on scarthgap. The
  layers are in the build; nothing ROS is in the image yet.
- **Undecided:** the IMU, encoders/position sensors, the second motor and
  the mechanical build.

Board details and pin usage: `docs/hardware.md`.

## How to work on this project

This is a learning project first. The robot is the north star, not a
deadline.

- **Small, digestible steps.** Keep each change small enough that the
  developer can read and understand all of it in one sitting. Never deliver
  a big feature in one go or dump hundreds of lines at once. Split the work
  and stop between steps.
- **Side quests are real work.** Blinky, demo programs and trying out a
  Zephyr or ROS 2 feature in isolation are legitimate tasks. Do what was
  asked. Don't steer back towards the end goal or add robot features to a
  demo.
- **Explain the why.** The developer wants to learn, so say why a design or
  API is used and what the industry-standard alternative is.
- **Demos still follow the architecture.** They can be small and throwaway,
  but they live in the right repo, build through the existing workflow and
  don't break the principles above (for example, no hard real-time on the
  A7).
- **Don't invent hardware.** If a task needs a pin, sensor, part or wiring
  that isn't decided yet, ask rather than assume. Look up pins in ST's
  GPIO mapping and record every assignment in `docs/hardware.md`.
