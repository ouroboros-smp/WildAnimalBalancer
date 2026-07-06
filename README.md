# WildAnimalBalancer

Keeps wild animals available where players actually are. Built for Folia, runs on Paper.

## Why this exists

On a long running survival server, animals run out. Not because spawning is broken, but because of how passive animals work. Cows, pigs, sheep, and chickens spawn almost entirely at world generation, in herds, when a chunk is first created. After that, vanilla repopulation is slow and capped. The animal mob cap sits around 10, and the game only attempts an animal spawn roughly every 400 ticks (about 20 seconds), against every tick for monsters.

So players settle an area, eat through the original herds, and food stops appearing. You get complaints like this:

> I'd say it would be very helpful if you increased animal spawn rates somehow. Ran out of food and had no mobs spawning for a long time.

Raising server spawn rates helps a little, but it boosts spawns everywhere and still will not reliably repopulate a stripped, settled area. WildAnimalBalancer fixes the actual problem. It watches the area around each player and tops up wild animals when that area falls below a target.

## What it does

Every cycle (30 seconds by default), for each online player, it:

1. Counts wild animals in the area around that player.
2. Works out a target based on how many players are sharing that area.
3. If the area has stayed short for several cycles, spawns the difference as a small same species herd on valid grassland nearby, up to a per cycle cap and an hourly per area budget.

Animals show up where people are playing, scaled to how busy the area is. Empty wilderness nobody is standing in stays empty, which is the point.

## Features

- Demand driven spawning. Animals are topped up around players, not scattered across every loaded chunk.
- Player density scaling. A spot with four players gets a higher target than a spot with one.
- Folia native. All counting and spawning runs on the region thread that owns the chunks, through each player's EntityScheduler. No main thread assumptions, no cross region access.
- Paper compatible. The same jar runs on Paper unchanged. It compiles against paper-api only, with no extra libraries to shade.
- Wild only. Tamed, leashed, and name tagged animals are ignored, so it does not pad your players' farms or count their pets.
- Throttled. A per cycle spawn cap keeps top ups gradual instead of dropping a herd on someone all at once.
- Farm proof. A shortage must persist for several cycles before a top up, and every area has an hourly spawn budget. Standing in a field and killing everything on repeat stops paying out, so breeding stays the efficient path to bulk food.
- Herds, not scatter. Each top up spawns one species as a small group around a single spot, like worldgen herds.
- Persistent. Spawned animals do not despawn when players leave, so top ups genuinely repopulate the area.
- Biome aware. A bundled snapshot of vanilla spawn data narrows the species list per biome, so no pigs on snowy plains and only mooshrooms on mushroom fields. It only ever filters; species you did not configure are never added. Overridable per biome, or disable it entirely.
- Configurable. Species list, targets, radius, per biome species overrides, and a per world allowlist, all in `config.yml`.
- Live reload. Retune with a command, no restart.
- Observable. Every decision is counted: `/wildlife status` in game, an optional per spawn audit log (console or JSONL file), an optional periodic summary line, and a built-in Prometheus endpoint for Grafana dashboards.

## How it works

Folia splits the world into regions that tick in parallel on separate threads, and you cannot read or spawn an entity from a thread that does not own it. Many spawn helper plugins ignore this and crash on Folia.

This one anchors all of its work on players. A lightweight async task walks the player list each cycle and hands each player off to their own region thread through `Entity#getScheduler()`. Everything that touches the world, counting nearby animals, checking blocks, and spawning, happens on the correct thread for that location. Entities sitting across a region boundary are skipped rather than forced, so you never see "accessing entity state off owning region's thread" errors.

On Paper, the same scheduler calls route to the single main thread, so the plugin behaves identically without a separate build.

## Requirements

| | |
|---|---|
| Server | Paper or Folia |
| Minecraft | 1.21+ |
| Java | 21+ |

The provided build targets paper-api 1.21.11. If you build it yourself for a different version, match that line to your server.

## Installation

1. Download the jar.
2. Put it in your server's `plugins` folder.
3. Start the server. The first start writes `plugins/WildAnimalBalancer/config.yml`.
4. Edit the config to taste and run `/wildlife reload`, or restart.

## Configuration

All settings live in `config.yml`. Edit, then run `/wildlife reload` to apply without a restart.

