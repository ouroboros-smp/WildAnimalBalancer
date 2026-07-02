# CONTEXT.md - WildAnimalBalancer design and domain

Deep background for agents and contributors. For build and commands see AGENTS.md.

## Why this exists
On a long-running survival server, passive animals run out. Cows, pigs, sheep, and chickens spawn almost entirely at world generation, in herds, when a chunk is first created. After that, vanilla repopulation is slow and capped: the animal mob cap sits around 10, and the game only attempts an animal spawn roughly every 400 ticks (about 20 seconds), against every tick for monsters. Players settle an area, eat through the original herds, and food stops appearing. Raising server-wide spawn rates boosts spawns everywhere and still will not reliably repopulate a stripped, settled area.

## What it does
Every cycle (30 seconds by default), for each online player it counts wild animals in the area around that player, works out a target from how many players share that area, and if the area is short it spawns the difference on valid grassland nearby, up to a per-cycle cap. Animals show up where people are playing, scaled to how busy the area is. Empty wilderness nobody is standing in stays empty, which is the point.

## Design decisions
- Demand-driven, not blanket. Top-ups happen around players, not scattered across every loaded chunk.
- Player-density scaling. Target = `base-target + per-additional-player * (extra players)`, capped at `max-target`.
- Wild only. Tamed, leashed, and name-tagged animals are ignored so the plugin does not pad player farms or count pets. "Wild" is a heuristic because the Bukkit API has no farmed flag.
- Grassland only. Animals need a grass block with sky light and headroom, so deserts, oceans, the Nether, and underground bases are not repopulated. This keeps spawns looking natural.
- Throttled. `max-per-cycle` keeps top-ups gradual instead of dropping a herd on someone at once.
- Online, loaded chunks only, by design.

## Tuning notes
- Still getting shortage complaints? Raise `max-per-cycle` before raising targets. That fills deficits faster without changing how many animals can exist in an area.
- Keep `scan-radius` at or below view distance in blocks; spawning outside loaded chunks does nothing.
- Pairs well with server config: in paper-world-defaults.yml, lower `entities.spawning.ticks-per-spawn.creature` and set `per-player-mob-spawns: true`.

## Where it fits
One of the Ouroboros SMP gameplay plugins. Like the others it keeps a clean, testable core so the logic survives the project's planned move from Folia to Minestom. It is unrelated to the Mehen governance system.