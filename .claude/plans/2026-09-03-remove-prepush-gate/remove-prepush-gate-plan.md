---
title: remove-prepush-gate — pre-push codex 리뷰 게이트 제거, codex 리뷰는 dlc code-reviewer 병행으로 일원화
status: done
started: 2026-09-03
updated: 2026-09-03
---

# Goal

push 마다 `codex exec review`(high) 를 돌려 P0~P3 로 차단하던 pre-push 게이트를 없앤다. codex 리뷰는 글로벌 §9 대로 구현 직후 code-reviewer 병행(크레딧 있으면)에서 받는다. pre-push 에는 codex 와 무관한 `deploy.yml` paths-ignore 자기제외 가드(#151)만 남긴다.

# Progress

- 2026-09-03: 사용자 결정 — "pre-push 에서 codex 리뷰를 받는 게 아니라 reviewer 에서 크레딧 있으면 codex 리뷰도 추가로". 근거: code-reviewer 의 codex 병행을 막던 agent hook 오탐이 PR #165 에서 고쳐졌고, 크레딧 소진이면 fail-closed 게이트가 모든 push 를 막는다(#165 push 2회 `CODEX_SKIP=1` 우회).
- 2026-09-03: plan-reviewer(Claude 단독) CONDITIONAL — 설치본 `.git/hooks/pre-push`(338줄, 07-28)에 `guard_workflow_self_exclusion` 이 정의·호출 모두 없음을 실측(정본 381줄과 2 hunk 차이) → 남기려는 가드가 5주간 로컬에서 미작동. PR #165 미머지. wiki orphan/outbound 제약. 전부 아래 Decisions 로 반영.
- 2026-09-03: PR #166. code-reviewer APPROVE → herestring 수정·README 한계 서술·옛 plan Next 교체 반영.
- 2026-09-03: 슬림 hook 12케이스 통과, README 재작성, CLAUDE/AGENTS 4행, wiki 5페이지+index+smoke+log, 옛 plan 2건(prepush-codex-hardening·prepush-codegraph-off) done 종료. 설치본 재설치 후 이 브랜치 push 가 codex 없이 통과.

# Next

- PR #166 머지 순서: **#165 먼저, 그 다음 #166**(아래 Decisions). 머지 후 종료.

# Decisions

- **pre-push 는 삭제가 아니라 슬림화**: `guard_workflow_self_exclusion`(deploy.yml 의 paths-ignore 가 워크플로 자신을 제외하면 push 차단, #151)만 유지. codex 호출·python3/perl 요구·lock·escalation timeout·docs-only 필터·`CODEX_SKIP`/`CODEX_ACK`/`CODEX_TIMEOUT`/`CODEX_KILL_GRACE`/`CODEX_LOCK_WAIT`/`CODEX_STALE_AGE`/`CODEX_ALLOW_SANDBOX_BYPASS`·`.git/codex-pre-push/` 로그·새 브랜치 base 해석은 제거. 메시지 접두 `[pre-push]`(그 문자열을 파싱하는 스크립트 없음 — 리뷰어 grep).
- **동작 변경 1건(의도)**: 구 hook 은 base 대비 새 커밋이 없으면 가드도 건너뛰었다. 슬림본은 `remote_sha == local_sha` 일 때만 건너뛰고 새 브랜치 첫 push 는 tip 을 검사한다. 현 main 의 paths-ignore(`**.md`·`.claude/**`·`wiki/**`·`docs/**`)는 가드에 안 걸리므로 재설치가 즉시 push 를 막지 않는다.
- **설치본 재설치는 tracked 정본 → `.git/hooks/pre-push` 복사 유지**(`core.hooksPath` 를 tracked 디렉토리로 돌리면 worktree 마다 체크아웃된 브랜치의 hook 이 돌아 옛 브랜치에서 구 codex 게이트가 되살아난다). 드리프트 재발 방지는 README 에 "정본 고치면 재설치" 경고로만 — Deferred 참조. `.git/hooks` 는 worktree 4개 공유라 재설치 즉시 전 worktree 의 codex 게이트가 꺼진다(이 세션에서 실행함).
- **머지 순서 #165 → 이 PR**: #165 가 미머지인 채 이 PR 만 머지되면 main 에서 push 게이트도 없고 code-reviewer 의 codex 도 agent hook 오탐에 막히는 구간이 생긴다. 로컬 설치본은 이미 슬림본이라 그 구간에서도 로컬 push 는 codex 를 안 돈다 — 크레딧 소진 상태라 어차피 codex 리뷰가 0 인 점을 사용자가 인지하고 결정했다.
- **wiki**: `prepush-codex-review` 는 stem 유지·`claim_state: current` 로 범위 재정의(현재 가드 + 제거 경위). inbound 2개 유지(`jdk-gradle-toolchain`·`worktree-workflow` — 문장은 고치되 링크는 남김), `plan-git-tracking` 의 "pre-commit 이 plans 를 스캔" 주장은 이 clone 에 pre-commit 이 없어 철회(링크 제거, outbound 3 남음). 브랜치명은 wiki 본문에 쓰지 않는다(smoke 음성검사).
- **AGENTS.md 는 CLAUDE.md 사본이 아니다**(Codex 용 변형) — 4행을 역할 중립 문구로 각각 작성.
- **롤백**: 정본 복구 `git show ffd6ec9:scripts/git-hooks/pre-push > .git/hooks/pre-push && chmod +x .git/hooks/pre-push`(codex·python3·perl·`CODEX_*` 우회가 함께 돌아옴). 데이터 유실 없음(로그·lock 은 재생성 가능). README Rollback 절에 동일 내용.
- 잔존물(`.git/codex-pre-push/*.jsonl`·`bypass.log`·`$TMPDIR/codex-pre-push.lock`)은 지우지 않았다 — README "잔존물 정리" 절의 명령으로 사용자가 선택 삭제.

# Key Files

- `scripts/git-hooks/pre-push` — 슬림 정본(가드만). `scripts/git-hooks/README.md` — 재작성.
- `CLAUDE.md`·`AGENTS.md` 4행.
- `wiki/pages/decision/prepush-codex-review.md`(재정의), `jdk-gradle-toolchain.md:50`, `worktree-workflow.md:27`, `plan-git-tracking.md:28`, `index.md`, `smoke.sh:43`, `log.md`.
- `.claude/plans/2026-07-18-prepush-codex-hardening`, `2026-07-28-prepush-codegraph-off` — done 종료.

# Blockers

# Acceptance

- ✅ A1 슬림 hook 을 임시 repo 에서 stdin 으로 12케이스 실행: 정상 paths-ignore 0 / `.github/**` 1 / inline 형식 1(fail-closed) / 키 없음 0 / deploy.yml 없음 0 / `"**"` 1 / `'**/*.yml'` 1 / 삭제 ref 0 / 빈 stdin 0 / 새 커밋 없음(불량 tip) 0 / 다중 ref good+bad 1 / 실패 뒤 다음 ref 계속 처리. 전부 통과.
- ✅ A2 `bash -n` + `shellcheck` 통과.
- ✅ A3 `git grep` — `codex-pre-push`·`CODEX_*`·`--no-verify` 는 historical 기록(옛 plan Progress·`.claude/tasks`·wiki lesson·log)과 README 의 Rollback/잔존물 절에만 남음.
- ✅ A4 `wiki/smoke.sh` 10/10, `wiki/verify.sh` 36 pages clean, 링크 검사 clean.
- ✅ A5 설치본 재설치 후 이 브랜치 push 가 codex 호출 없이 통과(push 로그에 `[pre-push]` 만).

# Review Disposition

- code-reviewer(Claude 단독, codex 크레딧 소진으로 미가용): APPROVE — Major 0, Minor 4, Nit 1. **fix** inline 검사의 `printf | grep -q` 를 herestring 으로(pipefail+SIGPIPE 로 64KB 초과 deploy.yml 에서 fail-open — 1.8MB 재현 후 수정, 재검증 rc=1). **fix(문서)** README 의 "fail-closed" 를 inline 한 형태로 한정하고 파서 한계(주석·빈 줄 뒤 항목 미인식, 리터럴 표지라 `.git*/**` 미탐지·`.github/ISSUE_TEMPLATE/**` 과차단)와 Rollback 의 "가드 오탐엔 롤백이 답 아님" 추가. **fix** 옛 plan 2건 `# Next` 옛 줄 삭제. **defer** 블록 파서가 주석·빈 줄을 건너뛰게 고치는 것(pre-existing, 동작 확장 — `# Deferred`).

- plan-reviewer 강한 우려 4: 설치본 드리프트(**fix** — Progress 기록 + README 경고 + 재설치 전 A1), PR #165 미머지(**fix** — 머지 순서 결정), wiki 링크 제약(**fix** — 링크 재배치), historical vs 현재 가드 모순(**fix** — current 로 범위 재정의). 약한 우려: 브랜치명 음성검사(fix), 옛 plan 2건(fix), A3 토큰(fix), AGENTS.md 변형(fix), 잔존물(README 명령), plan-git-tracking 주장(fix — 철회), `updated:` 갱신(fix), 누락 시나리오(fix — A1 12케이스). rollback 절(fix).

# Deferred

- pre-push 가드 블록 파서: `paths-ignore:` 다음 줄이 주석·빈 줄이면 뒤 항목을 못 보고 통과(pre-existing, 낮음 — PR 경로에선 `DeployWorkflowPathListTest` 가 최종 불변식). 고치려면 awk 에 `f&&/^ *(#|$)/{next}`.

- 설치본 드리프트 자동 방지 없음 — 정본 변경 시 재설치를 사람이 기억해야 한다(중). 후보: `core.hooksPath` 를 tracked 디렉토리로 두되 옛 브랜치 worktree 의 hook 이 도는 부작용을 받아들이거나, CI 에서 설치본 해시 비교는 불가(로컬 파일)하므로 세션 시작 hook 에서 비교.
- 이 repo 의 `.git/hooks/pre-push` 가 `~/.claude` 의 `install-hooks` 래퍼(main 직접 push 차단 + settings.json secret 스캔)를 점유·대체하고 있어 그 가드가 이 repo 엔 없다(⚠️ 설치 이력 미확인, `.git/hooks` 에 pre-commit 부재로 추론 — 중).
- docs-only 필터가 강제하던 "CI 설정·hook·`.claude/settings.json` 변경은 언제나 리뷰" 가 사라졌다 — code-reviewer 호출 조건이 이를 덮는지 dlc 에서 확인(낮음).
