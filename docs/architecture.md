# DontFall architecture

What runs where and how the parts talk, as it is now. The why is in
`VISION.md`; pins and memory in `docs/hardware.md`. Keep this file current:
see "Keeping this up to date" at the end.

## The whole system

GitHub and VS Code render this. In a plain terminal, read the edges:
`A -->|label| B` means A sends to B over that link. Dashed boxes are
planned, not built yet (`docs/foc-plan.md`, `docs/balance-plan.md`).

```mermaid
flowchart TB
    subgraph PC["PC (WSL2, pc/ros.sh: ROS 2 Jazzy container)"]
        tools["ros2 CLI, teleop_twist_keyboard,<br/>PlotJuggler, RViz"]
    end

    subgraph BOARD["STM32MP157C-DK2"]
        subgraph A7["Cortex-A7, Linux (Yocto df-image-minimal, signed boot)"]
            agent["micro-ROS Agent<br/>dontfall-uros-agent.service<br/>XRCE-DDS to DDS bridge"]
            app["dontfall-app, node dontfall_app<br/>dontfall-app.service<br/>watches /diagnostics, LD8"]
            rproc["remoteproc<br/>dontfall-m4.service<br/>loads dontfall-m4.elf"]
            web["web UI, screen, camera"]:::planned
        end

        subgraph M4["Cortex-M4, Zephyr (DontFall-M4)"]
            uros["uros.c, prio 5<br/>node dontfall_m4"]
            link["ipc.c, link_led.c<br/>rpmsg-tty, LD7 = Agent session"]
            ctrl["control.c + supervisor.c<br/>arm/disarm, tilt limit,<br/>IMU faults, cmd_vel timeout"]
            imu["imu.c, prio 7, 850 Hz<br/>tilt.c, imu_health.c"]
            wdog["wdog.c: WWDG1, 100 ms<br/>kicked only while every source checks in"]
            foc["FOC + balance controller"]:::planned
        end
    end

    sensor["LSM6DSOX IMU<br/>Arduino Modulino Movement"]
    motor["SimpleFOCMini + gimbal motor<br/>AS5048A encoder"]:::planned

    tools <-->|"ROS 2 (DDS), LAN"| agent
    agent <-->|"/dev/ttyRPMSG0, RPMsg"| link
    link <--> uros
    app <-->|"/diagnostics, DDS"| agent
    rproc -.->|loads and restarts| M4
    uros -->|"arm, cmd_vel"| ctrl
    ctrl -->|"state for /diagnostics"| uros
    imu -->|"samples, tilt"| ctrl
    imu -->|"/dontfall_m4/imu data"| uros
    sensor -->|"SPI5 + INT1 (EXTI3)"| imu
    ctrl -.->|setpoint| foc
    foc -.->|"PWM; encoder over SPI"| motor
    web -.->|"commands via ROS 2"| agent

    classDef planned stroke-dasharray: 5 5
```

## The ROS 2 interface of the M4

The node `dontfall_m4`, from `uros.c`. This is the only interface between
the A7 and the M4's control code.

| Name | Kind, type | QoS | Direction | Purpose |
|------|------------|-----|-----------|---------|
| `/dontfall_m4/imu` | publisher, `sensor_msgs/Imu` | best effort, 50 Hz | M4 -> A7 | IMU data and tilt estimate |
| `/diagnostics` | publisher, `diagnostic_msgs/DiagnosticArray` | reliable, 1 Hz | M4 -> A7 | IMU and supervisor state |
| `/dontfall_m4/arm` | service, `std_srvs/SetBool` | reliable | A7 -> M4 | arm (true) or disarm (false) |
| `/cmd_vel` | subscription, `geometry_msgs/Twist` | best effort | A7 -> M4 | speed setpoint, zeroed after a timeout |

## Who owns what

- **M4:** everything timing critical and every safety decision: the IMU,
  the supervisor (armed or not), the command timeout, the watchdog, and
  later the motors and balancing. It must stay safe with the A7 dead.
- **A7:** the micro-ROS Agent, the app (ROS 2 node `dontfall_app`), and later everything
  high level. It only sends commands; it never closes a fast loop.
- **Build host (`DontFall`):** Yocto layers in `meta-df`, the image, the
  M4 firmware built by bitbake from pinned revisions (`zephyr-m4`
  multiconfig), build and deploy scripts.

## Keeping this up to date

Update this file in the same change as any architectural change:

- a new or removed process, service, M4 thread or source module with its
  own job
- a new or changed link between parts (tty, RPMsg channel, bus, network)
- a micro-ROS entity added, removed, renamed or with another type or QoS
- a responsibility moving between the M4, the A7 and the PC
- a planned part becoming real (move it out of "Planned")

Bug fixes, tuning and changes inside a module don't need an update.
