# 3D model plan

Working plan for the robot's mechanical design as code: parts modelled
in Python with build123d, built and checked by a script, so an agent can
change a part, rebuild it, look at the result and hand it over for
review like any other code change. The first target is the bench robot
from `docs/hardware.md`: the free-standing reaction wheel balancer (one
motor, a flywheel on top, tilt from the IMU). The two-wheeled robot
comes after it. Each step is small and ends with something you can see.
Update the status as steps land.

| # | Step | Status |
|---|------|--------|
| 0 | Repo, pinned build123d, one test part, renders the agent can read | todo |
| 1 | Bought parts as simple models, sizes from datasheets and calipers | todo |
| 2 | Shared sizes and the check script (overlaps, print bed, walls) | todo |
| 3 | Flywheel: ring on the GM4108H, inertia from the model | todo |
| 4 | Balancer body: motor at the top, IMU, pivot edge | todo |
| 5 | Model to plant parameters for the balance gains | todo |
| 6 | URDF and meshes: the robot in 3D on the dashboard | todo |
| - | Two-wheeled robot body | later |

## Why build123d

Code CAD: the model is a Python program, so it diffs, reviews and
versions like the rest of the project, every size is a named parameter,
and an agent can edit it without a GUI. build123d sits on OpenCascade
(OCCT), a real B-rep kernel: exact solids, fillets and chamfers on
chosen edges, mass properties, and STEP export.

Alternatives and why not:

- **CadQuery**: the same OCCT kernel and much the same abilities, older
  with more examples online. Its chained "fluent" API gets hard to read
  in long parts; build123d came out of the CadQuery community with a
  cleaner builder/algebra style. Either would do; switching later is a
  rewrite of the part files, not of the parts.
- **OpenSCAD**: simple and popular for printed parts, but meshes only
  (CSG on triangles): no STEP, no fillet on one chosen edge, no exact
  mass properties.
- **GUI parametric CAD** (SolidWorks, Fusion, Onshape, FreeCAD): what
  mechanical engineers use in industry. The feature tree doesn't diff
  or review well and an agent can't drive it reliably. STEP is the
  industry's exchange format, so anything made here opens in those tools
  later.

## How the agentic loop works

An agent can't see a 3D viewer, so every build writes things it can
read, and the developer reviews in a real viewer:

1. Edit a part file (one part per change, like any small code step).
2. `build.py` builds every part and writes, per part, STEP (exchange,
   the source of truth for anything outside), STL/3MF (printing) and
   PNG views (front, side, top, iso).
3. `check.py` prints numbers: mass properties, bounding box, overlap
   volume between parts that touch in the assembly (should be 0), does
   it fit the print bed, thinnest wall where cheap to check.
4. The agent reads the PNGs and the check output, fixes what's wrong,
   then the developer looks at it in OCP CAD Viewer (`ocp_vscode`, a
   VS Code extension; works through VS Code's WSL remote) and decides.

The agent is good at sizes, maths and keeping parts consistent; it is
bad at judging printability, strength and looks from a picture. Those
stay the developer's call at every step, and a test print settles them.

## Step 0: repo and tooling

- New sibling repo `~/Projects/DontFall-cad` (a separate part of the
  project like the dashboard; it builds on the PC, not through Yocto).
  Same rules as the others: no AI attribution in commits.
- A Python venv with `requirements.txt` pinned to exact versions
  (build123d, ocp_vscode, the render dependency), so a rebuild gives the
  same model.
- Renders: try the cheapest that works headless in WSL (no GPU): STL
  through trimesh into matplotlib, or build123d's own hidden-line SVG
  projection turned into PNG. Pick one here; the other is the fallback.
- One test part (a block with a hole and a fillet) through the whole
  loop: `build.py`, `check.py`, PNGs, the viewer.
- Add the repo to `AGENTS.md`'s sibling list and `docs/development.md`;
  `docs/architecture.md` if it lists repos.
- Done when: the developer sees the test part in the viewer and the
  agent describes the same part from its PNG.

## Step 1: bought parts

Simple envelope models (outer shape, mounting holes, connectors that
need room), not detailed copies: DK2 with the MB1407 display, GM4108H
with its AS5048A, SimpleFOCMini v2.3, Modulino Movement, the webcam.

- Sizes from the makers' mechanical drawings (ST's DK2 drawings, the
  motor datasheet, SimpleFOC docs), each with its source in a comment.
  Parts on hand get checked with calipers; parts not on hand yet are
  marked unchecked until they arrive.
