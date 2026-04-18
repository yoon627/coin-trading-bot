# Git hooks

## pre-push

Gates `git push` via `codex exec review --base <base> --json` at high reasoning.
Parses the JSONL agent_message for `- [P0]`/`- [P1]` markers. Fail-closed on
codex errors or unparseable output. Docs-only pushes bypass.

### Setup

```bash
cp scripts/git-hooks/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push
```

Or set `core.hooksPath` to point here (applies to all hooks in this dir):

```bash
git config core.hooksPath scripts/git-hooks
```

### Requirements

- `codex` CLI on PATH (tested against 0.116.0)
- `python3` on PATH (JSONL parsing)
- `~/.codex/config.toml` configured (model + trust_level for this repo)

### Policy

| Event | Action |
|-------|--------|
| `codex` finds any `- [P0]` or `- [P1]` | BLOCK push — must fix before pushing |
| `codex` finds only `- [P2]`/`- [P3]` | BLOCK push until user re-runs with `CODEX_ACK=1 git push` (explicit review + accept) |
| `codex` finds nothing | Allow silently |
| `codex` exits non-zero, missing, or output unparseable | BLOCK (fail-closed) |
| Diff touches only docs (`*.md`, `docs/`, `.claude/tasks|memory/`, etc.) | Bypass codex |

### Acknowledging P2/P3 findings

```bash
git push                    # shows P2/P3 findings, blocks
# review the findings, decide they're acceptable
CODEX_ACK=1 git push        # passes P2/P3 gate; still blocks on P0/P1 and on codex errors
```

`CODEX_ACK=1` only relaxes the P2/P3 gate. P0/P1 findings and codex infrastructure failures still block unconditionally.

### Emergency bypass

```bash
CODEX_SKIP=1 git push
```

Leaves an audit line in `.git/codex-pre-push/bypass.log`. Avoid in normal flow.
The policy explicitly forbids `--no-verify` — use `CODEX_SKIP` instead so the
bypass is visible.

### Known limitations (open work)

| Area | Limitation | Workaround |
|------|------------|------------|
| Non-`origin` remotes | New-branch base resolution reads `refs/remotes/origin/HEAD` only. Pushes to other remotes may review a wider range than intended. | Push via `origin`; or set `CODEX_SKIP=1` for that push. |
| New branch cut from non-default branch | Base may fall back to `local_sha^`, reviewing only the tip commit. | Manually run `/codex-review` before pushing branches cut from long-lived non-`main` branches. |
| Multiple refs per push (`--all`, multiple `refspec`s) | Each ref reviewed sequentially at high reasoning — slow (1-2 min per ref). | Push refs individually. |
| Architectural/policy rules | Codex diff review catches in-code smells but cannot enforce policy not visible in a diff (e.g., "JWT secrets must be 256-bit", "auth endpoints must have Rate Limiting"). | Document those as code comments / tests; add dedicated lints if critical. |
| Log directory growth | `.git/codex-pre-push/*.jsonl` accumulates indefinitely. | Periodic `find .git/codex-pre-push -type f -mtime +30 -delete`. |

### Debugging a BLOCK

1. Find the log: `ls -lt .git/codex-pre-push/*.jsonl | head -1`
2. Inspect the findings printed to stderr, or re-extract: `python3 -c '...'` (see extract_agent_message in the hook).
3. If you disagree with codex's verdict, address the finding or use `CODEX_SKIP=1` and note the justification in the commit message / PR.
