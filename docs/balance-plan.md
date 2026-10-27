# Balance controller plan

Working plan for the balance controller before there are motors: design
and test it against a simulated robot on the host, then run it on the M4
with the real IMU and no motor. The target is the first bench robot from
`docs/hardware.md`: a free-standing reaction wheel balancer (one motor
with a flywheel on top, tilt from the IMU). Each step is small and ends
with something you can see. Update the status as steps land.

Uses the supervisor from `docs/archive/failsafe-plan.md` (armed or not) and needs
the room in `docs/hardware.md`, "M4 memory budget", for the M4 step.

| # | Step | Status |
|---|------|--------|
| 1 | Plant model and simulator on the host, tests of the model itself | done |
| 2 | LQR gains from a small Python script | done |
| 3 | Controller as plain C, closed-loop tests against the simulator | done |
| 4a | Robustness: wrong parameters, real sensors, delay, motor lag | done |
| 4b | Limits: largest catch, supervisor limits, flywheel size | done |
| 5 | Controller on the M4 with the real IMU, no motor | done |

## Why simulate first

The controller is the hardest part of milestone 1 and the easiest to get
wrong in ways that break hardware (a sign error spins the flywheel up at
full torque). A simulator lets you see it work, tune it and test the
fail-safe cases now. When the motor arrives, only the parameters change.
It also answers a design question for the build: how big must the
flywheel be for the motor's torque?

## The model

A reaction wheel pendulum: a body that pivots on its bottom edge and a
wheel at the top driven by the motor. The motor's torque spins the wheel
one way and pushes the body the other. Three states:

- θ body tilt from upright, θ̇ its rate (from the IMU),
- ω wheel speed relative to the body (from the motor's encoder later).

Equations (Cubli / Åström's reaction wheel pendulum, plain Newton):

    (I_b + m l²) θ̈ = m g l sin θ - τ
    I_w (θ̈ + ω̇)    = τ

τ is the motor torque; I_b, m, l the body's inertia, mass and height of
its centre of mass; I_w the wheel's inertia. None are known yet (no
mechanical build): the parameters live in one struct with rough guesses,
marked as guesses, and the motor's torque limit from the GM4108H
datasheet.

## Step 1: simulator

Done: `tests/balance/` in DontFall-M4 (suite `dontfall.balance`).

- `src/plant.c`/`.h`, plain C, test code only: the equations above,
  RK4, with the torque held for one control period (1/850 s, the
  measured IMU rate, imu-plan step 3) and the plant stepped 4 times in
  it. The parameters are guesses in `plant_guess` (0.6 kg, centre of
  mass at 12 cm, a 150 g ring of 6 cm radius as the wheel, 0.2 N m as a
  placeholder torque limit), which give a fall time constant
  T = sqrt((I_b + m l²) / (m g l)) = 0.12 s.
- Tests of the model, so later tests stand on something: from rest at
  1 mrad it follows θ0 cosh(t/T) to 0.2% for 3 T; with gravity off the
  angular momentum about the pivot stays constant to 0.1% under a
  swinging full torque. Both fail on a 10% wrong gravity term or a
  flipped wheel sign.
- To look at a run: `scripts/test.sh -s dontfall.balance
  -x=BALANCE_CSV=1` prints `csv,...` lines into the twister log, and
  `tests/balance/plot.py` (on the PC, matplotlib) plots them. From 2°
  it lies down in 0.56 s at 660°/s, as energy conservation predicts,
  while the wheel stays still in absolute terms (ω = -θ̇).

## Step 2: LQR gains

Done: `tests/balance/lqr.py` in DontFall-M4 writes
`app/src/balance_gains.h`.

- The model linearised about upright (sin θ ≈ θ) into ẋ = A x + B τ,
  x = (θ, θ̇, ω): open-loop poles 0 and ±8.15 /s, controllable.
- Full-state feedback τ = -(k1 θ + k2 θ̇ + k3 ω), the standard way for
  this robot. The ω term matters: without it the wheel spins up slowly
  until the motor runs out of speed.
- Discrete LQR (zero-order hold at 850 Hz, `solve_discrete_are`), so
  the sample and hold is part of the design. The continuous gains differ
  by under 1%: 850 Hz is fast for this robot.
- Weights by Bryson's rule (the largest θ, θ̇, ω and τ we accept): 5°,
  2 rad/s, 30 rad/s and a quarter of the torque limit. 2° with the full
  limit asked for 3x the limit on a 5° recovery.
- Result with the guessed parameters: k = (-2.07, -0.248, -0.00165),
  closed-loop poles -9.0 ± 2.9j and -2.3 /s. From 5° the linear model
  peaks at 181 mN m (limit 200) and 21 rad/s on the wheel, is under
  0.5° after 0.23 s, and overshoots to -1° while it spins the wheel back
  down. `lqr.py --plot` shows it.
- Runs on the PC (numpy, scipy), not in the M4 container, which has
  neither; like `plot.py`. The parameters are a copy of `plant_guess`;
  keep them in step.
- Left for step 3: PID on θ alone in the simulator, to see the wheel
  spin up without the ω term.

## Step 3: controller and closed-loop tests

Done: `app/src/balance.c`/`.h` in DontFall-M4 (not in the firmware build
yet, that is step 5), tests in `tests/balance/src/control_test.c`.

- Plain C: input θ, θ̇, ω and armed, output torque = -k·x clamped to
  the motor's limit. Zero when disarmed and on a NaN or infinite input.
  Pure state feedback keeps no state, so there is nothing to reset on
  arming.
- θ̇ straight from the gyro (bias removed), not from the filter: the
  tilt filter's 0.5 s time constant is for the angle. Which IMU axis is
  θ, and its sign, is step 5's job (through the `mount` matrix in
  `tilt.c`).