| Option | Default | What it does |
|---|---|---|
| `cycle-seconds` | 30 | How often each player's area is checked and topped up. |
| `scan-radius` | 96 | Blocks around a player to count animals and to spawn within. |
| `base-target` | 8 | Wild animals wanted in an area with a single player. |
| `per-additional-player` | 4 | Extra wanted for each additional player sharing the area. |
| `max-target` | 40 | Hard ceiling per area, regardless of crowd size. |
| `max-per-cycle` | 6 | Most animals spawned per area per cycle. Keeps top ups gradual. |
| `deficit-cycles` | 3 | Consecutive short cycles required before a top up. Stops instant refills after a slaughter. |
| `cell-hourly-budget` | 30 | Most animals spawned per ~128 block area per hour. The anti farm guardrail. |
| `persistent-spawns` | true | Spawned animals do not despawn when players leave. |
| `min-spawn-distance` | 24 | Closest an animal will spawn to the player. |
| `spawn-tries` | 20 | Location attempts per animal before giving up on a spot. |
| `min-sky-light` | 7 | Minimum sky light at the spawn block. Keeps spawns out of caves and shade. |
| `animals` | COW, PIG, SHEEP, CHICKEN | Species to spawn. Uses Bukkit EntityType names. |
| `vanilla-biome-defaults` | true | Narrow the species list per biome to what vanilla spawns there, using a bundled snapshot of vanilla Java 1.21 spawn data. Filter only, never adds species. Unknown (custom or datapack) biomes are not filtered. |
| `biome-animals` | (empty) | Per biome species overrides. Beats the vanilla filter. An empty list for a biome disables it. |
| `enabled-worlds` | (empty) | Worlds to run in. Empty means every world. |
| `log-spawns` | false | Log one console line per top-up with full context. |
| `spawn-log-file` | false | Also append each top-up as a JSON line to `plugins/WildAnimalBalancer/spawn-log.jsonl`. |
| `status-log-cycles` | 0 | Log a one-line stats summary every N cycles. 0 disables it. |
| `metrics.enabled` | false | Serve Prometheus metrics at `GET /metrics`. |
| `metrics.bind` | 127.0.0.1 | Address the metrics endpoint binds to. Keep it private. |
| `metrics.port` | 9940 | Port for the metrics endpoint. |

The target for an area is `base-target + per-additional-player * (extra players)`, capped at `max-target`.

## Commands

| Command | Description |
|---|---|
| `/wildlife reload` | Reload `config.yml` and restart the balancer with the new values. |
| `/wildlife status` | Print the monitoring counters and live cell gauges. |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `wildlife.admin` | op | Use of `/wildlife reload` and `/wildlife status`. |

## Monitoring

The balancer counts every decision it makes: censuses run and why any were skipped, shortages seen, which guardrail held a top-up back, and what actually spawned, per species and per world. Counters are always collected (they cost nothing); the config switches only control how they are exposed. They cover the server's lifetime and survive `/wildlife reload`.

### In game or console

- `/wildlife status` prints everything at any time: census results, the shortage funnel, spawn totals by species and world, and how many areas are active, building a deficit streak, or out of hourly budget.
- `status-log-cycles: 120` writes a one-line summary to the server log every 120 cycles (hourly at the default cycle length), so whatever already ships your logs picks it up.

### Spawn audit log

- `log-spawns: true` logs one console line per top-up: world, coordinates, biome, species, the wild count against the target, the streak that triggered it, and the hourly budget left. Top-ups are rare by design, so this stays quiet.
- `spawn-log-file: true` appends the same events to `plugins/WildAnimalBalancer/spawn-log.jsonl`, one JSON object per line, written on a dedicated IO thread. Handy with `jq`:

```sh
# spawns per species
jq -r '.species' spawn-log.jsonl | sort | uniq -c | sort -rn
# what an area received, when
jq -r 'select(.world == "world") | [.time, .x, .z, .species, .spawned] | @tsv' spawn-log.jsonl
```

### Prometheus and Grafana

The plugin can serve Prometheus's text format itself, no exporter plugin needed:

```yaml
metrics:
  enabled: true
  bind: 127.0.0.1
  port: 9940
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: wildanimalbalancer
    static_configs:
      - targets: ["127.0.0.1:9940"]
```

