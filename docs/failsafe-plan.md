# Commands and fail-safe plan

Working plan for how the A7 commands the M4 and what the M4 does when
those commands stop (VISION.md: "the A7 sends only high-level
commands", "fail safe without the A7"). No motors yet: everything here
works on the IMU alone, and the motor output is a place holder until
`docs/foc-plan.md` provides one. Each step is small and ends with
something you can see working. Update the status as steps land.

Needs room in the firmware first: `docs/hardware.md`, "M4 memory
budget" (a service and a subscription add type support and micro-ROS
entities).

| # | Step | Status |
|---|------|--------|
| 1 | Supervisor state machine as plain C, ztest on the host | done |
| 2 | Supervisor on the M4, its state on a topic (with imu-plan 7d) | done |
| 3 | Arm/disarm service from the A7 | done |
| 4 | `cmd_vel` subscription and the command timeout | done |
| 5 | M4 watchdog (WWDG1) and Linux's crash recovery | done |
| 6 | Retire the PING/PONG protocol on `/dev/ttyRPMSG0` | done |

## The supervisor

One small module on the M4 that owns "may the motors run?". The balance
controller and the motor code ask it; nothing else turns the motors on.
This is the usual split in industry: the controller computes, a separate
and simpler supervisor decides whether its output is used, so the safety
logic stays small enough to test fully.

States, to start (decide the details in step 1):

| State | Motors | Leaves when |
|-------|--------|-------------|
| DISARMED | off | arm request and all arm conditions hold -> ARMED |
| ARMED | controller output | disarm request, or any disarm condition -> DISARMED |

- **Arm conditions:** IMU healthy (`imu_latest()` == `IMU_OK`), tilt
  within a small window of upright (e.g. ±5°: the robot is held up by
  hand), no fault active.
