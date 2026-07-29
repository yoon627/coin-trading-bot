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
- `perl` on PATH (codex 호출 timeout wrapper — macOS 에 `gtimeout` 부재)
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

### codegraph MCP 비활성화 (#60)

codex 호출에 `-c mcp_servers.codegraph.enabled=false` 를 준다. 이유:

codex 는 리뷰마다 `codegraph serve --mcp` 를 새로 띄우는데, **Claude Code 세션도 각자 하나씩 띄운다**.
인스턴스가 여러 개면 `codegraph_explore` 가 응답하지 않아 리뷰가 통째로 타임아웃한다(#45 에서 실측한
근본원인이 "다중 세션 경합"이다). 아래 lock 은 **hook 끼리만** 직렬화하므로, 세션이 상시 띄워둔 서버와의
경합은 막지 못한다 — 세션을 2개 이상 열어두면 재현된다.

- codegraph 없이도 리뷰는 P0/P1 을 잡는다(실측: 같은 커밋 범위에서 P1 2건 포함 8건 검출).
- **전역 `~/.codex/config.toml` 은 건드리지 않는다** — 다른 프로젝트·대화형 codex 의 codegraph 는 유지된다.
- 근본 원인인 codegraph 다중 인스턴스 경합은 upstream 문제다. 거기서 고쳐지면 이 플래그를 되돌린다.

### Serialization & timeout

codex 동시 실행의 자원 경합을 막기 위해(원래는 codegraph 경합 방지 목적으로 도입 — #45):

- **직렬화**: codex review 를 머신 단위 lock(`$TMPDIR/codex-pre-push.lock`)으로 순차 실행 —
  다른 세션 review 중이면 대기(최대 `CODEX_LOCK_WAIT`). docs-only·`CODEX_SKIP` 은 lock 전 bypass(불필요 대기 없음).
- **timeout**: codex 호출에 escalation timeout(`TERM`→grace→`KILL`, process group). 초과 시 exit 124
  → BLOCK + `CODEX_SKIP` 안내. 무한 hang 을 유한화(자식이 `TERM` 을 무시해도 `KILL` 로 유한 리턴).

환경변수(기본값):

| var | default | 설명 |
|-----|---------|------|
| `CODEX_TIMEOUT` | 480 | codex 호출 상한(초) |
| `CODEX_KILL_GRACE` | 10 | `TERM` 후 `KILL` 까지(초) |
| `CODEX_LOCK_WAIT` | 600 | lock 획득 대기 상한(초) |
| `CODEX_STALE_AGE` | 600 | pid 없는 lock 을 stale 로 볼 age(초) |

### Rollback (hook 오동작 시)

실행본은 untracked `.git/hooks/pre-push`(설치본), 소스는 tracked `scripts/git-hooks/pre-push`.

```bash
git show <good-rev>:scripts/git-hooks/pre-push > .git/hooks/pre-push   # 이전 정상본 복구
chmod +x .git/hooks/pre-push
rm -rf "${TMPDIR:-/tmp}/codex-pre-push.lock"                            # 잔존 lock 정리
```

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
