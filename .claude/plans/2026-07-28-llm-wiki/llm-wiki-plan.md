---
title: llm-wiki — 영속 프로젝트 메모리 wiki/ 부트스트랩 + 기존 지식 광범위 이관
status: in_progress
started: 2026-07-28
updated: 2026-07-28
---

# Goal

이 repo 에 영속 프로젝트 메모리 `wiki/`(전역 CLAUDE.md §11)를 신설한다. 규약(`wiki/WIKI.md`)·index·log·pages 골격을 만들고, 흩어져 있던 지식(PROJECT_ANALYSIS·README·plan 15개·memory 13개·`docs/lessons.md` 6항목·GitHub 이슈)을 **주제별 상호링크 페이지 25개**로 재구성해 `/wiki query` 로 답변·구현 참고에 실제로 쓰이게 한다. `docs/lessons.md` 는 wiki 로 이관하고 항목별 포인터를 남긴다.

# Progress

- 2026-07-28: worktree `llm-wiki` 생성(.env 2개 복사). 규약을 코드로 확정(`check_links.py` 실측). 소스 인벤토리 완료(plan 15개 다이제스트, memory 13개, lessons 6항목, PROJECT_ANALYSIS). 사용자 결정: 시딩=광범위 이관, lessons=이관+포인터.
- 2026-07-28: **plan-review(codex, effort=medium) 완료 — Critical 5 / Major 11 / Minor 4.** 전량 검토해 처분을 `# Review Disposition` 에 기록. 주요 반영: source/raw 경계 재정의(C2), provenance 키 도입(C3), query smoke test 를 acceptance 로 승격(C4), lessons 발견성 퇴행 방지(C5), 링크 그래프 선설계(M1), 출처 권위 순서(M3). **자체 오류 1건 철회**: `.gitignore` 에 `.claude` negation 이 없다던 Deferred 항목은 틀렸다 — `.gitignore:62-71` 에 `.claude/*` + `!.claude/tasks/` + `!.claude/plans/` 가 실재하며 repo CLAUDE.md 서술이 정확했다(rtk 필터에 잘린 `cat` 출력을 Read 확인 없이 신뢰한 것이 원인).

- 2026-07-28: **구현 완료.** `wiki/` 신설(WIKI.md schema 1·index·log·pages 25개·verify.sh·smoke.sh), `.gitignore` 에 `wiki/raw/` 추가, `docs/lessons.md` 를 항목별 매핑 포인터로 축소, README 진입점 추가. 검증 3종 전부 통과(lint clean / 추가 불변식 clean 25페이지 / smoke 9-9). **smoke 의 음성 질의가 실제 규약 위반 1건을 잡았다** — `migration-numbering` 이 특정 브랜치의 V번호 선점 상태를 서술해 "진행 중 작업 상태 이관 금지"(위 Decisions)를 위반 → 확인 절차로 교체. 같은 유형 2건(`marketdata-pipeline`·`rightsizing-history` 의 브랜치명 직접 참조)도 함께 정리.

# Next

커밋 → push(pre-push codex 게이트 통과 확인) → PR. 이후 후속 제안 2건은 사용자 판단: ① `MEMORY.md` 에 lesson 인덱스 줄 추가(§13 의 wiki+memory 결합 — 무승인 적립 금지라 제안만) ② repo `CLAUDE.md` 문서 동기화 표에 wiki 행 추가(운영 자산이라 제안만).

# Decisions

## 지식 경계
- **wiki ≠ plan 복제**: plans(일시적 핸드오프, 종료 시 닫힘) vs wiki(작업 가로지르는 누적). `in_progress` plan 6개의 **진행 상태는 이관 금지**, 확정된 재사용 지식만 승격.
- **provenance 필수 (codex C3)**: active plan 안에도 `제안 / 로컬 구현 / PR 대기 / 머지 / 배포`가 섞여 있고 plan 자체가 stale 할 수 있다(engine-lifecycle plan 은 "PR #43 머지 대기", 이후 durability plan 은 "#43 머지 완료"로 기록 — 서로 어긋남). 따라서 페이지 frontmatter 에 **`claim_state`**(`current`|`historical`|`superseded`) + **`verified`**(확인 날짜 + 확인 방법/커밋)를 넣어 planned 가 implemented 로 위장하지 않게 한다. "코드로 확인"은 현재 HEAD 만 증명하며 배포 상태는 증명하지 못한다는 한계도 페이지에 명시.
- **출처 권위 순서 (codex M3)**: `현재 코드 > 머지된 결정 > active plan > 이슈 제안 > 과거 memory`. 충돌 시 상위 채택 + `> [!conflict]` 로 남긴다. **GitHub Issues 는 백로그 단일 소스**(repo CLAUDE.md)이므로 open issue 의 *상태*는 wiki 로 옮기지 않고, 종결된 결정만 승격한다(wiki 가 제2 백로그가 되는 것 방지).

