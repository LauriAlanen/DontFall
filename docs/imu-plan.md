# IMU bring-up plan

Working plan for getting tilt from the IMU on the M4 (see VISION.md: the M4
reads the IMU and runs the balance controller). The IMU is the Arduino
Modulino Movement (ST LSM6DSOX, 6-axis) on SPI. Each step is small and ends
with something you can see working. Update the status as steps land.

| # | Step | Status |
|---|------|--------|
| 1 | Wire the Modulino to SPI5, read WHO_AM_I with raw SPI from the M4 | done |
| 2 | Zephyr's LSM6DSO driver: polled accel + gyro in SI units | done |
| 3 | Data-ready interrupt (INT1) at a fixed rate, measure the timing | done |
| 4 | Publish the raw `sensor_msgs/Imu` over micro-ROS, plot it on the PC | done |
| 5 | Gyro bias, and the axes and signs on the bench | done |
| 6a | Tilt filter (complementary, roll + pitch) as plain C, ztest on the host | done |
| 6b | Run the filter on the M4, print the angles, check against an inclinometer | done (no inclinometer yet) |
| 6c | Orientation in `sensor_msgs/Imu`, PlotJuggler and RViz | done |
| 7a | Fault detection logic as plain C, ztest on the host | done |
| 7b | Run it on the M4, WHO_AM_I re-check, pull wires on the bench | done |
| 7c | Re-init the chip after a fault that doesn't heal by itself | done (software reset; no power pull) |
| 7d | Report the IMU health to the A7 | todo |
| 8 | Release: firmware in the image from bitbake | todo |

## Wiring

Modulino on the GPIO expansion header CN2, SPI5 on the M4. SPI4 (Arduino
D10-D13) was not used because PE11/PE14 are also TIM1_CH2/CH4, which the
motor PWM may need later.

SPI wiring (needs two Modulino mods, see below):

| Modulino | CN2 pin | SoC pin | Use |
|----------|---------|---------|-----|
| SCL (4-pin I2C header J4) | 23 | PF7 | SPI5_SCK |
| SDA (4-pin I2C header J4) | 19 | PF9 | SPI5_MOSI |
| SDO/SA0 (1x10, 5th) | 21 | PF8 | SPI5_MISO |
| CS (1x10, 8th) | 24 | PF6 | chip select, as a GPIO |
| INT1 (1x10, 3rd) | 26 | PF3 | data ready (EXTI3), from step 3 |
| VDDIO (1x10, 1st) | 1 / 17 | - | 3V3 (powers VDD too) |
| GND | GND pin | - | GND |

What the Modulino really has (ABX00101 datasheet + measurements, the
datasheet has no schematic and its labels mislead):

- **SCx/SDx on the 1x10 header are not the SPI bus.** They are the
  LSM6DSOX's auxiliary pins (sensor hub / OIS SPI), tied to GND on the
  board (ST: tie to VDDIO or GND when unused). Measured ~0 Ω to GND, and
  no continuity to SDA/SCL. The datasheet's "SPI Clock"/"SPI Data" labels
  are wrong for us. The main SPI clock and data-in are the chip's SCL/SDA
  pins, on the Qwiic connectors and the 4-pin header J4. Leave SCx/SDx
  unconnected: wired to SCK/MOSI they short the STM32 outputs to GND.
- **SDO/SA0 is tied to GND** by the I2C address solder jumper (default
  address 0x6A = SA0 low). In SPI mode SDO is the chip's output, so the
  jumper's GND side must be cut, and the 0x6B side left open too (that
  ties SDO to 3V3). Until then SPI reads 0x00.
- **CS is tied to 3V3** by a solder bridge, which keeps the chip in I2C
  mode. Remove it so the M4 can pull CS low. With CS free, the chip is in
  an undefined mode while the M4 isn't running; a 10 kΩ pull-up to 3V3
  would fix that if it ever matters. I2C on this module now only works
  while CS is held high.
- Header order from the VDDIO end: VDDIO, GND, INT1, INT2, SDO/SA0, SCx,
  SDx, CS, SDOAUX, OCSAUX. Early batches print the 5th pad "SDO" and the
  9th (SDOAUX) "SDO/SA0" (datasheet pinout note): go by position.
