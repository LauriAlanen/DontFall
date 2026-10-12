# Two-core blinky design

## Intent

A side-quest demo: each core of the STM32MP157C-DK2 blinks its own on-board
LED, so you can see at a glance that both the A7 (Linux) and the M4 (Zephyr)
are alive and running independently. It is a learning exercise, not a robot
feature.

## What it does

| Core | LED | Pin | Interval | Mechanism |
|------|-----|-----|----------|-----------|
| A7 (Linux) | Blue LD8 | PD11 | toggles every 1000 ms | `dontfall-app` through the Linux LED class, run by a systemd service |
| M4 (Zephyr) | Orange LD7 | PH7 | toggles every 500 ms | Zephyr GPIO API (`gpio_dt_spec`), driven by a `k_timer` |

"Interval" means the time between toggles (1000 ms on, 1000 ms off for the
A7). This is the same convention as Zephyr's blinky sample. *(Builder's call.)*

## Decided with the developer

- LEDs: orange LD7 for the M4, blue LD8 for the A7.
- The A7 uses the **LED class** (`/sys/class/leds/<name>/{trigger,brightness}`),
  not raw GPIO/libgpiod. LD8 is already described as an LED in ST's Linux
  devicetree (`label = "heartbeat"`, `linux,default-trigger = "heartbeat"`),
  so no devicetree change is needed. The app takes the LED over by writing
  `none` to `trigger`.
- The A7 blink lives in `dontfall-app`, which becomes a long-running process
  started by a new `dontfall-app.service`.
- **The OpenAMP hello stays.** The app still sends "hello from A7" once at
  startup and prints the M4's reply, then blinks.

## Builder's calls (not discussed; check these)

- **Blink mode is opt-in with `--led <sysfs-led-dir>`.** Without it, the app
  behaves exactly as today (hello, then exit with codes 0/1/2), so the
  existing host tests and `./deploy.sh --run /usr/local/bin/dontfall-app`
  still work. The service runs `dontfall-app --led /sys/class/leds/heartbeat`.
- In blink mode a failed hello (no device, no reply) is logged to stderr but
  is **not fatal**: the LED keeps blinking. A missing or unwritable LED
  directory **is** fatal (exit 1), so systemd reports the misconfiguration.
- On SIGTERM/SIGINT (`systemctl stop`) the app turns the LED off, restores
  the trigger it found at startup (normally `heartbeat`) and exits 0.
- The A7 loop sleeps to absolute deadlines (`clock_nanosleep`,
  `CLOCK_MONOTONIC`, `TIMER_ABSTIME`) so the interval doesn't drift.
- The M4 uses a periodic `k_timer` (toggle in the expiry callback) instead of
  a thread with `k_msleep`: it needs no thread or stack, and the period
  doesn't drift.
- The service unit lives in `meta-df` next to the recipe, the same pattern as
  `dontfall-m4.service`. It must be on the rootfs, because `/usr/local`
  (userfs) is mounted later. It orders after `dontfall-m4.service` and
  `dev-ttyRPMSG0.device` so the hello has a channel to talk to, and it only
  *wants* them, so the blink still starts if the M4 is down.
- The service runs as root (sysfs LED writes need it). That's fine for a
  demo; the production alternative is a udev rule granting a dedicated user
  access to the LED.

## Constraints

- Follows `VISION.md`: no hard real-time on the A7. A 1 s Linux blink is not
  timing-critical.
- Pin ownership is recorded in `docs/hardware.md` in the same change that
  starts using the pins.
- Both cores share GPIO register banks. Zephyr's STM32 GPIO driver and
  Linux's stm32 pinctrl driver serialise register access through the
  hardware semaphore (HSEM). PH7 is not claimed by Linux, so no devicetree
  or boot-chain change is needed. Never touch the signed boot chain.

## Non-goals

- No new RPMsg protocol: the LEDs are not commanded across cores.
- No ROS 2 or micro-ROS.
- No bump of the release pins (`SRCREV`, `SRCREV_dfm4`); that waits until
  the developer pushes the sibling repos.
