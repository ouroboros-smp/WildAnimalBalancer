# AGENTS.md - WildAnimalBalancer

Agent-facing guide for this repository. The human overview is in README.md; the design rationale is in CONTEXT.md. Read all three before changing code.

## What this is

WildAnimalBalancer keeps wild passive animals available near online players. Version 2 is a Gradle multi-module project with one shared behavioral core, a Paper/Folia server plugin, a Fabric server mod, and an optional Fabric client HUD.

## Stack and targets

- Gradle wrapper: 9.5.1, Kotlin DSL.
- Group and version: `com.ouroboros`, `2.0.0`.
- `core`: Java 21, platform-neutral logic and monitoring.
- `paper`: Java 21, Paper API 1.21.11, Folia-native and Paper-compatible.
- `fabric`: Java 25, Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Loom 1.17.14.
- `fabric-hud`: Java 25, Minecraft 26.2, optional client-only admin HUD.
- Paper and Fabric produce separate server artifacts. Never put both on the same server.
- Do not raise the Paper or core compile target above Java 21. Fabric 26.2 requires Java 25.

## Build, test, and run

On Windows, use `gradlew.bat`. On Unix-like systems, use `./gradlew`.

- Everything: `.\gradlew.bat build`
- Shared tests: `.\gradlew.bat :core:test`
- Paper tests and jar: `.\gradlew.bat :paper:test :paper:jar`
- Fabric server jar: `.\gradlew.bat :fabric:build`
- Fabric HUD jar: `.\gradlew.bat :fabric-hud:build`
- Paper development server: `.\gradlew.bat :paper:runServer`
- Fabric development server: `.\gradlew.bat :fabric:runServer`
- Fabric client with the HUD: `.\gradlew.bat :fabric-hud:runClient`

Distributable jars land in:

- `paper/build/libs/WildAnimalBalancer-paper-2.0.0.jar`
- `fabric/build/libs/WildAnimalBalancer-fabric-2.0.0.jar`
- `fabric-hud/build/libs/WildAnimalBalancer-fabric-hud-2.0.0.jar`

After regenerating the wrapper, run `git update-index --chmod=+x gradlew` or Linux CI will fail.

## Module map

- `core`: `Settings`, YAML parsing and validation, target math, deficit and budget decisions, biome pool selection, the wild-animal predicate, stats, JSONL logging, and the Prometheus server. It has no Bukkit, Paper, Fabric, or Minecraft classes.
- `paper`: `WildlifePlugin` and `WildAnimalBalancer`. Adapts core settings and decisions to Bukkit registries, Folia schedulers, world access, commands, and entity spawning.
- `fabric`: `WildlifeMod`, `WildlifeRuntime`, `FabricBalancer`, commands, and the versioned HUD payload. Adapts the same core behavior to Minecraft 26.2 registries and the Fabric lifecycle.
- `fabric-hud`: client receiver, key binding, and compact top-right admin overlay. It depends on the main Fabric mod for the payload contract.
- `core/src/main/resources/vanilla-biome-animals.yml`: bundled vanilla spawn snapshot shared by both server platforms.

Shared behavior belongs in `core`. Platform modules should contain only lifecycle, registry, scheduler, command, networking, world, and rendering adapters.

## Paper and Folia threading rules

- A lightweight async task walks the online player list each cycle and hands each player to their owning region through `Entity#getScheduler()`.
- Everything that reads world state, reads entities, checks blocks, or spawns runs on the player's owning region thread.
- Folia requires ownership of every chunk touched by an entity query. The census checks the whole scan box with `Bukkit.isOwnedByCurrentRegion(location, chunkRadius)` and skips boundary-crossing scans. Do not replace this with exception handling.
- Spawn candidate chunks are checked again before block reads.
- Coarse per-world cell claims happen only after the ownership pre-check, so a skipped player never claims the cell or resets its streak.
- Paper routes the same scheduler calls to its main thread.

