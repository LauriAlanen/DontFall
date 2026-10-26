# Camera plan

Working plan for the USB camera: bring it up on the A7, put it on the
ROS 2 graph with stock tooling, see it on the PC, and then give it jobs.
The A7 only captures and forwards images; all perception runs on the
PC, so the A7 keeps its CPU for the robot's own features. The first
jobs need no robot (the face looks at you); the
long-term one is the headline goal from `VISION.md`, following a
person. Each step is small and ends with something you can see. Update
the status as steps land.

| # | Step | Status |
|---|------|--------|
| 0 | Check the camera on the board, no code | done |
| 1 | Camera as a ROS 2 topic: stock driver, `dontfall-camera.service` | done |
| 2 | See it on the PC in Foxglove; measure the WiFi link | done |
| 3 | Calibrate it (checkerboard on the PC), `camera_info` on the board | later (not needed for 4-5) |
| 4 | PC vision node: a YOLO detector for people and faces | todo |
| 5 | The face looks at you (detections from the PC) | todo |
| 6 | Follow a person, dry run (a suggested `Twist`, never sent to `/cmd_vel`) | todo |

Steps 0-3 are the bring-up every later use needs. Steps 4-6 are one
path through the ideas below; any of the side quests can go in between.

## What is already there

- `kernel-module-uvcvideo` is in the machine conf (image plan step 2),
  so a UVC webcam should show up as `/dev/video*` without changes.
- The image has no camera tools: no `v4l-utils`, no `usbutils`
  (`lsusb`), no GStreamer, no OpenCV.
- meta-ros (jazzy) has the stock ROS 2 camera pieces as recipes:
  camera drivers `v4l2-camera`, `usb-cam`, `gscam` and `camera-ros`
  (libcamera), plus `image-transport`, `compressed-image-transport`,
  `camera-info-manager`, `camera-calibration`, `cv-bridge`,
  `vision-msgs`, `apriltag-ros`. meta-oe has `opencv` 4.9 (with the
  `dnn` module) and `v4l-utils`. None of them has been built here yet;
  expect meta-ros fixes like the ones in `docs/lessons-learned.md`
  ("Yocto").
- The PC runs ROS 2 Jazzy in Docker (`pc/ros.sh`), on the same DDS
  network as the board: a node there sees the board's topics as if it
  were local. WSL2 has 12 cores and 15 GB; no NVIDIA GPU is visible in
  WSL, so plan for CPU inference (still far faster than the A7).
- The face model was built to take a new input without touching the
  drawing: "look at a detected person" was planned in from the start
  (`docs/archive/face-plan.md`, "The code's shape").

## Where things run, and why

| Where | What | Why there |
|-------|------|-----------|
| Board (A7) | camera driver, forwarding compressed frames, the face (only listens to detections) | close to the hardware; cheap, so the A7 stays free for the robot's own features |
| PC | all image processing: detectors (YOLO, pose, ...), the behaviour that uses them while developing | 12 fast cores vs two 650 MHz A7s; Python and the ML ecosystem; fast to iterate |

**No heavy computation on the A7.** The A7 will get more features
(motor and balance tooling, behaviour, the face growing richer), and
the micro-ROS Agent on it carries `cmd_vel` to the M4. Image
processing is the easiest way to eat both cores, so it never runs
there: the camera service only moves bytes. The cost: without the PC
the robot has no camera features (the face just goes back to idle
glances). That is accepted.

This is the usual split for research and hobby robots: an "offboard"
computer does the expensive perception over the network, the robot
does what must not depend on the network. Production robots move
perception on board (a bigger SoC or an accelerator) once the
algorithms settle. The rules that keep the split safe:

- **Nothing safety-relevant depends on the PC** (`VISION.md`). The PC
  is a commander like teleop: if the PC, WiFi or a PC node dies,
  detections stop, any following stops, and the M4's `cmd_vel` timeout
  brings the robot back to balancing in place. That path already exists.
- **Standard topics between the parts.** Detectors publish
  `vision_msgs/Detection2DArray`; the face and the follower only know
  the topic, not where the detector runs. If the robot ever gets a
  stronger computer, a detector moves there without anyone else
  changing.
- **Detections, not images, are the product.** Images go to the PC
  once, compressed; what comes back is a few hundred bytes of boxes.
- **Latency is part of the design.** WiFi adds tens of ms, sometimes
  much more. Fine for gaze and slow following; every message carries
  its image's timestamp so consumers can see how old it is and drop
  stale ones.

