---
title: deploy-paths-ignore — 문서 전용 push 가 운영 봇을 재시작시키지 않게 한다
status: done
started: 2026-08-26
updated: 2026-08-31
---

# Goal

`.github/workflows/deploy.yml` 의 `push` 트리거에 `paths-ignore` 를 넣어, 문서·wiki·plan 만 바뀐
커밋이 운영 배포(=컨테이너 재생성=트레이딩 엔진 재시작)를 일으키지 않게 한다.

# Progress

- 2026-08-26 실제 사고로 발견. `#132` 작업 마무리 중 plan 커밋 `bcef6ec` 이 배포를 트리거해
  운영 로그에 `Container app-app-1 Recreated` 가 찍혔다 — **문서 커밋이 실거래 봇을 재시작시켰다.**
- 2026-08-26 Explore 완료. 아래 4가지를 코드·문서로 확인.
- 2026-08-26 code-reviewer + codex → **REQUEST CHANGES**. 내가 놓친 Critical(자가치유 파괴)을 반영해
  stale 가드를 코드 diff 기준으로 함께 전환. wiki·README 동기화 추가.
- 2026-08-31 PR #149 머지(squash `e8ed9d4`) → 배포 **실제 실행** 확인(`App healthy!`).
  worktree·로컬(`6540f61`)·원격 브랜치 정리, 로컬 main 동기화.
- 2026-08-31 **필터 실동작 관찰 완료** — `253fe58`(plan 전용 push)이 워크플로를 만들지 않았다.
  이것으로 마지막 Acceptance 항목이 충족돼 **done**.

## 확인한 것

| 항목 | 결과 |
|---|---|
| 워크플로 개수 | `deploy.yml` **하나뿐**. (글로벌 CLAUDE.md 가 언급한 `lint.yml` 은 이 repo 에 없다 — 그 서술이 stale) |
| 브랜치 보호 | **없음**(`branches/main/protection` 404, rulesets `[]`) → `test` 는 required check 이 아니다 |
| 제외 경로가 이미지에 들어가나 | **아니다.** Dockerfile 은 gradle 파일·`common/`·`bot/` 만 COPY. 앱 리소스에 `.md` 없음 |
| 빌드 경로 안의 `.md` | `bot/src/test/resources/backtest/README.md` 1개뿐. `BacktestFixtures.kt:10` 이 주석으로 언급만 하고 런타임에 읽지 않는다 |

## `paths-ignore` 의미 (✅확실 — GitHub 문서 직접 확인)

> When **all** the path names match patterns in `paths-ignore`, the workflow will not run.
> If **any** path names do not match patterns in `paths-ignore`, **even if some path names match**, the workflow will run.

→ 코드와 문서가 섞인 커밋은 정상 배포된다. 이 변경이 기대는 핵심 전제이고, 반대였다면 심각한 버그였다.

⚠️추정: `**.md` 가 하위 디렉토리를 매칭하는지는 치트시트 원문을 못 가져왔다. GitHub 자체 예시
(`'**.js'` → *"would run anytime you push a JavaScript file"*)가 그렇게 읽힌다. **틀려도 안전한 방향**이다
— 덜 걸러져서 배포가 더 도는 쪽이지 배포가 빠지는 쪽이 아니다.

# Next

**종료.** PR #149 머지·배포(`e8ed9d4`, `App healthy!`) → 필터 실동작 관찰까지 확인 완료.
Acceptance 전 항목이 증거로 충족됐다.

이 plan 이 닫혀도 남는 것은 `# Deferred` 2건(가드 목록 동기 CI 강제 / `wiki/verify.sh` 페이지 수
tripwire baseline 실패)이며, 둘 다 이 작업 범위 밖이다.

# Decisions

1. **`push` 트리거만 건드린다** (사용자 지시 2026-08-26).
   `pull_request` 는 그대로 둬서 문서 PR 도 테스트를 계속 돈다. 지금은 브랜치 보호가 없어 required
   check 함정이 없지만, 나중에 보호를 켜면 docs-only PR 이 "expected" 상태로 영영 못 머지되는 고전적
   함정이 생긴다. 미리 피한다.

2. **제외 목록 4개** — `**.md` · `.claude/**` · `wiki/**` · `docs/**`.
   전부 실행 이미지 밖이다. **`deploy/**` 는 넣지 않는다** — compose·배포 스크립트가 들어 있어
   배포에 직접 영향을 준다. `Dockerfile`·`.dockerignore`·`gradle/**` 도 같은 이유로 제외 목록 밖이다.

