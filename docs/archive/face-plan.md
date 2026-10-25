# Face plan (googly eyes)

Finished plan (all steps done, 2026-10-25) for the robot's face: two
eyes on the DK2's 480x800 DSI display that blink, look around, slide
with the IMU like googly eyes and show the robot's state. Personality,
not a status display (VISION.md). The code is `src/face/` in
DontFall-app (`dontfall-face`, ROS 2 node `dontfall_face`).

| # | Step | Status |
|---|------|--------|
| 0 | Check the display and touch on the board, no code | done |
| 1 | GPU on: accept ST's EULA, Vivante driver, `kmscube` on the screen | done |
| 2 | SDL2 in the build, a new `dontfall-face` program: still eyes from one shader | done |
| 3 | Moods and tweens; alive when idle (blinks, glances), CPU/GPU measured | done |
| 4 | ROS 2 node: googly eyes driven by the IMU's gravity vector | done |
| 5 | Moods from the robot's state (armed, fault, link lost) | done |
| 6 | Touch: poke the eyes | done |
| 7 | Systemd service, starts at boot, below the robot's own processes | done |

## What is already there

- The image has the display stack in the kernel and nothing above it:
  `stm-drm` (LTDC), `dw-mipi-dsi-stm`, the `orisetech-otm8009a` panel,
  `edt-ft5x06` touch and the open-source GPU driver `etnaviv` are
  installed (machine conf, `docs/archive/image-plan.md` step 2), and
  `/dev/dri/card*` showed up on the board. There is no Weston, Wayland or
  X: `df-image-minimal` starts from `packagegroup-core-boot`.
- The GPU is a Vivante GC400: OpenGL ES 2.0, small but plenty for 2D
  effects at 480x800. ST's userspace for it (`gcnano-userland`: EGL,
  GBM, GLES 2.0) is in meta-st-stm32mp, behind the ST EULA. Accepting it
  (`ACCEPT_EULA_stm32mp157c-dk2 = "1"`) turns on the `gpu` machine
  feature, and ST's `gpu_vivante.inc` then makes gcnano the provider of
  `virtual/egl`, `virtual/libgles2` and `virtual/libgbm`.
- oe-core has SDL2 with a KMS/DRM backend (`PACKAGECONFIG` `kmsdrm`,
  `gles2`, off by default) and `kmscube`.

## The look: procedural eyes, no artwork

Eyes in the style of Anki's Cozmo/Vector: each eye is one rounded
rectangle on black, and everything is drawn from code. There are no
images to draw or maintain.

- A **mood** is a small set of numbers per eye: width, height, corner
  radius, x/y offset, and the top and bottom "lid" cuts (an angle and an
  amount that hide part of the rectangle).
- Expressions are just moods: blink = height to almost zero, happy = the
  bottom cut into an arc, angry/sad = the top cut tilted in or out,
  surprised = bigger, sleepy = half height, looking around = offset.
- Changes are **tweens**: the face moves from the current mood to a
  target mood over some milliseconds with an easing curve. Every
  transition is smooth for free, and a new mood is one table entry.

Open-source examples of the same idea (e.g. FluxGarage RoboEyes, for
small OLEDs) show how much character this gives with almost no code.

## The approach and why

**One OpenGL ES 2.0 fragment shader on the GPU, through SDL2 on
DRM/KMS, as its own ROS 2 node.**

- **The GPU, with ST's driver.** ST's EULA is accepted (hobby project),
  so the GC400 does the drawing and the A7 cores stay free for ROS 2,
  the micro-ROS Agent and later the camera. ST's gcnano driver is what
  ST supports and what all its graphics demos use. It is proprietary and
  tied to ST's kernel; Mesa's open-source etnaviv is the alternative,
  but less proven on this GPU.
- **A shader instead of a drawing library.** Rounded rectangles with lid
  cuts are a few lines as a signed distance function (SDF): for each
  pixel the shader computes the distance to the eye's edge and colours
  it, with `smoothstep` for clean anti-aliased edges. Glow, gradients
  and squash come almost free from the same distance. The mood numbers
  are the shader's uniforms; the C++ side only updates them each frame.
  This is how a lot of modern UI and game effects are drawn, and it
  leaves room for much richer looks later without any artwork.