- No guessed sizes: where there is no drawing and no part, ask.
- A "Mechanical" table in `docs/hardware.md` with the key sizes and
  masses (weighed where on hand), the same way pins are recorded.
- Done when: all on-hand parts are checked against the calipers and
  shown together in the viewer.

## Step 2: shared sizes and checks

- `params.py`: fastener choice (e.g. M3 with heat-set inserts), wall
  thickness, print clearances, the printer's bed size. One place, so a
  change ripples through every part.
- A test coupon (holes, insert bosses, a slot at a few clearances),
  printed to find the right clearances for this printer.
- `check.py` from step 0 grows the overlap and print-bed checks for the
  assembly.
- Done when: the coupon is printed and its best clearances are in
  `params.py`.

## Step 3: flywheel

The balance plan picked a 100-150 g ring at 7-8 cm radius with the mass
at the rim (`docs/archive/balance-plan.md`, step 4b). A printed ring that
size weighs far less, so the mass comes from metal at the rim (steel
nuts and bolts, washers or a bought steel ring) on a printed hub that
bolts to the GM4108H's rotor (bolt pattern from its drawing).

- The model gives the wheel's I_w from its mass properties, with real
  densities per material (steel, PLA/PETG at the print's infill).
- Done when: mass and I_w are within the plan's range and the developer
  has reviewed the hub; printing waits until the motor is on hand to fit
  it.

## Step 4: balancer body

A frame that stands on a pivot edge, with the motor axis at the top
(the balance plan guessed the wheel centre at 17 cm), the IMU near the
pivot line with its axes along the body's, and room for the driver and
cables. Whether the DK2 rides on it or stays on the bench with cables is
an open question below; it changes the mass a lot.

- The IMU's mounting sets the axis mapping: keep it in the one place
  the IMU plan left for it, and note the orientation in the model.
- Done when: the assembly passes `check.py` and the developer has
  reviewed it; printing waits for the motor.

## Step 5: model to plant parameters

The payoff for the controller: the balance simulator's numbers are
guesses (`plant_guess` in DontFall-M4 `tests/balance/src/plant.c`).

- A script exports m, l, I_b and I_w about the pivot edge from the
  assembly to a small JSON file; `flywheel.py` and `lqr.py` in
  DontFall-M4 take it instead of the guesses, and the gains are
  regenerated.
- Printed parts' real mass differs from the model (infill, walls): once
  printed, weigh them and scale each part's density to match, keeping
  the model's mass distribution.
- Done when: the gains come from the model and the balance tests still
  pass with them.

## Step 6: the robot in 3D on the dashboard

Standard ROS: a URDF with the meshes from `build.py`,
`robot_state_publisher` turns the joint states and the IMU's tilt into
TF, and Foxglove's 3D panel (or RViz) draws the robot leaning and the
wheel turning, live or from a bag.

- `robot_state_publisher` runs on the PC side, not on the A7 (no extra
  load on the board). Update `docs/architecture.md` with it.
- Done when: tilting the IMU by hand tilts the model on the dashboard.

## Open questions

- Which 3D printer (bed size, materials), or a print service?
- On the balancer: does the DK2 ride on the body, or stay on the bench
  with cables to the IMU, driver and motor? Cables pull on a small
  balancer; a board on top adds a lot of mass up high.
- Power for the first bench robot: the bench supply on a cable, or a
  battery on the body (undecided in `docs/hardware.md`)?
- Flywheel mass: nuts and bolts at the rim, or a bought steel ring?
