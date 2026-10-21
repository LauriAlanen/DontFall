# M4 code space plan

Working plan for making room in the M4 firmware before motor control and
the balance controller go in. Code runs from MCU SRAM1 (128 KB,
`zephyr,flash`) and was at **124.4 KB (94.9%)** after imu-plan step 6c,
**58.4 KB (45.6%)** after step 3 (micro-ROS moved to RETRAM, which is
at 52.8 of 63 KB, 83.9%).
FOC, the balance controller, a supervisor and more micro-ROS entities
won't fit. Each step is small and ends with something you can check.
Update the status and numbers as steps land.

Read `docs/archive/ros-plan.md` step 4 first (why the layout is what it is, and
why `CONFIG_XIP=n` was rejected).

| # | Step | Status |
|---|------|--------|
| 1 | Measure: what uses the 124 KB, and the micro-ROS build flags | done |
| 2 | A RETRAM code region, one small app file moved there | done |
| 3 | Move micro-ROS code to RETRAM | done |
| 4 | Release: firmware from bitbake, budget written down | done |

## Where the room is

The M4 sees four RAM blocks, all reserved for it by Linux (remoteproc
carveouts, `m4_rproc` `memory-region` in the kernel's
`stm32mp15xx-dkx.dtsi`):

| M4 address | A7 address | Size | Now |
|------------|------------|------|-----|
| 0x00000000 | 0x38000000 | 64 KB RETRAM | vector table (1 KB, `CONFIG_ROMSTART_RELOCATION_ROM`), libmicroros code and rodata from 0x400 (step 3) |
| 0x10000000 | 0x10000000 | 128 KB SRAM1 | all other code and rodata, resource table |
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

**Results (done).** `rom_report` in the dev build, sizes in bytes,
before and after the `-Os` fix below. (`rom_report` also counts the
664 B vector table in RETRAM; the linker's FLASH line doesn't.)

| Group | -O3 | -Os |
|-------|----:|----:|
| micro-ROS libraries (`modules/lib/micro_ros/src`) | 49295 | 43333 |
| - Micro-XRCE-DDS-Client | 17558 | 14102 |
| - rcl | 15132 | 13596 |
| - micro-CDR | 5166 | 5170 |
| - rclc | 4206 | 3900 |
| - rmw-microxrcedds | 3069 | 2949 |
| - rcutils | 2732 | 2302 |
| - rmw | 1120 | 1052 |
| micro-ROS generated type support (module's `micro_ros_src/{build,install}`) | 13994 | 12986 |
| Zephyr: drivers (SPI 2834, UART 2308, sensor 2202, clock 1344, GPIO 1218, EXTI 732) | 12238 | 12238 |
| Zephyr: kernel | 7350 | 7350 |
| Zephyr: arch + lib | 4416 | 4416 |
| HALs (stm32 3926, st 1386, libmetal 826) | 6138 | 6154 |
| open-amp | 3888 | 3888 |
| Our app (`app/src`, imu.c biggest at 2351) | 6428 | 6428 |
| No path: libc/libgcc (soft double ~2.3 K, float trig ~4 K, printf 1.2 K), ISR table 1.2 K | 16006 | 13911 |
| Hidden (linker padding, small symbols) | 5417 | 4753 |
| **Total** | **126398** | **116685** |

- **libmicroros was built with `-O3`.** Zephyr passes its `-Os` in
  `CMAKE_C_FLAGS_INIT`, but `libmicroros.mk` sets
  `CMAKE_BUILD_TYPE=Release`, whose `-O3 -DNDEBUG` comes later on the
  command line and wins. Taken: module patch `0003` switches to
  `MinSizeRel` (`-Os -DNDEBUG`). FLASH 124.4 KB -> 113.3 KB (94.9% ->
  88.5%), ~9.7 KB saved by `rom_report`. bitbake applies the same
  patch (step 4 checks).
  Board check: IMU 850 Hz, fetch max 54 us (same as `-O3`), Imu topic
  ~49 Hz on the PC, Agent restart reconnects.
- micro-ROS in total is now ~56 KB (libraries + type support), plus
  some micro-CDR functions under "no path". RETRAM has ~63 KB free, so
  it fits in step 3, but with only ~7 KB to spare there.
- Taken too: the UART driver. Zephyr's board defaults enabled USART3
  (PB10/PB12) and UART7 (PE7/PE8, Arduino D0/D1) and the M4 set them up
  at boot, though nothing on the M4 uses a UART (console is the RAM
  console). Now `status = "disabled"` in the overlay and
  `CONFIG_SERIAL=n`, so the M4 no longer claims those pins either.
  FLASH 113.3 KB -> 110.8 KB (86.6%).
- Assert is already off (`CONFIG_ASSERT` unset); float printf off
  since imu-plan step 6.

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

**Results (done).**
- Overlay: the SoC's `retram` node (0x0, 64 KB) is replaced by
  `retram: memory@400` (63 KB, `compatible = "zephyr,memory-region"`,
  `zephyr,memory-region = "RETRAM"`), which gives the linker a `RETRAM`
  region. The vector table keeps its own 1 KB `ROMSTART_REGION` at 0x0:
  `CONFIG_ROMSTART_REGION_ADDRESS`/`_SIZE` are fixed Kconfig defaults
  (0x0, 1 KB), not taken from the node, so the two regions sit side by
  side without overlapping.
- `CONFIG_CODE_DATA_RELOCATION=y` and
  `zephyr_code_relocate(FILES src/protocol.c LOCATION RETRAM_TEXT NOCOPY)`
  in `app/CMakeLists.txt`. Linker output: `RETRAM 296 B`, FLASH 232 B
  less. `readelf -l`: its own `R E` segment at 0x400 with load address =
  run address (nothing to copy); `zephyr.map` has `protocol_rx` at 0x400.
- **Calls between RETRAM and SRAM1 go through veneers.** 0x400 and
  0x1000xxxx are ~256 MB apart, outside a Thumb `BL`'s ±16 MB, so the
  linker adds a small long-branch stub (`.text.reply.__stub`, 48 B
  here) per far call target. Automatic and only a few cycles per call,
  but it counts towards the size of whatever moves in step 3.
- MPU: no extra region needed. Privileged threads run with the default
  memory map as background, which allows execution at 0x400.
- Board: boots, `heartbeat: A7 link up` (PING/PONG runs `protocol_rx`
  from RETRAM), IMU 850 Hz / fetch 54 us. 5 `stop`/`start` cycles in a
  row: each comes back with the A7 link up, micro-ROS publishing, IMU
  at 850 Hz and the ttys still `ttyRPMSG0`/`ttyRPMSG1`.

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

**Results (done).**
- `zephyr_code_relocate()` can't take the prebuilt `.a` (its script
  looks for objects built from sources), and no existing snippet
  location works either: ld puts an input section into the *first*
  output section whose pattern matches, and Zephyr's text section
  (`*(.text.*)`) comes before every location that may define output
  sections. Zephyr patch `0004` adds one, `PRE_TEXT_SECTIONS`, included
  right before the text section in the Cortex-M linker script.
  (Rejected: feeding extracted objects to `zephyr_code_relocate()`,
  indirect CMake plumbing; a custom linker script, region redeclared.)