## 구조
- **카테고리**: `concept/`(도메인·아키텍처) · `decision/`(이 repo 결정 + `lesson-*`) · `entity/`(외부 사실·버전) · `source/`(외부 원문 1:1 요약) · `query/`(질의 결과 filed).
- **`source/`·`raw/` 는 외부 원문 전용 (codex C2 해소)**: wiki skill 은 "원문 기반 ingest 마다 `pages/source/<name>.md` 1:1" 을 요구하지만, 이번 이관 소스는 전부 **repo 내 tracked 문서**(plan·lessons·PROJECT_ANALYSIS·README)라 raw 보존 대상이 아니다 → `sources:` 에 repo 상대경로로 참조하면 추적이 성립한다. 따라서 부트스트랩에서 `source/` 페이지는 만들지 않고 `raw/` 도 비운다. 외부에서 가져온 원문(웹 문서·API 스펙 캡처)이 생길 때 그때 1:1 규칙을 적용한다. 이 예외를 `WIKI.md` 에 명문화한다.
- **`raw/` 소실 위험 명시 (codex M8)**: raw 는 gitignored → worktree 삭제 시 무경고 동반 삭제(전역 CLAUDE.md §8). 따라서 **재취득 가능한 원문만** raw 에 둔다는 규약을 WIKI.md 에 넣는다. 부트스트랩 시점엔 raw 가 비어 있어 실제 노출은 없다.
- **stem 전역 유니크는 규약으로 강제 (codex C1)**: `check_links.py` 는 `pages[md.stem] = ...` 로 덮어써서 중복을 **조용히 삼킨다**(다른 카테고리에 같은 stem 이 있어도 무보고). 파일명 정규식도 검사하지 않는다. 검증기(홈 디렉토리 운영 자산)는 고치지 않고, repo 쪽에서 중복·정규식을 **별도 명령으로 검증**해 acceptance 에 넣는다.
- **schema version + checker drift (codex M11)**: `WIKI.md` 에 `schema: 1` 과 "이 규약이 전제하는 검증 불변식"을 repo 안에 적어, 홈 디렉토리 checker 가 바뀌어도 repo 가 규약의 소유자로 남게 한다.

## 링크 그래프 (codex M1 — 구현 전 선설계)
배치별로 만들면 dead link 또는 의미 없는 상호링크가 양산되므로, **아래 registry 대로 25개 골격을 한 번에 생성**한 뒤 내용을 채운다. 각 edge 는 의미 있는 관계일 때만 둔다.

