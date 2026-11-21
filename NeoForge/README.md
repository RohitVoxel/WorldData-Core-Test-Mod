# WorldData Core Test Suite (NeoForge)

ATM_PACK V1: a repeatable, ops-count-driven synthetic workload for WorldData Core, timed against plain Minecraft (the Minecraft baseline beside the Core arm is sampled). NeoForge 21.1, Minecraft 1.21.1, Java 21. Not a gameplay mod.

Full documentation: docs/index.html

## Layout

- `benchmark-common/` - pure Java framework (plan, session, ledger, latency, reports). No Minecraft, NeoForge or Core imports. Its classes are packed into the mod jar.
- `worlddata-test-suite/` - the one NeoForge mod (id `worlddata_core_test`): Minecraft reader and writer, the `/worlddatatest` commands, the run launcher, the client F3 block, and the WorldData Core integration in `net.worlddata.benchmark.coreintegration`.

## Quick start

1. `./gradlew build` - Gradle downloads WorldData Core 1.0.0 from CurseForge (project 1812200, through cursemaven), so there is no jar to copy. The mod jar lands in `worlddata-test-suite/build/libs/`.
2. Put the mod jar and WorldData Core (https://www.curseforge.com/minecraft/mc-mods/worlddata-core) in `mods/`, or use `./gradlew :worlddata-test-suite:runClient` / `:worlddata-test-suite:runServer`.
3. In a throwaway world: `/worlddatatest run [core|vanilla|auto] [ops] [opsPerSec] [tickMs] [seed] [thrash]` (alias `/wdtest`, permission level 2). The source is chosen per run, so Minecraft does not have to be restarted: `run core` reads through WorldData Core, `run vanilla` (or `minecraft`) reads through Minecraft only, `auto` and no word pick Core when it is installed. `opsPerSec` is the most ops sent in any one second; left out or 0 means no cap (as fast as the time budget allows). `thrash` is the number of thrash workers, 16 by default, up to 64. It runs in the dimension you run it from, vanilla or custom. Other commands: `stop`, `status`, `times`, `latency`, `apicoverage`, `captureaudit`, `sources`, `counts [chunk|section]`, `ramp [core|vanilla|auto] <targetMs> [options]` (alias `throttle`), `efficiency [auto|core|vanilla|minecraft] [options]`, the checks `coverage [radius]`, `verify [radius]` and `monitorcheck`, and the micro-bench `bench [compare] <api> [count] [mode]`.

Choosing the source: run the same ops and seed once with `run vanilla 200000` and once with `run core 200000` in the same game, then `times` compares them. A Minecraft run made while WorldData Core is installed still has Core doing its own tick work (chunk ingestion, cache upkeep), because Core is loaded; the report says so and marks that run in `times`. For a pure Minecraft baseline, take the Core jar out of `mods/`. `sources` explains the choices and shows what `auto` means in this game.

Checks (they only read, never load a chunk, and refuse to run while a run is active; each writes a text report to `<game dir>/worlddata-core-test-reports/checks/`):

- `coverage [radius]` (1 to 32, default 8): compares the chunks Minecraft holds around you with what WorldData Core serves live. A chunk Minecraft holds but Core does not serve is a gap; a chunk Core calls live that Minecraft dropped is a ghost. Disagreements are looked at again after 2 s and 10 s, so a delay and a real gap give different verdicts. Use it after changing render distance or moving fast.
- `verify [radius]` (1 to 3, default 1): compares Core's derived layers with Minecraft over the loaded chunks around you: section emptiness, water at every block, the height of every column for all four height kinds (`WORLD_SURFACE`, `OCEAN_FLOOR`, `MOTION_BLOCKING`, `MOTION_BLOCKING_NO_LEAVES`) against a block scan that uses Minecraft's own heightmap predicates, the same four heights against Minecraft's own heightmaps (those four lines are only warnings), and block scans by name and by tag against a count made from Minecraft. Run it when the world is quiet.
- `monitorcheck`: switches the Core API monitor on for a moment and checks that it counts exactly what this mod calls, tells the calls apart from nested ones, flags repeated reads, scales sampled callers to the exact total and stops when switched off. It also reports whether the loader mapped this mod's class to its mod id; if not, the monitor falls back to class names. It restores the monitor's state afterwards and clears its counters.

Micro-bench (`bench [compare] <api> [count] [mode]`): one timed burst of a single Core read on the server thread, with the closest Minecraft read beside it when you say `compare`. It never loads a chunk, refuses to run while a run is active, and writes a text report to `<game dir>/worlddata-core-test-reports/bench/<api>[-compare]-<time>.txt` (chat only says it was written; the exact location is in the server log, like the other reports).

- `api`: the point reads `stateIdAt`, `blockAt`, `materialAt`, `waterAt`, `isPresent`, `matchesAt` and the two scans `hasBlock` and `countBlocks`, which each scan one whole chunk for `minecraft:stone`. Light is not benched.
- `count`: 100,000 by default for a point read, 10,000 for `hasBlock`, 1,000 for `countBlocks`, clamped to 1,000,000, 200,000 and 50,000. A point read takes tens of nanoseconds, so the default burst lasts milliseconds; raise the count for a longer window. Each pass stops at 4 s so a slow call cannot trip the server watchdog, and the report says so.
- `mode`: `fixed` (default) cycles 64 positions around you inside your own chunk; `random` draws 65,536 seeded positions across the chunks within 32 blocks of you, y within 16 of you; `acquire` is `random` with the Core chunk lookup (`WDC.requests().chunk`) inside every call. In `fixed` and `random` each chunk result is resolved once before the timed loop, so the numbers are the API alone.
- `compare` reads the same positions in the same order through Minecraft: `ServerLevel.getBlockState` for the point reads (plus `Block.getId`, a fluid check, `isAir`, `is(Blocks.STONE)` or a tag classification, as the API needs) and `LevelChunkSection.getBlockState` over the non-empty sections of one pre-resolved chunk for the scans. Minecraft looks the chunk up itself on every call; use `acquire` for the Core to do the same. For the APIs whose values are comparable (`waterAt`, `isPresent`, `matchesAt`, `hasBlock`, `countBlocks`) the report also says whether both sides read the same values.
- What is timed: a first pass without any per-call timer gives wall time and ops/s; a second pass times every call for min / p50 / p99 / max (a bounded buffer of at most 1,000,000 samples). The report prints the clock's own cost per timed call, which is inside the latency numbers and not subtracted. 50,000 warm-up calls (16 and 8 for the scans) per side are not counted.
- Positions are always LIVE: `fixed` refuses with the chunk and its Core status when your own chunk is not LIVE in the Core (and loaded in Minecraft for `compare`); `random` and `acquire` skip chunks that are not, report how many, and refuse only when none is usable.
- The numbers come from this micro-bench, not from pack TPS or MSPT.

The build downloads WorldData Core 1.0.0 from CurseForge. The op mix uses the Core's block lookup by tag, name and state id, `hasTagAt` and `WDC.blocks()`, so it needs a Core that has them (1.0.0 or newer).

Report and counts file locations are written to the server log, not to chat.

Every report starts with one line saying the numbers come from WorldData Core Test, a dedicated benchmark mod running a synthetic workload, and are not a guarantee of TPS or MSPT in any modpack. `report.md` is the short report (header, time to finish, outcomes, WorldData Core vs Minecraft, latency, force-load waves, block writes); batches, phases, thrash workers, lag probes, allocation and the block-write check are in `report.json`. `captureaudit` shows the block-write check on demand.

`ramp` raises ops per second step by step until MSPT stays above `targetMs + overMs` for a hold window; `efficiency` counts useful ops per ms inside a fixed window of op time per tick. Both only read, run in a stable area on either arm, and have no gates. See `docs/index.html`.

Ramp and efficiency syntax. Options are positional or `key=value`; the source defaults to `auto` (Core when installed).

- `ramp|throttle [core|vanilla|minecraft|auto] <targetMs> [seed overMs holdTicks startOps stepOps tickIssueMs stepSeconds maxOps maxMinutes packBaselineMs coreBudgetMs]` plus `arm=`, `thrash=` and `note=`. Defaults: overMs 10, holdTicks 100, start 100 ops/s, +100 ops/s every second, 5,000,000 ops at most, 60 minutes at most.
- `efficiency [core|vanilla|minecraft|auto] [windowMs ticks seed coreBudgetMs safetyMs holdTicks warmup]` plus `thrash=` and `note=`. Defaults: 1 ms window, 300 ticks, 60 warm-up ticks.

Run limits: `ops` 1,000 to 1,000,000,000 (default 200,000), `opsPerSec` 0 (no cap, the default) to 1,000,000,000, `tickMs` 1 to 5 (default 3), `thrash` 16 (default) to 64.

The test mod never parks the server thread: force-load waves add tickets and poll readiness once per tick, and the Minecraft baseline beside the Core arm is sampled (one read in 32, one in 256 for scans, with a per-action floor), see `docs/index.html`.

## Credits and license

Author: Rohit_1597, the same author as WorldData Core. MIT, see `LICENSE.txt`.

Store page text for Modrinth and CurseForge: `store_description.md`.
