# Hunk — reviewing agent-authored changesets

Terminal diff viewer built for reviewing code an agent wrote, while it writes it.
Installed via Homebrew (`brew install hunk`), currently **0.20.0**. Home: <https://hunk.dev>.

The point of Hunk over `git diff` is the **split of roles**:

| Role | Interface | Who drives |
|---|---|---|
| Reading the diff | the TUI (`hunk diff`, `hunk show`) | **you**, in your terminal |
| Annotating the diff | `hunk session *` CLI, via a local daemon | **the agent**, from its own shell |

The agent never opens the TUI. It talks to your live window through the daemon —
navigating your viewport, painting highlights on exact expressions, and leaving
inline notes that appear above the hunk they describe. That is what makes review
concurrent with the work rather than a post-mortem on it.

The bundled agent-side skill lives at `$(hunk skill path)` and is installed for
Claude Code at `~/.claude/skills/hunk-review/`.

---

## The four flows

### 1. Watch the agent as it works — the main event

```bash
hunk diff --watch --agent-notes
```

Run this in a side pane and leave it open. `--watch` reloads as the working tree
changes, so each edit the agent makes lands in your view as it happens, and the
agent annotates each change as it makes it. `--agent-notes` matters — notes are
**hidden by default**, which makes a working setup look broken (see Gotchas).

### 2. Review a finished commit

```bash
hunk show 3bfb99d               # a specific commit
hunk show HEAD~1
hunk show HEAD~1 -- README.md   # scoped to a path
```

Read-only, no working-tree pollution. Good for walking through work after the fact.

### 3. Review a whole branch as one changeset

```bash
hunk diff main...public-rate-limiting
hunk diff main...feature -- backend/src/main/java/com/easycrm/platform
```

The three-dot form is the branch's own work, excluding what `main` gained meanwhile.

### 4. Review what you're about to commit

```bash
hunk diff                       # working tree (includes untracked files)
hunk diff --staged              # staged only
hunk diff --exclude-untracked   # tracked changes only
hunk stash show                 # a stash entry
hunk patch some.patch           # a patch file, or stdin
```

---

## Launch options worth knowing

| Flag | Effect |
|---|---|
| `--watch` | auto-reload when the diff input changes |
| `--agent-notes` / `--no-agent-notes` | show or hide agent notes **by default** |
| `--mode auto\|split\|stack` | layout |
| `--sidebar` / `--no-sidebar` | files pane |
| `--wrap` / `--no-wrap` | wrap or truncate long lines |
| `--line-numbers` / `--no-line-numbers` | line numbers |
| `-x, --tab-width <1-16>` | tab stops (default 4) |
| `--theme <name>` | named theme override |
| `--experimental` | enables STML rich-markup notes |
| `--fast` | experimental faster syntax highlighting |

Git plumbing entry points: `hunk pager` (as a Git pager), `hunk difftool <left> <right>`.

Keys: `?` inside the TUI is the authoritative keymap. Per hunk.dev, `[` / `]` walk
hunks and `1` / `2` / `0` toggle layout.

---

## Agent-side commands (`hunk session *`)

These are what the agent runs. Useful for you too — `--next-comment` is the fastest
way to walk everything the agent flagged.

**Inspect**

```bash
hunk session list                                    # find live sessions
hunk session get --repo .                            # path / repo / source / focus
hunk session context --repo .                        # current focus
hunk session review --repo . --json                  # file + hunk structure
hunk session review --repo . --include-patch --json  # raw diff text (only when needed)
```

**Navigate** — steers your viewport

```bash
hunk session navigate --repo . --file <path> --hunk 2        # 1-based
hunk session navigate --repo . --file <path> --new-line 66
hunk session navigate --repo . --next-comment                # walk annotations
hunk session navigate --repo . --prev-comment
```

**Comment** — inline notes above the hunk

```bash
hunk session comment add --repo . --file <path> --new-line 66 \
  --summary "..." --rationale "..." --author claude [--focus]

# many notes at once, validated as a batch before anything mutates:
hunk session comment apply --repo . --stdin < notes.json

hunk session comment list --repo . --type user   # human-authored notes
hunk session comment rm --repo . <comment-id>
hunk session comment clear --repo . --yes
```

`comment apply` payload:

```json
{"comments":[
  {"filePath":"backend/.../RateLimitFilter.java","newLine":66,
   "summary":"one sentence","rationale":"why it matters","author":"claude"}
]}
```

Each item needs `filePath`, `summary`, and exactly one target
(`newLine`, `oldLine`, `hunk`, or `hunkNumber`).

**Highlight** — paint a character range on one line

```bash
hunk session highlight add --repo . --file <path> --new-line 66 \
  --start 64 --end 108 --tone warning --focus
hunk session highlight clear --repo .
```

Tones: `match` (default), `info`, `warning`, `error`, `current`.

**Reload** — swap the session's contents without relaunching

```bash
hunk session reload --repo . -- diff
hunk session reload --repo . -- diff main...feature -- backend/
hunk session reload --repo . -- show HEAD~1
```

---

## Gotchas

- **Agent notes are hidden by default.** `hunk session get` reports
  `Agent notes visible: no`. Notes apply successfully and stay invisible — looks
  like a broken integration. Launch with `--agent-notes`, or toggle in the TUI.
- **`--` before the nested command** in `session reload`, always.
- **Highlight offsets are `[start, end)` in UTF-16 code units**, 0-based into the
  line's text. End is exclusive. Compute them, don't count by hand.
- **`hunk diff` includes untracked files.** Use `--exclude-untracked` for tracked only.
- **"No active Hunk sessions"** while Hunk is visibly running usually means the
  agent's sandbox is blocking localhost, not that the session died.
- **Multiple sessions on one repo** — pass the session ID instead of `--repo`.
- The agent should read `session review --json` for structure first, and only add
  `--include-patch` for files it genuinely needs in raw form; the patch text is
  large and inflates its context for no gain.

---

## Worked example

The demo run on commit `3bfb99d` (the rate-limit fix), start to finish:

```bash
# you, in a terminal:
hunk show 3bfb99d --agent-notes

# the agent, from its own shell:
hunk session list                       # -> 363a7ce9, 11 files
hunk session get --repo .               # structure, no raw patch pulled
hunk session comment apply --repo . --stdin < notes.json   # 5 notes
hunk session highlight add --repo . \
  --file backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitFilter.java \
  --new-line 66 --start 64 --end 108 --tone warning --focus
```

Result: viewport lands on the `getPathWithinApplication` call with the expression
painted orange, and five notes sit above the hunks they explain.
