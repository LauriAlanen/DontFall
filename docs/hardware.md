# DontFall hardware

Board: STM32MP157C-DK2.

## Pin reference

For every pin question, the source of truth is ST's GPIO mapping for the
DKx boards:
<https://wiki.st.com/stm32mpu/wiki/STM32MP157x-DKx_-_hardware_description#GPIO_mapping>

Before using a pin, check there that it is routed to a header (Arduino or
GPIO expansion) and not already used by something on the board. Don't guess
pins from memory.

The tables below are transcribed from that page. If they disagree with it,
the ST page wins.

### GPIO expansion connector (CN2)

| Function | STM32 pin | Pin | Pin | STM32 pin | Function |
|----------|-----------|-----|-----|-----------|----------|
| 3V3 | - | 1 | 2 | - | 5V |
| GPIO2 / I2C5_SDA | PA12 | 3 | 4 | - | 5V |
| GPIO3 / I2C5_SCL | PA11 | 5 | 6 | - | GND |
| GPIO4 / MCO1 | PA8 | 7 | 8 | PB10 | GPIO14 / USART3_TX |
| GND | - | 9 | 10 | PB12 | GPIO15 / USART3_RX |
| GPIO17 / USART3_RTS | PG8 | 11 | 12 | PI5 | GPIO18 / SAI2_SCKA (1) |
| GPIO27 / SDMMC3_D3 | PD7 | 13 | 14 | - | GND |
| GPIO22 / SDMMC3_CK | PG15 | 15 | 16 | PF1 | GPIO23 / SDMMC3_CMD |
| 3V3 | - | 17 | 18 | PF0 | GPIO24 / SDMMC3_D0 |
| GPIO10 / SPI5_MOSI | PF9 | 19 | 20 | - | GND |
| GPIO9 / SPI5_MISO | PF8 | 21 | 22 | PF4 | GPIO25 / SDMMC3_D1 |
| GPIO11 / SPI5_SCK | PF7 | 23 | 24 | PF6 | GPIO8 / SPI5_NSS |
| GND | - | 25 | 26 | PF3 | GPIO7 |
| I2C1_SDA (2) | PF15 | 27 | 28 | PD12 | I2C1_SCL (2) |
| GPIO5 / MCO2 | PG2 | 29 | 30 | - | GND |
| GPIO6 / TIM5_CH2 | PH11 | 31 | 32 | PD13 | GPIO12 / TIM4_CH2 |
| GPIO13 / TIM3_CH2 | PC7 | 33 | 34 | - | GND |
| GPIO19 / SAI2_FSA (1) | PI7 | 35 | 36 | PB13 | GPIO16 / USART3_CTS |
| GPIO26 / SDMMC3_D2 | PF5 | 37 | 38 | PI6 | GPIO20 / SAI2_SDA (1) |
| GND | - | 39 | 40 | PF11 | GPIO21 / SAI2_SDB (1) |

1. SAI2 is shared with the audio codec. By default it goes to the codec
   (solder bridges SB13-SB16); see the DKx user manual.
2. I2C1 also talks to several on-board peripherals. Anything added to it
   must not disturb that bus.

### Arduino Uno connector

CN13 (10x1):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | ARD_D8 | PG3 | IO |
| 2 | ARD_D9 | PH6 | TIM12_CH1 |
| 3 | ARD_D10 | PE11 | SPI4_NSS and TIM1_CH2 |
| 4 | ARD_D11 | PE14 | SPI4_MOSI and TIM1_CH4 |
| 5 | ARD_D12 | PE13 | SPI4_MISO |
| 6 | ARD_D13 | PE12 | SPI4_SCK |
| 7 | GND | - | GND |
| 8 | VREFP | - | VREF+ |
| 9 | ARD_D14 | PA12 | I2C5_SDA |
| 10 | ARD_D15 | PA11 | I2C5_SCL |

CN14 (8x1):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | ARD_D0 | PE7 | UART7_RX |
| 2 | ARD_D1 | PE8 | UART7_TX |
| 3 | ARD_D2 | PE1 | IO |
| 4 | ARD_D3 | PD14 | TIM4_CH3 |
| 5 | ARD_D4 | PE10 | IO |
| 6 | ARD_D5 | PD15 | TIM4_CH4 |
| 7 | ARD_D6 | PE9 | TIM1_CH1 |
| 8 | ARD_D7 | PD1 | IO |

CN16 (8x1, power):

