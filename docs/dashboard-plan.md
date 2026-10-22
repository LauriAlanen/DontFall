# PC dashboard plan

Working plan for a visual app on the PC to watch and control the robot:
live plots, state, diagnostics, arm/disarm and driving. The board serves
no web pages; it only offers its interface, and everything visual runs
on the PC on the same network. Each step is small and ends with
something you can see working. Update the status as steps land.

| # | Step | Status |
|---|------|--------|
| 1 | Foxglove bridge in the PC container, see the board's topics | done |
| 2 | A DontFall layout in the repo: plots, diagnostics, raw messages | done |
| 3 | Control from the dashboard: arm/disarm and drive | done |
| 4 | First custom panel: robot status and a big DISARM | |
| 5 | Decide: keep growing panels, or a standalone app | |

## The idea, refined

The board already publishes an API: the ROS 2 graph. Topics, services
and (later) parameters, described in `docs/architecture.md` ("The ROS 2
interface of the M4"), reachable from any machine on the LAN over DDS.
`pc/ros.sh` already uses it for PlotJuggler, RViz and teleop.

So the dashboard doesn't need a second API on the A7 (no REST server,
no custom WebSocket protocol). It speaks ROS 2 like every other tool.
Why this is the better choice:

- **One interface, not two.** A REST API next to the ROS 2 topics would
  have to be kept in sync with them by hand, and every new topic would
  need glue code on the board. With ROS 2 a new topic shows up in the
  dashboard, `ros2 topic`, rosbag and PlotJuggler at once.
- **Nothing extra on the A7.** No web server, no extra recipe in the
  image, no CPU spent on the board serving pages. The A7 keeps its job:
  the Agent, the app, later perception.
- **Industry practice.** Robotics teams look at robots this way: the
  robot publishes ROS topics, the operator's machine runs Foxglove or
  RViz. Web pages served from the robot are for end users, not for
  development and diagnostics.

The common industry tool for this is **Foxglove**: a desktop app with
plots, 3D view, diagnostics, raw message, teleop and service panels,
and custom panels written in TypeScript/React. It talks to ROS through
`foxglove_bridge`, a ROS 2 node that turns the graph into one WebSocket.
`Lichtblick` is the open-source fork of Foxglove Studio with the same
panels and bridge, if the Foxglove account or licence ever gets in the
way.

```
 Windows                         WSL2 (pc/ros.sh container, host network)
 Foxglove / Lichtblick  <------> foxglove_bridge  <---- DDS, LAN ----> board
   ws://localhost:8765             (ROS 2 node)                        (A7 Agent + app,
                                                                         M4 via micro-ROS)
```

The bridge runs on the PC, not on the board: it is one more DDS
subscriber on the LAN like `ros2 topic echo`, so the image stays as it
is. If DDS over Wi-Fi turns out flaky later (multicast discovery often
is), the bridge can move onto the A7 so only one TCP connection crosses
the network. That is a recipe in `meta-df` and a decision for then.

### Safety stays on the M4

The dashboard is a commander like teleop, nothing more. The M4 already
does the right thing when the PC goes away: `cmd_vel` older than
500 ms (`SUPERVISOR_CMD_TIMEOUT_US`) becomes zero speed, and arming is
latched on the M4 (`docs/archive/failsafe-plan.md`). So a crashed
browser tab, a closed laptop or a dropped Wi-Fi can't leave the robot
driving. The dashboard must never be the thing that keeps the robot
safe; a DISARM button is a convenience, not the safety mechanism.

## Step 1: bridge and first look

- Add `ros-jazzy-foxglove-bridge` to `pc/Dockerfile` and a
  `pc/ros.sh bridge` shortcut (`ros2 launch foxglove_bridge
  foxglove_bridge_launch.xml`, default port 8765).
- Install Foxglove (or Lichtblick) on Windows, open a connection to
  `ws://localhost:8765` (WSL2 forwards localhost ports to Windows).
- Done when: `/dontfall_m4/imu` and `/diagnostics` show up in the topic
  list and a Plot panel draws the IMU live.

## Step 2: a DontFall layout

- Build a layout in the app and save it to `pc/foxglove/dontfall.json`,
  so it is in git and anyone (or a new PC) gets the same view.
  Import it with Layouts -> Import from file. Tilt uses Foxglove's
  `.@rpy` path function (quaternion to roll/pitch); the armed badge reads
  `/diagnostics.status[1]`, the supervisor status (`diag.c`).
- Panels: tilt and gyro plots from `/dontfall_m4/imu`, the Diagnostics
  panel on `/diagnostics` (IMU health, supervisor state, disarm
  reason), Raw Messages for poking around, topic rates.
- Learn: how the message types (`sensor_msgs/Imu`,
  `diagnostic_msgs/DiagnosticArray`) look field by field; this is why
  VISION.md insists on standard types: stock panels understand them.

## Step 3: control

- Teleop panel publishing `geometry_msgs/Twist` on `/cmd_vel`, at a
  rate well above 2 Hz so the 500 ms timeout doesn't trip while a key
  is held.
- Service Call panel for `/dontfall_m4/arm` (`std_srvs/SetBool`): arm
  and disarm, and see the reply (refused arm and why).
- Done when: arm, drive, release, watch the setpoint go to zero after
  the timeout on the plot, disarm. All on the bench, no motors.

## Step 4: first custom panel

A Foxglove extension (TypeScript + React, `npm init foxglove-extension`)
with what the stock panels show poorly:

- armed / disarmed as a big coloured badge, the last disarm reason
- a tilt gauge (an artificial horizon) from the IMU
- micro-ROS link up/down (topic age), LD7/LD8 shown on screen
- a DISARM button

Small, one panel, one sitting. This is where the custom "visual app"
starts, without building windows, connections and plotting from
scratch.

## Step 5: panels or a standalone app

Decide once the panels exist and it's clear what's missing. Options:

| Option | Good for | Cost |
|--------|----------|------|
| Keep Foxglove + custom panels | plots, 3D, recording and replay come free | lives inside Foxglove's look and limits |
| Standalone web/desktop app (React or Tauri) on `roslibjs` over the same bridge | full control of the look | rebuild plots, layouts, replay yourself |
| Python desktop app (PySide6 + rclpy) as a ROS 2 node | no bridge, direct DDS | Qt UI work, plotting by hand |

My expectation: Foxglove + panels carries far, and the bridge from
step 1 serves any of the three, so nothing is lost by starting there.

## Later, needs board or M4 work

Not part of this plan; each is its own step in the plan that needs it.

- **Tuning gains live** (balance controller): ROS 2 parameters on the
  M4 cost micro-ROS entities and RAM, check the M4 memory budget in
  `docs/hardware.md`. A service or topic for gains may be cheaper.
- **3D robot model**: a URDF and `robot_state_publisher` on the A7 (or
  PC) and TF from the tilt, so the 3D panel shows the robot leaning.
- **Camera**: `sensor_msgs/CompressedImage` from the A7, shown in an
  Image panel. Mind the Wi-Fi bandwidth.
- **Recording**: `ros2 bag record` on the PC, replayed in Foxglove.
- **Security**: DDS on the LAN is open to anyone on it. Fine at home;
  SROS2 or a fixed `ROS_DOMAIN_ID` when that changes.

## Open questions

- Foxglove (account, free tier) or Lichtblick (fully open source)?
- Where do custom panels live: `pc/` in this repo, or a new sibling
  repo `DontFall-dashboard` once they grow?
