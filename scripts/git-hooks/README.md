# Git hooks

## pre-push

Gates `git push` via `codex exec review --base <remote_sha>` at high reasoning.
Fail-closed on codex errors or missing verdict. Docs-only pushes bypass.

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

- `codex` CLI on PATH
- `~/.codex/config.toml` configured (model + trust_level for this repo)

### Known limitations

See `docs/git-hooks-review-notes.md` (if present) for open issues and
scheduled improvements.