| Pin | Signal | Comment |
|-----|--------|---------|
| 1 | NC | reserved for test |
| 2 | 3V3 | IOREF 3V3 |
| 3 | NRST | NRST |
| 4 | 3V3 | 3V3 |
| 5 | 5V | 5V |
| 6 | GND | GND |
| 7 | GND | GND |
| 8 | VIN | not connected |

CN17 (6x1, analog):

| Pin | Signal | STM32 pin | Comment |
|-----|--------|-----------|---------|
| 1 | A0 | PF14 | ADC1_IN0 |
| 2 | A1 | PF13 | ADC1_IN1 |
| 3 | A2 | ANA0 | ADC1_IN6 |
| 4 | A3 | ANA1 | ADC1_IN2 |
| 5 | A4 | PC3 / PA12 | ADC1_IN13 |
| 6 | A5 | PF12 / PA11 | |

PA12/PA11 (I2C5) are on CN2 pins 3/5 and ARD_D14/D15, and are also listed
as an option for A4/A5. Those are the same pins, so check the DKx user manual
for how A4/A5 are routed before using them alongside I2C5.

### User buttons and LEDs

| LED | Button | GPIO |
|-----|--------|------|
| Green LD5 (3) | USER1 (3) | PA14 |
| Red LD6 (3) | USER2 (3) | PA13 |
| Orange LD7 | - | PH7 |
| Blue LD8 | - | PD11 |

3. The LED and the button share the GPIO. The LED is active low (it lights
   when the GPIO output is low).

Some user LEDs show system activity by default; see ST's "LEDs and buttons
on STM32 MPU boards" article before taking one over.

## Pin assignments

Every pin the project uses goes in this table, in the same change that
starts using it. "Core" is the core that owns the peripheral: M4 (Zephyr)
or A7 (Linux).

| Signal | SoC pin | Header pin | Peripheral / AF | Core | Notes |
|--------|---------|------------|-----------------|------|-------|
| LD7 orange user LED | PH7 | on-board | GPIO output | M4 | Agent link status: toggles every 500 ms while the micro-ROS session with the Agent is up, off otherwise (Zephyr `led0`, `link_led.c`) |
| LD8 blue user LED | PD11 | on-board | GPIO via Linux LED class (`/sys/class/leds/heartbeat`) | A7 | M4 link status: toggles every 1000 ms while the M4's `/diagnostics` arrive (3 s timeout), off otherwise; dontfall-app takes it over from the heartbeat trigger |
| IMU SPI clock | PF7 | CN2 23 | SPI5_SCK | M4 | LSM6DSOX on the Modulino Movement, SPI mode 3 (docs/archive/imu-plan.md) |
| IMU SPI MOSI | PF9 | CN2 19 | SPI5_MOSI | M4 | |
| IMU SPI MISO | PF8 | CN2 21 | SPI5_MISO | M4 | |
| IMU chip select | PF6 | CN2 24 | GPIO output (SPI5 `cs-gpios`) | M4 | active low; hardware NSS not used |
| IMU INT1 | PF3 | CN2 26 | GPIO input (EXTI3) | M4 | data ready, pulsed, active high (imu-plan step 3) |

## M4 memory budget

From the linker's memory report (`scripts/build.sh` or the bitbake
`log.do_compile`), after failsafe plan step 6. How it got here:
`docs/archive/m4-memory-plan.md`; why the layout is what it is:
`docs/archive/ros-plan.md` step 4.

| Region | Holds | Used | Free |
|--------|-------|-----:|-----:|
| SRAM1 (`FLASH`), 128 KB | our code, Zephyr, drivers, libc, all rodata, micro-ROS message type code | 86.5 KB (67.6%) | 41.5 KB |
| RETRAM (`RETRAM`), 63 KB | libmicroros runtime code (XRCE client, rcl, rclc, rmw, micro-CDR) | 50.0 KB (79.3%) | 13.0 KB |
| SRAM2 (`RAM`), 128 KB | static data, bss, stacks | 72.5 KB (56.6%) | rest is the malloc arena micro-ROS allocates from |

Rules:
- Every plan step that adds M4 code notes the new FLASH and RETRAM
  sizes in its own plan, and updates this table when it lands.
- Stay under 90% of each region.
- What goes where (`app/retram.ld`): RETRAM holds only libmicroros's
  runtime code, which grows only with a new *kind* of entity (the first
  service added ~7 KB, the first subscription ~1.8 KB). Its rodata
  and the generated code of every message type (`*rosidl*` objects) are
  in SRAM1, so a new message type costs SRAM1, not RETRAM.