## Limits that shape the design

- **Board CPU.** Two Cortex-A7 cores at 650 MHz, already running the
  micro-ROS Agent (carries `cmd_vel` to the M4), the app and the face.
  The STM32MP157 has no hardware video or JPEG codec, so every decode,
  encode and colour conversion on the board is CPU work. The camera
  service is niced below the Agent, like the face, and has a CPU budget
  (`CPUQuota=` in its unit) so it can never take more than its share.
- **MJPEG passthrough is what makes this work.** Most webcams compress
  to MJPEG themselves. A driver that publishes those JPEG frames as
  `sensor_msgs/CompressedImage` without decoding them costs the board
  almost nothing, and the PC gets full-quality frames. Whether the
  camera offers MJPEG (step 0) and which driver passes it through
  untouched (step 1: `gscam` and `camera-ros` are the candidates to
  check, `usb_cam` decodes) is the most important choice of the plan.
  If the camera only gives raw YUYV, the A7 would have to encode JPEG
  itself, which goes against the rule above: then the choice is a
  small resolution and rate, or a camera with MJPEG (the developer's
  call).
- **WiFi.** Raw 320x240 RGB at 15 fps is ~3.5 MB/s, too much; 640x480
  JPEG at 15 fps is roughly 0.5-1 MB/s, fine. Only compressed images
  leave the board. Step 2 measures what the link really gives.
- **RAM.** 512 MB, of which the GPU reserves 64 MB. With no OpenCV or
  models on the board, the camera service should cost only a few MB;
  step 1 measures it.
- **Bench first.** There is no robot to drive, so following ends at a
  suggested command you can watch, not at the motors (step 6).

## The approach and why

- **Stock driver, not our own V4L2 code.** The ROS 2 camera drivers
  publish `sensor_msgs/Image` or `CompressedImage` plus
  `sensor_msgs/CameraInfo`, expose V4L2 controls as parameters and work
  with `image_transport`, so rosbag, Foxglove, RViz and the calibration
  tool work with no glue (`VISION.md`: standard message types). Writing
  a V4L2 capture loop is a fine side quest, but not what the robot
  should depend on.
- **`image_transport`** lets each subscriber pick raw or compressed;
  nothing is compressed unless someone listens.
- **Perception nodes only listen and publish detections**; they never
  command the robot. Behaviour (gaze, following) lives in whoever
  listens to the detections.

Alternatives, for comparison:

- **Streaming video to the PC outside ROS** (GStreamer RTP/H.264): less
  bandwidth than JPEG, the standard for video links, but the A7 would
  have to encode H.264 in software and the images would be invisible to
  ROS tooling. JPEG over ROS first; revisit only if step 2 shows WiFi is
  the bottleneck.
- **Perception on the board** (OpenCV `dnn` or ST's X-LINUX-AI with
  TFLite): no network dependence, but a few fps at best for person
  detection, and it would take most of the A7. Rejected: the A7 is kept
  for the robot's own features.
- **Cloud or a vision-language model** (ask a model "what do you see?"):
  possible from the PC as a side quest, too slow for anything closed-loop.

## Step 0: check the camera on the board

- Add `v4l-utils` to the image for now (like `libdrm-tests` in face
  plan step 0).
- On the board: `dmesg` for the UVC probe, `ls /dev/video*`,
  `v4l2-ctl --list-devices`, `v4l2-ctl -d /dev/video0
  --list-formats-ext` (formats, sizes, frame rates), `--list-ctrls`
  (exposure, focus, ...).
- Grab one frame (`v4l2-ctl --stream-mmap --stream-count=1
  --stream-to=frame.jpg` with an MJPEG format, raw YUYV otherwise),
  copy it to the PC and look at it.
- Record in `docs/hardware.md`: model, USB id, which DK2 port, the
  device node, the formats and sizes worth using. Key question: MJPEG
  at 640x480 and up?
- Done when: a frame from the board is on the PC's screen.

Result (2026-10-26): Dell Webcam WB3023 on `/dev/video0`, found by
`uvcvideo` with no changes. It offers **MJPEG** from 640x480 @30 up to
2560x1440, so passthrough works and raw formats (YUYV, NV12) are only
640x480 and smaller. Streaming MJPEG costs the A7 ~0% CPU. Frames at
640x480 and 1280x720 copied to the PC look sharp and well exposed.
640x480 is ~27 kB per frame (~0.4 MB/s at 15 fps), 1280x720 ~68 kB.
`power_line_frequency` defaults to 60 Hz; set it to 50 Hz in step 1.
`v4l-utils` stays in the image as a debug tool. Details in
`docs/hardware.md` ("Camera").

