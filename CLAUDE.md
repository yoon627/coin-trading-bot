# coin-trading-bot — Environment

이 파일은 **환경/빌드 정보**만 담는다. 개발 워크플로우 규칙은 `~/.claude/CLAUDE.md` 참고.
코드 스타일·보안·클린코드·테스트 체크는 `.git/hooks/pre-push`의 codex review가 게이트한다.

## Build & Test

- JDK 21 필요. Gradle toolchain + Foojay resolver가 설정되어 있어, 로컬에 JDK 21이 없으면 첫 빌드 시 자동 다운로드됨 (`~/.gradle/jdks`에 캐시). `JAVA_HOME`을 수동으로 잡을 필요 없음.
- 빌드: `./gradlew build` (Windows cmd는 `gradlew.bat build`)
- 테스트: `./gradlew test`
- 타입체크: `./gradlew compileKotlin`
- 코드 수정 후 커밋 전 최소 `compileKotlin` 통과 확인.

## 모듈 구조

`settings.gradle.kts`: `include("common", "bot")`

- `bot` — Spring Boot 메인 애플리케이션 (실거래 봇 + REST API + SPA + in-process 시세 수집, port 8080). Upbit WS ticker + REST 캔들 폴링을 `marketdata/` 에서 직접 수집(구 collector/Kafka 흡수).
- `common` — 공용 도메인 모델(`NormalizedTicker`/`NormalizedCandle` 등), 인디케이터, 스윙 전략 7개.

> 거래소는 Upbit only. 구 `collector`·`research` 모듈, Kafka, ML/스캘핑/Claude 분석은 경량화(rightsizing) 과정에서 제거됨.

## 스펙 문서

- 아키텍처 변경은 `PROJECT_ANALYSIS.md`에 반영
- 설계 스펙은 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`

## TODO / 백로그 = GitHub Issues

- 이 레포의 할 일·백로그·미룬 항목은 **GitHub Issues** 로 관리한다 (`TODO.md` 등 추적용 파일을 새로 만들지 않는다).
- 작업 아이디어·버그·후속 작업은 이슈로 생성하고, PR/커밋에서 `Closes #N` 으로 연결.
- `docs/superpowers/specs/` 의 설계 스펙과 `~/.claude/CLAUDE.md` §10 의 plan 은 **진행 중 작업의 설계·상태 기록 전용** — 백로그 저장소가 아니다.

## plan 버전관리 (이 repo 고유)

- `~/.claude/CLAUDE.md` §10 의 `.claude/plans/` 를 이 repo 는 **git 추적**한다 (`.gitignore` 의 `!.claude/plans/` — `.claude/tasks/` 와 동일 negation 패턴). worktree 삭제 시 plan 소실 방지가 목적.
- ⚠️ 글로벌 `/e`·`/c`·`/wt` skill 은 plans/ 가 **gitignored 라고 전제**한다 (예: `/e` 의 "plan 이 worktree 내부면 삭제 제안 생략", worktree 삭제 전 main 백업). 이 repo 에선 그 전제가 어긋난다 — plan 이 git 에 보존되므로 worktree 삭제 전 별도 백업이 불필요하다.
- 작업 worktree 에서 plan 은 코드와 별도 커밋(`chore(plan): ...`) 하거나 작업 커밋에 포함한다.

## 머지 후 main 동기화 (이 repo 고유)

머지·worktree 정리 후 세션이 main worktree 로 복귀할 때, **아래 안전 조건을 모두 만족하면** `git pull origin main --rebase` 로 로컬 main 을 최신화한다. 하나라도 어긋나면 **건너뛰고 사용자에게 알린다** (자동 rebase 금지 — 예상외 히스토리 재작성·충돌 방지).

- **clean**: main worktree 가 `git status --porcelain` 비어있음 (uncommitted 변경 없음; untracked 는 무관).
- **fast-forward 가능**: 로컬 전용 커밋 없음 (`git log origin/main..HEAD` 비어있음). 로컬 커밋이 있으면 rebase 가 히스토리를 재작성하므로 건너뛴다.

## 문서 동기화 대상

글로벌 `~/.claude/CLAUDE.md`의 "문서 동기화(범위 한정)" 기준에 해당할 때 업데이트:

| 변경 종류 | 업데이트 대상 |
|-----------|---------------|
| 외부 visible behavior / Public API / CLI 변경 | `README.md` |
| 모듈 구조·의존성·아키텍처 변경 | `PROJECT_ANALYSIS.md` + `README.md` (해당 섹션) |
| 설계 결정·신규 서브시스템 | `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` 신규 |
| 운영/배포 절차 변경 | `docs/runbook/` (존재 시) 또는 `README.md`의 운영 섹션 |
| 보안 정책 변경 | `SECURITY.md` (존재 시) |
| `wiki/pages/` 추가·변경 | `wiki/index.md` 등재 동기화 (누락·잉여 모두 lint 실패) |

내부 리팩터·테스트 전용·invisible 버그 수정은 문서 업데이트 불필요.

## LLM Wiki (`wiki/`)

이 repo 의 누적 지식베이스. 운영 규약의 단일 소스는 **`wiki/WIKI.md`** 이고, 아래는 이 repo 에서 지켜야 할 최소 규칙만 적는다 (글로벌 `~/.claude/CLAUDE.md` §11 의 repo 측 구체화).

- **작업 시작 시 `wiki/index.md` 를 먼저 조회한다.** 관련 페이지가 있으면 읽고 시작하고, 없으면 "wiki 에 없음"이며 추측으로 답하지 않는다.
- **코드를 바꾸면 그 파일을 `sources` 로 가진 페이지를 함께 갱신한다.** 이건 위 문서 동기화 표와 같은 급의 의무다. 페이지가 **디렉토리**를 `sources` 로 선언하기도 하므로(예: `swing-strategies` 는 `common/.../strategy/`) 파일 경로만 grep 하면 놓친다 — 상위 경로도 함께 본다:
  ```bash
  f=common/src/main/kotlin/com/trading/common/strategy/MeanReversion.kt
  while [ "$f" != "." ]; do grep -rl -- "$f" wiki/pages/; f=$(dirname "$f"); done | sort -u
  ```
- **페이지에 넣지 않는 것**: 진행 중 작업의 상태(→ `.claude/plans/`), 열린 이슈의 상태(→ GitHub Issues). 넣으면 이중 소스가 되고 반드시 stale 해진다.
- **근거 없는 서술 금지**: 문서가 코드와 어긋난 전례가 있다(리스크 파라미터 정반대 기재). 코드로 확인한 것만 쓰고 `sources`·`verified` 에 근거를 남긴다.
- **검증 3종** — 페이지를 건드렸으면 돌린다:
  ```bash
  uv run --no-project python "$HOME/.claude/skills/wiki/check_links.py" wiki
  bash wiki/verify.sh   # stem 중복·정규식·frontmatter 값 (검증기 미커버분)
  bash wiki/smoke.sh    # 대표 질문에 답이 되는가 + 진행중 작업 상태 침범 검사
  ```
- `wiki/raw/` 는 gitignored 다 — worktree 삭제 시 함께 사라지므로 **재취득 가능한 외부 원문만** 둔다.
