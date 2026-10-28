# FOC groundwork plan

Working plan for the motor control parts that don't need a motor: the
FOC maths, three-phase PWM from the M4, and the safe state of the motor
outputs. The motor (GM4108H-120T with AS5048A encoder) and the driver
(SimpleFOCMini v2.3, DRV8316) are not on hand yet, see
`docs/hardware.md`. VISION.md puts FOC for both motors on the M4. Each
step is small and ends with something you can see. Update the status as
steps land.

| # | Step | Status |
|---|------|--------|
| 1 | Pin and peripheral map, on paper, for decision | todo |
| 2 | FOC maths as plain C, ztest on the host | todo |
| 3 | Centre-aligned three-phase PWM on TIM1, fixed duties | todo |
| 4 | Open-loop rotating voltage vector, seen on three LEDs | todo |
| 5 | Safe outputs: boot, M4 stop, crash | todo |
| - | After the hardware arrives: ADC current sensing, encoder, current loop | later |

## What FOC needs from the M4

Field oriented control drives the motor's three phases so the magnetic
field stays at 90° to the rotor, which gives smooth torque proportional
to current. Each PWM period (typically 20-25 kHz, above hearing):

1. sample the phase currents (ADC, triggered by the PWM timer),
2. read the rotor angle (encoder),
3. Clarke and Park transforms: three currents to two (d, q) in the
   rotor's frame,
4. two PI controllers: d current to 0, q current to the torque setpoint
   (from the balance controller),
5. inverse Park and space vector PWM back to three duty cycles.

Steps 2-5 of this plan cover the maths and the PWM; the current
sensing, encoder and PI loops need the hardware.

Zephyr's PWM API sets one channel at a time and has no centre-aligned
or ADC-trigger options, so motor PWM uses the timer directly through
ST's LL drivers (in `hal_stm32`, already a module), the same way ST's
own motor control SDK does. Zephyr still does the clocks and pinctrl.

## Step 1: pin and peripheral map

Paper only; you decide, then it goes into `docs/hardware.md`.