## Step 1: camera as a ROS 2 topic

- MJPEG passthrough (step 0: the camera has it): try `gscam`, then
  `camera-ros`. Start at 640x480 @15 fps. The camera service is niced
  and capped (`CPUQuota=`).
- Build it into the image (parse check first), run it by hand:
  `ros2 topic hz`, `ros2 topic echo --once .../camera_info`.
- Measure the board's CPU (`top`) and RAM with and without the camera.
- `dontfall-camera.service` like the face's: started at boot, `Nice=`
  below the Agent, restarted on failure, bound to `/dev/video0` (like
  the Agent is to its tty).
- Update `docs/architecture.md` (a new process and topics).

Result (2026-10-26): `gscam` (meta-ros) with the pipeline `v4l2src
device=/dev/camera ! image/jpeg,640x480,30/1 ! videorate max-rate=15`
publishes the camera's JPEGs unchanged on `/camera/image_raw/compressed`
(~28 kB, 15.0 fps measured on the PC, ~420 kB/s). New recipe
`dontfall-camera`: the service, `/etc/dontfall/camera.yaml`, and a udev
rule for `/dev/camera` and its systemd device unit (udev doesn't tag
video devices for systemd on its own); the service starts when the
camera appears. On the board: gscam ~3% CPU and 26 MB RSS; while the PC
receives, kernel time goes from ~1% to ~13% (the WiFi driver sending),
the real cost of streaming. Lessons:
- gscam needs `cv_bridge`, whose package asks for the `opencv` meta
  package: every OpenCV module, GTK3, python3-opencv. A `cv-bridge`
  bbappend drops it (only core, imgproc and imgcodecs are linked), and
  `BAD_RECOMMENDATIONS` drops `gstreamer1.0-plugins-base-meta`; the image
  went from 777 packages / 397 MB back to 598 / 316 MB.
- Without that meta package, gscam's `appsink` must be listed
  (`gstreamer1.0-plugins-base-app`), or the pipeline fails with
  `gst_bin_add() failed`.
- `videorate drop-only=true ! image/jpeg,framerate=15/1` still gave
  30 fps; `videorate max-rate=15` works.
- gscam logs a missing calibration file (`/camera_info/dell_wb3023.yaml`)
  until step 3 sets `camera_info_url`.

## Step 2: see it on the PC, measure the link

- Foxglove's Image panel on the compressed topic, added to
  `DontFall-dashboard/layouts/dontfall.json`.
- Measure: fps reaching the PC, bandwidth, latency (stamp vs PC clock,
  and wave a hand), the board's CPU, at two or three resolution/rate
  settings. This link is the pipe to the PC's perception, so its
  numbers decide the settings for steps 4-6.

Result (2026-10-26):

- Foxglove: an Image panel on `/camera/image_raw/compressed` in
  `DontFall-dashboard/layouts/dontfall.json`, next to the gyro and
  `cmd_vel` plots.
- Clocks: the board's is within ~5 ms of NTP, the PC's (WSL2) was
  ~135 ms fast, so "board stamp vs PC clock" is useless for latency.
  Measured instead with a throwaway echo: a node on the PC sends each
  frame's stamp straight back (`/camera/echo`), and `ros2 topic delay`
  on the board compares it with the board's own clock. That is the
  round trip a detection will have (image to the PC, small message
  back), minus the inference time. Ping round trip is ~3 ms.
- Raw TCP board to PC: ~4.1 MB/s (34 Mbit/s).
- Every PC process that subscribes gets its own copy of each frame over
  WiFi (DDS unicast). With three subscribers (echo, `topic hz`,
  `topic bw`):

  | Setting | fps on the PC | per subscriber | round trip |
  |---------|---------------|----------------|------------|
  | 640x480 @15 | 15.0 | 426 kB/s | 54 ms (46-136) |
  | 640x480 @30 | 20.3 | 560 kB/s | 4.2 s, growing |
  | 1280x720 @15 | 8.3 | 574 kB/s | 8 s |
  | 1280x720 @30 | 8.1 | 559 kB/s | 18 s |

  With only the echo subscribing:

  | Setting | round trip |
  |---------|------------|
  | 640x480 @30 (~0.8 MB/s) | 68 ms (63-94) |
  | 1280x720 @15 (~1 MB/s) | 84 ms (78-117) |
  | 1280x720 @30 (~2 MB/s) | 3-8 s, overloaded |