- **GND must be wired.** Without it the chip finds ground through its
  signal pins: reads work right after bus activity and turn to garbage
  after a few hundred ms idle. The SPI pin settings were never the
  problem.
- VDDIO is tied to the 3.3 V rail by a closed solder jumper, so one 3.3 V
  wire powers VDD too (the LSM6DSOX needs both, VDDIO <= VDD + 0.1 V).
- No I2C pull-ups on the board. For I2C, the STM32's internal pull-ups
  (Zephyr's i2c5 pinctrl) were enough at 100 kHz on short wires.
- The chip stays in I2C mode until CS goes low the first time. CS must be
  driven, not left floating.
- Pins go into the pin assignment table in `docs/hardware.md` in the
  change that first uses them (step 1).

## Step 1: WHO_AM_I over raw SPI

Goal: prove wiring, power, pin muxing and that the M4 owns SPI5, before
any driver is involved. If step 2 then fails, the problem is in the
driver config, not the hardware.

- Overlay: a `cs-gpios` on `&spi5` (PF6). Zephyr's DK2 board file already
  sets up SPI5 on PF6-PF9 and enables it.
- Small `imu.c` in DontFall-M4: `spi_transceive` of register 0x0F with the
  read bit (0x8F), SPI mode 3, 1 MHz. `printk` the result (it appears in
  `remoteproc0/trace0`). Expected: **0x6C**.
- Check on the board that Linux doesn't use SPI5 (`ls /sys/bus/spi/devices`
  should show nothing on that controller).
- Done when: the trace shows 0x6C after each M4 boot.

Code (DontFall-M4): `app/src/imu.c`, `imu` node under `&spi5` in
`app/boards/stm32mp157c_dk2.overlay`. The node already uses the
`st,lsm6dso` compatible so step 2 only has to enable the driver; with
`CONFIG_SENSOR` off nothing binds to it. SPI5's pinctrl drops PF6 (no
hardware NSS); PF6 is `cs-gpios` instead.

