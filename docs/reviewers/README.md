# Specialist Reviewers — How to Add One

The list of reviewers and when to use them is [`registry.md`](registry.md). This file is how to add
one.

## Layout

| What | Where |
|---|---|
| Agent definition | `.claude/agents/<domain>-review-<lens>.md` |
| Rules shared by a domain's reviewers (context to load, severity, output format) | `docs/reviewers/<domain>/protocol.md` |
| One-line "Use when" for every reviewer | `docs/reviewers/registry.md` (imported by `CLAUDE.md`) |
| Drift guard | `scripts/check-reviewer-registry.sh`, run in CI's `check` job |

`<domain>` is a short area name (`frontend`, `backend`, `infra`, `data`…). The `-review-` infix is what
the drift guard keys on, so every specialist reviewer must have it and nothing else in `.claude/agents/`
may.

## Adding a reviewer

1. **Protocol.** If `docs/reviewers/<domain>/protocol.md` does not exist, create it, using
   [`frontend/protocol.md`](frontend/protocol.md) as the template. It holds everything the domain's
   reviewers share, so the agent files stay short.
2. **Agent file.** Create `.claude/agents/<domain>-review-<lens>.md`:
   ```markdown
   ---
   name: <domain>-review-<lens>
   description: Use when <one sentence: which artifacts, touching what>.
   tools: Read, Grep, Glob, Bash
   ---

   You are ... Your lens is **<lens>**.

   **First, read `docs/reviewers/<domain>/protocol.md` and follow it exactly.** Then review against
   the checklist below. Report only items where you found something.

   ## Checklist
   ...
   ```
   - `name` equals the filename without `.md`.
   - `description` is **one sentence starting "Use when"** that names the triggers, not the reviewer's
     personality. It is what agents match on. Avoid `: ` inside it (it breaks YAML).
   - Tools stay read-only. No `Edit`, `Write`, `WebFetch` or `Agent`.
   - Checklists are written in our own words with a source link per section. **Never** make the agent
     fetch its rules at run time.
3. **Registry.** Add `` - `<name>` — <description> `` under the right domain heading in `registry.md`,
   with the sentence **character-for-character equal** to `description`.
4. **Sources.** Add the sources and their licences to the domain protocol's sources table.
5. **Check.** Run `scripts/check-reviewer-registry.sh` and confirm it passes.
6. **New session.** Claude Code loads agent definitions at session start, so a new reviewer is callable
   by name only after a restart.

## Removing or renaming

Change the agent file and the registry line in the same commit. The check fails if either is left behind.