## Fabric threading rules

- Fabric world and entity work runs only from the server tick thread.
- At the start of a cycle, the balancer queues online player IDs. It spreads those censuses over the cycle instead of doing every player in one tick.
- The coarse cell claim, census, target decision, block checks, and spawn all happen synchronously on that server thread.
- Fabric has no Folia region boundary skip. The shared `skipped_region_boundary` metric remains present and stays zero.
- Config file parsing may run off-thread, but applying a new runtime happens on the server thread. Lifecycle callbacks and command registrations are installed once and must never be duplicated by reload.
- The HUD payload is sent only when the client can receive it and the player has `wildlife.hud` permission.

## Configuration

Paper writes `plugins/WildAnimalBalancer/config.yml`. Fabric writes `config/wildanimalbalancer/config.yml`. First-run files are copied from the matching bundled resource without rewriting comments or ordering.

Both platforms use the same keys and defaults: `cycle-seconds` (30), `scan-radius` (96), `base-target` (8), `per-additional-player` (4), `max-target` (40), `max-per-cycle` (6), `deficit-cycles` (3), `cell-hourly-budget` (30), `persistent-spawns` (true), `min-spawn-distance` (24), `spawn-tries` (20), `min-sky-light` (7), `animals`, `vanilla-biome-defaults`, `biome-animals`, `enabled-worlds`, logging settings, and metrics settings.

- Entity values use canonical IDs such as `minecraft:cow`. Legacy values such as `COW` remain accepted and normalize to the canonical form.
- `enabled-worlds` uses Bukkit world names on Paper, such as `world`.
- `enabled-worlds` uses dimension IDs on Fabric, such as `minecraft:overworld`.
- `biome-animals` keys use biome key paths such as `snowy_plains` on both platforms.
- The bundled biome table is a filter only. It never adds a species the admin did not configure. An explicit empty biome list disables that biome.
- `/wildlife reload` applies changes without resetting lifetime counters.

Monitoring counters are always collected. `/wildlife status`, `log-spawns`, `spawn-log-file`, `status-log-cycles`, and the built-in Prometheus endpoint control exposure. The metrics supplier may read counters and map sizes only, never world state.

## Permissions and HUD

- `wildlife.admin`: use `/wildlife reload` and `/wildlife status`. Paper defaults it to op. Fabric uses Fabric Permissions API with game-master level fallback.
- `wildlife.hud`: receive HUD samples. Fabric uses game-master level fallback.
- The optional HUD toggles with `H`, renders wild count versus target, deficit streak, and remaining hourly budget, then fades when samples become stale.

## Tests

Core tests cover config compatibility and validation, target math, deficit outcomes, budgeting, biome pools, the exhaustive wild decision table, stats, metrics formatting, and JSONL output. Paper tests cover the Bukkit adapter and Folia scan ownership radius. Keep platform-neutral regressions in `core` whenever possible.

Integration CI boots Paper and Folia 1.21.11, retains the Folia Mineflayer spawn check on 1.21.4, and boots a real Fabric 26.2 server with a Prometheus scrape assertion.

The release workflow always creates a GitHub release. It publishes to Modrinth only when the repository variable `MODRINTH_PROJECT_ID` is configured; that path also requires the `MODRINTH_TOKEN` secret.

## House rules

- No em dashes anywhere in code, comments, docs, or generated files.
- Conventional commits: `feat:`, `fix:`, `docs:`, `chore:`, or `refactor:`.
- `main` is protected. Land changes through a pull request. Never push directly to `main` or force-push.
- Do not commit secrets or bypass hooks.
- No AI attribution in commits, pull requests, code, comments, or generated files. Do not add model names, `Co-authored-by`, or generated-by notices.
- Wild is intentionally heuristic: an animal is wild when it is not tamed, leashed, or name-tagged. A claims integration would be a separate platform adapter concern.
