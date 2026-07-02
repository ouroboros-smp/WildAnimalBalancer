# CLAUDE.md

This file points Claude Code and other Claude agents at the canonical project context.

Read **AGENTS.md** for the stack, build and test commands, layout, the Folia threading rules, config knobs, and house style. Read **CONTEXT.md** for why the plugin exists and the spawn math. Read **README.md** for the human-facing overview.

Claude-specific reminders:
- No em dashes in any output. Use conventional commits (feat/fix/docs/chore/refactor).
- All world and entity work is anchored on players and runs through each player's region thread via `Entity#getScheduler()`. Never touch an entity off its owning region (see AGENTS.md, Folia threading rules).
- main is protected. Open a PR; never push to main directly or force-push.