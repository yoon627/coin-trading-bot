---
title: prepush-codex-hardening — pre-push codex hang 재발방지 (flock 직렬화 + timeout)
status: done
started: 2026-07-18
updated: 2026-09-03
---

# Goal

pre-push codex review 가 codegraph MCP(`codegraph_explore`)에서 다중 세션 경합 시 무한 hang → push 무한 대기하는 문제 재발방지. codegraph 는 살리고(경합만 제거):
1. **직렬화**: 여러 세션의 codex review 를 머신 단위 lock 으로 순차 실행 → codegraph 동시 경합 제거.
2. **timeout**: codex 호출에 상한 → hang 시 무한 대신 유한(BLOCK + CODEX_SKIP 안내).

# Progress

- 2026-07-18: 근본원인 규명(별건) — codex review 가 codegraph_explore MCP tool 에서 hang, codex 단독 exec 는 정상(동시성 무해 검증). Explore: scripts/git-hooks/pre-push 정독 — codex 호출 2지점(:167-173 정상, :180-185 detached-worktree), CODEX_SKIP bypass(:23-27)·P0/P1·P2/P3·fail-closed 구조 파악.
- 2026-07-18: plan-review(Claude, 실측) **CONDITIONAL** — 3 강한우려 반영: ① TERM 단발 kill 은 TERM-무시 자식에서 waitpid 무한(원래 hang 재현+trap 미발화 lock 영구점유) → **TERM→grace→KILL escalation** ② `trap EXIT` 무조건 rmdir → 남의 lock stomp → **소유(pid) 검증 후 제거** ③ stale: 제거 후 mkdir 재시도 복귀, pid없음은 **dir age 임계 초과 시만** stale. +setsid 손자 pgrp kill 탈출(실제 codex 관찰), rollback=`git show <rev>:...>.git/hooks/pre-push`.
- 2026-07-18(구현·검증): hook 에 lock/timeout 함수 3개(acquire_codex_lock·release_codex_lock·run_codex_timeout) + codex 호출 2지점 대체(escalation·소유·age-stale 반영). bash -n OK, 함수 실측 9/0, 실제 codex escalation 유한 리턴 실증. README(환경변수·rollback) 갱신, .git/hooks 재설치(identical). code-review 메인 직접(plan-review 강검토+실측으로 subagent 생략). → 커밋·CODEX_SKIP push·PR.

# Next

- (종료) lock 직렬화·escalation timeout 은 정본에 랜딩됐고 pre-push codex 게이트 자체가 2026-09-03 제거됐다 — 후속 없음.

draft plan → plan-reviewer(Claude only — 이 작업 자체가 codex hang 수정이라 codex 병행 생략) → 구현 → hook 실행 검증 → PR + .git/hooks 재설치.

# Decisions

