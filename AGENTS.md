# Project Rules

Read `VISION.md` first: project goal, architecture principles, and how to
pace work (small, digestible steps). `docs/architecture.md` shows what
runs where and how the parts talk. Skim `docs/lessons-learned.md` before
board, Yocto patch or M4 memory work. Before adding M4 code, check the
M4 memory budget and its rules in `docs/hardware.md` ("M4 memory
budget"). Finished plans are in `docs/archive/`.

## Architecture diagram

- `docs/architecture.md` must match the system. Update it in the same
  change as anything architectural (list at its end: processes, M4
  threads, links, micro-ROS entities, who owns what, a planned part
  becoming real), in whichever repo the change happens.

## Git commits

- NEVER add AI/tool attribution to commits: no "Generated with Devin",
  no "Co-Authored-By" bot trailers, no similar footers. Write plain
  commit messages only.

## Testing

- Write tests only for critical parts (e.g. control logic, safety and
  fail-safe behaviour, protocol/message handling). Small features don't
  need tests.
- Test code the developer asks for to try out or understand a feature is
  temporary: keep it minimal (one small test or script), not a full
  suite, and don't treat it as permanent.
- If unsure whether something is a real feature (needs proper tests) or
  just an experiment, ask before writing tests.

## Build & verify

Workflow overview: `docs/development.md`. Sibling repos:
`~/Projects/DontFall-app` (A7 app), `~/Projects/DontFall-M4-ws/DontFall-M4`
(Zephyr M4 firmware, west workspace).

- Private repos are fetched over ssh: load the agent first
  (`. ~/.ssh/ssh-agent` if the human has started one, else ask them to
  `ssh-add`).
- Parse check: `./build.sh bitbake -p </dev/null`
- M4 firmware via Yocto: `./build.sh bitbake mc:zephyr-m4:dontfall-m4 </dev/null`
- Full image: `./build.sh </dev/null` (= `bitbake df-image-minimal`)
- App host build: `cd ../DontFall-app && cmake -B build && cmake --build build` (no tests yet)
- M4 dev build: `../DontFall-M4-ws/DontFall-M4/scripts/build.sh </dev/null`
- M4 host tests (ztest/twister, native_sim): `../DontFall-M4-ws/DontFall-M4/scripts/test.sh </dev/null`
- Zephyr pin check: `../DontFall-M4-ws/DontFall-M4/scripts/check-zephyr-pin.sh`
- Board checks need the board IP from the human (`./deploy.sh <ip> ...`,
  `scripts/deploy.sh <ip> --log`). Never touch the signed boot chain
  (`SIGN_*`, keys, TF-A/FIP).
- Only one bitbake can use `build-stm32mp157c-dk2` at a time.