Verified: `imu: WHO_AM_I 0x6c` on every M4 boot, also after bursts and
500 ms idle, with Zephyr's default SPI5 pin settings at ~960 kHz
(measured; 100 kHz is below SPI5's /256 limit and gets -EINVAL).

How the Modulino problems were found (temporary code, removed):
- SPI read 0x00, also with a pull-up on MISO: something held MISO low
  (SDO tied to GND).
- I2C on I2C5 (CN2 pins 3/5): `0x6a: WHO_AM_I 0x6c`, so the chip was
  alive and SA0 low.
- Bit-banged I2C over the SPI wires (PF7 SCL, PF9 SDA): driving the MISO
  wire moved the address between 0x6A and 0x6B (MISO path OK), but CS low
  didn't stop the chip answering (CS tied to 3V3).
- Bit-banged SPI mode 3 read 0x6C while hardware SPI was flaky; that was
  the missing GND (above), not the peripheral.

## Step 2: Zephyr driver, polled

- Zephyr's `st,lsm6dso` driver (`drivers/sensor/st/lsm6dso`) covers the
  LSM6DSOX: same registers, same WHO_AM_I (0x6C). It needs ST's sensor
  HAL module `hal_st`.
- `hal_st` isn't in DontFall-M4's `west.yml` `name-allowlist` yet. Add it
  there. meta-zephyr's 3.7.0 recipe already fetches it
  (`SRCREV_hal_st`), so bitbake needs nothing new, and
  `check-zephyr-pin.sh` should confirm both revisions match.
- DT node `st,lsm6dso` under `&spi5`. Start with a moderate ODR and full
  scale (e.g. 104 Hz, ±4 g, ±500 dps). Read with `sensor_sample_fetch` /
  `sensor_channel_get` from a thread a few times a second and `printk`.
  Drop the raw SPI code from step 1.
- Sanity checks: flat on the table, one accel axis reads about ±9.81 m/s²
  and the other two about 0. Gyro about 0 at rest. Rotate the board by
  hand and see the right axis respond with the right sign.

Code (DontFall-M4): `hal_st` in `west.yml`, `CONFIG_SENSOR=y`, ODR and
full scale as DT properties on the `imu` node (the driver writes them at
boot; without them the chip stays powered down), `app/src/imu.c` polls
at 4 Hz with the generic sensor API and prints fixed point (printk has
no float support). `hal_st` is b77157f6 on both sides, as Zephyr's own
`west.yml` and meta-zephyr's 3.7.0 inc pin it; `check-zephyr-pin.sh`
compares the Zephyr revision, which fixes the module revisions.

Verified: at rest, |a| ≈ 10.0 m/s² (module tilted on its wires, e.g.
x 4.6, y -7.6, z -4.7) and gyro within ±0.06 rad/s. Tilting by hand
moves the ~9.81 m/s² between the axes. Signs are checked in step 4.

## Step 3: data-ready interrupt and timing

- Before using PF3, check ST's DKx GPIO mapping that EXTI line 3 isn't
  taken on the board (one EXTI line per pin number on all ports).
- `irq-gpios` on INT1 (PF3), `CONFIG_LSM6DSO_TRIGGER` and a data-ready
  trigger. Raise the ODR toward the balance loop rate (decide here:
  e.g. 416 Hz or 833 Hz).
- Measure: samples per second and the spread of the gap between samples
  (`k_cycle_get_32`). Print a summary once a second. This shows whether
  the sensor can clock the balance loop.
- Note the SPI clock here too: try up to 10 MHz and keep what is
  reliable with the real wires.

EXTI3 check: one EXTI line serves pin 3 of every port, and the mux
(EXTICR) is shared by the A7 and the M4. On the board Linux uses only
GPIO EXTI line 7 (`stm32gpio 7`, the SD card detect); PB3, PD3 and PE3
are claimed only as alternate functions, not as interrupts. So EXTI3 is
free for PF3. Zephyr's EXTI code writes the M4's own mask register
(`C2IMR1`) on the MP1.

Decided: ODR 833 Hz for accel and gyro (balance loops usually run at
200-500 Hz; this leaves headroom). SPI 10 MHz requested, 8 MHz real
(SPI5 kernel clock is 64 MHz, dividers are powers of two).

Code (DontFall-M4): `irq-gpios` (PF3), `int-pin = <1>` and `drdy-pulsed`
on the `imu` node, `CONFIG_LSM6DSO_TRIGGER_OWN_THREAD`. `imu.c` sets an
accel data-ready trigger; the handler (in the driver's coop thread)
fetches accel + gyro and records the gap since the last sample and the
fetch time. A preemptible thread prints the summary once a second.
Pulsed, not latched: the driver masks the GPIO interrupt while it reads,
and a latched INT1 that goes high in that window never makes a new edge,
so sampling would stop for good. Pulsed only loses one sample.

Measured (module at rest, 8 MHz SPI):

    imu: 850 Hz, gap us min 1176 avg 1176 max 1176, fetch us max 55, errors 0

- The rate is 850 Hz, not 833: the chip's ODR comes from its internal
  oscillator, here about 2% fast (the M4 clock is from a crystal). The
  tilt filter must use the measured time between samples, not 1/833 s.
- Over 2 minutes every window was 850 samples with 0 errors (no lost
  samples, no stall). Gaps stayed within 1162-1190 µs (±14 µs, mostly
  ±3), so the sensor can clock the balance loop. 8 MHz SPI is reliable
  on the current wires.
- One sample fetch is ~55 µs at 8 MHz (12 data bytes plus commands);
  with the driver's two status reads per sample that is roughly 7% of
  the M4 (estimate, not measured). The SPI driver is interrupt driven
  (`CONFIG_SPI_STM32_INTERRUPT`, an interrupt per few bytes); SPI DMA
  would cut that if the M4 gets tight.

## Step 4: raw IMU over micro-ROS

Moved up from after the tilt filter: seeing the data plotted on the PC
makes every later step (bias, axis signs, filter tuning) easier to check
than reading printk lines.

- Publish `sensor_msgs/Imu` on `/dontfall_m4/imu`, `frame_id` `imu_link`,
  angular velocity and linear acceleration as measured. No orientation
  yet: `orientation_covariance[0] = -1` (REP 145: "no orientation").
  Other covariances all 0 = unknown, until they are measured.
- 50 Hz, best effort (sensor data QoS): it is telemetry, a lost message
  doesn't matter and must never hold anything up.
- Sampling stays in the driver's thread at the sensor rate. The micro-ROS
  thread only copies the latest sample, so a slow or missing Agent can't
  slow the IMU down.
- Header stamp from the Agent's clock (`rmw_uros_sync_session`), so the
  PC tools see real times.
- Replaces the hello-world counter (same publisher count). Check RAM and
  code size against `docs/archive/ros-plan.md` step 4.
- Done when: `ros2 topic hz` shows ~50 Hz on the PC and PlotJuggler
  plots the six values live.

How to look at it from the PC: `docs/development.md`, "Looking at the
data on the PC" (`pc/ros.sh`, PlotJuggler, RViz).

Code (DontFall-M4): `imu_latest()` in `imu.c` copies the newest sample
under the same spinlock the sampling thread uses; `uros.c` publishes it
from a 20 ms rclc timer with `rclc_publisher_init_best_effort` and syncs
the clock with the Agent after creating the entities.

Verified: on the PC, `ros2 topic hz` ~49.6 Hz, `ros2 topic echo` shows
`frame_id: imu_link`, `orientation_covariance[0] = -1`, gyro ~0 and
|a| ~10 m/s², and PlotJuggler plots the six values live. Stamps were
~0.6 s ahead of the PC clock: the board (NTP) and the PC (WSL) clocks
differ, not a problem for plotting.

- **Code size: 118.6 KB of 128 KB (90%)**, up 14 KB. About 4.4 KB is the
  `sensor_msgs/Imu` type support, ~2.5 KB software double maths (the
  M4's FPU is single precision only, ROS messages are double). Motor
  control will need more room: options then are less micro-ROS (fewer
  entities, smaller MTU), putting code in the free RETRAM (only the
  vector table is there) or moving the SRAM1/SRAM2 split.

## Step 5: gyro bias and orientation

Bench only: there is no robot yet (VISION.md, "Bench first, robot
later"), so the module is tested loose on the table.

- Gyro bias: average N samples at startup while the module is still,
  subtract it afterwards. Decide what happens if it moves during that
  window (e.g. retry while the variance is too high).
- Axes and signs: check the readings against the axes printed on the
  Modulino / in the LSM6DSOX datasheet (right-handed). Gravity reads
  +9.81 on the axis pointing up; turning counter-clockwise about an axis
  (seen from its tip) gives positive rate on it.
- Done when: the bias is subtracted (gyro ~0 at rest right after boot)
  and each axis responds with the datasheet sign.

Decided: if the module moves during calibration, retry forever. Running
on an uncalibrated gyro is worse than waiting. Until calibration has
worked, `imu_latest()` has no sample, so nothing is published.

The sensor-to-body axis mapping moved to step 6: it has no user before
the filter, and the ROS topic stays in the sensor frame (`imu_link`);
in ROS the mounting becomes a TF transform once there is a robot.

Code (DontFall-M4, `imu.c`): skip the first 85 samples (~100 ms, gyro
turn-on garbage of a few rad/s), then average 850 samples (~1 s). Accept
if every axis's max - min stays under 0.1 rad/s and |bias| < 0.1 rad/s
(zero-rate level is ±1 dps typical), else start a new window. Samples
are integer micro-units, so the subtraction at 850 Hz is integer maths.
The summary thread prints each failed window and the result:

    imu: gyro bias [rad/s] x -0.0031 y  0.0015 z -0.0073 (try 1)

Axes and signs checked by hand in PlotJuggler: the axis pointing up
reads +9.81 m/s², and turning about an axis by the right-hand rule gives
positive rate on it, for all three axes, matching the datasheet.
(Accel is checked by holding still in each orientation, not by moving:
a push gives a spike one way and the stop one the other way.)

Measured:
- Bias repeats to 0.1 mrad/s over a dozen boots (about 0.2-0.4 °/s per
  axis; uncorrected, z alone would integrate to ~25° per minute).
- After calibration, at rest: gyro mean ~0.0002 rad/s, std 1.1-1.7
  mrad/s per axis. Accel std ~0.025 m/s².
- Quiet desk: window range ~0.01 rad/s. During one test, desk vibration
  gave 0.04-0.11, which failed the first try at 0.03 rad/s; 0.1 rad/s
  lets that through and still rejects handling. If a slow steady turn
  ever gets accepted as bias, a stricter test is two windows in a row
  that must agree.

Found on the way: **every second M4 start hung** in the LSM6DSO driver
init. GPIO, EXTI and SPI5 are not reset when the M4 restarts, and the
IMU keeps pulsing INT1 while the M4 is stopped, so EXTI3 is pending at
the next boot. The driver's interrupt thread then loops `while (1)`
until the data-ready flags clear, but with no trigger handler set yet
nobody reads the data, and that coop thread spins forever. Its chip reset
stops the pulses, so the following boot works. Fixed by
`patches/zephyr/0003-sensor-lsm6dso-dont-spin-on-drdy-without-handler.patch`
(loop only on flags that have a handler). Verified: 12 restarts in a row.
Also a fail-safe point for step 7: one stray edge could hang the M4.

## Step 6: tilt estimate

- Complementary filter: integrate the pitch gyro, correct slowly with the
  angle from the accelerometer. One tuning constant (time constant).
  Kalman can come later if this isn't enough.
- Keep the maths in a plain C file with no Zephyr dependencies, so it can
  be built and tested on the host.
- Tests (control logic, so this is a critical part): constant tilt
  converges to the right angle, gyro bias doesn't make the angle drift,
  the angle follows a step within the expected time.
- Sensor-to-body axis mapping (ROS REP 103: x forward, y left, z up) in
  one place, used by the filter. Identity until the robot is built.
- Add the filter's orientation to the `sensor_msgs/Imu` from step 4
  (and its covariance), and publish the tilt angle on its own (e.g.
  `std_msgs/Float32`) for plotting. `CONFIG_MICROROS_PUBLISHERS` goes up.
- Done when: the tilt angle matches a protractor or phone inclinometer to
  about a degree and holds steady at rest, and RViz (`rviz_imu_plugin`)
  shows the orientation following the module.

Split into three steps, each ending with something to see:

- **6a** filter and tests on the host only; the firmware doesn't use it
  yet.
- **6b** run it in the sampling thread with the measured time between
  samples, print roll and pitch in the summary, compare with an
  inclinometer. Floats on the M4 need `CONFIG_FPU` (off today: maths is
  software float); check code size.
- **6c** orientation quaternion (roll, pitch, yaw 0 with a large yaw
  covariance, REP 145) in the Imu message, tilt as `std_msgs/Float32`,
  RViz.

Decided: roll and pitch, not pitch alone. Balancing uses pitch only, but
the Imu message needs a full orientation, and roll costs one more
filter. Time constant 0.5 s to start, a common value for balancing
robots; tune on the robot. Tests use Zephyr's ztest, run by twister on
`native_sim/native/64` (the 32-bit `native_sim` needs gcc multilib).

### 6a: filter and tests

Code (DontFall-M4): `app/src/tilt.c`/`tilt.h` (not in the firmware build
until 6b), tests in `tests/tilt`, run with `scripts/test.sh` (twister in
the Zephyr container; the container gained `tabulate`, `natsort`,
`colorama` and `ply` for it).

- Accelerometer angles: roll = atan2(ay, az), pitch = atan2(-ax,
  sqrt(ay² + az²)), from gravity in body axes g·(-sin θ, sin φ cos θ,
  cos φ cos θ).
- Gyro: body rates converted to Euler angle rates, not used raw. Raw
  rates are only right near level: turning in place while leaning would
  leak the yaw rate into roll (r·tan θ).
- Blend: angle += k·(accel angle - angle), k = dt / (τ + dt), the
  discrete first-order low pass. The difference is wrapped to ±180°, so
  a robot lying upside down doesn't swing roll back through 0.
- The first sample takes the accelerometer angles, so the estimate is
  right at once instead of converging from 0 over ~2 s.
- Sensor-to-body mapping: the `mount` matrix in `tilt.c`, identity.
- Not done (yet): ignoring the accelerometer while |a| is far from g
  (bumps, hard braking). Worth trying on the robot if the angle jumps.

Tests (6, all pass): first sample = accel angles; an accel-only step
reaches 63% after τ and settles in 5τ; a 0.01 rad/s bias (10x the
leftover after step 5) settles at b·τ (0.29°) and doesn't grow;
tipping at 1 rad/s is tracked within 0.1° (accel alone would lag 29°);
turning at 2 rad/s while leaning 10° keeps roll at 0; roll crossing
±180° takes the short way. Checked the tests can fail: without the
gyro term or the Euler coupling, 3 of them fail.

### 6b: filter on the M4

Code (DontFall-M4): `on_drdy()` in `imu.c` runs `tilt_update()` on every
calibrated sample, dt from the cycle counter; the summary prints roll and
pitch in degrees. `CONFIG_FPU` with the soft-float ABI (float arguments
in integer registers, so libmicroros, built without the FPU, links as
is) and `CONFIG_FPU_SHARING`. `CONFIG_FPU` also turns on float printf
(~10 KB); `CONFIG_CBPRINTF_FP_SUPPORT=n` turns it off again.

Measured: code 124.1 KB of 128 KB (94.7%, +2.7 KB for the filter, float
maths and FPU context switching). Still 850 Hz, 0 errors, fetch 53 µs.
At rest (module on its side): roll -91.7°, pitch 1.5°, matching atan2
of the raw accel; over 60 s roll moved 0.015°, pitch 0.006°. 5 M4
restarts in a row came up fine.

Inclinometer comparison: not done. Roll and pitch looked right by eye in
PlotJuggler (6c); compare with a phone inclinometer when the 1° matters
(e.g. when tuning the balance controller).

### 6c: orientation on the topic

Code (DontFall-M4): `tilt_quaternion()` in `tilt.c` (roll, pitch, yaw 0
to x, y, z, w; a test checks it turns the accel reading into gravity
straight up), `imu_latest()` hands it out with the sample, `uros.c` puts
it in the Imu message. Orientation covariance: (1°)² = 3e-4 rad² for
roll and pitch (the accuracy aimed for at rest), 1e6 for yaw (not
estimated).

Decided: no separate `std_msgs/Float32` tilt topic. PlotJuggler adds
`orientation/roll` / `pitch` / `yaw` to any quaternion it parses, so the
Imu topic is enough for plotting, and it saves a publisher and the type
support code. A tilt topic can come with the balance controller's state.

The orientation is in body axes (after `mount` in `tilt.c`), the rates
and accelerations in sensor axes, all under `imu_link`. Identical while
`mount` is identity; once the IMU is mounted, publish all of it in one
frame (or the TF from body to `imu_link`).

Measured: code 124.4 KB (94.9%). `ros2 topic echo` shows orientation
x -0.717, w 0.697 (roll -91.7°, pitch 1.2°, as the trace prints) and the
covariance as set.

## Step 7: fault detection

The balance controller must know when it can't trust the tilt
(VISION.md: fail safe without the A7).

- Detect: no data-ready within a few sample periods, SPI errors,
  WHO_AM_I wrong on re-check, values stuck or out of range.
- Expose one "IMU healthy" flag (plus a reason) for the controller to
  disarm on. Report it to the A7 too (e.g. in diagnostics).
- Tests for the detection logic (fail-safe behaviour).
- Try it on the bench: pull the MISO wire or INT1 wire while running and
  check the flag goes bad, then comes back after a re-init.

Split into four steps, like step 6:

- **7a** the detection logic and tests on the host only; the firmware
  doesn't use it yet.
- **7b** feed every fetch into it in `on_drdy()`, re-check WHO_AM_I
  every second from the summary thread, print fault changes in the
  trace, restart the tilt filter from the accelerometer after a fault.
  Bench: pull INT1, then MISO, while running.
- **7c** a fault that can't heal on its own (chip reset or power loss:
  its ODR registers go back to power-down, so no data ever comes):
  write the ODR, full scale and INT1 setup again.
- **7d** tell the A7. Code space (~95%) decides how: e.g.
  `diagnostic_msgs` costs type support, the Imu message's covariance
  (`-1`) or not publishing cost nothing.

What happens on a fault is the controller's job, not the IMU's: it
disarms and stays disarmed (latched) until told otherwise. The IMU flag
itself recovers, so a re-init or a wire pushed back in is seen.

### 7a: detection logic and tests

Code (DontFall-M4): `app/src/imu_health.c`/`.h` (not in the firmware
build until 7b), tests in `tests/imu_health`. Samples go in as the
micro-units `imu.c` already has; `imu_health_check(now)` gives `IMU_OK`
or the fault, to ask right before using a sample.

| Fault | When | Catches |
|-------|------|---------|
| no data | no good sample for 10 ms (~12 periods) | INT1 off, chip powered down, MISO low (the driver's status read then sees no data ready and skips the callback; seen in 7b) |
| read | 3 failed fetches in a row | SPI errors |
| stuck | all six values unchanged 20 samples in a row | MISO stuck: every register reads 0x00 or 0xFF |
| range | any axis at full scale 3 samples in a row | real value beyond the sensor (crash, > 573 dps) |
| who_am_i | the re-check reads a wrong id | chip swapped, bus garbage that looks alive |
| rate | samples under 500 µs apart, 3 in a row (added in 7b) | the driver looping on garbage, see 7b |

- Every fault latches, also "no data" when samples come back after a gap,
  and clears after 85 good samples in a row (~100 ms). Startup works the
  same way, so the first 100 ms are untrusted. who_am_i also needs a good
  re-check first: random bus data can look like good samples.
- Stuck is safe from false alarms: at rest the gyro noise (~1.5 mrad/s
  std) is ~5 LSB of the 500 dps scale, so six axes identical 20 times in
  a row doesn't happen on a live sensor.
- Full scale is the largest reading, 32767 LSB times the sensitivity:
  3.998 g and 573 dps (the 500 dps range really reaches 573).
- The time check uses an unsigned difference of a wrapping µs clock.

Tests (8 in 7a, 9 after 7b, all pass): startup needs 85 samples; one missed pulse is fine,
just over 10 ms isn't, and it latches; 2 read errors are fine, 3 aren't; stuck
zeros after 20 repeats, also while they keep coming; gyro and accel
saturation, a 2-sample spike is fine; who_am_i holds against any number
of good samples until a good re-check; a fault every 84 samples never
clears; the clock wrapping. Checked the tests can fail: clearing faults
after 1 good sample fails 4 tests, ignoring the who_am_i gate fails 1.

### 7b: on the M4, bench test

Code (DontFall-M4): `on_drdy()` feeds every fetch (raw values, before
the bias) into `imu_health` under the sampling lock. While there is a
fault the sample isn't handed out, the gyro calibration skips it (stuck
zeros would pass as a perfect calibration) and the tilt filter restarts,
so it takes the accelerometer's angles once healthy instead of
integrating garbage. The summary thread re-reads WHO_AM_I once a second
with a raw SPI read and prints `FAULT <reason>` every second while
unhealthy, `healthy (was ...)` once on recovery. `imu_latest()` now
returns the fault (`IMU_OK` or the reason) instead of a bool; the
micro-ROS publisher skips the message on anything but `IMU_OK`. The µs
clock comes from `k_uptime_ticks()` (100 µs ticks): the 32-bit cycle
counter wraps at 2^32 cycles (~20 s), not 2^32 µs. RAM console 1 KB ->
8 KB (~40 s of summaries), so a bench test can be read back.

Changes to 7a found on the board:
- **rate** fault: with the 3.3 V wire off the chip ran half powered off
  its I/O pins, still answered WHO_AM_I right, and the driver read
  garbage at 15-40 kHz (status always "data ready"). The garbage changed
  enough to pass as live, so stuck didn't catch it.
- **no data** is judged from the gap since the last fetch of any kind,
  not the last good one: a run of saturated or stuck samples kept being
  relabelled "no data" on the first good sample after it. And range is
  set after stuck, since a sensor pinned at full scale repeats itself.

Measured: code 125.4 KB (95.7%, +1 KB), RAM 47.6% (+7 KB, the console).
850 Hz, fetch 54 µs as before. 20 M4 restarts in a row came up healthy;
~75 WHO_AM_I re-checks in a row read right.

Bench (pull a wire for ~5 s, push it back):

| Pulled | Trace | Back in |
|--------|-------|---------|
| INT1 | `FAULT no data`, 0 Hz, WHO_AM_I fine | `healthy (was no data)` within the second |
| MISO | `WHO_AM_I 0x00`, `FAULT who_am_i`, 0 Hz (status reads 0, no callbacks) | `no data` for a second (the 5 s gap), then `healthy` |

Read errors stayed 0: an SPI master never sees an error from a missing
wire, it reads whatever is on MISO. Faults show up as values, not errors.

Not seen on the bench: stuck and range (they need MISO pinned at a level
or a crash). Both are covered by the host tests.

Detour (two days): after the 3.3 V wire came loose, step 7b booted bad
about one time in four (bits shifted by one: WHO_AM_I 0x6c read as 0x58,
CTRL3_C 0x44 as 0x08, so the driver's init cleared auto-increment and
then looped). The cause turned out to be the module wiring: after a
resolder, SDO was shorted to GND (reads all 0x00). A WHO_AM_I over I2C
(0x6c at 0x6A) proved the chip alive. With SDO fixed, nothing reproduces,
also without the suspected SPI fix (STM32H7/MP1 SPI pins floating
between transfers, AFCNTR / upstream `st,gpio-control`). That fix was
backported, then dropped again as not needed. If shifted reads ever come
back on good wiring, that is the next thing to try.

### 7c: re-init after a chip reset

A chip reset or power loss puts the LSM6DSOX's registers back to
defaults, ODR "power down" among them. It still answers WHO_AM_I right,
but never makes data again, so "no data" would stay forever.

Code (DontFall-M4, `imu.c`): right after the driver's init and the
trigger, `imu_init()` reads back the registers the driver set up and
keeps them (`cfg_regs`: CTRL1_XL..CTRL10_C, FIFO_CTRL4 for FIFO bypass,
COUNTER_BDR_REG1 for pulsed data ready, INT1_CTRL for data ready on
INT1, I3C_BUS_AVB). Once a second, after a good WHO_AM_I, the summary
thread reads them again; if one differs it prints
`imu: chip setup lost (reg .. .., was ..), writing it again` and writes
all of them back with raw SPI writes: CTRL3_C (BDU, auto-increment)
first, the ODRs last, so the chip starts only when fully set up. The
health checks need nothing new: "no data" clears by itself once samples
come back.

Decided:
- Save from the chip rather than write our own values: the register
  encoding of ODR and full scale stays in the driver, one place. Zephyr
  3.7 has no API to re-run a driver's init, and the sensor API
  (`sensor_attr_set`, trigger) can't restore pulsed data ready.
- Only after a good WHO_AM_I: with MISO off every read is 0x00, which
  would look like lost setup every second.
- No host test: the logic is a compare and a write loop, tested on the
  board instead.
- Limitation: if the driver's init itself goes wrong (as with the bad
  SDO wiring in 7b), the saved copy is wrong too and gets restored as
  is. The health checks still flag the result.

Measured: code 125.7 KB (95.9%, +0.3 KB). Test with a temporary hook
that set CTRL3_C's software reset bit 10 s after boot (removed):

    TEST: chip SW reset
    imu: chip setup lost (reg 0x12 0x04, was 0x44), writing it again
    imu: FAULT no data
    imu: healthy (was no data)

Back at 850 Hz within ~2 s. Without the hook: 20 M4 restarts and ~40 s
of running with no false "setup lost". Not tested: a real power loss
(pulling 3.3 V back-powers the chip through its signal pins; not worth
risking the sensor).

## Step 8: release

- Bump `SRCREV_dfm4`, build the image, deploy, check the IMU topic from
  the image's own firmware.
- Mark the IMU as decided in VISION.md ("Undecided" list).

## Open questions

- Balance loop rate. The IMU runs at 833 Hz nominal (step 3), so any
  rate up to that works.
- Where the IMU sits on the robot, once there is one; close to the pivot
  axis keeps linear acceleration from fooling the accelerometer.
- Code space (128 KB, ~95% after step 6). Best next room: code in
  RETRAM (64 KB at address 0, reserved for the M4 by Linux, only the
  vector table there now): `CONFIG_ROMSTART_REGION_SIZE=64` plus a
  `zephyr_linker_sources(ROM_START ...)` snippet moving e.g. micro-ROS
  code there. Moving the SRAM1/SRAM2 split is awkward: the MPU needs
  power-of-two aligned regions, so only 192 KB / 64 KB works, too little
  data.
- Whether INT2 is needed at all (e.g. for FIFO or free-fall). Not wired
  for now.
