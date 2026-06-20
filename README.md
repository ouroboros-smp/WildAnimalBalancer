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
3. If the area is short, spawns the difference on valid grassland nearby, up to a per cycle cap.

Animals show up where people are playing, scaled to how busy the area is. Empty wilderness nobody is standing in stays empty, which is the point.

## Features

- Demand driven spawning. Animals are topped up around players, not scattered across every loaded chunk.
- Player density scaling. A spot with four players gets a higher target than a spot with one.
- Folia native. All counting and spawning runs on the region thread that owns the chunks, through each player's EntityScheduler. No main thread assumptions, no cross region access.
- Paper compatible. The same jar runs on Paper unchanged. It compiles against paper-api only, with no extra libraries to shade.
- Wild only. Tamed, leashed, and name tagged animals are ignored, so it does not pad your players' farms or count their pets.
- Throttled. A per cycle spawn cap keeps top ups gradual instead of dropping a herd on someone all at once.
- Configurable. Species list, targets, radius, and a per world allowlist, all in `config.yml`.
- Live reload. Retune with a command, no restart.

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
| `min-spawn-distance` | 24 | Closest an animal will spawn to the player. |
| `spawn-tries` | 20 | Location attempts per animal before giving up on a spot. |
| `min-sky-light` | 7 | Minimum sky light at the spawn block. Keeps spawns out of caves and shade. |
| `animals` | COW, PIG, SHEEP, CHICKEN | Species to spawn. Uses Bukkit EntityType names. |
| `enabled-worlds` | (empty) | Worlds to run in. Empty means every world. |

The target for an area is `base-target + per-additional-player * (extra players)`, capped at `max-target`.

## Commands

| Command | Description |
|---|---|
| `/wildlife reload` | Reload `config.yml` and restart the balancer with the new values. |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `wildlife.admin` | op | Use of `/wildlife reload`. |

## Tuning tips

- Still getting shortage complaints? Raise `max-per-cycle` before you raise the targets. That fills deficits faster without changing how many animals can exist in an area.
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
