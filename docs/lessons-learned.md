# Lessons learned (for agents)

Only traps that cost a past agent real time. Keep each entry short; add one
only if it saves the next agent significant work.

## Board access

- **Non-login ssh has no ROS env.** `ssh root@<ip> 'ros2 ...'` fails; prefix
  the command with `. /etc/profile;` (it sources `/opt/ros/jazzy/setup.sh`).
- **Board shell is BusyBox.** Short option forms only (`head -n 30`, not
  `head -30`), no `devmem`, no `timeout` or `pkill` (background the
  command, `sleep`, then `kill $!`). Read device tree / sysfs / debugfs instead.
- **Host key changed after a reflash** is expected, not an attack:
  `ssh-keygen -R <ip>`, then reconnect.
- Kill anything you started for a test (e.g. `micro_ros_agent`) before
  handing the board back.

## PC (ROS 2 in Docker)

- **`ros2` CLI hangs in a container when it spawns its own daemon**, and
  a ROS process as PID 1 ignores SIGTERM/Ctrl-C (`timeout` doesn't stop
  it). `pc/ros.sh` uses `docker run --init` and starts the daemon first.
  Use it, or pass `--no-daemon` (not accepted by `ros2 topic hz`).

## Yocto

- **meta-ros recipes that fetch from git may lack `S`** (written for newer
  bitbake). The error looks like a broken patch ("can't find file to
  patch") because the source dir is empty; set `S = "${WORKDIR}/git"`.
- **Don't add `meta-zephyr-bsp`**, only `meta-zephyr-core`. The bsp layer
  ships its own `stm32mp157c-dk2.conf`, which clashes with our A7 machine
  of the same name.
- **Never hand-write patches.** Edit the unpacked source under git and use
  `git format-patch`/`git diff`; hand-made patches failed to apply.

- **U-Boot extlinux `TIMEOUT 0` can hang the boot.** Any `MENU` line in
  extlinux.conf (ST adds `MENU BACKGROUND` with the `splashscreen` machine
  feature) turns on U-Boot's prompt, and timeout 0 then waits forever at
  "Enter choice:". No `MENU` line = no prompt, boots the first label.

## M4 (Zephyr)

- **`scripts/build.sh` fails with `/usr/bin/arm-none-eabi-gcc --version`
  failed**: VS Code's CMake Tools configured `app/` into `build/` with the
  host compiler. `scripts/build.sh -p always` fixes it; point CMake Tools
  elsewhere (`cmake.buildDirectory`) or disable its configure-on-open.
- **Memory layout / resource table:** read `docs/ros-plan.md` step 4 before
  touching the linker or `zephyr,flash`/`zephyr,sram`. `CONFIG_XIP=n` looks
  simpler but the heap overwrites `.resource_table` (rpmsg fails with -2).
- **Restarting the M4 does not reset its peripherals.** GPIO, EXTI, SPI
  and the sensors keep their state (and keep running) while the M4 is
  stopped, so the next boot can find pending interrupts. Symptom: every
  second start hangs with an empty trace0 and no `/dev/ttyRPMSG*`.
  Test with a few `stop`/`start` cycles, and find the hang with
  temporary `SYS_INIT` printk markers at each init level.
- **The rpmsg tty number can change.** If something still holds
  `/dev/ttyRPMSG1` open when the M4 restarts (e.g. the Agent), the new
  channel comes back as `ttyRPMSG2`, and the number can stay leaked until
  the board reboots. `dontfall-uros-agent.service` is bound to the tty
  device so this doesn't happen; a hand-started Agent must be stopped
  before restarting the M4.
- **micro-ROS client does nothing until Linux writes first** on
  `/dev/ttyRPMSG1` (`printf '\n' > /dev/ttyRPMSG1` before the Agent).
- **Right after an M4 restart, `/dev/ttyRPMSG1` doesn't exist yet.** A
  `printf` to it then creates a regular file, the Agent fails with
  `get termios attributes error | errno: 25`, and the M4 got no rpmsg
  channels. Wait for `[ -c /dev/ttyRPMSG1 ]`; `rm` a stale regular file
  and restart the M4.

## Hardware

- **Both cores share the GPIO register banks.** Zephyr's STM32 GPIO driver
  and Linux's stm32 pinctrl serialise access through the HSEM, so an M4
  pin is safe as long as the Linux devicetree doesn't claim it (no DT or
  boot-chain change needed). Record ownership in `docs/hardware.md`.

- **Modulino Movement (IMU) is wired for I2C only from the factory.** SPI
  needs board mods (SA0 jumper, CS bridge) and uses SDA/SCL, not the
  header's SCx/SDx. See `docs/imu-plan.md`, Wiring.
- **Flaky reads that recover after bus activity = missing GND**, not SPI
  settings. Check the ground wire before tuning pins. Bit-banging the same
  pins is a quick way to split hardware from peripheral/driver problems.
