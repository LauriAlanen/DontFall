# DontFall architecture

What runs where and how the parts talk, as it is now. The why is in
`VISION.md`; pins and memory in `docs/hardware.md`. Keep this file current:
see "Keeping this up to date" at the end.

## The whole system

```
 PC (WSL2, pc/ros.sh: ROS 2 Jazzy container)
   ros2 topic/service, teleop_twist_keyboard, PlotJuggler, RViz,
   foxglove_bridge <-> ws://localhost:8765 <-> Foxglove on Windows
   (layout and panels: DontFall-dashboard)
   pc/vision (pc/ros.sh vision): ROS 2 node dontfall_vision, YOLOX
   people and YuNet faces on the camera; publishes /vision/people,
   /vision/faces and the frames for the PC (/vision/image/compressed)
        ^
        | ROS 2 (DDS) over the LAN
        v
+------------------------------- STM32MP157C-DK2 ------------------------------+
|                                                                             |
|  Cortex-A7, Linux (Yocto df-image-minimal, signed boot)                     |
|  +--------------------------------+   +----------------------------------+  |
|  | micro-ROS Agent                |   | dontfall-app (DontFall-app)      |  |
|  | dontfall-uros-agent.service    |<->| dontfall-app.service             |  |
|  | XRCE-DDS <-> DDS bridge        |DDS| ROS 2 node dontfall_app, watches |  |
|  +---------------+----------------+   | /diagnostics, LD8                |  |
|                  | /dev/ttyRPMSG0     +----------------------------------+  |
|                  |                    +----------------------------------+  |
|                  |                    | dontfall-face (DontFall-app)     |  |
|                  |                    | dontfall-face.service, Nice 10   |  |
|                  |                    | ROS 2 node dontfall_face: eyes   |  |
|                  |                    | on the DSI panel                 |  |
|                  |                    | (SDL2 on KMS, GLES 2.0 on GPU),  |  |
|                  |                    | hears /dontfall_m4/imu,          |  |
|                  |                    | /diagnostics and /vision/faces   |  |
|                  |                    | (from the PC)                    |  |
|                  |                    +----------------------------------+  |
|                  |                    +----------------------------------+  |
|                  |                    | dontfall-camera (meta-df)        |  |
|                  |                    | dontfall-camera.service, Nice 10,|  |
|                  |                    | CPUQuota 25%. gscam: the USB     |  |
|                  |                    | camera's MJPEG (/dev/camera),    |  |
|                  |                    | never decoded, 640x480 @15 on    |  |
|                  |                    | /camera/image_raw/compressed     |  |
|                  |                    +----------------------------------+  |
|                  |                                                          |
|  remoteproc (dontfall-m4.service) loads /lib/firmware/dontfall-m4.elf       |
|  ================= OpenAMP: RPMsg over shared memory ======================  |
|                  |                                                          |
|  Cortex-M4, Zephyr (DontFall-M4)                                            |
|  +---------------+----------------+   +----------------------------------+  |
|  | ipc.c: rpmsg-tty               |   | link_led.c                       |  |
|  | uros.c (prio 5), node          |-->| LD7 on while the Agent session   |  |
|  | dontfall_m4: imu, arm,         |   | is up                            |  |
|  | cmd_vel, diagnostics           |   +----------------------------------+  |
|  +---------------+----------------+                                         |
|                  | arm, cmd_vel ^ state                                     |
|  +---------------v--------------+-+   +----------------------------------+  |
|  | control.c + supervisor.c       |<--| imu.c (prio 7, 850 Hz)           |  |
|  | arm/disarm, tilt limit, IMU    |   | tilt.c filter, imu_health.c      |  |
|  | faults, cmd_vel timeout        |   +----------------+-----------------+  |
|  +--------------------------------+                    | SPI5 + INT1 (EXTI3)|
|  wdog.c: WWDG1, 100 ms, kicked only while every source checks in           |
+--------------------------------------------------------+--------------------+
                                                         |
                                       LSM6DSOX IMU (Arduino Modulino Movement)

 Planned, not built yet (docs/foc-plan.md, docs/balance-plan.md,
 docs/camera-plan.md):
   M4: FOC for the motors and the balance controller, the AS5048A encoder
   on SPI, SimpleFOCMini drivers (PWM).
```

