# CLAUDE.md

This file points Claude Code and other Claude agents at the canonical project context.

Read **AGENTS.md** for the stack, module layout, build and test commands, platform threading rules, config knobs, and house style. Read **CONTEXT.md** for why the project exists and the spawn math. Read **README.md** for the human-facing overview.

Claude-specific reminders:
- No em dashes in any output. Use conventional commits (feat/fix/docs/chore/refactor).
- Keep platform-neutral behavior in `core`; do not introduce Bukkit, Fabric, or Minecraft classes there.
- On Paper/Folia, world and entity work runs through the player's region scheduler. On Fabric, it stays on the server tick thread and is spread across the cycle. See AGENTS.md before changing either adapter.
- main is protected. Open a PR; never push to main directly or force-push.

## AI Attribution

No AI attribution of any kind in commits, PRs, code, comments, or generated files. No "Co-authored-by", no "Generated with", no model names.