- **SDL2 for the plumbing.** It opens the display through DRM/KMS and
  GBM, creates the EGL/GLES context, does vsync'd page flips, reads
  touch from evdev and keeps time: everything around the shader, without
  writing it ourselves. It is in oe-core and widely used for kiosks and
  embedded games. No compositor: one full-screen program owns the
  display (DRM master), the usual set-up for a single-purpose screen.
- **A separate program and ROS 2 node (`dontfall-face`)**, not a thread
  in `dontfall-app`: rendering is the biggest and least trusted piece of
  the A7 software, so it gets its own process. A crash in it doesn't take
  the app down, systemd restarts it alone, and it can run at a lower
  priority than the micro-ROS Agent, which carries `cmd_vel` to the M4.
  It lives in the DontFall-app repo as a second executable, built by the
  same recipe.
- **The face only listens.** It subscribes to topics and never commands
  the robot. Nothing on the robot depends on it, so it may fail at any
  time without touching safety.

Alternatives, for comparison: raw GBM/EGL like `kmscube` (the same thing
as SDL2 with more boilerplate and no input handling), Qt Quick on eglfs
(the industry-standard HMI toolkit, but a new layer, long builds and far
more than two eyes need), LVGL (light, but renders on the CPU in this
version), Flutter (Dart, a ROS 2 bridge, heavy for a 32-bit A7). Because
the face model is kept apart from the drawing (below), changing the
renderer later replaces only that part.

## The code's shape

- **Face model** (plain C++, no SDL or GL): the current mood, the target
  mood and the tween between them, the gaze offset, and the googly
  physics state (step 4).
- **Inputs** set targets: the clock (blinks, glances), the IMU (googly
  physics), the robot state (moods), touch.
- **Renderer** (SDL2 + GLES 2.0): each frame, copies the model's numbers
  into the shader's uniforms and draws. The shader source is a file next
  to the code.

A new input later (e.g. look at a detected person) touches only the
model, never the drawing code.

## Step 0: check the display and touch

- On the board: `modetest` (from `libdrm-tests`, added to the image for
  now) lists connectors, CRTCs, planes and the panel's mode, and can
  show a test pattern. `evtest` (or `cat /dev/input/event*`) shows touch
  events.
- Note what is drawing on the panel now (the kernel console through DRM's
  fbdev emulation, the getty on tty1) and whether it gets in the way.
- Record in `docs/hardware.md`: DRM card and connector, mode, pixel
  formats of the primary plane, touch input device, backlight sysfs path.
- Done when: a test pattern is on the screen and touch events print.

Result (2026-10-25): the panel's DRM driver (`stm`) had never probed.
The LTDC waits for both of its outputs in the devicetree, DSI and HDMI,
and image plan step 2 had dropped the HDMI bridge module (`sii902x`),
so it stayed in deferred probe; the `/dev/dri/card0` seen then was
`simpledrm`, U-Boot's framebuffer. `kernel-module-sii902x` is back in
the machine conf. With it: `DSI-1` connected at 480x800, test pattern
on the panel, touch events from `evtest`. Details in `docs/hardware.md`
("Display and touch"). Nothing else draws on the panel (no fbdev
console), so nothing needs to be moved out of the way in step 7.

## Step 1: GPU on

- Read the EULA (`meta-st-stm32mp/conf/eula/ST_EULA_SLA`, which covers
  the Vivante licence too), then set `ACCEPT_EULA_stm32mp157c-dk2 = "1"`
  in `conf/templates/default/local.conf.sample` (so new build dirs get
  it) and in the build's own `conf/local.conf` (git-ignored).
- The kernel side: ST's out-of-tree `galcore` module
  (`gcnano-driver-stm32mp`) replaces `kernel-module-etnaviv` in the
  machine conf's module list; both bind to the same GPU node, so only
  one may be installed. Check that the image really gets the module and
  `gcnano-userland`: `df-image-minimal` skips packagegroup-base-extended,
  which is where machine recommends normally come in, so they may need
  listing explicitly (like the M4 firmware).
- `kmscube` in the image for now: a spinning cube through GBM, EGL and
  GLES 2.0 straight on KMS, the standard first test of this stack.
- Note the build time (a few GPU packages, not a full rebuild) and the
  rootfs size change here.
- Done when: `kmscube` spins smoothly on the panel and `galcore` is
  loaded.