## The ROS 2 interface of the M4

The node `dontfall_m4`, from `uros.c`. This is the only interface between
the A7 and the M4's control code.

| Name | Kind, type | QoS | Direction | Purpose |
|------|------------|-----|-----------|---------|
| `/dontfall_m4/imu` | publisher, `sensor_msgs/Imu` | best effort, 50 Hz | M4 -> A7 | IMU data and tilt estimate |
| `/diagnostics` | publisher, `diagnostic_msgs/DiagnosticArray` | reliable, 1 Hz | M4 -> A7 | IMU and supervisor state |
| `/dontfall_m4/arm` | service, `std_srvs/SetBool` | reliable | A7 -> M4 | arm (true) or disarm (false) |
| `/cmd_vel` | subscription, `geometry_msgs/Twist` | best effort | A7 -> M4 | speed setpoint, zeroed after a timeout |

## The camera's topics

The node `camera` (gscam, `dontfall-camera.service`, parameters in
`/etc/dontfall/camera.yaml`; `docs/camera-plan.md`).

| Name | Type | QoS | Purpose |
|------|------|-----|---------|
| `/camera/image_raw/compressed` | `sensor_msgs/CompressedImage` (`jpeg`) | best effort (SensorDataQoS), 15 Hz | the camera's MJPEG frames, 640x480, ~28 kB each, stamped at capture, frame `camera_optical_frame` |
| `/camera/camera_info` | `sensor_msgs/CameraInfo` | best effort, 15 Hz | lens model; empty until the camera is calibrated (camera plan step 3) |

## The PC vision node's topics

The node `dontfall_vision` (`pc/vision`, runs on the PC only). Nothing on
the robot depends on it.

| Name | Type | QoS | Purpose |
|------|------|-----|---------|
| `/vision/people` | `vision_msgs/Detection2DArray` | reliable, 15 Hz | people in the frame (pixels), with the frame's header and capture stamp |
| `/vision/faces` | `vision_msgs/Detection2DArray` | reliable, 15 Hz | faces, the same way; `dontfall_face` on the board looks at the biggest |
| `/vision/annotations` | `foxglove_msgs/ImageAnnotations` | reliable, 15 Hz | the same boxes, drawn by Foxglove |
| `/vision/image/compressed` | `sensor_msgs/CompressedImage` | best effort, 15 Hz | the frame it looked at, for PC viewers, so the board sends each frame once |

## Who owns what

- **M4:** everything timing critical and every safety decision: the IMU,
  the supervisor (armed or not), the command timeout, the watchdog, and
  later the motors and balancing. It must stay safe with the A7 dead.
- **A7:** the micro-ROS Agent, the app (ROS 2 node `dontfall_app`), the face
  (ROS 2 node `dontfall_face`, only listens), the camera (node `camera`,
  only forwards the camera's own JPEGs), and later everything high
  level. It only sends commands; it never closes a fast loop, and does
  no heavy computation (image processing runs on the PC).
- **PC (`DontFall-dashboard`, `pc/` here):** watching and commanding
  over ROS 2 (Foxglove, PlotJuggler, teleop). A commander like any other;
  nothing on the robot depends on it.
- **Build host (`DontFall`):** Yocto layers in `meta-df`, the image, the
  M4 firmware built by bitbake from pinned revisions (`zephyr-m4`
  multiconfig), build and deploy scripts.

## Keeping this up to date

Update this file in the same change as any architectural change:

- a new or removed process, service, M4 thread or source module with its
  own job
- a new or changed link between parts (tty, RPMsg channel, bus, network)
- a micro-ROS entity added, removed, renamed or with another type or QoS
- a responsibility moving between the M4, the A7 and the PC
- a planned part becoming real (move it out of "Planned")

Bug fixes, tuning and changes inside a module don't need an update.
