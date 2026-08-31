---
title: deploy-path-list-sync — deploy.yml 의 경로 제외 목록 두 곳을 자동으로 묶는다 (#151)
status: in_progress
started: 2026-09-01
updated: 2026-09-01
---

# Goal

`deploy.yml` 에 같은 경로 제외 목록이 **두 곳**(트리거 `on.push.paths-ignore`, 가드 `Check deployment commit is current main` 의 `:(exclude)`)에 있고 주석으로만 묶여 있다. 어긋나면 **조용한 미배포**가 되므로 기계가 강제하게 만든다.

# Progress

- 2026-09-01: Explore 완료.
  - 두 목록 위치 확인 — 트리거는 `deploy.yml:24-28`, 가드는 `:162` 한 줄(`':(exclude)**.md' ':(exclude).claude/**' ':(exclude)wiki/**' ':(exclude)docs/**'`). 현재는 서로 일치한다.
  - `scripts/` 에는 shell 뿐이고 python 스크립트가 없다. 이 repo 에 python 검사 관례를 새로 들이는 셈이 된다.
  - **`org.yaml:snakeyaml:2.3` 이 이미 `testRuntimeClasspath` 에 있다**(Spring Boot 경유). 의존성 추가 없이 Kotlin 테스트로 YAML 을 파싱할 수 있다.
- 2026-09-01: **구현 완료.** `DeployWorkflowPathListTest` 2건 + `build.gradle.kts` 에 `repo.root` 한 줄. `deploy.yml` 은 건드리지 않았다.
- 2026-09-01: **테스트가 실제로 잡는지 4케이스로 관찰.** 통과만으로는 검사 대상이 검사되는지 증명되지 않아, `deploy.yml` 을 일부러 어긋나게 만들고 되돌렸다 — baseline PASS / 트리거에만 경로 추가 → **FAIL(동일성 테스트가 잡음)** / 양쪽에 `deploy/**` 추가 → **FAIL(위험 경로 테스트가 잡음)** / 복구 PASS. 각 케이스에서 어느 테스트가 잡았는지까지 확인했다.
- 2026-09-01: **자체 리뷰 1건 반영** — `repo.root` 가 없으면(IDE 직접 실행) NPE 가 났다. 상향 탐색 fallback 을 넣고, `repo.root` 를 존재하지 않는 경로로 바꿔 **fallback 이 실제로 파일을 찾아내는 것까지 관찰**했다.
- 2026-09-01: 검증 통과 — `test --parallel`·`build`·wiki 3종(link/verify/smoke 10-0).
- 2026-09-01: **pre-push codex 5라운드 7건 반영(P1 4 + P2 3).** 매 라운드가 앞선 방어의 우회 경로를 짚었다 — 리터럴 매칭 → glob, 샘플 하드코딩 → 입력 열거, 패턴 나열 → 표지 기반, 형식 가정 → fail-closed. 마지막 지적으로 대표 경로 하드코딩을 **배포 입력 실제 열거**로 바꿨다 — 샘플 방식은 계속 빠지는 게 생긴다. 내가 만든 안전장치가 정작 가장 위험한 케이스를 놓치고 있었다 — `**` 하나로 전체를 제외해도, 큰따옴표 pathspec 을 써도 통과했다. 둘 다 고치고 4케이스로 재관찰(baseline PASS / 목록 불일치 FAIL / `**` FAIL / 큰따옴표 FAIL / 복구 PASS).

# Next

push → CI 에서 이 테스트가 실행됐는지 확인 → PR → 머지.

# Decisions

- **CI 스텝이 아니라 Kotlin 테스트로 만든다(이슈 제안에서 변경).** 이슈는 `test` job 에 python 스텝 추가를 제안했으나, 이 repo 의 CI 게이트가 이미 `./gradlew test` 라 테스트로 넣으면 **자동으로 강제되면서 로컬에서도 push 전에 잡힌다**. python/pyyaml 을 CI 에 새로 들일 필요도 없다(snakeyaml 이 이미 있다). 검사의 강제력은 같고 실행 지점만 앞당겨진다.
- **`on` 키는 boolean 으로 파싱될 수 있다** — YAML 1.1 에서 `on:` 은 `true` 다. snakeyaml 도 그럴 수 있으므로 `true` 키와 `"on"` 키를 모두 시도한다(이슈 예시 python 코드도 같은 처리를 했다).
- **경로는 `repo.root` 시스템 프로퍼티로 해결** — Gradle 테스트의 working dir 는 모듈 디렉토리(`bot/`)라 `.github/` 가 상대경로로 안 잡힌다. 루트 `build.gradle.kts` 의 `tasks.withType<Test>` 에 한 줄 추가한다(암묵적 `..` 탐색보다 명시적이다).
- **위험 경로 단정도 함께** — 미래 편집자가 `deploy/**`·`Dockerfile`·`gradle/**`·`.github/**` 를 제외 목록에 넣으면 그 파일만 바꾼 push 가 조용히 미배포된다. 이슈가 요구한 항목이다.

# Key Files

- `.github/workflows/deploy.yml` — 검사 대상(두 목록). 이 작업에서 내용은 바꾸지 않는다
- `bot/src/test/kotlin/com/trading/bot/ci/DeployWorkflowPathListTest.kt` (신규) — 동일성·위험 경로 검사
- `build.gradle.kts` — `systemProperty("repo.root", ...)` 한 줄
- `scripts/git-hooks/pre-push` — `paths-ignore` 자기무력화 가드(+ `README.md` 정책 표)

# Blockers

없음.

# Acceptance

- [x] **두 목록이 다르면 실패한다** — 한쪽만 바꿔 실제로 실패를 관찰한다(테스트 자체가 산출물이라 통과만으로는 증명이 안 된다)
- [x] **위험 경로가 들어가면 실패한다** — `deploy/**` 를 제외 목록에 넣어 실패를 관찰한다
- [x] **현재 상태에서는 통과한다** — 두 목록이 일치하는 지금은 초록
- [ ] **CI 에서 실행된다** — `./gradlew test` 에 포함되므로 별도 스텝 없이 게이트에 걸린다. CI 결과에서 실행 확인
- [x] **검증 통과** — `compileKotlin` · `test --parallel` · `build`
- [x] **문서 동기화 판정** — `deployment-stack` 의 "쌍으로 유지해야 한다" 서술에 **이제 테스트가 강제한다**를 추가(그 페이지가 `deploy.yml` 을 sources 로 가진다). README 는 대상 아님(사용자 노출 없는 내부 검증)

# Review Disposition

| finding | 처분 | 근거 |
|---|---|---|
| **pre-push codex P1** 위험 경로를 리터럴 포함으로만 검사 | **fix** | 제외 목록에 `**` 만 넣어도 모든 파일이 배포에서 빠지는데 그 리터럴은 어디에도 없어 통과했다. **대표 경로 13개를 실제 glob 으로 매칭**해 하나라도 삼켜지면 실패시킨다. `**` 케이스로 실패를 관찰 |
| **pre-push codex P1** 가드 정규식이 작은따옴표만 파싱 | **fix** | 큰따옴표 pathspec 이 추출에서 빠지면 두 목록이 어긋나도 비교가 통과한다. 정규식을 `['\"]` 로 넓히고, **`:(exclude)` 출현 수와 파싱 수가 다르면 실패**시켜 앞으로 다른 형식이 생겨도 조용히 빠지지 않게 했다 |
| **pre-push codex P2** `run` 전체에서 `:(exclude)` 를 긁는다 | **fix** | 목록을 옮기며 옛 pathspec 을 주석에 남기면 그 주석을 실제 가드 목록으로 오인한다. `git diff --name-only` 명령 범위(줄 연속 포함)로 좁혔다. 주석에 옛 목록을 넣어도 PASS 하는 것을 관찰 |
| **pre-push codex P2(3라운드)** 대표 경로 샘플에 `resources/static` 이 빠져 UI 제외를 못 잡는다 | **fix (설계 전환)** | 손으로 나열한 샘플은 반드시 빠지는 게 생긴다. **Dockerfile 이 이미지에 넣는 입력을 실제로 열거**하도록 바꿨다(`bot/src`·`common/src`·`gradle`·`deploy` 트리 + 단일 파일). 지적된 `static/**` 케이스를 실제로 재현해 `index.html`·`ui.jsx` 가 삼켜진다고 보고하는 것을 확인 |
| **오탐 1건** 열거 전환 후 `deploy/*/README.md` 등이 삼켜졌다고 실패 | **fix (열거 조정)** | `.md` 는 이 repo 가 **의도적으로** 배포 무관으로 두는 것이다(그래서 "실행 리소스에 .md 를 두지 말 것" 규약이 따로 있다). 배포 입력 열거에서 `.md` 를 빼고 그 근거를 주석에 남겼다 — 검사 약화가 아니라 정책 반영 |
| **pre-push codex P1(4라운드)** 검사가 자기 자신을 끄는 변경을 못 막는다 | **fix (pre-push 가드)** | `paths-ignore` 에 `.github/**`·`**` 를 넣고 main 에 직접 push 하면 워크플로가 안 생겨 테스트도 안 돈다. `scripts/git-hooks/pre-push` 에 **자기무력화 패턴만** 막는 shell 가드를 추가했다(위험 5종 DETECT·무해 2종 clean 확인). hook 에서 gradle 을 돌리지 않은 것은 JDK 환경 차이로 `./gradlew` 가 실패하면 모든 push 가 막히기 때문이다 — 역할을 나눠 나머지 불변식은 CI 테스트가 본다 |
| **codex 5라운드(중단됨) 선반영** 가드가 `.github/workflows/*.yml`·`**/deploy.yml` 을 놓친다 | **fix** | codex 가 결론 전에 타임아웃됐지만, 로그에 남은 glob 검증 결과가 두 패턴도 워크플로를 매칭함을 보여줬다. 패턴 나열 대신 **표지 기반**(`.github`·`.yml`/`.yaml`·선두 `**`)으로 넓혔다 — 삼키는 패턴 7종 BLOCK / 정상 편집 4종 allow 확인 |
| **codex P1(5라운드)** 가드가 큰따옴표·inline YAML 을 못 읽어 우회된다 | **fix** | 따옴표를 벗겨 비교하도록 바꾸고, **inline 형식은 읽을 수 없으므로 차단**(fail-closed)한다 — 파싱 실패가 곧 우회가 되면 가드가 무의미하다. 작은/큰/무따옴표 8종 BLOCK · inline BLOCK · 정상 2종 allow 확인 |
| **codex P2(5라운드)** `.md` 를 통째로 배포 입력에서 제외 | **fix** | 오탐을 없애려다 너무 넓게 뺐다. `bot/src/main`·`common/src/main` 의 `.md` 는 jar 에 들어가므로 검사 대상으로 되돌렸다. 임시로 `resources/tmpcheck/note.md` 를 만들어 실제로 잡히는 것을 확인 — wiki 의 "실행 리소스에 .md 를 두지 말 것" 함정을 이제 테스트가 잡는다 |
| **자체리뷰 1** `repo.root` 부재 시 NPE | **fix** | Gradle 은 넘겨주지만 IDE 직접 실행에는 없다. 상향 탐색 fallback + 못 찾으면 무엇이 없는지 알려주는 메시지. fallback 동작을 실제로 관찰 |
| **설계** 이슈는 CI 스텝 + python 을 제안 | **변경(Kotlin 테스트)** | 이 repo 의 CI 게이트가 이미 `./gradlew test` 라 테스트로 넣으면 자동 강제되고 **로컬에서도 push 전에 잡힌다**. snakeyaml 이 이미 클래스패스에 있어 의존성도 안 는다. 강제력은 같고 실행 지점만 앞당겨진다 |
| **가드 스텝 `id` 의존** | **wontfix(문서화)** | `id=main-head` 가 바뀌면 테스트가 명시적 에러로 죽는다. 그 메시지에 "이름이 바뀌었으면 이 테스트도 함께 고친다"를 적어 두는 편이, 이름 변경을 조용히 통과시키는 것보다 낫다 |

# Deferred

(없음)

# Workflow Findings

(없음)
