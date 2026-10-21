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
| 1 | Supervisor state machine as plain C, ztest on the host | todo |
| 2 | Supervisor on the M4, its state on a topic (with imu-plan 7d) | todo |
| 3 | Arm/disarm service from the A7 | todo |
| 4 | `cmd_vel` subscription and the command timeout | todo |
| 5 | M4 watchdog (WWDG1) and Linux's crash recovery | todo |
| 6 | Retire the PING/PONG protocol on `/dev/ttyRPMSG0` | todo |

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

## Open questions

- A local stop button that works without the A7. The DK2's USER1/USER2
  buttons share pins with LD5/LD6; who owns them is not checked yet.
- Whether arming needs more than one ROS call (e.g. hold-to-arm, or arm
  only from the web UI later).
