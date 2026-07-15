# Review prompt - WildAnimalBalancer

Paste the block below to drive an intent-conformance review. This is not a style pass: the intended behavior lives in AGENTS.md and CONTEXT.md, and the review compares the code against it.

---

Do an intent-conformance review of this repo, not a style pass.

The intended behavior is written down. Read AGENTS.md and CONTEXT.md FIRST, then read the code.

For each meaningful area of the code, tell me:
1. What the docs say it should do (cite the doc and section).
2. What the code actually does.
3. Match or not. If not, classify it as CODE BUG (code violates stated intent) or DOC DRIFT (docs are stale vs working code).
4. Severity (P0/P1/P2) and the smallest correct fix.

Check these invariants explicitly:
- Platform-neutral config, math, decisions, monitoring, and logging stay in `core`, which has no server API types.
- On Paper/Folia, all counting and spawning runs on the owning region through each player's EntityScheduler. A scan box that crosses a region boundary is skipped before the entity query.
- On Fabric, all world and entity access stays on the server tick thread, and queued player work is spread across each cycle.
- `/wildlife reload` preserves lifetime counters and does not duplicate Fabric lifecycle, command, or tick callbacks.
- The wild predicate excludes tamed, leashed, and name-tagged animals.
- The per-area target matches the config formula: base-target + per-additional-player * (extra players), capped at max-target.
- Per-cycle spawning is throttled by max-per-cycle; spawns respect min-spawn-distance, min-sky-light, and grassland-only placement.
- enabled-worlds is honored (empty means every world), using world names on Paper and dimension IDs on Fabric.
- The optional HUD is client-only, permission-gated, and uses a versioned payload that fades when samples become stale.

Rules:
- Where code and docs disagree, say which one you would change and why.
- Findings first. Propose diffs only after I confirm.
- No em dashes; conventional commit messages if you write any.
- Default branch is main.

End with a short table: area | verdict | severity.