Result (2026-10-25): EULA accepted in `local.conf.sample` and the
build's `local.conf`; `etnaviv` out of the machine conf; the image
installs `${GPU_IMAGE_INSTALL}` (ST's images do that themselves, ours
starts from core-boot) and `kmscube`. Build: only the GPU packages and
the image rebuilt. On the board: `galcore` 6.4.21 loaded, `/dev/galcore`,
EGL 1.5 and OpenGL ES 2.0 (GLSL ES 1.00) from Vivante, with
`EGL_KHR_platform_gbm` and `GL_OES_standard_derivatives` (handy for
anti-aliased SDF edges). `kmscube` runs at 50.0 fps, locked to the
panel's 50 Hz mode, at ~2% CPU.

- The GPU driver reserves 64 MB of RAM (`gpu@d4000000` in the
  devicetree): Linux now sees 396 MB. After boot: 56 MB used (was 37),
  rootfs 281 MB (was 249), `/vendor` 6.7 of 14 MB, systemd 14.4 s.
- The libraries live on the vendorfs partition (`/vendor/lib`, ST's
  `ST_VENDORFS`), found through `/etc/ld.so.conf.d/gcnano.conf`.
  `deploy.sh` now mounts `/vendor` too before an image deploy.
- An image deploy only adds files: the old `etnaviv.ko` had to be
  removed on the board by hand.

## Step 2: SDL2 and still eyes

- `libsdl2` with `kmsdrm` and `gles2` on (and `wayland`/`x11` off) in a
  `libsdl2_%.bbappend` in meta-df; `libsdl2` in the recipe's DEPENDS.
- Second executable `dontfall-face` in DontFall-app, installed next to
  `dontfall-app`. Started by hand over ssh for now (`./deploy.sh`).
- SDL2 opens the display fullscreen with a GLES 2.0 context; one
  full-screen quad and the eye shader (SDF of a rounded rectangle, one
  colour, e.g. cyan, on black) with a hard-coded neutral mood.
- Screen orientation: a rotation uniform in the shader (one place),
  default landscape; the real value comes when the robot is built (see
  "Open questions").
- Done when: two eyes are on the screen and quitting the program leaves
  the screen in a sane state.

Result (2026-10-25): `dontfall-face` in DontFall-app (`src/face/`:
`face.hpp` model, `renderer.*` and `eye_shader.hpp`, `main.cpp`), built
by the `dontfall-app` recipe, run by hand. Two cyan rounded eyes on the
panel at ~3% CPU; SIGTERM ends it cleanly. SDL2 needed two fixes in
`libsdl2_%.bbappend` before it worked with ST's gcnano:

- `SDL_KMSDRM_SHARED=OFF`: SDL dlopen()s GBM by the file name behind
  `libgbm.so` (`libgbm.so.1.0.0`), which gcnano has only on `/vendor/lib`,
  so KMS/DRM failed and SDL silently fell back to its offscreen driver
  ("eglQueryDevicesEXT() failed"). Linked normally, the loader finds it
  by SONAME.
- `libudev` as a runtime dependency: SDL dlopen()s it too, and SDL 2.30
  segfaults on start without it.