- Tests on the nonlinear simulator with the guessed parameters, the
  controller seeing the true state:
  - from 5°: swings through upright to -0.95° while it spins the wheel
    down, under 0.5° from 0.9 s on (test: under 1.5°, then under 0.5°
    after 1 s); peaks at 181 mN m and 21 rad/s like the linear design;
  - a push to 0.5 rad/s from upright: within 0.83°, settled by 0.75 s;
  - the wheel spins back down to under 1 rad/s;
  - a 0.5° IMU offset: the robot still stands truly upright and the
    wheel holds a steady k0 offset / k2 = 11 rad/s. With k2 = 0 (PD on
    θ alone, the hobby alternative) it settles at the wrong angle and
    the wheel speeds up at 16 rad/s² for good: why the ω term is there.
    It also means the upright reference must be calibrated: every 0.5°
    of error costs 11 rad/s of wheel speed;
  - disarmed gives zero; the output never exceeds the limit; NaN and
    infinity give zero.
- The tests can fail: a flipped k1 or k3 fails 5 of the 6, a third of k2
  fails the recovery and the push.
- Plots: `-x=BALANCE_CSV=1`, then `plot.py recover`, `push`, `offset`,
  `offset_no_omega`.

## Step 4: robustness

Real hardware differs from the model; find out by how much it can
before it falls. To get a feel for it first: `tests/balance/sandbox.py`
(on the PC), the simulated robot live with sliders for all of the below.

### 4a: wrong parameters, real sensors, delay, lag (done)

`tests/balance/src/robust_test.c` in DontFall-M4. Unlike step 3 the
controller doesn't see the true state: a simulated IMU reads gravity,
the body's own acceleration at its height, noise (gyro std 1.5 mrad/s,
accel 0.025 m/s², imu-plan step 5) and a gyro bias 5x what calibration
leaves, and the firmware's `tilt.c` turns that into θ. The torque
arrives one sample late, through a 2 ms first-order lag for the FOC
current loop.

- **With the measured IMU** it recovers from 5° and stands to under 0.5°
  (actually ~0.02°), wheel back near 0.
- **The accelerometer is fooled by the body's own motion.** Falling
  freely with the IMU at the centre of mass it sees only
  1 - m l² / (I_b + m l²) = 19% of the tilt. Released at 5° with a
  filter that was started on that reading, the controller thought it
  was at 1° and recovered badly. So: **arm while holding it still** and
  let the filter settle (the tests hold it 2 s, 4 τ, first). Step 5 and
  the supervisor's arming should keep this in mind.
- IMU at the pivot, the centre of mass or twice that high: all stand.
- **Wrong parameters are the weak spot**, not delay or lag. Mass, centre
  of mass height and wheel inertia each 25% off in every combination:
  all stand. The worst way is heavier and taller with a lighter wheel
  (more gravity torque, less wheel): poles -9.0 ± 2.9j become
  -1.1 ± 5.9j /s at 25%, a 1 Hz wobble. At 30% that way it falls (a
  test pins this down); with the true state it barely stands
  (-0.27 ± 6.1j), the accelerometer takes the rest. So **measure the
  real robot's parameters and rerun `lqr.py`** rather than trust the
  guesses, or design the gains for a heavier robot than expected.
- **Delay and lag have a big margin**: 20 samples (24 ms) of delay plus
  20 ms of motor lag together still stand (settles in ~3 s, wheel to
  45 rad/s instead of 21). Alone it falls past ~50 ms of delay or ~90 ms
  of lag (sandbox, ideal sensors). The real loop has ~1 ms of each.

### 4b: limits, supervisor, flywheel (done)

With the guessed motor: 0.2 N m and a top speed of 60 rad/s, its torque
falling linearly to 0 there (back-EMF). Both are guesses until the
GM4108H's datasheet numbers are in; rerun `flywheel.py --t-max --w-top`
and the searches behind the tests then.

- **The motor runs out of speed, not torque.** Torque alone would catch
  16° (asin(τ_max / m g l)); the 60 rad/s top speed cuts it to about 9°.
