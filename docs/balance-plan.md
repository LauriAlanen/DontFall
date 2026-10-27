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
| 3 | Controller as plain C, closed-loop tests against the simulator | todo |
| 4 | Robustness: wrong parameters, noise, delay, limits | todo |
| 5 | Controller on the M4 with the real IMU, no motor | todo |

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

- `app/src/balance.c`/`.h`, plain C: input θ, θ̇, ω and armed, output
  torque, clamped to the motor's limit. Zero when disarmed, and its
  state reset on arming.
- θ̇ straight from the gyro (bias removed), not from the filter: the
  tilt filter's 0.5 s time constant is for the angle.
- Which IMU axis is θ: through the `mount` matrix in `tilt.c`
  (identity until the robot exists), not hard-coded.
- Tests (control logic, so a critical part): recovers from 5° to within
  0.5° in a set time; recovers from a push (an impulse on the body);
  wheel speed returns towards zero; disarmed gives zero torque; the
  output never exceeds the limit. Check the tests can fail (e.g. flip
  the sign of k1).
- Done when: the tests pass and a plot of a recovery looks right.

## Step 4: robustness

Real hardware differs from the model; find out by how much it can
before it falls.

- Parameters off by ±30% from what the controller was designed for.
- Gyro noise and leftover bias from imu-plan step 5 (std ~1.5 mrad/s,
  bias residual), accel noise through the tilt filter, one sample of
  delay, torque that lags the command (a first-order lag standing in for
  the FOC current loop).
- Torque and wheel speed limits: the largest start angle and push it can
  still catch. This gives the supervisor's tilt limit and a "saturated
  too long" disarm condition (failsafe plan, step 1).
- Use it to pick a flywheel size for the motor's torque, before
  building anything.
- Done when: the limits are written down here and covered by tests.

## Step 5: on the M4, no motor

- Run the controller in the IMU sampling path (or a thread woken by
  it, decide here by measuring the time it takes), behind the
  supervisor. The motor is a stub that only stores the torque.
- ω is 0 until there is an encoder; say so in the code.
- Publish the commanded torque and θ for plotting, on the status topic
  from the failsafe plan (or a temporary one).
- Bench: arm with the module flat, tilt it by hand in PlotJuggler: the
  torque must push back against the tilt (this checks the sign through
  the whole chain), grow with the angle, and go to zero on disarm or
  past the limit.
- Measure the loop's time on the M4 and note the code size.
- Done when: those checks pass.

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