- **PWM: TIM1**, the advanced timer made for motors (centre-aligned
  mode, dead time, break input, ADC trigger). Linux leaves it disabled
  (`&timers1` in the kernel's `stm32mp15xx-dkx.dtsi`). Candidate pins:
  CH1 PE9 (Arduino D6), CH2 PE11 (D10), CH3 PE13 (D12); CH4 stays
  internal as the ADC trigger. The IMU plan already kept SPI4 free for
  this. Check each in ST's GPIO mapping.
- **Driver enable and fault:** two GPIOs, from the SimpleFOCMini's
  pinout (check its docs for v2.3: which pins, active level, and
  whether the DRV8316 there is the SPI or the hardware-configured
  variant).
- **Encoder (AS5048A, SPI):** SPI4 is gone if TIM1 uses PE11/PE13.
  Options: share SPI5 with the IMU (another chip select; check the bus
  time at the FOC rate together with the IMU's), or use the encoder's
  PWM output on a timer input. Decide here.
- **Current sense ADC:** Linux owns ADC1 and ADC2 today (the DK2's
  USB-C CC lines, `&adc` in the same dtsi). The M4 can't have them
  without a kernel device tree change. Options: patch the Linux DT
  (what does the board lose? the CC sensing for USB-C power), or start
  without current sensing (voltage-mode FOC, which SimpleFOC also
  supports) and add it later. Decide here; it only blocks the "later"
  row.
- Second motor: a second timer (TIM8?) and its pins, at least reserve
  them on paper so the first motor's choices don't block it.
- Done when: the table is agreed and in `docs/hardware.md`.

## Step 2: FOC maths

- `app/src/foc_math.c`/`.h`, plain C in float (the M4's FPU), no Zephyr
  calls: Clarke, Park, inverse Park, space vector PWM (min/max
  injection: the same result as the textbook sector method, simpler),
  electrical angle from the encoder's mechanical angle and the pole
  pair count (from the motor datasheet), angle wrapping.
- CMSIS-DSP has `arm_clarke_f32`, `arm_park_f32` and `arm_sin_cos_f32`;
  it's the industry alternative. Writing them yourself is a few lines
  each and teaches what they do; switch later if speed demands it.
- Tests (control maths, so critical): Clarke/Park round trip; a known
  current vector gives the expected d and q; SVPWM duties always in
  0..1, and the largest circle SVPWM can make (phase voltage amplitude
  V_bus/√3, about 15% more than plain sine PWM's V_bus/2) without
  clipping; angle wrapping at the edges.
- Time it on the M4 later (step 4): one iteration must fit well inside
  a 40-50 µs PWM period.
- Done when: the tests pass on `native_sim`.

## Step 3: three-phase PWM

- TIM1 in centre-aligned mode at the chosen frequency (start 20 kHz),
  three channels, outputs off until the code turns them on. Fixed duties
  e.g. 25%, 50%, 75% to start.
- Check the frequency and duties with the 2-channel scope: CH1 on PWM
  phase A and CH2 on phase B, then move CH2 to phase C. Trigger on
  CH1. In centre-aligned mode the pulses of all phases are centred on
  the same instant (the middle of the period), so the two traces line
  up around a common centre; edge-aligned PWM would line them up at
  the start instead. Seeing that is the check that the mode is right.
- Ground the probes to a DK2 GND pin, short ground leads; 3.3 V
  logic at 20 kHz needs nothing special (x10 probes are fine).
- Code size and the timer's clock (APB) go into this file.
- Done when: all three channels show the set duties at the set
  frequency.

## Step 4: open-loop rotation on LEDs

- Turn a voltage vector slowly (e.g. 0.5 Hz electrical) through the
  step 2 maths and write the duties every PWM period from the timer's
  update interrupt. This is exactly SimpleFOC's open-loop velocity mode,
  the first thing to try on a real motor.
- Three LEDs (with resistors) on the three outputs fade in and out 120°
  apart: SVPWM you can see.
- Measure the interrupt's time on the M4 (cycle counter, like the IMU
  fetch) and check the IMU still reads 850 Hz with 0 errors next to it.
- Also on the scope: a spare GPIO set high at the start of the
  interrupt and low at the end (a temporary debug pin, put it in
  `docs/hardware.md` while it's used). CH1 on a PWM phase, CH2 on that
  pin shows where in the period the interrupt runs and how long it
  takes, jitter included: the same method later shows where the ADC
  samples.
- Done when: the LEDs show the rotation and the timing is written down.

## Step 5: safe outputs

imu-plan step 5 found that **an M4 restart doesn't reset its
peripherals**. With PWM that means: if the M4 is stopped by Linux,
crashes or is reset by the watchdog (failsafe plan step 5), TIM1 may keep
putting out the last duties, and a motor would keep getting voltage.

- Try it with the step 4 LEDs: stop the M4 with remoteproc while they
  run and see what they do. The scope on two phases (single-shot
  trigger on the stop) shows exactly when and how the outputs change,
  not just whether the LEDs look dark.
- Fixes, in layers (the industry pattern: software turns off, hardware
  makes sure):
  - first thing at boot (an early `SYS_INIT`), force the outputs off
    before anything else runs;
  - on a disarm, the supervisor turns them off (TIM1's main output
    enable, MOE);
  - TIM1's break input can cut the outputs in hardware; decide whether
    to wire the driver's fault pin to it;
  - the driver's enable pin needs a pull-down so the driver is off
    whenever the M4 isn't driving it. Check what the SimpleFOCMini has.
- Done when: the LEDs go dark on an M4 stop, a crash and a watchdog
  reset, and the reason is written down here.

## Later, with the hardware

- Encoder read at the FOC rate, mechanical and electrical angle, motor
  zero-angle alignment.
- Current sensing (step 1 decision) sampled at the PWM centre, where
  the low-side switches conduct: which end of the count that is depends
  on the PWM mode, work it out with step 3's setup and check it on the
  scope (a phase output plus a GPIO toggled at the ADC trigger or in
  its end-of-conversion interrupt).
- Current PI loops, then torque control for the balance controller
  (`docs/archive/balance-plan.md`).
