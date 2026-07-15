# WildAnimalBalancer

Keeps wild passive animals available where players actually are. Version 2 runs on Paper, Folia, and Fabric from one shared behavioral core.

## Why this exists

On a long-running survival server, passive animals run out. Cows, pigs, sheep, and chickens spawn heavily during world generation, while later vanilla replenishment is slow and capped. Players settle an area, consume the original herds, and eventually stop finding food animals.

WildAnimalBalancer watches occupied areas and tops up only when the local wild population stays below a demand-scaled target. Empty wilderness is left alone. Persistent deficit and hourly budget guardrails keep the balancer from becoming a repeatable meat farm.

## Platforms

| Server platform | Minecraft | Java | Artifact |
|---|---:|---:|---|
| Paper, Folia, Purpur | 1.21.x, built and smoked on 1.21.11 | 21 | `WildAnimalBalancer-paper-2.0.0.jar` |
| Fabric server | 26.2 | 25 | `WildAnimalBalancer-fabric-2.0.0.jar` |
| Fabric client HUD, optional | 26.2 | 25 | `WildAnimalBalancer-fabric-hud-2.0.0.jar` |

Fabric Loader 0.19.3 or newer and Fabric API 0.154.2+26.2 or newer are required for the Fabric artifacts. The Fabric server jar bundles Fabric Permissions API, the shared core, and its YAML parser.

Paper and Fabric are separate server artifacts. Install only the one matching the server.

## Features

- Demand-scaled top-ups around online players.
- One census per coarse occupied area per cycle, even when players overlap.
- A target that grows with local player count and stops at a configured ceiling.
- Consecutive-deficit and hourly-area budgets that resist slaughter loops.
- Same-species groups on loaded, lit grassland, with a minimum player distance.
- Wild-only counting. Tamed, leashed, and name-tagged animals are excluded.
- Persistent spawned animals, enabled by default.
- Shared biome-aware filtering and per-biome overrides.
- Live config reload without resetting lifetime counters.
- Status commands, optional JSONL audit logging, and a built-in Prometheus endpoint.
- An optional permission-gated Fabric admin HUD.

## Installation

### Paper or Folia

1. Put `WildAnimalBalancer-paper-2.0.0.jar` in the server's `plugins` folder.
2. Start the server with Java 21.
3. Edit `plugins/WildAnimalBalancer/config.yml`.
4. Run `/wildlife reload` or restart the server.

### Fabric server

1. Install Fabric Loader and Fabric API for Minecraft 26.2.
2. Put `WildAnimalBalancer-fabric-2.0.0.jar` in the server's `mods` folder.
3. Start the server with Java 25.
4. Edit `config/wildanimalbalancer/config.yml`.
5. Run `/wildlife reload` or restart the server.

The first start copies the complete bundled config file verbatim, including comments and ordering.

### Optional Fabric admin HUD

On an administrator's Fabric client, put all of these in `mods`:

- Fabric API for Minecraft 26.2
- `WildAnimalBalancer-fabric-2.0.0.jar`
- `WildAnimalBalancer-fabric-hud-2.0.0.jar`

The server still needs its own copy of `WildAnimalBalancer-fabric-2.0.0.jar`. Press `H` to toggle the compact top-right panel. It shows the most recent wild count and target, deficit streak, and remaining hourly budget. Samples fade after one cycle and disappear after two cycles. The server sends them only to clients that support the payload and players allowed by `wildlife.hud`.

## Upgrading from 1.x

Paper and Folia administrators can replace the old jar with the new Paper jar. The config directory and command names are unchanged. Existing values such as `COW`, `PIG`, `SHEEP`, and `CHICKEN` remain valid and normalize to canonical IDs internally.

For a Fabric migration, copy the values you want into `config/wildanimalbalancer/config.yml`. Two config fields deserve attention:

- Species now display as canonical IDs such as `minecraft:cow`. Legacy unqualified names are accepted.
- `enabled-worlds` means Bukkit world folder names on Paper, but dimension IDs such as `minecraft:overworld` on Fabric.

Monitoring series and command names remain stable, so existing Prometheus dashboards can scrape either server implementation. The `skipped_region_boundary` result exists on both; Fabric reports zero because it has no Folia region ownership boundary.

## How balancing works

Every cycle, 30 seconds by default, the balancer:

1. Claims one coarse per-world cell for an online player.
2. Counts wild animals and nearby players within the scan radius.
3. Computes `base-target + per-additional-player * extra players`, capped at `max-target`.
4. Advances that cell's consecutive deficit streak when the population is short.
5. Once the streak threshold is met, applies the per-cycle and hourly cell budgets.
6. Chooses one allowed species and tries to place a small group on suitable loaded grassland.

On Folia, player work is dispatched through each player's region scheduler. A census is skipped before querying entities if its scan box crosses a region boundary. Paper routes the same calls through its main thread.

On Fabric, the mod queues online player IDs at the start of the cycle and spreads their work across the cycle's ticks. All world reads and spawns stay on the server thread.

## Configuration

Both server implementations use the same keys and defaults.