- So the budget is **~1 MB/s of camera data in total over WiFi**, far
  below what TCP gets (DDS over UDP, large fragmented messages on WiFi).
  Above it, frames don't just drop: they queue and the delay grows to
  seconds. The board's kernel (`sys`) time is 12-30% while sending,
  growing with the data rate: WiFi costs the A7 more than gscam does.
- Settings kept: **640x480 @15** (~0.43 MB/s per subscriber, ~55 ms
  round trip), room for two subscribers.
- For step 4: Foxglove and the vision node would be two copies (~0.86
  MB/s, near the limit). Better: one process on the PC subscribes to the
  board and republishes on a PC-only topic that both use (e.g.
  `topic_tools relay`), so the board sends each frame once. The
  standard bigger fix is `rmw_zenoh` (one copy per host through a
  router); not needed yet.

## Step 3: calibrate it

- Print a checkerboard, run `camera_calibration` (`cameracalibrator`)
  on the PC, move the board around until it is happy.
- Save the result as a YAML file on the board that the driver loads
  (`camera_info_url`), so `camera_info` carries the real lens model.
- Why: anything that measures in the world (tag distance, a person's
  bearing from the robot, undistorting) needs it. "Is there a face, and
  roughly where" (steps 4-5) doesn't.

## Step 4: PC vision node

- A Python ROS 2 node in `pc/vision/`, run in the PC container (a
  `pc/ros.sh vision` command): subscribes to the compressed images, decodes, runs YOLO
  (person class; plus a face model) through ONNX Runtime on the CPU,
  publishes `vision_msgs/Detection2DArray` on `/vision/people` and
  `/vision/faces`, stamped with the image's time.
- Why Python on the PC: that is where the ML tools live (Ultralytics
  export, ONNX Runtime, MediaPipe), and it never has to be built for the
  board.
- Seeing it: Foxglove draws boxes over the image from an
  `ImageAnnotations` message.
- Measure: inference time, end-to-end delay (image stamp to detection
  arriving back on the board).

## Step 5: the face looks at you

- `dontfall_face` subscribes to `/vision/faces` (people as a fallback);
  the biggest face sets the gaze target (image x/y to eye offset),
  smoothed by the existing tweens. Old detections (by stamp) are
  ignored.
- Moods: happy or surprised when someone appears, back to idle glances
  when nobody has been seen for a while, sleepy after longer.
- Bench friendly and fun: the robot "notices" people with no motors.
- With the PC off, no detections arrive and the face simply stays in
  its idle moods.

## Step 6: follow a person, dry run

- A small behaviour node on the PC turns the chosen person's box into
  a suggested `geometry_msgs/Twist`: turn to centre them, forward/back
  to keep the box a set size. Published on `/follow/cmd_vel`, never on
  `/cmd_vel`.
- Watch it in Foxglove next to the image: walk around and see the
  suggested turn and speed react; unplug the PC's WiFi and see it stop.
- When the robot exists: a `twist_mux` (stock ROS 2) picks between
  teleop and follow by priority, and the M4's supervisor and command
  timeout stay the safety net, as for any other commander.

## Side quests (any time after step 2)

All of them run on the PC, except the V4L2 one, which only moves bytes.

- **Motion wakes the face**: frame differencing, a few lines of OpenCV.
- **Colour blob following**: follow a bright ball by HSV threshold; the
  classic first visual-servo exercise, and a way to build step 6 early.
- **QR codes as commands** (`cv::QRCodeDetector`): show a card to change
  the robot's mood.
- **Write your own V4L2 capture** on the board: `VIDIOC_REQBUFS`, mmap,
  queue and dequeue buffers; what the stock driver does underneath.
- **AprilTags** (`apriltag_ros`, after step 3): a tag's 3D pose; the
  standard way to give a robot a target or a home.
- **Pose and gestures** (YOLO-pose or MediaPipe): raise a hand to start
  following, cross arms to stop; the face reacts to a wave.
- **Follow *this* person** (re-identification or a tracker such as
  ByteTrack): keep the same target when people cross.
- **Depth from one camera** (Depth Anything): a rough depth image, to
  see obstacles or a person's distance.
- **"What do you see?"**: send an occasional frame to a vision-language
  model and show the answer in Foxglove (or as a mood on the face).
- **Record and replay**: `ros2 bag record` the compressed images and
  the IMU, replay them on the PC to develop detectors without the board.
