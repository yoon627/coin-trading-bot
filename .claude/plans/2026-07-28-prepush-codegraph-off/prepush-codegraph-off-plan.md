---
title: prepush-codegraph-off — pre-push codex 리뷰에서 codegraph MCP 비활성화 (#60)
status: in_progress
started: 2026-07-28
updated: 2026-07-28
---

# Goal

`scripts/git-hooks/pre-push` 의 codex 호출에서 codegraph MCP 를 끄고, 그 결과 push 게이트가 codegraph 다중 인스턴스 경합에 걸려 480초 타임아웃하는 문제를 없앤다. `#45` 가 넣은 lock·escalation timeout 은 그대로 둔다. Closes #60.

# Progress

- 2026-07-28: worktree 생성(.env 2개 복사). Explore — codex 호출이 **두 군데**(HEAD 일치 경로 `:247-252`, 임시 worktree 경로 `:261-266`)이고 둘 다 `-c model_reasoning_effort=high` 사용. wiki 역참조로 `wiki/pages/decision/prepush-codex-review.md` 가 이 hook 을 sources 로 가짐을 확인(갱신 대상). **경합 조건이 현재 성립**(codegraph serve 4쌍 가동) — 실측 검증 환경이 갖춰짐.

- 2026-07-28: 구현 완료. 두 호출을 `codex_cfg` 변수로 통일하고 `-c mcp_servers.codegraph.enabled=false` 추가, lock/timeout 로직은 불변(diff 12+/5-). 타임아웃 메시지에서 "likely codegraph MCP hang" 오진 유도 문구 교체. `bash -n` 통과. `.git/hooks/pre-push` 재설치 후 정본과 `diff` 일치 확인. 문서 2곳(`scripts/git-hooks/README.md`, wiki `prepush-codex-review`) 갱신, wiki 검증 3종 통과. **규모 small 재판정 + plan-review 생략** — 수정안이 이슈 #60 에 명시된 상태로 사용자가 그것을 지정해 승인했고 변경이 3~5줄이라, 동일 관점은 구현 후 code-review 로 커버한다(§5·§9 생략 사유 기록).

- 2026-07-28: **실측 검증 통과.** codegraph serve **6개 가동**(경합 조건 성립) 상태에서 `CODEX_SKIP` 없이 push → **3분 47초에 정상 완료**(상한 480초). 수정 전 동일 조건에서 2회 연속 타임아웃한 것과 대비된다. hook 로그: `running codex exec review` → `codex found no blocking issues` — **bypass 가 아니라 리뷰가 실제로 돌아 통과**했다. 리뷰 JSONL 파싱 결과 `item.type == mcp_tool_call` **0건**(grep 이 잡은 문자열은 diff 안의 브랜치명·문서 내용). 이 push 가 code-review 를 겸했다(P0/P1 0).

# Next

PR 생성(`Closes #60`) → 머지 → worktree 정리.

# Decisions

- **끄는 위치는 이 repo 의 hook** (전역 `~/.codex/config.toml` 이나 `~/.claude` 가 아니라). 이유: ① 고장난 것이 이 repo 의 push 게이트다 ② tracked 라 다른 머신·다른 사람에게 **전파**된다(개인 설정은 전파 안 됨) ③ 범위가 정확히 일치 — 전역에서 끄면 다른 프로젝트의 대화형 codex·Claude Code 세션까지 codegraph 를 잃는다.
- **두 호출을 공통 변수로 묶는다**: 같은 플래그를 두 곳에 복붙하면 한쪽만 고치는 드리프트가 난다. `sandbox_flag` 와 동일한 unquoted 확장 패턴을 따른다(값에 공백이 없어 word splitting 안전).
- **on/off 환경변수는 두지 않는다**: upstream 이 고쳐지면 코드에서 되돌린다. 되돌릴 근거·시점을 주석과 이슈 번호로 남기는 편이 미사용 플래그를 남기는 것보다 낫다(YAGNI).
- **lock 은 유지**: `#45` 의 lock 은 주석상 "codegraph 동시 경합 방지"가 목적이라 codegraph 를 끄면 명분이 약해지지만, codex 동시 실행 자체의 자원 경합 방어는 남는다. 게이트에서 방어를 걷어내는 변경은 이 작업 범위 밖.
- **타임아웃 메시지 갱신**: "likely codegraph MCP hang" 은 이제 오진을 유도한다 → 원인 후보를 다시 쓴다.
- **근본 원인은 upstream**: codegraph 0.9.9 의 다중 인스턴스 경합 자체는 이 repo 에서 못 고친다. 이 변경은 우리 push 경로의 **회피**임을 문서에 명시한다.

# Key Files

- `scripts/git-hooks/pre-push` — tracked 정본. `:247-252`(HEAD 일치), `:261-266`(임시 worktree), `:244`(lock 주석), `:272`(타임아웃 메시지)
- `.git/hooks/pre-push` — 설치본(untracked). 수정 후 재설치 필요
- `scripts/git-hooks/README.md` — 설치 절차·동작 문서
- `wiki/pages/decision/prepush-codex-review.md` — 이 hook 을 sources 로 가짐(repo CLAUDE.md 갱신 의무)

# Acceptance

- [x] 두 codex 호출 **모두** codegraph 비활성화 플래그를 받는다 (한쪽만 고치면 임시 worktree 경로에서 hang 재발)
- [x] lock·escalation timeout·P0/P1 파싱·`CODEX_ACK`·`CODEX_SKIP`·docs-only bypass **로직 불변** (diff 로 확인)
- [x] `bash -n scripts/git-hooks/pre-push` 문법 통과
- [x] **실측**: 경합 조건(codegraph serve 2+ 인스턴스 가동) 하에서 이 브랜치 push 가 타임아웃 없이 리뷰를 완료한다 — 수정 전 2회 실패한 것과 동일 조건. 로그에서 `mcp_tool_call` 부재 확인
- [x] 재설치된 `.git/hooks/pre-push` 가 tracked 정본과 동일 (`diff` 로 확인)
- [x] 문서 동기화: `scripts/git-hooks/README.md` + `wiki/pages/decision/prepush-codex-review.md`, wiki 검증 3종 통과
- [x] 이슈 #60 이 PR 로 닫히도록 `Closes #60` 연결

# Blockers

(없음)