- `app/retram.ld`: `*libmicroros.a:*(.text .text.* .rodata .rodata.*)`
  into `RETRAM`, added with `zephyr_linker_sources(PRE_TEXT_SECTIONS ...)`.
  Step 2's `protocol.c` relocation and `CONFIG_CODE_DATA_RELOCATION` are
  gone again; the `retram` DT region stays.
- Sizes (linker): FLASH 113.2 KB -> 58.4 KB (86.4% -> 45.6%), RETRAM
  52.8 KB of 63 KB (83.9%): 49.8 KB code, 4.4 KB rodata, 216 B veneers.
  Nothing from libmicroros is left in SRAM1. Veneers for the calls the
  other way add 208 B in SRAM1, so the move costs ~0.4 KB in total.
- Board: IMU 850 Hz, fetch max 54-59 us (unchanged), Imu topic ~49 Hz
  on the PC, 3 Agent kill/restarts each reconnect (new datawriter), 3
  M4 restarts each come back with the A7 link, micro-ROS and IMU, ttys
  unchanged. Host tests pass with the Zephyr patch.
- Headroom: RETRAM has ~10 KB left for micro-ROS growth (every new
  message type adds generated type support code there). If it runs
  out, `.rodata` (4.4 KB) can go back to SRAM1 first.

## Step 4: release

- Bump `SRCREV_dfm4`, build the image, deploy, check the topic from the
  image's own firmware (`./build.sh bitbake mc:zephyr-m4:dontfall-m4`
  builds the same overlay and CMake, so it should just work).
- Write the budget down in this file: SRAM1 and RETRAM used/free, and a
  rule for later steps, e.g. "every plan step that adds M4 code notes
  the new size; stay under 90% of each region".
- Add a lesson to `docs/lessons-learned.md` if anything here cost time.

**Results (done).** `SRCREV_dfm4` -> `cfcbe20`. bitbake applies all
seven patches (Zephyr 0001-0004, module 0001-0003) and links the same
sizes as west (RETRAM identical, FLASH 4 B apart). Release image
deployed (`--image --reboot`): the board's `dontfall-m4.elf` has the
same md5 as the bitbake ELF, IMU 850 Hz / fetch 54-58 us, Agent session
up, Imu topic ~49 Hz on the PC.

Current numbers and rules now live in `docs/hardware.md`, "M4 memory
budget"; this is the snapshot at release.

**Budget** (linker's memory report, `scripts/build.sh` or the bitbake
`log.do_compile`, at DontFall-M4 `cfcbe20`):

| Region | Holds | Used | Free |
|--------|-------|-----:|-----:|
| SRAM1 (`FLASH`), 128 KB | our code, Zephyr, drivers, libc, all non-micro-ROS rodata | 58.4 KB (45.6%) | 69.6 KB |
| RETRAM (`RETRAM`), 63 KB | libmicroros code + rodata | 52.8 KB (83.9%) | 10.2 KB |
| SRAM2 (`RAM`), 128 KB | static data, bss, stacks | 60.8 KB (47.5%) | rest is the malloc arena micro-ROS allocates from |

Rules for later steps:
- Every plan step that adds M4 code notes the new FLASH and RETRAM
  sizes in its own plan.
- Stay under 90% of each region. RETRAM is already at 84%, so a new
  micro-ROS message type (generated type support lands there) should
  check it; first relief is moving libmicroros's `.rodata` (4.4 KB)
  back to SRAM1 in `app/retram.ld`.
- Our own code goes in SRAM1. Only move something else to RETRAM if it
  isn't timing critical, and expect a few hundred bytes of veneers.

## Open questions

- If even this isn't enough later: fewer micro-ROS entities, a smaller
  MTU or history, or `float` instead of `double` maths in our code
  (the M4's FPU is single precision only).