| stem | category | outbound |
|---|---|---|
| architecture-overview | concept | trading-engine-loop, marketdata-pipeline, persistence-schema, rightsizing-history |
| trading-engine-loop | concept | exit-gates, swing-strategies, persistence-schema, architecture-overview |
| exit-gates | concept | trading-engine-loop, backtest-engine, swing-strategies |
| swing-strategies | concept | trading-engine-loop, backtest-engine, exit-gates |
| marketdata-pipeline | concept | architecture-overview, persistence-schema, upbit-api |
| persistence-schema | concept | migration-numbering, trading-engine-loop, marketdata-pipeline |
| backtest-engine | concept | swing-strategies, exit-gates, strategy-evolution-expectations |
| rightsizing-history | decision | architecture-overview, marketdata-pipeline, persistence-schema |
| migration-numbering | decision | persistence-schema, worktree-workflow |
| plan-git-tracking | decision | worktree-workflow, docs-code-sync, lesson-resume-state-sources |
| worktree-workflow | decision | plan-git-tracking, prepush-codex-review, migration-numbering, lesson-branch-checkout-drift |
| prepush-codex-review | decision | worktree-workflow, docs-code-sync, jdk-gradle-toolchain |
| docs-code-sync | decision | plan-git-tracking, github-issues-backlog, architecture-overview |
| github-issues-backlog | decision | docs-code-sync, worktree-workflow |
| strategy-evolution-expectations | decision | backtest-engine, swing-strategies |
| lesson-resume-state-sources | decision | worktree-workflow, plan-git-tracking |
| lesson-secure-cookie-http | decision | deployment-stack, lesson-cors-origin-rebuild |
| lesson-single-point-verification | decision | lesson-secure-cookie-http, deployment-stack |
| lesson-ec2-sizing-oom | decision | deployment-stack, rightsizing-history |
| lesson-deploy-script-pitfalls | decision | deployment-stack, lesson-ec2-sizing-oom |
| lesson-branch-checkout-drift | decision | worktree-workflow, plan-git-tracking |
| lesson-cors-origin-rebuild | decision | lesson-secure-cookie-http, deployment-stack, lesson-single-point-verification |
| upbit-api | entity | marketdata-pipeline, trading-engine-loop |
| jdk-gradle-toolchain | entity | architecture-overview, prepush-codex-review |
| deployment-stack | entity | architecture-overview, lesson-secure-cookie-http, lesson-ec2-sizing-oom, lesson-deploy-script-pitfalls |

inbound 검산: 25개 전부 ≥1(고아 없음), outbound 전부 ≥2. `index.md` 는 이 25개를 카테고리 heading 아래 등재.

## lessons 이관
- **원문 6항목 → 7페이지 (codex M5)**: 4번 항목이 EC2 OOM·`set -e` 단락·MSYS path 3주제를 한 항목에 담고 있어 `lesson-ec2-sizing-oom` + `lesson-deploy-script-pitfalls` 로 분리. 항목별 매핑 표를 Acceptance 로 검증(페이지 존재만으로 "유실 0" 판정 금지).
- **현재 적용성 재평가 (codex M6)**: 4번은 app+collector+kafka 5컨테이너 전제인데 현재는 경량화로 collector/Kafka 제거 → `claim_state: historical` + 지금도 유효한 부분(최소 4GB·deploy.sh 두 함정)만 현재형으로 재서술.
- **포인터는 한 줄이 아니라 항목별 목록 (codex M7·C5)**: 기존 plan 들이 `docs/lessons.md` 를 참조하고 있고(ec2-tls-caddy plan), 전역 CLAUDE.md §13 은 wiki(상세) + `MEMORY.md` 인덱스(자동 상기) **결합**을 요구한다. 한 줄로 줄이면 발견성이 퇴행하므로 → 6항목 각각에 대응 wiki 페이지 링크 + 원본 마지막 커밋(`331426f`) 명시. `MEMORY.md` 인덱스 줄 추가는 §13 무승인 적립 금지 게이트에 걸리므로 **Report 에서 제안**(이번 범위 밖).

## 기타
- **문서 동기화 대상 (codex M9)**: wiki 는 런타임 아키텍처가 아니라 **개발 워크플로 서브시스템**이므로 `PROJECT_ANALYSIS.md`(런타임 구조 문서)는 갱신 대상이 아니다. `README.md` 에 진입점만 추가한다. 별도 design spec 은 만들지 않는다 — `wiki/WIKI.md` 자체가 그 역할을 한다(사유를 Report 에 명시). repo `CLAUDE.md` 에 wiki 항목 추가는 운영 자산 자가수정(§1)이라 **제안만**.
- **subagent 미사용**: 이 세션은 "Do not call the AgentTool unless the user requested it" 지시하에 있다 → CLAUDE.md §5 의 plan-reviewer/code-reviewer 를 subagent 로 돌리지 않고 **메인 직접 점검 + codex 병행**(§5 의 "같은 관점으로 직접 점검" 경로). 생략이 아니라 수행 주체 변경.

# Key Files

