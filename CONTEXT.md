# CONTEXT.md - WildAnimalBalancer design and domain

Deep background for agents and contributors. For build and commands see AGENTS.md.

## Why this exists
On a long-running survival server, passive animals run out. Cows, pigs, sheep, and chickens spawn almost entirely at world generation, in herds, when a chunk is first created. After that, vanilla repopulation is slow and capped: the animal mob cap sits around 10, and the game only attempts an animal spawn roughly every 400 ticks (about 20 seconds), against every tick for monsters. Players settle an area, eat through the original herds, and food stops appearing. Raising server-wide spawn rates boosts spawns everywhere and still will not reliably repopulate a stripped, settled area.

## What it does
Every cycle (30 seconds by default), for each occupied area (one census per coarse per-world cell per cycle, anchored on the first player dispatched there) it counts wild animals around the anchor player, works out a target from how many players share that scan area, and if the area is short it spawns the difference on valid grassland nearby, up to a per-cycle cap. Players sharing a cell are folded into that one census rather than each triggering their own. Animals show up where people are playing, scaled to how busy the area is. Empty wilderness nobody is standing in stays empty, which is the point.

Version 2 implements that policy through a platform-neutral Java core and two server adapters. The Paper adapter targets Paper and Folia 1.21.11 on Java 21. The Fabric adapter targets Minecraft 26.2 on Java 25. A separate optional Fabric client module renders admin-only samples sent by the server mod. Targets, streaks, budgets, biome filtering, monitoring, config parsing, and wildness decisions must stay behaviorally identical across the server adapters.

## Design decisions
- Demand-driven, not blanket. Top-ups happen around players, not scattered across every loaded chunk.
- Player-density scaling. Target = `base-target + per-additional-player * (extra players)`, capped at `max-target`.
- Wild only. Tamed, leashed, and name-tagged animals are ignored so the plugin does not pad player farms or count pets. "Wild" is a heuristic because the Bukkit API has no farmed flag.
- Grassland only. Animals need a grass block with sky light and headroom, so deserts, oceans, the Nether, and underground bases are not repopulated. This keeps spawns looking natural.
- Throttled. `max-per-cycle` keeps top-ups gradual instead of dropping a herd on someone at once.
- Farm-proof. Wildlife is a commons players can raid, so the balancer must not become a free meat faucet that replaces husbandry. A shortage has to persist for `deficit-cycles` consecutive censused cycles before a top-up (a just-slaughtered herd does not respawn on the spot, and passing through an area triggers nothing), and each ~128-block area has an hourly spawn budget (`cell-hourly-budget`). Breeding stays the efficient path to bulk food; the balancer guarantees a starting stock, not a supply.
- Herds, not scatter. A top-up spawns one species as a small group around a single spot, like worldgen herds, instead of sprinkling singles across the whole radius.
- Persistent spawns. Topped-up animals are flagged not to despawn when players leave, so repopulation sticks instead of evaporating behind the player.
- Biome-appropriate by default. The server APIs do not provide one stable, cross-platform source for the intended passive animal pool, so the distributable jars bundle a shared vanilla spawn snapshot (`vanilla-biome-animals.yml`). With `vanilla-biome-defaults` on, it narrows the configured species list per biome. It is a filter, never an expansion: species the admin did not configure are never introduced, and biomes missing from the snapshot are left unfiltered so custom worlds keep working. `biome-animals` replaces the species list for one biome, or disables it with an empty list. The snapshot can drift when Mojang changes spawn lists; it is updated with the project, not at runtime.
- Online, loaded chunks only, by design.

## Platform execution models

Paper and Folia share one adapter because Paper exposes Folia's schedulers. Work is anchored to players and dispatched to each player's owning region. A census whose scan box crosses a Folia region boundary is skipped before the entity query. This is why the monitoring contract includes `skipped_region_boundary`.

Fabric's dedicated server is single-threaded for world simulation. It snapshots player IDs at the start of a cycle and drains the work queue across the remaining ticks in that cycle. All entity and block access remains on the server thread. Fabric never emits a region boundary skip, but it preserves that shared metric with a value of zero so dashboards do not need platform-specific schemas.

Config and monitoring infrastructure are shared. Paper identifies allowed worlds by Bukkit world name. Fabric identifies them by dimension ID. Entity types are canonical namespaced strings in the core, then resolved through the platform registry. Legacy Bukkit enum names remain accepted at the config boundary for a no-surprise 1.x upgrade.

## Tuning notes
- Still getting shortage complaints? Look at `deficit-cycles` and `cell-hourly-budget` first; they decide how quickly and how much an area can refill. Raising `max-per-cycle` fills deficits faster within those limits. All three are economy levers: the cheaper wild meat is, the less players breed, so move them gently.
- Keep `scan-radius` at or below view distance in blocks; spawning outside loaded chunks does nothing.
- The deficit streak is tracked per ~128-block cell. A player based right on a cell border can alternate between cells and struggle to build a streak in either; if a specific base reports no top-ups, check this before raising `deficit-cycles` tolerance.
- Pairs well with server config: in paper-world-defaults.yml, lower `entities.spawning.ticks-per-spawn.creature` and set `per-player-mob-spawns: true`.

## Where it fits
One of the Ouroboros SMP gameplay projects. The multi-module design lets Paper/Folia and Fabric share one tested policy without pretending that their threading, registry, command, and networking APIs are interchangeable. It is unrelated to the Mehen governance system.
