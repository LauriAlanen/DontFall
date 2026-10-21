# M4 code space plan

Working plan for making room in the M4 firmware before motor control and
the balance controller go in. Code runs from MCU SRAM1 (128 KB,
`zephyr,flash`) and is at **124.4 KB (94.9%)** after imu-plan step 6c.
FOC, the balance controller, a supervisor and more micro-ROS entities
won't fit. Each step is small and ends with something you can check.
Update the status and numbers as steps land.

Read `docs/ros-plan.md` step 4 first (why the layout is what it is, and
why `CONFIG_XIP=n` was rejected).

| # | Step | Status |
|---|------|--------|
| 1 | Measure: what uses the 124 KB, and the micro-ROS build flags | todo |
| 2 | A RETRAM code region, one small app file moved there | todo |
| 3 | Move micro-ROS code to RETRAM | todo |
| 4 | Release: firmware from bitbake, budget written down | todo |

## Where the room is

The M4 sees four RAM blocks, all reserved for it by Linux (remoteproc
carveouts, `m4_rproc` `memory-region` in the kernel's
`stm32mp15xx-dkx.dtsi`):

| M4 address | A7 address | Size | Now |
|------------|------------|------|-----|
| 0x00000000 | 0x38000000 | 64 KB RETRAM | vector table only (`CONFIG_ROMSTART_RELOCATION_ROM`) |
| 0x10000000 | 0x10000000 | 128 KB SRAM1 | code, rodata, resource table |
| 0x10020000 | 0x10020000 | 128 KB SRAM2 | data, bss, stacks, malloc arena |
| 0x10040000 | 0x10040000 | 64 KB SRAM3 | rpmsg vrings and buffers |

RETRAM is almost empty: ~63 KB more code space without touching the
SRAM1/SRAM2 split (that split can't move usefully: the MPU needs
power-of-two aligned regions, so only 192/64 KB works, too little data).
remoteproc loads every ELF segment to its address, so code linked into
RETRAM just works, as long as it is linked in place (no copy at boot).

Why move micro-ROS and not our code: it is the biggest single block, it
changes only when the pin in `micro_ros.yml` changes, and it isn't
timing critical. Our own code (FOC, controller, IMU) stays in SRAM1
where it is now. Both regions are on the M4's code bus, so neither is
slower; step 3 checks that anyway.

## Step 1: measure

Goal: know what to move before moving anything, and catch cheap wins.

- `west build -t rom_report` (and `ram_report`) in the dev build. Write
  down the big groups: libmicroros, kernel, drivers (SPI, sensor, IPM,
  OpenAMP/libmetal), libc, software double maths, our app.
- Check how libmicroros is compiled: the module's toolchain file decides
  `-Os` vs `-O2`. Built for speed, `-Os` might save several KB for free.
  If so, it's a one-line change in the module patch, and bitbake builds
  it the same way (check both).
- Look for things we pay for and don't use (e.g. Kconfig defaults like
  assert strings, unused POSIX bits).
- Done when: a table here with the main users and their size, and a
  decision whether any cheap win is worth taking before step 2.

## Step 2: RETRAM code region

Goal: prove the mechanism with something small before micro-ROS.

- A `retram` memory node in the overlay with
  `zephyr,memory-region = "RETRAM"` (and `zephyr,memory-attr` for the
  MPU if needed), so the linker gets a RETRAM region. The vector table
  must stay at address 0: the region either starts after it, or the
  vector table moves into it explicitly. Work out which with
  `ROMSTART_RELOCATION_ROM` in the linker script.
- Move one small, harmless app file (e.g. `protocol.c`) with
  `zephyr_code_relocate(FILES ... LOCATION RETRAM_TEXT NOCOPY)`.
  `NOCOPY` because remoteproc already put the code there; without it
  Zephyr would try to copy it from a load address at boot.
- Check: `zephyr.map` shows the file's `.text` at 0x0000xxxx,
  `readelf -l zephyr.elf` has a segment for it, remoteproc loads it
  (trace0 boots), and PING/PONG still works (it runs that code).
- The MPU: Zephyr runs our threads privileged with the default memory
  map as background, which allows execution at address 0. If a fault
  says otherwise, add an MPU region for RETRAM.
- Done when: the firmware boots from the board with that file in
  RETRAM, and survives 5 M4 restarts in a row.

## Step 3: micro-ROS to RETRAM

- libmicroros is a prebuilt `.a` imported into the build, not a Zephyr
  library with sources, so `zephyr_code_relocate(LIBRARY ...)` may not
  take it. Fallback: a linker snippet
  (`zephyr_linker_sources(...)`) that places
  `*libmicroros.a:*(.text* .rodata*)` in the RETRAM region. Its `.data`
  and `.bss` stay in SRAM2.
- Move step 2's test file back.
- Check: SRAM1 use drops by roughly libmicroros's size (step 1), the
  Imu topic still runs at ~50 Hz on the PC, the IMU summary still reads
  850 Hz with the same fetch time, Agent kill/restart still reconnects.
- If micro-ROS is bigger than RETRAM, move only its biggest parts (the
  XRCE-DDS client and micro-CDR, from the step 1 table).
- Done when: the numbers are in this file and the checks above pass.

## Step 4: release

- Bump `SRCREV_dfm4`, build the image, deploy, check the topic from the
  image's own firmware (`./build.sh bitbake mc:zephyr-m4:dontfall-m4`
  builds the same overlay and CMake, so it should just work).
- Write the budget down in this file: SRAM1 and RETRAM used/free, and a
  rule for later steps, e.g. "every plan step that adds M4 code notes
  the new size; stay under 90% of each region".
- Add a lesson to `docs/lessons-learned.md` if anything here cost time.

## Open questions

- If even this isn't enough later: fewer micro-ROS entities, a smaller
  MTU or history, or `float` instead of `double` maths in our code
  (the M4's FPU is single precision only).
