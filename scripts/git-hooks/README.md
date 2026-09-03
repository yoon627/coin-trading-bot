# Git hooks

## pre-push

`deploy.yml` 의 `paths-ignore` 가 워크플로 자신을 제외하는 변경만 막는다(#151). 그 외에는 아무것도
하지 않는다 — **codex 리뷰 게이트는 2026-09-03 에 제거**했다(경위는 wiki `prepush-codex-review`).
코드 리뷰는 구현 직후 리뷰 단계(code-reviewer + codex 병행, 크레딧 가용 시)가 담당한다.

### Setup

```bash
cp scripts/git-hooks/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push
```

실행본은 untracked `.git/hooks/pre-push`(설치본), 소스는 tracked `scripts/git-hooks/pre-push`.
**정본을 고치면 반드시 재설치한다** — 2026-07-28~09-03 사이 설치본이 정본보다 뒤처져 아래 가드가
로컬에서 한 번도 돌지 않은 채 5주가 지났다. `.git/hooks` 는 이 clone 의 모든 worktree 가 공유하므로
재설치는 전 worktree 에 즉시 적용된다.

### Requirements

`bash`, `git`, `awk`, `sed`, `grep` 뿐. codex·python3·perl 은 더 이상 필요 없다.

### Policy

| Event | Action |
|-------|--------|
| `deploy.yml` 의 `paths-ignore` 가 워크플로 자신을 제외 (`**`·`.github/**`·`**.yml` 등) | BLOCK |
| `paths-ignore` 가 inline 형식(`paths-ignore: [ ... ]`) | BLOCK (fail-closed — 파서가 못 읽는 형식을 통과시키지 않는다) |
| `deploy.yml` 없음 / `paths-ignore` 키 없음 | Allow |
| ref 삭제, 새 커밋 없는 재-push | Skip |
| 그 외 모든 push | Allow silently |

`paths-ignore` 자기제외를 여기서 막는 이유: 그 패턴이 들어가면 `deploy.yml` 을 바꾸는 push 가
워크플로 실행을 만들지 않아, 경로 목록을 강제하는 `DeployWorkflowPathListTest` 가 영영 돌지
않는다(#151). 검사가 자기 자신을 끄는 변경만 hook 이 막고, 목록 동기·배포 입력 같은 나머지
불변식은 그 테스트가 본다 — hook 에서 gradle 을 돌리지 않는 것은 JDK 환경에 따라 `./gradlew`
가 실패해 모든 push 를 막을 수 있기 때문이다.

새 브랜치 첫 push 는 tip 커밋의 `deploy.yml` 을 검사한다(이전 codex 게이트는 base 대비 새 커밋이
없으면 건너뛰었는데, 이제는 `remote_sha == local_sha` 일 때만 건너뛴다).

### Rollback (hook 오동작 시)

```bash
git show <good-rev>:scripts/git-hooks/pre-push > .git/hooks/pre-push   # 이전 정상본 복구
chmod +x .git/hooks/pre-push
```

codex 게이트가 있던 마지막 정본은 `ffd6ec9:scripts/git-hooks/pre-push`(2026-09-03 이전 main).
되돌리면 `codex`·`python3`·`perl` 요구와 `CODEX_SKIP`/`CODEX_ACK` 우회, `.git/codex-pre-push/` 로그가
함께 돌아온다.

### 제거된 codex 게이트의 잔존물 정리 (로컬, 선택)

```bash
rm -rf .git/codex-pre-push                    # 리뷰 로그(*.jsonl)·bypass.log
rm -rf "${TMPDIR:-/tmp}/codex-pre-push.lock"  # 직렬화 lock
```
