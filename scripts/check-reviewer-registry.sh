#!/usr/bin/env bash
# Fails when docs/reviewers/registry.md and the specialist reviewer agents in .claude/agents/ disagree.
#
# Every agent file matching *-review-*.md must:
#   - declare `name:` equal to its filename stem,
#   - declare a one-line `description:`,
#   - appear in the registry as "- `name` — description", with the sentence identical,
# and every registry entry must have an agent file. The registry is imported into CLAUDE.md, so a
# stale line there sends agents to a reviewer that does not exist, or hides one that does.
#
# Written for bash 3.2 (macOS default) as well as CI's bash: no associative arrays, no mapfile.
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
AGENTS_DIR="$ROOT/.claude/agents"
REGISTRY="$ROOT/docs/reviewers/registry.md"

fail=0
err() { echo "reviewer-registry: $*" >&2; fail=1; }

[ -f "$REGISTRY" ] || { err "missing $REGISTRY"; exit 1; }
[ -d "$AGENTS_DIR" ] || { err "missing $AGENTS_DIR"; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# "name<TAB>description" from the agent files.
: > "$tmp/agents"
for f in "$AGENTS_DIR"/*-review-*.md; do
  [ -e "$f" ] || continue
  stem="$(basename "$f" .md)"
  # Only the frontmatter block: between the first two '---' lines.
  front="$(awk 'NR==1 && $0=="---" {in_fm=1; next} in_fm && $0=="---" {exit} in_fm {print}' "$f")"
  name="$(printf '%s\n' "$front" | sed -n 's/^name:[[:space:]]*//p' | head -n1)"
  desc="$(printf '%s\n' "$front" | sed -n 's/^description:[[:space:]]*//p' | head -n1)"
  if [ -z "$name" ]; then err "$stem.md has no name: in its frontmatter"; continue; fi
  if [ "$name" != "$stem" ]; then err "$stem.md declares name '$name'; it must equal the filename"; fi
  if [ -z "$desc" ]; then err "$stem.md has no description: in its frontmatter"; continue; fi
  printf '%s\t%s\n' "$stem" "$desc" >> "$tmp/agents"
done

# "name<TAB>description" from the registry lines: - `name` — description
: > "$tmp/registry"
grep -E '^- `[a-z0-9-]+-review-[a-z0-9-]+` — ' "$REGISTRY" \
  | sed -E 's/^- `([a-z0-9-]+)` — (.*)$/\1\t\2/' > "$tmp/registry" || true

# A registry line that names a reviewer but is not in the exact format would be silently skipped
# above, so catch it explicitly.
if grep -nE '`[a-z0-9-]+-review-[a-z0-9-]+`' "$REGISTRY" | grep -vE ':- `[a-z0-9-]+-review-[a-z0-9-]+` — ' > "$tmp/malformed"; then
  while IFS= read -r line; do err "registry line not in the form '- \`name\` — sentence': $line"; done < "$tmp/malformed"
fi

cut -f1 "$tmp/agents" | sort > "$tmp/agent_names"
cut -f1 "$tmp/registry" | sort > "$tmp/registry_names"

dups="$(uniq -d "$tmp/registry_names")"
[ -z "$dups" ] || err "registry lists these more than once: $dups"

while IFS= read -r n; do
  [ -n "$n" ] && err "agent .claude/agents/$n.md is not in docs/reviewers/registry.md"
done < <(comm -23 "$tmp/agent_names" "$tmp/registry_names" | sort -u)

while IFS= read -r n; do
  [ -n "$n" ] && err "registry entry '$n' has no .claude/agents/$n.md"
done < <(comm -13 "$tmp/agent_names" "$tmp/registry_names" | sort -u)

# Sentences must match exactly for reviewers present in both.
while IFS="$(printf '\t')" read -r n agent_desc; do
  reg_desc="$(awk -F '\t' -v n="$n" '$1==n {print $2; exit}' "$tmp/registry")"
  if [ -n "$reg_desc" ] && [ "$reg_desc" != "$agent_desc" ]; then
    err "'$n' description differs from its registry sentence
    agent:    $agent_desc
    registry: $reg_desc"
  fi
done < "$tmp/agents"

count="$(wc -l < "$tmp/agent_names" | tr -d ' ')"
if [ "$count" -eq 0 ]; then
  # Non-vacuity: a glob or path mistake that finds no agents would otherwise pass trivially.
  err "found zero *-review-*.md agents under $AGENTS_DIR -- the check is measuring nothing"
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "reviewer-registry: OK ($count reviewers, registry and agents agree)"