SDL's cursor and "Could not restore CRTC" messages in its verbose log
are harmless (the LTDC has no cursor plane; there was no picture before
to restore). GLES 2.0 headers come with SDL2; CMake finds `libGLESv2`
with `find_library` (pkg-config looks under `/usr/local` because the
recipe's prefix is `/usr/local`).

## Step 3: moods, tweens, alive when idle

- The mood struct and a table of moods: neutral, happy, sad, angry,
  surprised, sleepy, blink. Lid cuts in the shader. Tween from current
  to target with easing.
- A temporary way to try moods (e.g. cycle through them every few
  seconds) to see them all and tune the numbers.
- Idle life: blinks at random intervals (a few seconds apart, sometimes
  double) and random glances (saccades: the offset jumps quickly, holds,
  comes back). Small, quick movements are what make eyes look alive.
- Frame pacing from vsync (SDL swap interval 1); no busy loop.
- Measure: CPU of `dontfall-face` with `top`, and the GPU's frame time
  (does it keep the panel's refresh rate). The GC400 is small: if a
  full-screen shader is too slow, draw one quad around each eye instead
  of the whole screen. Write the numbers here; they are the face's
  budget on the A7.
- Done when: every mood looks right, the idle eyes look alive and the
  numbers are recorded.

Result (2026-10-25), in three parts:

- 3a: `mood.hpp` (a mood = size, corner radius, top lid amount and tilt,
  bottom lid amount and roundness, colour; the table of six moods),
  `Tween<T>` (smoothstep from the current value to a target), lids in the
  shader. `--demo` cycles the moods, `--mood <name>` holds one.
- 3b: `idle.cpp`: blinks every 2-6 s (20% double), glances every 1-4 s
  (90 ms saccade, 40% back to the middle); looking sideways grows the eye
  on that side a little. Timings are constants at the top of the file.
- 3c: a colour per mood (cyan neutral, warm yellow happy, blue sad, red
  angry, pale surprised, dim sleepy) and a glow. `--stats` prints the
  frame rate, `--gpu-time` also the GPU's time per frame (glFinish).

Measured on the GC400, frames are paced by the 50 Hz panel, so a frame
that misses 20 ms drops the rate to 25 fps:

| Version | GPU ms per frame | fps |
|---|---|---|
| shader over the whole screen, two eyes, lids, glow | ~110 | 8.3 |
| one rectangle per eye (glow 40 px) | 21 | 26 |
| filling those rectangles with a flat colour | 2.7 | 50 |
| + unused lids skipped, glow 28 px: neutral / sleepy | 14.6 / 19.9 | 50 / 26 |
| final: glow 12 px, worst mood (sleepy) | 15.6 | 50 |

- The GC400 is slow at per-pixel maths, not at filling: a flat colour
  costs ~2.7 ms for both eye rectangles, the eye shape and glow ~12 ms,
  the top lid ~2 ms and the bottom lid ~4 ms. Lower precision (mediump)
  and a parabola instead of the circle made no difference.
- The real budget is below 20 ms: frames of ~18 ms GPU time still ran at
  ~26 fps (clear, page flip). Keep the worst mood under ~16 ms.
- A branch on a uniform (lid used or not) is nearly free, every pixel
  takes the same way.
- Final: 49-50 fps in the demo and idle, ~7% of one A7 core. 32-bit
  colour stays (RGB565 wouldn't help: filling isn't the cost).
- Headroom is small: anything bigger on screen (larger eyes, a wider
  glow, more shapes) needs measuring with `--gpu-time --mood sleepy`.

## Step 4: googly eyes from the IMU

- `dontfall-face` becomes a ROS 2 node (`dontfall_face`) and subscribes
  to `/dontfall_m4/imu` (best effort, 50 Hz).
- Googly physics: the eyes' offset is a mass on a spring towards the
  gaze point, pulled by the gravity direction from the IMU's
  `linear_acceleration`, with damping and a soft edge. Tilt the board
  and the eyes slide downhill; shake it and they wobble, squashing a
  little (height/width) when they hit the edge. Integrated at the frame
  rate, not the IMU rate.
- Which IMU axes map to screen x/y: one small mapping in the face code,
  identity until the robot exists (same idea as the M4's `mount` matrix).
- Threads: the ROS executor and the render loop each in their own
  thread, sharing the latest IMU sample under a mutex; rendering never
  waits on ROS.
- `docs/architecture.md`: add the new process and its subscriptions.
- Done when: tilting and shaking the board moves the eyes believably.

Result (2026-10-25): `imu_listener.cpp` (node `dontfall_face`, best
effort subscription, spun in its own thread, latest sample under a
mutex; rclcpp's signal handlers off so SDL's SIGTERM still ends the
loop) and `googly.cpp` (spring towards the idle gaze, pushed by minus the
accelerometer's in-plane part, smoothed over 60 ms, round edge with
bounce and squash). 50 fps, ~8% of one core. Tilting and shaking the IMU
module (it isn't on the DK2) moves the eyes. Also: passing moods (every
6-15 s a random mood for 2-4 s) and a surprised look when the eyes hit
the edge hard, until step 5 drives the moods.

Tuning by feel, for the next time:

- Spring 4 Hz / 0.25 damping felt stiff and slow tilts barely moved the
  eyes; 2.5 Hz with twice the push was chaotic and sloppy; kept 3.5 Hz,
  0.4 damping, 0.17 of the edge per m/s^2.
- A dead zone (~6 degrees) against noise felt clunky: removed, the 60 ms
  smoothing is enough.
- Much of the early "sloppy" was the axis mapping: the module lies turned
  90 degrees to the landscape view. `imu_push()` in `googly.hpp`: screen
  x = IMU y, screen y = IMU x, checked by tilting.

## Step 5: moods from the robot's state

- Inputs: the supervisor's state in `/diagnostics` (armed, disarmed,
  fault), and whether IMU messages arrive at all.
- Mapping: neutral (disarmed), alert/surprised (just armed, then
  neutral-focused), angry or squint (fault), sleepy and slowly closing
  (no IMU messages for a few seconds, i.e. the M4 link is down or the
  Agent is gone).
- Done when: arming, disarming, a fault and stopping the Agent each
  change the face.

Result (2026-10-25): the node also subscribes to `/diagnostics` (only
the M4's message, the one with `dontfall_m4: supervisor`).
`robot_state.hpp` (plain logic): link lost when no IMU sample or M4
status for 3 s, fault on any non-OK M4 status (IMU error or a safety
disarm), else armed or disarmed. `mood_picker.cpp`: link lost = sleepy,
after 10 s asleep (new mood, closing over 3 s); fault = angry; armed =
surprised for 1.2 s, then focused (new mood, green-cyan); disarmed =
neutral with passing moods (happy, sad, surprised; not angry or sleepy,
which mean something), restarted on every return to disarmed. A hard
knock of the googly eyes surprises it while armed or disarmed. The face
starts as link lost, so it wakes up when the M4's data arrives. Each
change is logged, e.g. `mood angry (fault: supervisor disarmed (tilt))`.
Checked on the board: arm, disarm, tilt past the limit, stopping and
starting the micro-ROS Agent.

## Step 6: touch

- SDL2's touch events (`SDL_FINGERDOWN` and friends, from evdev) on the
  touch device from step 0; check SDL2 sees it without udev rules.
- Poke an eye: a hard blink or annoyed squint, then back. A long press:
  something sillier (dizzy wobble).
- Touch never commands the robot (no arming or driving from the screen).
- Done when: pokes are noticed reliably.

Result (2026-10-25): SDL's touch events (`SDL_FINGERDOWN`, 0..1 on the
panel) came through from evdev with no udev rules or setup. The renderer
maps them to logical pixels with the same transform it draws with. A
touch on or near an eye (30 px) blinks at once and squints for 1 s (new
`squint` mood); a touch elsewhere makes the eyes glance there and hold
for 2 s; holding 0.8 s makes them roll round in circles for 2 s (the
googly spring chases a point on a circle). Touch only changes the face.

## Step 7: service

- `dontfall-face.service`, like `dontfall-app.service`: ROS environment,
  `Restart=on-failure`, and `Nice=10` so it can never take CPU from the
  micro-ROS Agent or the app.
- The getty and console on the panel from step 0 out of the way, if they
  draw over it.
- Optional: the eyes as soon as the program starts, before the ROS node
  is up, so the screen isn't black during boot.
- `kmscube` and the step 0 tools out of the image again.
- `docs/architecture.md`: the face as a service, the GPU in use.
- Done when: the face comes up on boot, comes back after `kill`, and the
  robot's other services don't notice it.

Result (2026-10-25): `dontfall-face.service` from the `dontfall-app`
recipe: ROS environment, `RequiresMountsFor=/usr/local /vendor` (binary
on userfs, GPU libraries on vendorfs), `Restart=on-failure` every 2 s
with no start limit (in case it starts before the panel's DRM driver;
on the board it didn't need to), `Nice=10`. Up ~8 s after it starts
(sleepy until the M4's data arrives, then neutral), boot still 14.4 s,
~8% of one core. After `kill -9` systemd restarts it in 2 s; the app and
the micro-ROS Agent don't notice. No getty or console to move (step 0),
the eyes start before ROS is up anyway. `modetest`, `evtest` and
`kmscube` are out of the image again.

## Later

- Look at things: a topic such as `/face/look_at` that the person
  follower (or the camera) can publish to; the gaze goes there instead of
  random glances.
- Richer looks in the same shader: glow, iris rings, scanlines, a
  "glitch" on faults.
- Preview the face on the PC (the same SDL2 + GLES code in WSLg) to tune
  moods without deploying.
- Screen brightness: dim when idle, off when disarmed for a long time.
- Sounds with the moods (the audio codec is not in the image now).

## Open questions

- How the screen sits on the robot (landscape or portrait, which way up).
  Not decided until there is a robot; until then the rotation is one
  uniform.
- Whether `libdrm-tests`/`evtest`/`kmscube` stay in a dev image or go
  away after bring-up.
- Whether SDL2's KMS/DRM backend works with ST's gcnano GBM out of the
  box; if not, fall back to raw GBM/EGL like `kmscube` does (step 1
  proves that path works).