- **Disarm conditions:** IMU fault, tilt beyond a limit (e.g. ±25°;
  the balance plan's simulation will say what can still be caught),
  later: motor driver fault, controller saturated too long.
- **Latched:** after a disarm the M4 never re-arms by itself, also when
  the cause goes away. It needs a new arm request (imu-plan step 7:
  "what happens on a fault is the controller's job, it stays disarmed
  until told otherwise"). The reason for the last disarm is kept and
  reported.
- **Lost commands don't disarm.** A `cmd_vel` older than the timeout
  becomes zero speed: balance in place. Losing the Agent is the same.
  Disarming is for when balancing isn't possible, not for when the A7
  is quiet (VISION.md).
- **Time comes from the M4 clock.** Timeouts use the time a message
  arrived on the M4 (`k_uptime_get`), never the message stamp: the A7
  and M4 clocks differ (imu-plan step 4 saw 0.6 s).

## Step 1: supervisor logic and tests

- `app/src/supervisor.c`/`.h`, plain C with no Zephyr calls, like
  `imu_health.c`: inputs are passed in (now, IMU fault, tilt, arm and
  disarm requests, latest command and when it came), output is the
  state, the disarm reason and the setpoint to use.
- Tests (fail-safe behaviour, so a critical part): arming refused for
  each failed condition; every disarm condition disarms; no automatic
  re-arm after the cause clears; disarm always works, also while
  already disarmed; a stale command gives zero setpoint but stays
  armed; the clock wrapping. Check the tests can fail.
- Decide: the angles, the command timeout (0.5 s is common for
  `cmd_vel`), and whether "arm" while armed is OK or an error.
- Done when: the tests pass on `native_sim`, not in the firmware yet.

Done (DontFall-M4 `app/src/supervisor.c`, `tests/supervisor`, 15 tests).
Decided:

- Arm within ±5° of upright, disarm beyond ±25° (still a guess until
  balance plan step 4), command timeout 0.5 s. Constants in
  `supervisor.h`.
- Arm while armed is accepted (returns OK): a retried service call
  isn't an error.
- Arm refusal reasons are the disarm reasons (`imu`, `tilt`); a refusal
  leaves the last disarm reason alone. Before the first arm the reason
  is `boot`, after a disarm call `request`.
- Inputs are a struct (`now_us`, `imu_ok`, `tilt` in rad on the balance
  axis): the caller maps the IMU to them, so the supervisor doesn't
  depend on `imu_health.h` or the mount. Motor fault and saturation
  later become new fields.
- Same microsecond clock as `imu_health` (`now_us()` in `imu.c`), so
  timeouts are wrap-safe unsigned differences. `supervisor_update()`
  forgets a stale command, else after a 71 min silence the wrapped
  clock would make it look fresh again.
- NaN tilt fails the checks (written as `!(|tilt| <= limit)`); non-finite
  commands are ignored.
- Arming clears the last command: the robot starts from standing still.
- Checked the tests can fail: NaN tilt passing, the stale command kept,
  and an automatic re-arm are each caught.

## Step 2: on the M4, state on a topic

- Call the supervisor from the IMU sampling path (every sample, so it
  reacts within ~1 ms). Nothing to switch off yet: it only computes.
- Print state changes in the trace.
- Publish the state and disarm reason. This is the same question as
  imu-plan step 7d (IMU health to the A7): decide both together, one
  M4 status topic. Options: `diagnostic_msgs/DiagnosticArray` (the
  standard, but strings and arrays: code and RAM), or a small standard
  type per value. Code space from the M4 memory budget decides.
- Done when: pulling the IMU's INT1 wire shows DISARMED with reason
  "imu" on the PC (`ros2 topic echo`).

Done (DontFall-M4: `control.c`, `diag.c`, `uptime_us.h`). How it ended up:

- `control.c` owns the supervisor. `imu.c` doesn't know about it: it
  calls a sample callback that `main.c` passes to `imu_init()`, with the
  time, whether the sample is trusted (healthy and gyro calibrated) and
  roll/pitch. The supervisor gets `tilt_from_upright()` (new in `tilt.c`,
  tested): the total lean in any direction, since the balance axis isn't
  decided on the bench.
- A lost INT1 never calls the callback, so the sampling path alone can't
  notice it. A 10 ms `k_timer` in `control.c` feeds the supervisor "IMU
  not ok" when no sample came for `IMU_HEALTH_TIMEOUT_US`; one spinlock
  serialises both callers.
- State changes are printed (`control: ARMED`, `control: DISARMED
  (tilt)`) from the system work queue, not the sampling path.
- One clock for all modules: `uptime_us()` in `uptime_us.h` (was a static
  in `imu.c`).
- Status topic: `/diagnostics`, `diagnostic_msgs/DiagnosticArray` at 1 Hz,
  reliable (what rqt_robot_monitor subscribes with). Entries
  `dontfall_m4: imu` (OK, or ERROR with the fault) and `dontfall_m4:
  supervisor` ("armed", "disarmed (reason)": WARN after a fault, OK after
  boot or a request). 1 Hz is enough because the disarm latches. This
  closes imu-plan 7d.
- Size: SRAM1 +5.3 KB, RETRAM +3.4 KB to 89.2%, SRAM2 +4.8 KB (the second
  publisher's buffers). Step 3 must free RETRAM first (`docs/hardware.md`).
- Checked on the board: `/diagnostics` shows `imu: ok` and `supervisor:
  disarmed (boot)`, IMU still 850 Hz with 0 errors. Arming only comes in
  step 3, so the INT1 check here is the IMU entry going to ERROR (done,
  it goes to `no data` and back to `ok`); the supervisor's disarm on it
  is checked in step 3.

## Step 3: arm and disarm

- A service `~/arm` of type `std_srvs/srv/SetBool` (true = arm, false =
  disarm), the standard type for this. The response's `success` and
  `message` say why arming was refused. `CONFIG_MICROROS_SERVERS` goes
  up; the executor gets one more handle.
- The service callback runs in the uros thread. It only posts a request
  (atomic flag or `k_msgq`); the supervisor acts on it in the sampling
  path, so there's one writer of the state.
- Bench: `ros2 service call /dontfall_m4/arm std_srvs/srv/SetBool
  "{data: true}"` from the PC with the module flat (accepted) and on its
  side (refused, "tilt"). Tilt it past the limit while armed: DISARMED,
  stays so when put back flat.
- Done when: those three cases behave as described.

Done (DontFall-M4 `uros.c`, `control_arm()`/`control_disarm()`). How it
ended up:

- The service doesn't post a request for the sampling path, as planned
  above: `control_arm()` takes the lock the sampling path and the
  no-sample timer already use and runs `supervisor_arm()` against the
  latest sample right away. The supervisor still has one consistent
  state, and the response can say why arming was refused without a
  round trip between threads. A sample older than the IMU's no-data
  timeout counts as "IMU not ok".
- Responses: `armed`, `disarmed`, `refused: imu`, `refused: tilt`.
  Refusals are also printed in the trace.
- `std_srvs` wasn't built into libmicroros (upstream ignores it): module
  patch 0004.
- Memory: RETRAM went to 89.9% with the service code (~7 KB of rcl/rmw/
  XRCE service support), even after moving libmicroros's rodata out. Now
  the generated message type code is in SRAM1 too (`app/retram.ld`):
  RETRAM 76.4%, SRAM1 65.0%, SRAM2 55.2% (`docs/hardware.md`).
- Checked on the board: `/dontfall_m4/arm` listed, arm with the module
  flat says `armed` and `/diagnostics` shows `armed`, arm again `armed`,
  disarm `disarmed` twice, status `disarmed (request)`.
- Checked by hand too: arm on its side (`refused: tilt`); tilt past
  25 deg while armed (`disarmed (tilt)`, stays so when put back flat);
  pull INT1 while armed (`disarmed (imu)`, step 2's check).

## Step 4: `cmd_vel` and the timeout

- Subscribe to `cmd_vel` (`geometry_msgs/msg/Twist`): linear.x is
  forward speed, angular.z turn rate (REP 103). Topic name `cmd_vel`
  (global), the name every ROS tool uses. Copy it with the arrival time
  under a spinlock, like `imu_latest()`.
- The bench target (reaction wheel) has no use for it, and that's fine:
  this sets up the interface for the two-wheeled robot. Report the
  setpoint in use on the status topic so it can be checked.
- QoS: decide here. Best effort matches a steady stream from teleop and
  never waits; the timeout covers lost messages.
- Bench: `teleop_twist_keyboard` on the PC. The status shows the
  setpoint follow the keys and go to zero ~0.5 s after teleop stops,
  after the Agent is killed, and after the network cable is pulled,
  while the state stays ARMED.
- Done when: those cases pass.

Done (DontFall-M4 `uros.c`, `control_command()`, `diag.c`). How it ended
up:

- `/cmd_vel` (`geometry_msgs/msg/Twist`), best effort. The callback
  hands linear.x and angular.z to `control_command()`, which stamps them
  with `uptime_us()` under the supervisor's lock; the supervisor ignores
  non-finite values and times them out after 0.5 s (step 1).
- The setpoint in use is reported on `/diagnostics`, supervisor status,
  values `speed` and `turn` (3 decimals). At 1 Hz it shows the setpoint
  following and going to zero, not the exact 0.5 s: the host tests cover
  that.
- PC: `pc/ros.sh teleop` (teleop_twist_keyboard added to `pc/Dockerfile`).
- Size: RETRAM +1.8 KB to 79.3% (subscription runtime), SRAM1 +2.6 KB.
- Checked on the board with `ros2 topic pub` at 10 Hz: ignored while
  disarmed (0.000); armed it follows (0.300, -0.500); 0.000 after the
  publisher stops, still armed; 0.000 and still armed after stopping and
  restarting the Agent while commands were flowing.
- Checked from the PC too: teleop (keys move the setpoint, zero
  after letting go), and pulling the network cable while driving.

## Step 5: watchdog

What happens when the M4 itself hangs (e.g. imu-plan step 5's boot
hang)? Today: nothing notices. The industry answer is a hardware
watchdog that resets the core if the code stops kicking it.

- The MP15's window watchdog WWDG1 belongs to the M4 (Zephyr:
  `wwdg1`, `st,stm32-window-watchdog`, disabled now). Linux's
  `m4_rproc` node has an interrupt on EXTI line 68, which ST uses for
  the M4 watchdog: remoteproc sees it as a crash and can restart the
  firmware and write a core dump (`m4fwcoredump` is already in the
  image). Check both in ST's wiki and the reference manual before
  relying on it.
- Kick it where it proves the important work runs, e.g. from the IMU
  sampling path every N samples, not from a thread that only kicks.
  WWDG timeouts are short (tens of ms); work out the window.
- Bench: a temporary build that hangs on purpose after 10 s; see the
  watchdog fire, Linux log the crash, the M4 come back and the topic
  resume. Check the core dump.
- Find out what a watchdog reset resets: imu-plan step 5 found that an
  M4 restart doesn't reset its peripherals. With motors this matters:
  see `docs/foc-plan.md`, safe outputs.
- Done when: a hang is recovered without touching the board.

Done (DontFall-M4 `wdog.c`, Zephyr patch 0005). How it ended up:

- Kicked differently than planned above. Not from the IMU sampling path:
  a lost INT1 stops it while the M4 is fine, and the supervisor already
  handles that (disarmed, reason `imu`); a reset would only lose the
  reason. Instead a task monitor: a thread at the lowest application
  priority kicks every 20 ms, and only if every check-in source checked
  in since the last kick. The one source now is `control.c`'s 10 ms
  timer. So it fires when any thread or ISR spins (the kicker starves),
  when interrupts stay locked or kernel timers stop (no check-in). A
  future FOC interrupt becomes another source.
- Timeout 100 ms (WWDG1 counts PCLK1 104.4 MHz / 4096 / 64: 100.4 ms;
  the most it can do is ~320 ms). The window is open (the driver allows
  a kick at any time). It keeps counting under a debugger: freezing it
  needs DBGMCU, which Linux owns.
- Upstream Zephyr couldn't run WWDG1 on the MP1: its DT clock cell
  (0x800) was SPI2's enable bit, not WWDG1's (0x10000000), and the clock
  driver had no rate for it. Zephyr patch 0005 fixes both.
- Linux side as expected: `m4_rproc` has the "wdg" interrupt (EXTI 68),
  recovery is enabled by default.
- Bench, with a temporary build whose main thread spins after 10 s:
  ~100 ms later `crash detected in m4: type watchdog`, `recovering m4`,
  the firmware restarts, the IMU calibrates again (850 Hz, 0 errors), the
  Agent reconnects and `/diagnostics` is back, `disarmed (boot)`: a
  watchdog reset always comes back disarmed. It repeats every 10 s, as
  it should with that build.
- Core dumps: `systemctl start st-m4coredump` (sets remoteproc's
  coredump to enabled) and ST's udev rule writes one per crash to
  `/var/crash` (~625 KB, all M4 memory). Left off in the image: a crash
  loop would fill the rootfs. Turn it on while debugging.
- Normal build: no watchdog reset over ~2 min of arm/disarm calls, a
  20 Hz `cmd_vel` stream and an Agent restart.
- What a watchdog reset resets: it is an M4-only reset, and Linux then
  stops and restarts the M4 like `remoteproc stop/start`, so peripherals
  keep their state in between. The IMU comes back fine. For motor PWM
  this is what `docs/foc-plan.md` step 5 checks with LEDs.
- Size: SRAM1 +1.3 KB, SRAM2 +0.8 KB (kicker stack), RETRAM unchanged.

## Step 6: retire PING/PONG

VISION.md: micro-ROS replaces the line protocol on `/dev/ttyRPMSG0`.
Once steps 2-4 work, the Agent session already tells each side the
other is alive.

- Remove `protocol.c`, the first rpmsg channel and its A7 side in
  DontFall-app (frees M4 code and RAM).
- Decide here what LD7 and LD8 show then (e.g. LD7: Agent connected,
  from `uros.c`; LD8: an A7 app that sees the M4 status topic), and
  what DontFall-app becomes: probably the A7's ROS 2 node.
- Change both repos and the image in one release.

Done (DontFall-M4, DontFall-app, the image). How it ended up:

- M4: `protocol.c` and the first channel are gone; micro-ROS is the only
  rpmsg channel, so it is now `/dev/ttyRPMSG0` (the Agent unit moved
  with it). `heartbeat.c` became `link_led.c`: LD7 blinks while the
  Agent session is up (set by `uros.c`, which already pings the Agent
  every second).
- DontFall-app is now the A7's ROS 2 node `dontfall_app` (rclcpp): it
  subscribes to `/diagnostics` and LD8 blinks while the M4's statuses
  arrive (3 s timeout, `m4_link.cpp`, no I/O like the old heartbeat
  logic). LD8 so checks the whole chain M4 -> rpmsg -> Agent -> DDS ->
  A7 app.
- Building it in Yocto and running it as a service needed two fixes,
  both in `docs/lessons-learned.md`: the recipe sets up the ROS prefix
  for plain CMake, and the service disables rclcpp's file logger.
- M4 size: SRAM1 -0.7 KB.
- Checked on the board: one tty (`ttyRPMSG0`), both nodes on the graph,
  LD8 blinking; stopping the Agent: app `M4 link lost`, LD8 off; starting
  it: link up again; `systemctl restart dontfall-m4`: tty back as
  `ttyRPMSG0`, Agent follows, app lost then up.

## Open questions

- A local stop button that works without the A7. The DK2's USER1/USER2
  buttons share pins with LD5/LD6; who owns them is not checked yet.
- Whether arming needs more than one ROS call (e.g. hold-to-arm, or arm
  only from the web UI later).