4. **stale 가드를 SHA 비교에서 코드 diff 비교로 바꾼다** (code-review C1 반영, 2026-08-26).

   `paths-ignore` 만 넣으면 **조용한 미배포**가 생긴다. 코드 push 의 빌드가 도는 ~20분 사이에 문서
   전용 push 가 들어오면 그 push 는 실행을 만들지 않으므로, stale 로 스킵된 코드를 배포해 줄 후속
   실행이 없다. 결론만 success 인 채 옛 이미지가 계속 돈다.

   **이건 가정이 아니라 오늘 실측된 경로다** — `d21a3eb`(#132 코드)의 배포는 stale 로 스킵됐고,
   7분 뒤 plan 커밋 `bcef6ec` 의 실행이 그 코드를 운영에 올렸다. 필터가 있었다면 `bcef6ec` 은
   실행을 만들지 않아 **#132 수정이 배포되지 않았을 것이다.**

   → main 이 앞서 있어도 그 차이가 전부 배포 무관 경로면 배포를 진행한다.
   가드의 제외 목록은 `on.push.paths-ignore` 와 **쌍으로 유지**하며, 이 불변식은 검증 스크립트가
   기계로 확인한다(한쪽만 넓히면 그 경로가 다시 조용한 미배포 구간이 된다).

5. **탈출구는 이미 있다** — `workflow_dispatch` 가 main 에서 배포까지 수행한다
   (`if: ... || (github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main')`).
   문서만 바꾼 뒤 그래도 배포해야 하면 수동 실행하면 된다. 새로 만들 필요 없다.

# Key Files

- `.github/workflows/deploy.yml` — `on.push.paths-ignore` (이 작업의 전부)

# Blockers

없음.

# Acceptance

- [x] **YAML 유효 / 트리거 3종 유지 / `pull_request` 무변경 / 위험 경로 미포함 / job 3개 유지**
      증거: 아래 단정 스크립트 PASS (2026-08-26).

      ```bash
      uv run --no-project --with pyyaml python -c "
      import yaml
      d = yaml.safe_load(open('.github/workflows/deploy.yml'))
      on = d.get(True) or d.get('on')
      assert set(on) == {'push','pull_request','workflow_dispatch'}
      assert on['push']['paths-ignore'] == ['**.md','.claude/**','wiki/**','docs/**']
      assert 'paths-ignore' not in on['pull_request'] and 'paths' not in on['pull_request']
      danger = ['deploy/','Dockerfile','.dockerignore','gradle/','docker-compose','.gradle.kts','.github/']
      assert not [p for p in on['push']['paths-ignore'] for x in danger if x in p]
      assert list(d['jobs']) == ['test','build-and-push','deploy-vultr']
      "
      ```

- [x] **가드 목록 ↔ `paths-ignore` 동기** — 두 목록이 어긋나면 조용한 미배포가 생기므로 기계로 묶었다.
      증거: 검증 스크립트가 가드 `run` 에서 `:(exclude)` 패턴을 파싱해 `paths-ignore` 와 동일 단정. PASS.
- [x] **가드 로직 시뮬레이션** — 실제 커밋으로 4케이스 검증. PASS.

      | GITHUB_SHA | origin/main | 판정 | 의미 |
      |---|---|---|---|
      | `d21a3eb` | `d21a3eb` | `true` | 동일 — 정상 배포 |
      | `d21a3eb` | `bcef6ec` | `true` | **오늘 사고 시나리오 — 이제 배포된다**(전에는 스킵) |
      | `63911f8` | `9350bd6` | `false` | 코드로 앞섬 — 스킵이 맞다 |
      | `63911f8` | `d21a3eb` | `false` | 〃 |

- [x] **문서 동기화** — wiki `deployment-stack.md`(+`verified`) · `README.md` 배포 흐름 절.
      증거: `check_links.py` clean · `smoke.sh` 10/10.
      ⚠️ `verify.sh` 는 페이지 수 tripwire 로 실패하나 **baseline 실패로 입증**(아래 `# Deferred`).
- [x] **저장소 무회귀** — `./gradlew build` SUCCESSFUL, 테스트 **760건 / 실패 0 / 에러 0**.
      (이 변경은 코드를 건드리지 않으므로 무회귀 확인용이다.)
- [x] **가드 변경이 정상 배포를 막지 않는다** — PR #149 머지(`e8ed9d4`)의 배포가 **실제로 실행**됐다
      (스킵 아님). 증거: run `33378753372` 로그
      `=== 대상 SHA=e8ed9d487b4f LAST_GOOD=9350bd6d9f51 자동롤백=rollback-ok ===` →
      `Container app-app-1 Recreated` → `App healthy! (e8ed9d48…)`.
      이 PR 은 `.yml` 변경이라 필터에 걸리지 않는 것도 함께 확인됐다(실행이 생성됐다).

- [x] **필터 실동작 — 관찰로 확인 완료 (2026-08-31).**
      `253fe58`(`.claude/**` 전용 push)이 원격 main 에 올라갔는데
      **그 커밋의 워크플로 실행이 생성되지 않았다** — `gh run list --branch main` 의 최신 실행은
      여전히 직전 코드 커밋 `e8ed9d4` 다. 변경 전이었다면 전체 빌드 + 운영 배포 + 봇 재시작을
      일으켰을 커밋이다. `**.md`·`wiki/**`·`docs/**` 는 같은 원리라 별도 관찰 없이 성립한다고 본다.

# Review Disposition

## code-reviewer + codex (2026-08-26) — REQUEST CHANGES → 전부 반영

| # | 지적 | 처분 | 조치 |
|---|---|---|---|
| **C1** | `paths-ignore` 가 stale 가드의 **자가치유를 끊어** 코드 push 가 green 인 채 미배포된다 | **fix** | **내가 놓친 Critical.** 아래 Decision 4 로 가드를 코드 diff 기준으로 전환. 증거가 이 세션 실행 이력에 있었다(`d21a3eb` skipped / `bcef6ec` success) — 내 변경이 있었다면 **#132 수정이 배포되지 않았을 것**이다. |
| **M1** | wiki `deployment-stack.md`·`README.md` 동기화 누락 | **fix** | 두 문서 모두 "머지 = 무조건 배포"라 서술하고 있었다. 조건부로 정정 + 자가치유 전제가 왜 깨질 뻔했는지 명시. `verified` 갱신. |
| m1 | plan `# Deferred` 의 인과가 반대 | **fix** | `# Deferred` 에 정정 기록. |
| m2 | `**.md` 의 미래 취약성(`bot/`·`common/` 에 `.md` 추가 시 조용한 스킵) | **fix** | 트리거 주석에 ⚠️ 한 줄 추가. |
| m3 | `'**.md'` 의 루트 파일 매칭 미확정 | **defer** | 치트시트 원문 확보 실패. **틀려도 안전한 방향**(덜 걸러져 배포가 더 돎)이라 blocker 아님. 머지 후 관찰로 확정. |
| Nit | 주석 "gradle 파일"이 `gradle/` 디렉토리와 `*.gradle.kts` 를 뭉갬 | **fix** | 분리 표기. |
| Nit | "커밋" → 정확히는 push 단위 diff | **fix** | 표현 정정. |
| Nit | 주석이 설정보다 김 | **wontfix** | ⚠️ 블록은 "지금 왜 이래야 하나"(미래 편집자가 `deploy/**` 를 넣으면 배포가 죽는다)라 코드에 남을 값이 있다. 운영 절차 서술은 README·wiki 로 옮겼다. |
| — | codex "조용한 미배포 경로 없음" | **false-positive** | 리뷰어가 실행 이력 3건으로 반증. codex 는 정적 판단만 했다. |

리뷰어가 refuted 로 정리한 7건(위험 경로 오포함·`**.md` 오삼킴·PR 함정·YAML 오류·대형 diff 등)은
내가 사전에 확인한 내용과 일치했다.

# Deferred

**GitHub Issues 로 이관했다 (2026-08-31)** — 이 plan 이 닫혀도 유실되지 않는다.

| 항목 | 이슈 |
|---|---|
| 가드 목록 ↔ `paths-ignore` 동기를 CI 로 강제 | **#151** |
| `wiki/verify.sh` 페이지 수 tripwire baseline 실패 | **#152** |

아래는 이관 당시의 원본 기록이다.

- **배포가 스킵돼도 job 은 `success` 로 보인다.** `d21a3eb` 실행이 stale 가드에 걸려 전 단계를
  건너뛰었는데 결론은 success 였다. 배포 확인은 job 결론이 아니라 `App healthy!` 로그로 해야 한다.
  심각도: 중.

  ⚠️ **초안의 인과가 반대였다** (code-review Minor 로 정정). "`paths-ignore` 가 이걸 줄여준다"고
  적었으나 사실은 **겹쳤을 때 구제해 주던 실행이 사라지는** 쪽이다. 그래서 가드 보정(Decision 4)이
  같은 PR 에 필요했다. plan 이 단일 진실 소스인데 틀린 인과를 박아두면 다음 세션이 그대로 오판한다.

- **가드 목록 ↔ `paths-ignore` 동기를 CI 로 강제하면 더 낫다.** 지금은 주석 ⚠️ 두 곳 + 이번 작업의
  일회성 검증 스크립트로만 묶여 있다. 한쪽만 넓히면 조용한 미배포가 되므로 영구 가드가 어울리는데,
  운영 워크플로에 스텝을 추가하는 건 이번 범위 밖이라 뺐다. 심각도: 중.

  재현용 단정(요지): `deploy.yml` 을 파싱해 `on.push.paths-ignore` 와
  `jobs.deploy-vultr.steps[id=main-head].run` 안의 `':(exclude)…'` 목록이 **정확히 같은지** 비교.

- **`wiki/verify.sh` 페이지 수 tripwire 가 baseline 에서 이미 깨져 있다** — 31페이지인데 기대 범위가
  `28±2`. **입증**: base(HEAD `9350bd6`) 의 `git ls-tree` 기준 이미 31개이고 이번 변경은 페이지를
  추가하지 않았다(기존 1개 수정만). 다른 세션이 페이지 2개를 추가하며 범위를 안 올린 것으로 보인다.
  나머지 검사(stem 유니크·정규식·frontmatter)와 `check_links.py`·`smoke.sh` 는 전부 통과한다.
  가드 주석이 "무한 증식 감지"라 밝히듯 의식적으로 올리는 tripwire 이므로, **범위를 올릴지는 페이지를
  추가한 쪽이 판단할 일**이라 여기서 고치지 않는다(§3-4 범위 밖 발견).

# Workflow Findings

(없음)