- With the measured IMU (`robust_test.c`): caught from rest up to 8.5°,
  falls from 9°; a push from upright caught up to 1.2 rad/s (it leans
  to 6°), not 1.3.
- **Supervisor:** `SUPERVISOR_DISARM_TILT` goes from the 25° guess to
  15°, past anything it can catch with room for a better flywheel; past
  it, staying armed only spins the wheel up. The 5° arm limit is inside
  the catch limit. The dashboard's tilt panel draws the new ring.
- **Saturated too long:** every catch keeps the command at the limit for
  under 0.18 s, every fall for over 0.31 s before it reaches the disarm
  tilt. 0.25 s is the proposed threshold, for step 5. It also covers
  what the tilt check can't see: the robot pinned against something,
  leaning a little with the wheel at full speed.
- **Wrong sizes and the speed limit add up:** the worst ±25% corner
  (heavier, taller, lighter wheel) wobbles at up to 63 rad/s and falls
  with the 60 rad/s motor. The parameter tests run without a top speed
  to see the gains alone.
- **Flywheel:** `tests/balance/flywheel.py` builds the robot for each
  ring flywheel (mass 50-400 g, radius 3-8 cm; the rest of the robot
  0.45 kg, centre of mass 10 cm, wheel centre at 17 cm, all guesses),
  redesigns the gains and finds the largest catch and push. Radius
  matters most (inertia goes with r², so less speed for the same
  momentum). Mass has a sweet spot around 100-150 g: past that its
  weight at the top adds more gravity torque than it helps.
  **Pick: a 100-150 g ring at 7-8 cm radius, mass at the rim**: about
  11.6° and 1.7 rad/s (ideal sensors), against 9.4° for the 150 g, 6 cm
  guess. As big as the frame allows.

## Step 5: on the M4, no motor

Done. In DontFall-M4:

- `control.c` runs the controller on every IMU sample, right after the
  supervisor, under the same lock: θ the filter's pitch, θ̇ the gyro's
  pitch rate (bias removed, `tilt.c` keeps it as `pitch_rate`), ω 0 until
  there is an encoder (`WHEEL_SPEED`). The motor is a stub that only
  stores the torque; the limit is the 0.2 N m guess (`TORQUE_MAX`).
- Supervisor: arming needs |pitch rate| ≤ 0.2 rad/s (`moving` refusal),
  so the filter is on the true angle (step 4a); a new disarm reason
  `saturated` after 0.25 s with the output at its limit (step 4b). Tests
  in `tests/supervisor` for both.
- Telemetry: `/dontfall_m4/joint_states`, `sensor_msgs/JointState`, joint
  `wheel`, effort = the torque, 50 Hz best effort. θ is the pitch in
  `/dontfall_m4/imu`. The Foxglove layout has a "Balance" plot of both
  (pitch in rad and torque in N m share a scale).
- Loop time on the M4 (supervisor + controller, with the lock): at most
  12.3 µs, ~1% of the 1176 µs sample period, so it stays in the sampling
  path. Code: FLASH +4.8 KB (71.3%), RETRAM +0.2 KB (79.7%), RAM +2.3 KB
  (58.5%), mostly the JointState publisher.
- Bench (2026-10-27, 4 min, module tilted by hand, recorded from the PC):
  armed 37 times, refused with `moving` 9 times and `tilt` outside 5°,
  disarmed with `saturated` when held past ~6°. Fitting the published
  torque to the published pitch and rate gives k = 2.074 and 0.248
  (firmware 2.075 and 0.248), 1.2 mN m rms off; the torque had the sign
  of the pitch in every still sample past 1°. Roll gives no torque but
  counts for the supervisor's tilt limits (any direction).
- Not checked yet, needs the motor: which way the real wheel turns for
  positive torque. That is the first thing to check when it is wired,
  with the robot held and the torque limit low.

## Open questions

- A local stop button that works without the A7, before a motor runs
  (from the failsafe plan). The DK2's USER1/USER2 buttons share pins
  with LD5/LD6; who owns them is not checked yet.
- Whether arming needs more than one ROS call (e.g. hold-to-arm, or arm
  only from the web UI later; from the failsafe plan).
- The GM4108H's top speed at the supply voltage: a 5° recovery already
  needs ~21 rad/s on the wheel with the guessed sizes, and a gimbal
  motor is slow. A heavier wheel needs less speed.
- Loop rate: every IMU sample (850 Hz) or every second one. Decide in
  step 5 from the measured time.
- Kalman filter instead of the complementary filter, if step 4 shows the
  angle estimate is what limits it.
- Where the IMU sits on the robot, once there is one; close to the pivot
  axis keeps linear acceleration from fooling the accelerometer.
- Whether the IMU's INT2 is needed at all (e.g. for FIFO or free-fall).
  Not wired for now.
- The two-wheeled robot is a different model (wheel on the ground,
  forward speed from `cmd_vel`). Reuse the same structure later.