- Our own code goes in SRAM1. Only move something else to RETRAM if it
  isn't timing critical, and expect a few hundred bytes of veneers.
- If even this isn't enough later: fewer micro-ROS entities, a smaller
  MTU or history, or `float` instead of `double` maths in our code
  (the M4's FPU is single precision only).

## Display and touch

The DK2's own MB1407 board, checked in face plan step 0
(`docs/archive/face-plan.md`).

| What | Value |
|------|-------|
| Display | LTDC -> DSI -> Orise Tech OTM8009A panel, DRM driver `stm` |
| DRM device | `/dev/dri/by-path/platform-5a001000.display-controller-card` (the card number can change: `simpledrm` holds card0 until `stm` loads) |
| Connector | `DSI-1` (id 34), CRTC id 41; `HDMI-A-1` (SiI9022 bridge) exists too, unused |
| Modes | 480x800 portrait, 50 Hz (preferred) and 60 Hz; 52x86 mm |
| Planes | primary (id 37): XR24/AR24, RG24, RG16 (RGB565) and more; one overlay (id 42) |
| Backlight | `/sys/class/backlight/5a000000.dsi.0`, 0-255 |
| Touch | FocalTech `EP0110M09` (driver `edt-ft5x06`) on I2C1 at 0x38, `/dev/input/by-path/platform-40012000.i2c-event`; x 0-479, y 0-799 (same orientation as the panel), 2 touch points, ~100 Hz |
| Console | none on the panel: no fbdev emulation, the VT console is the dummy device, so the getty on tty1 draws nothing |
| GPU | Vivante GC400 rev 4652 at `59000000.gpu`, ST's `galcore` driver (`/dev/galcore`), OpenGL ES 2.0 + EGL 1.5 + GBM from `gcnano-userland` on `/vendor/lib`; reserves 64 MB of RAM at 0xd4000000 |

## Camera

Checked in camera plan step 0 (`docs/camera-plan.md`).

| What | Value |
|------|-------|
| Camera | Dell Webcam WB3023, USB id 413c:c03e (Wistron), UVC 1.00, USB 2.0 high speed |
| Port | DK2 USB host, behind the on-board hub (0424:2514), bus path `usb-5800d000.usb-1.2` |
| Devices | `/dev/video0` capture, `/dev/video1` metadata, `/dev/media0`; `/dev/camera` links to the capture node and has a systemd unit `dev-camera.device` (udev rule `70-dontfall-camera.rules`, any UVC camera) |
| Formats | MJPG: 640x480 @30, 1280x720 @60/30/24, 1920x1080 @30/24, 2560x1440 @30/24; YUYV and NV12: 640x360 and 640x480 @30 only |
| MJPEG frame size (this room) | 640x480 ~27 kB (~0.8 MB/s at 30 fps), 1280x720 ~68 kB (~2 MB/s at 30 fps) |
| Board CPU while streaming MJPEG to a file | ~0% (`v4l2-ctl`, no decode) |
| Controls | autofocus (continuous), auto exposure, white balance, pan/tilt/zoom (digital), `power_line_frequency` defaults to 60 Hz: set 50 Hz (`1`) in Finland against flicker |
| Probe warning | `Failed to set UVC probe control : -32 (exp. 26)` at boot; harmless, streaming works |

## Parts

| Part | Status |
|------|--------|
| STM32MP157C-DK2 | on hand |
| MB1407 DSI display, 480x800 (comes with the DK2) | on hand, the robot's screen |
| Dell Webcam WB3023 (USB camera, 2K, UVC) | on hand, works on the board (camera plan step 0) |
| Arduino Modulino Movement (ABX00101), ST LSM6DSOX 6-axis IMU; SPI clock/data-in are the SDA/SCL pins (not the 1x10 header's SCx/SDx), SDO/CS/INT on the 1x10 header, SPI needs the SA0 jumper cut and the CS-to-3V3 bridge removed, no I2C pull-ups fitted. Details: docs/archive/imu-plan.md | on hand, modded for SPI, works |
| SimpleFOCMini v2.3 (DRV8316, 5-35 V, 3x low-side current sense), x2-3 | planned, after the IMU works |
| iPower GM4108H-120T gimbal motor with AS5048A encoder (SPI, 14-bit), x1 | planned, after the IMU works |
| Current-limited bench supply, 12-24 V | planned, after the IMU works |
| Second motor | undecided |

First bench target, once the IMU and the motor work on their own: a
free-standing reaction wheel balancer (one motor with a flywheel on top,
tilt from the IMU, no pivot encoder). Pins are
not assigned yet.