- `wiki/WIKI.md` — 스키마·운영 규약 단일 소스(신규). schema version·카테고리·provenance 키·권위 순서·raw 경계 포함
- `wiki/index.md` — 25페이지 카테고리별 등재(누락·잉여 시 lint 실패)
- `wiki/log.md` — ingest/lint 이력 append
- `wiki/pages/{concept,decision,entity}/` — 페이지 본체(부트스트랩에선 source/·query/ 비움)
- `.gitignore` — `wiki/raw/` 추가 (`.claude/*` negation 블록과 무관한 top-level 패턴)
- `docs/lessons.md` — 항목별 포인터로 축소(원본 커밋 `331426f`)
- `README.md` — wiki 진입점 추가
- `~/.claude/skills/wiki/check_links.py` — 검증기(수정 금지 대상, 결함은 Decisions 참조)

# Acceptance

- [x] **구조 lint**: `check_links.py wiki` → `wiki link check: clean` (exit 0). 최초 실행에서 orphan 2건(`lesson-resume-state-sources`·`upbit-api` — registry 설계 edge 누락) 검출 → 의미 있는 위치에 링크 추가 후 clean
- [x] **stem 중복·정규식** (codex C1): `wiki/verify.sh` — 25개 전부 `^[a-z0-9-]+$`, 중복 0
- [x] **frontmatter 값 검증** (codex M2): `wiki/verify.sh` — `category`=디렉토리 일치, `sources` 비어있지 않음, `created`/`updated` ISO 날짜
- [x] **페이지 수 25 ± 2** (codex Minor1): 25 (verify.sh 가 상·하한 모두 검사)
- [x] **raw ignored** (codex M10): `git check-ignore -v wiki/raw/.gitkeep` → `.gitignore:79:wiki/raw/` 매치
- [x] **lessons 이관 충실도** (codex M5): 원문 6항목 → 7페이지 매핑 표를 `docs/lessons.md` 에 기록. 각 페이지가 증상·원인·해결·관련 파일을 모두 담음. EC2 항목은 3주제(메모리/`set -e`/MSYS)가 섞여 있어 2페이지로 분리, 전제가 낡은 부분은 `claim_state: historical` + 유효 부분만 현재형
- [x] **query smoke test** (codex C4): `wiki/smoke.sh` **9/9 pass**. 음성 질의가 실제 위반 1건 검출(위 Progress) — 테스트가 작동함을 입증
- [x] **소스 coverage matrix**: `wiki/log.md` 에 `ingested | 부분 ingested | intentionally-skipped | skipped` 표기
- [x] **문서 동기화**: `README.md` 참고 사항에 wiki 진입점 추가. `PROJECT_ANALYSIS.md` 는 런타임 아키텍처 문서라 대상 아님(codex M9 처분대로)
- [x] **리뷰 처분**: codex Critical 5 / Major 11 / Minor 4 → **미해결 0**. `defer` 2건은 아래 `# Deferred` 에 추적 위치 명시

# Blockers

(구현 blocker 없음. 아래는 진행 중 확정할 **결정 필요** 항목 — codex Minor4)
- GitHub 이슈 이관 범위: 종결 이슈의 결정만 승격하는 경계를 페이지 작성 시점에 항목별 확정.
- 작업량: 25페이지 × 코드 확인은 한 세션을 넘길 수 있다. 넘기면 `# Progress` 에 배치 단위로 기록하고 이어서 진행.

# Review Disposition

codex plan-review (2026-07-28, effort=medium) — Critical 5 / Major 11 / Minor 4.