Exported series:

| Metric | Type | Meaning |
|---|---|---|
| `wildlife_census_total{result}` | counter | Censuses by outcome: `run`, `skipped_region_boundary`, `skipped_shared_cell`, `skipped_disabled_world`, `failed`. |
| `wildlife_deficit_cycles_total` | counter | Censused cycles that found an area short. |
| `wildlife_topup_blocked_total{reason}` | counter | Top-ups withheld: `deficit_streak`, `hourly_budget`, `no_spawn_spot`, `empty_biome_pool`. |
| `wildlife_spawn_groups_total` | counter | Herd top-ups performed. |
| `wildlife_spawned_animals_total` | counter | Animals spawned. |
| `wildlife_spawned_species_total{species}` | counter | Animals spawned, by species. |
| `wildlife_spawned_world_total{world}` | counter | Animals spawned, by world. |
| `wildlife_cells_active` | gauge | Areas censused within the last few cycles. |
| `wildlife_cells_on_deficit_streak` | gauge | Areas currently building a shortage streak. |
| `wildlife_cells_budget_spent` | gauge | Areas whose hourly spawn budget is fully spent. |

Queries worth graphing or alerting on:

- `rate(wildlife_spawned_animals_total[1h])`: the meat faucet dial. If this trends up over weeks while player count does not, revisit `deficit-cycles` and `cell-hourly-budget` (they are economy levers).
- `increase(wildlife_census_total{result="skipped_region_boundary"}[1h])`: on Folia, a persistently high value means players are living near region borders and rarely get censused; their areas will feel empty even though the plugin is healthy.
- `increase(wildlife_topup_blocked_total{reason="no_spawn_spot"}[1h])`: rising means the terrain keeps rejecting spawns (no grass, low sky light), so shortages will not fill where players actually are.
- `increase(wildlife_census_total{result="failed"}[10m]) > 0`: should be zero; anything else is a bug worth reporting.

The endpoint reads only the plugin's own counters, never world or entity state, so scraping cannot affect a tick. Still, keep the bind on localhost or a private scrape network.

### What to watch in the server log

- `WARNING ... census failed for <player>`: the ownership pre-check should make this impossible, so a repeating warning means a real bug is being skipped. It is rate limited to once per cycle.
- Any Folia `ERROR` mentioning "accessing entity state off owning region": should never appear; the plugin skips region boundaries instead of forcing them.
- If you want to confirm the census cost stays negligible on region threads, profile with [spark](https://spark.lucko.me/) (`/spark profiler`) during peak hours; the balancer's work shows up under the plugin's scheduled tasks.

## Tuning tips

- Still getting shortage complaints? Look at `deficit-cycles` and `cell-hourly-budget` first; they decide how quickly and how much an area can refill. Raising `max-per-cycle` fills deficits faster within those limits. All three are economy levers: the cheaper wild meat is, the less your players breed, so move them gently.
- Keep `scan-radius` at or below your view distance in blocks. Spawning outside loaded chunks does nothing.
- To run this only in your survival world, list it under `enabled-worlds`. Leaving it empty runs everywhere, including mining or resource worlds where you may not want it.
- Want more variety? Add any breedable animal's EntityType to the `animals` list, for example `HORSE`, `RABBIT`, or `GOAT`.

## Notes and limitations

- Wild is a heuristic. The Bukkit API has no flag that marks an animal as farmed, so the plugin treats anything that is not tamed, leashed, or name tagged as wild. An unfenced, unnamed cow standing in someone's base will count as wild. If you need strict separation, pair it with a claims plugin and add a claim check. The code has a marked spot for it.
- It spawns on grassland only. Animals need a grass block with sky light and headroom, so the balancer will not repopulate deserts, oceans, the Nether, or an underground base. That is intentional. It keeps spawns looking natural.
- It only acts around online players in loaded chunks, by design.
- It is not a hostile mob or farm booster. It targets wild animal availability, nothing else.

## Pairing with server config

This plugin handles the "animals are gone where I'm standing" problem. If you also want the world to refill a bit on its own, in `config/paper-world-defaults.yml` lower `entities.spawning.ticks-per-spawn.creature` from the vanilla default and set `per-player-mob-spawns: true`. The two approaches stack well.

## License

Set your license here.