- **직렬화 = mkdir-lock**(shell 내장, macOS flock 명령 부재): `${TMPDIR:-/tmp}/codex-pre-push.lock` atomic mkdir → 성공 직후 pid 파일 기록(acquire=mkdir 성공으로만 정의). **stale 판정(plan-review ③)**: ⓐ pid 있고 `kill -0` 실패 → stale, ⓑ pid 없으면 dir age > `CODEX_STALE_AGE`(기본 600s=timeout+grace+margin, 갓 생성 live lock evict 방지) 일 때만 stale. stale 이면 `rm -rf` 후 **continue(mkdir 재시도 — 제거≠획득)**. 획득 대기 상한 `CODEX_LOCK_WAIT`(escalation 으로 최대 점유 상한되므로 **600s** 로 단축), 30s 마다 "held by pid X for Ns" 출력. 초과 시 fail(BLOCK, 재시도/CODEX_SKIP). **release 는 소유 검증(plan-review ②)**: lock pid == `$$` 일 때만 `rm -rf`. `trap release EXIT`.
- **timeout = perl wrapper**(macOS gtimeout 부재): `fork`+`setpgrp(0,0)` 자식에서 exec, `alarm(CODEX_TIMEOUT)`(기본 480s). **escalation(plan-review ①)**: 1차 ALRM=`kill('TERM',-$pid)`+`alarm(grace 10s)`, 2차 ALRM=`kill('KILL',-$pid)`. codex($pid) KILL 은 무시 못 하므로 `waitpid` 가 유한 리턴(hook hang 방지 — 핵심). timeout 시 exit 124 → 기존 review_rc!=0 fail 경로 + "timeout, CODEX_SKIP 안내". ⚠️ codex 가 codegraph MCP 를 setsid 로 띄우면 손자는 pgrp kill 탈출(orphan) — waitpid 는 codex 만 기다리므로 hook 은 안 막히나, 손자 잔존은 Acceptance 에서 실제 codex 로 관찰.
- **CODEX_SKIP 순서 보존**: lock acquire 는 `is_docs_only`/`ZERO_SHA`/`base==local_sha` early-return **이후, codex exec 직전**에 배치(docs-only·삭제 push 불필요 직렬화 방지). CODEX_SKIP(:23-27)·CODEX_ACK·sandbox·P0/P1·P2/P3·detached-worktree 분기 불변.
- **rollback**: 실행본은 untracked `.git/hooks/pre-push`(`core.hooksPath=.git/hooks` 절대·전 worktree 공유). 롤백 = `git show <good-rev>:scripts/git-hooks/pre-push > .git/hooks/pre-push` + 잔존 lock `rm -rf ${TMPDIR:-/tmp}/codex-pre-push.lock`.
- **codegraph 비활성 안 함**: 사용자 결정 — 경합만 제거해 codegraph review 품질 유지.
- **적용 범위**: codex 호출 2지점 모두 lock+timeout. lock 은 codex 실행 구간만(파싱·판정은 밖).
- **codex 병행 리뷰 생략**: plan-reviewer/code-reviewer 는 Claude subagent 만 — 이 작업이 codex hang 을 고치는 것이라 codex 리뷰가 같은 hang 에 걸림(메타). push 도 CODEX_SKIP(옛 hook 은 아직 hang).

# Key Files

- `scripts/git-hooks/pre-push` — tracked hook 소스. codex 호출 :167-173, :180-185. 여기에 lock+timeout 함수 추가·호출 대체.
- `.git/hooks/pre-push` — 설치본(untracked). 소스 수정 후 재설치(복사) 필요.
- `scripts/git-hooks/README.md` — 설치 절차 문서(있음) — 갱신 대상 여부 확인.

# Acceptance

- [x] 직렬화: verify_hook.sh T5(2번째 lock 대기 후 획득 2s) + T7(소유검증 release, 남의 lock 유지) PASS
- [x] timeout 기본: T2 무한 sleep → 3s(=CODEX_TIMEOUT) 후 exit 124 PASS
- [x] **timeout escalation(핵심)**: T3 `trap "" TERM` → grace 후 KILL 유한(5s) 리턴 PASS + **실제 codex 관찰**: escalation wrapper(30s)로 실제 codex review → rc 124·정확히 30s 유한 리턴·codex 프로세스 잔존 없음(hook hang 방지 실증)
- [~] **손자 정리**: T4 same-pgrp 손자 kill PASS. 실제 codex 는 30s 내 codegraph_explore 도달 전 timeout(초기 git 명령 단계)이라 setsid 손자 여부 **미확정**(codegraph net 0). ⚠️ 단 waitpid 는 codex 만 기다리므로 손자 orphan 이어도 **hook hang 무관** — 핵심 목표(무한 hang 방지) 달성. setsid orphan 정리는 별건(리소스 누수, 무해).
- [x] stale lock: T6 죽은 pid lock 제거 후 획득 PASS (+age-stale 로직: pid 없으면 age>STALE_AGE 만 제거)
- [x] 정상 경로 보존: CODEX_SKIP(:23 최상단, lock 전)·docs-only/ZERO_SHA/no-new-commits(review_ref early-return, acquire 앞) 확인 — docs-only 직렬화 안 함
- [x] `bash -n` 문법 통과 + `.git/hooks/pre-push` 재설치(cp, identical)
- [x] rollback 문서화: scripts/git-hooks/README.md 에 직렬화·timeout·환경변수·rollback(`git show <rev>:...>.git/hooks/pre-push` + lock rm) 추가

# Blockers

(없음)