| # | finding | 처분 |
|---|---|---|
| C1 | 검증기가 stem 중복·정규식을 강제하지 않음 | **fix** — 별도 검증 명령을 Acceptance 에 추가, WIKI.md 에 규약 명문화 |
| C2 | "광범위 이관" ↔ source 1:1 ↔ 25페이지 상호모순 | **fix** — source/raw = 외부 원문 전용으로 경계 재정의, repo 내 문서는 `sources:` 참조 |
| C3 | in_progress 미복제만으론 planned→implemented 위장 못 막음 | **fix** — `claim_state`/`verified` provenance 키 도입 |
| C4 | Acceptance 가 "실제 query 에 쓰임"·이관 커버리지 미검증 | **fix** — query smoke test 8문항(음성 질의 포함) + coverage matrix 추가 |
| C5 | lessons 를 wiki 로만 옮기면 자동 상기 퇴행(§13 은 wiki+memory 결합) | **fix(부분)** — 포인터를 항목별 목록으로 유지. `MEMORY.md` 인덱스 추가는 §13 무승인 적립 금지라 **Report 제안**으로 이관 |
| M1 | 링크 그래프 선설계 없음 → 인위적 상호링크 양산 | **fix** — 25행 registry 표 + inbound 검산을 plan 에 선기재 |
| M2 | frontmatter 값 미검증(빈 sources·날짜·category 불일치 통과) | **fix** — 값 검증을 Acceptance 항목으로 |
| M3 | 출처 권위·충돌 해결 규칙 없음, open issue 상태 이관 위험 | **fix** — 권위 순서 명문화 + open issue 상태 이관 금지 |
| M4 | stale 방지 장치 없음 | **fix(축소)** — `verified` 키 + WIKI.md 갱신 책임 규정까지. 정기 lint 자동화·review_after 는 **defer**(추적: 이 plan Deferred + 후속 이슈 제안) |
| M5 | "내용 유실 0" 을 페이지 존재로 판정 불가 | **fix** — 항목별 매핑 표 대조를 Acceptance 로 |
| M6 | 과거 lesson 의 현재 적용성 미평가 | **fix** — `claim_state: historical` 구분 + 유효 부분만 현재형 재서술 |
| M7 | lessons.md 축소의 rollback 부재 | **fix** — 항목별 포인터 + 원본 커밋(`331426f`) 명시 |
| M8 | raw/ 가 gitignored 라 worktree 삭제 시 소실 | **fix** — "재취득 가능한 원문만" 규약 + 부트스트랩에선 raw 비움 |
| M9 | 문서 동기화 대상이 repo 규약과 불일치 | **fix** — PROJECT_ANALYSIS 제외(사유 명시), README 진입점만, design spec 대신 WIKI.md |
| M10 | `.gitignore` 근거 서술이 틀림 | **fix** — 잘못된 Deferred 철회(위 Progress), 파일 단위 check-ignore 로 변경 |
| M11 | WIKI.md ↔ 홈 checker 버전 drift | **fix(축소)** — `schema: 1` + 전제 불변식 명문화까지. checker hash 고정은 **defer**(홈 자산 수정 없이는 강제 불가) |
| m1 | 페이지 수 상한 미검증 | **fix** — 25 ± 2 |
| m2 | index 카테고리 배치 기계 검증 불가 | **fix(수동)** — 육안 대조를 Acceptance 에 포함 |
| m3 | "Disposition 기록"은 품질 acceptance 아님 | **fix** — "Critical/Major 미해결 0" 으로 변경 |
| m4 | `Blockers: 없음` 성급 | **fix** — 결정 필요 항목으로 전환 |

# Deferred

- **stale 관리 자동화**(codex M4 잔여): 정기 lint 스케줄·`review_after` 만료 알림·코드 변경 시 sources 역참조 절차. 이번 범위는 `verified` 키 + 규약 문구까지. 후속 GitHub 이슈로 제안.
- **checker 버전 고정**(codex M11 잔여): 홈 디렉토리 `check_links.py` 의 hash/버전을 repo 가 고정·검증하는 장치. 운영 자산 수정 없이는 부분적으로만 가능.

# Workflow Findings

- **rtk 필터가 shell 출력을 손상시켜 사실 오판을 유발**(2026-07-28, 이 세션 2회): ① `find` 출력이 이모지 헤더로 오염돼 경로가 깨짐 ② `cat .gitignore` 출력이 62줄 이후로 잘려, `.claude` negation 이 "없다"고 오판 → plan 에 틀린 Deferred 를 기록(codex 가 반박해 발견). 교훈: **파일 내용 판단은 Bash `cat`/`head` 가 아니라 Read 도구로** 한다(CLAUDE.md §1 "코드를 보고"의 실무적 함의). `tail` 도 rtk 가 `read -1` 오류로 실패시킨 사례 있음.
