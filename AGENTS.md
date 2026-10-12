# Project Rules

Read `VISION.md` first: project goal, architecture principles, and how to
pace work (small, digestible steps).

## Git commits

- NEVER add AI/tool attribution to commits: no "Generated with Devin",
  no "Co-Authored-By" bot trailers, no similar footers. Write plain
  commit messages only.

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
- App host tests: `cd ../DontFall-app && cmake -B build && cmake --build build && ctest --test-dir build`
- M4 dev build: `../DontFall-M4-ws/DontFall-M4/scripts/build.sh </dev/null`
- Zephyr pin check: `../DontFall-M4-ws/DontFall-M4/scripts/check-zephyr-pin.sh`
- Board checks need the board IP from the human (`./deploy.sh <ip> ...`,
  `scripts/deploy.sh <ip> --log`). Never touch the signed boot chain
  (`SIGN_*`, keys, TF-A/FIP).
- Only one bitbake can use `build-stm32mp157c-dk2` at a time.