| Option | Default | Meaning |
|---|---:|---|
| `cycle-seconds` | 30 | Seconds between cycles. |
| `scan-radius` | 96 | Horizontal and vertical census range, and maximum spawn distance. |
| `base-target` | 8 | Wanted wild animals for one player. |
| `per-additional-player` | 4 | Extra target per additional local player. |
| `max-target` | 40 | Hard target ceiling for an area. |
| `max-per-cycle` | 6 | Most animals spawned for an area in one cycle. |
| `deficit-cycles` | 3 | Consecutive short censuses required before spawning. |
| `cell-hourly-budget` | 30 | Most animals spawned per coarse area per hour. |
| `persistent-spawns` | true | Mark topped-up animals as persistent. |
| `min-spawn-distance` | 24 | Closest allowed spawn to the anchor player. |
| `spawn-tries` | 20 | Candidate location attempts before giving up. |
| `min-sky-light` | 7 | Minimum sky light at the spawn block. |
| `animals` | cow, pig, sheep, chicken | Canonical entity IDs the balancer may spawn. |
| `vanilla-biome-defaults` | true | Filter the configured species through the bundled vanilla biome table. |
| `biome-animals` | empty | Explicit species replacement per biome. An empty list disables that biome. |
| `enabled-worlds` | empty | Allowed worlds or dimensions. Empty means all. |
| `log-spawns` | false | Log one server line per successful top-up. |
| `spawn-log-file` | false | Append top-ups to a JSONL audit file on a dedicated IO thread. |
| `status-log-cycles` | 0 | Write a one-line summary every N cycles. Zero disables it. |
| `metrics.enabled` | false | Serve Prometheus text at `GET /metrics`. |
| `metrics.bind` | 127.0.0.1 | Metrics listener address. Keep it private. |
| `metrics.port` | 9940 | Metrics listener port. |

Use canonical species IDs in new configs:

```yaml
animals:
  - minecraft:cow
  - minecraft:pig
  - minecraft:sheep
  - minecraft:chicken
```

Biome override keys are key paths such as `snowy_plains`. The bundled table only filters the configured species and never adds one. Unknown custom biomes remain unfiltered.

Paper world allowlist example:

```yaml
enabled-worlds:
  - world
```

Fabric dimension allowlist example:

```yaml
enabled-worlds:
  - minecraft:overworld
```

## Commands and permissions

| Command | Permission | Description |
|---|---|---|
| `/wildlife reload` | `wildlife.admin` | Parse and apply config without restarting. |
| `/wildlife status` | `wildlife.admin` | Print counters and current cell gauges. |

Paper defaults `wildlife.admin` to operators. Fabric uses Fabric Permissions API with game-master permission level as its fallback. The Fabric HUD uses `wildlife.hud` with the same game-master fallback.

## Monitoring

Counters cover the server lifetime and survive `/wildlife reload`. The scrape supplier reads only counters and map sizes, never world or entity state.

Enable the endpoint:

```yaml
metrics:
  enabled: true
  bind: 127.0.0.1
  port: 9940
```

Exported series:

| Metric | Type | Meaning |
|---|---|---|
| `wildlife_census_total{result}` | counter | Censuses by `run`, `skipped_region_boundary`, `skipped_shared_cell`, `skipped_disabled_world`, or `failed`. |
| `wildlife_deficit_cycles_total` | counter | Censused cycles that found an area short. |
| `wildlife_topup_blocked_total{reason}` | counter | Top-ups held by streak, budget, terrain, or biome pool. |
| `wildlife_spawn_groups_total` | counter | Successful group top-ups. |
| `wildlife_spawned_animals_total` | counter | Total animals spawned. |
| `wildlife_spawned_species_total{species}` | counter | Animals spawned by species. |
| `wildlife_spawned_world_total{world}` | counter | Animals spawned by world or dimension. |
| `wildlife_cells_active` | gauge | Recently censused coarse cells. |
| `wildlife_cells_on_deficit_streak` | gauge | Cells currently building a shortage streak. |
| `wildlife_cells_budget_spent` | gauge | Cells that have spent their hourly allowance. |

Paper writes the optional audit file to `plugins/WildAnimalBalancer/spawn-log.jsonl`. Fabric writes it to `config/wildanimalbalancer/spawn-log.jsonl`.

Useful alert signals include any rise in `wildlife_census_total{result="failed"}`, frequent Folia boundary skips, and sustained `no_spawn_spot` blocks in areas players expect to refill.

## Notes and limitations

- Wild is a heuristic. An unnamed, unleashed, untamed farm animal still counts as wild.
- Spawning requires a grass block, sky light, and two air blocks. Deserts, oceans, underground bases, and the Nether generally will not refill.
- Only online players and loaded chunks are considered.
- This is not a hostile mob or general farm booster.

## Building

The Gradle toolchain resolver obtains Java 21 for core and Paper tasks and Java 25 for Fabric tasks when needed.

```powershell
.\gradlew.bat build
.\gradlew.bat :paper:runServer
.\gradlew.bat :fabric:runServer
.\gradlew.bat :fabric-hud:runClient
```

Build outputs are under each module's `build/libs` directory. The internal core jar is not a server installation artifact.

## License

Set your license here.
