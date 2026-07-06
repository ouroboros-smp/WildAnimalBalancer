# AGENTS.md - WildAnimalBalancer

Agent-facing guide for this repo. The human overview is in README.md; the design rationale is in CONTEXT.md. Read all three before changing code.

## What this is
A Minecraft server plugin (Folia-native, Paper-compatible) for Ouroboros SMP that keeps wild animals available where players actually are. It watches the area around each online player and tops up wild animals when that area falls below a demand-scaled target.

## Stack and targets
- Language: Java 21 (toolchain pinned in build.gradle.kts). Do not raise the compile target above 21.
- Server API: `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, compileOnly. The Folia schedulers are part of paper-api, so this one dependency covers Paper and Folia across 1.21.x. No libraries to shade.
- Build: Gradle (Kotlin DSL), wrapper 8.14.2, `xyz.jpenilla.run-paper` for local runs.
- Manifest: src/main/resources/plugin.yml (`folia-supported: true`, `api-version: '1.21'`).
- group `com.ouroboros`, version 1.0.0. Public repo.

## Build, test, run
- Build + unit tests: `./gradlew build` (jar lands at build/libs/WildAnimalBalancer-1.0.0.jar).
- Unit tests only: `./gradlew test` (JUnit 5 + Mockito).
- Local server: `./gradlew runServer` (downloads Paper 1.21.11).
- On Windows use `.\gradlew.bat`. After regenerating the wrapper, run `git update-index --chmod=+x gradlew` or CI fails on a non-executable wrapper.

## Layout
- `com.ouroboros.wildlife.WildlifePlugin`: JavaPlugin entry. Loads config, registers `/wildlife` (reload, status), starts and stops the balancer, spawn logger, and metrics endpoint.
- `com.ouroboros.wildlife.WildAnimalBalancer`: the cycle engine. Counts, targets, and spawns.
- `com.ouroboros.wildlife.BalancerStats`: lock-free monitoring counters (LongAdder), owned by the plugin so they survive reload; renders /wildlife status lines, the periodic summary, and the Prometheus text format. Pure JDK.
- `com.ouroboros.wildlife.SpawnLogger`: optional JSONL spawn audit log, appended on a dedicated IO thread so region threads never block on disk. Pure JDK.
- `com.ouroboros.wildlife.MetricsServer`: optional built-in Prometheus endpoint (JDK HttpServer, one daemon thread). The scrape supplier reads counters and map sizes only, NEVER world state. Pure JDK.
- Tests: ConfigParsingTest, TargetMathTest, WildAnimalPredicateTest, BalancerStatsTest, SpawnLoggerTest, CensusOutcomeTest.

## Folia threading rules (do not violate)
- The plugin anchors all work on players. A lightweight async task walks the online player list each cycle and hands each player off to their own region thread via `Entity#getScheduler()`.
- Everything that touches the world (counting nearby animals, checking blocks, spawning) happens on the player's owning region thread. Never read or spawn an entity from a thread that does not own it.
- Folia requires the current region to own every chunk an entity query touches, and it logs at ERROR before throwing, so boundary handling cannot be a catch. The census pre-checks ownership of the whole scan box (`Bukkit.isOwnedByCurrentRegion(location, chunkRadius)`) and skips that player's census for the cycle when the box crosses a region boundary: skipped, never forced, and the log never shows "accessing entity state off owning region". Spawn-spot chunks are re-checked individually before block reads.
- Overlapping player areas are deduped each cycle by a coarse per-world ~128-block cell claim, made only after the ownership pre-check passes so a skipped boundary player never blocks the cell or resets streaks. One census runs per cell per cycle; the target scales with the players found in the anchor player's scan box.
- On Paper these scheduler calls route to the single main thread, so the same jar behaves identically with no separate build.

## Config (src/main/resources/config.yml, live reload via /wildlife reload)
Key knobs: `cycle-seconds` (30), `scan-radius` (96), `base-target` (8), `per-additional-player` (4), `max-target` (40), `max-per-cycle` (6), `deficit-cycles` (3, consecutive short cycles required before a top-up), `cell-hourly-budget` (30, most spawns per ~128-block area per hour), `persistent-spawns` (true, spawned animals do not despawn when players leave), `min-spawn-distance` (24), `spawn-tries` (20), `min-sky-light` (7), `animals` (COW, PIG, SHEEP, CHICKEN as Bukkit EntityType names), `vanilla-biome-defaults` (true, narrows the animals list per biome using the bundled src/main/resources/vanilla-biome-animals.yml snapshot of vanilla Java 1.21 spawn data; filter only, never adds species, unknown biomes unfiltered), `biome-animals` (empty, explicit per-biome species overrides that beat the vanilla filter; an empty list disables a biome), `enabled-worlds` (empty means every world). Target for an area = `base-target + per-additional-player * (extra players)`, capped at `max-target`. `deficit-cycles` and `cell-hourly-budget` are the anti-farm guardrails; top-ups spawn as one same-species group per cycle.

Monitoring knobs (counters are always collected; these only control exposure): `log-spawns` (false, one console line per top-up), `spawn-log-file` (false, JSONL audit at plugins/WildAnimalBalancer/spawn-log.jsonl), `status-log-cycles` (0 = off, one-line summary every N cycles), `metrics.enabled`/`metrics.bind`/`metrics.port` (false/127.0.0.1/9940, built-in Prometheus endpoint). `/wildlife status` prints the counters at any time. See README "Monitoring" for the exported series.

## CI (.github/workflows)
- build.yml: gradle build + jar artifact on push to main and on PRs.
- integration.yml: boots real Paper and Folia 1.21.11 servers (boot-smoke), asserts "WildAnimalBalancer running." with no enable or Folia errors; plus a Folia E2E driven by a Mineflayer bot (pinned to 1.21.4 because Mineflayer's protocol caps there; plugin behaviour is identical across 1.21.x).
- release.yml: pushing a `v*` tag builds and publishes to GitHub Release + Modrinth via mc-publish. `modrinth-id` is still the placeholder `YOUR_MODRINTH_PROJECT_ID`; set it before a real release.

## House rules
- No em dashes anywhere (code, comments, docs). Use commas, periods, parentheses, or a semicolon.
- Conventional commits: feat:, fix:, docs:, chore:, refactor:.
- main is protected. Land changes via PR; do not push to main directly or force-push.
- Do not commit secrets and do not bypass pre-commit hooks.
- "Wild" is a heuristic: anything not tamed, leashed, or name-tagged. There is a marked spot in the code for a claims-plugin check if strict separation is ever needed.

## AI Attribution

No AI attribution of any kind in commits, PRs, code, comments, or generated files. No "Co-authored-by", no "Generated with", no model names.
