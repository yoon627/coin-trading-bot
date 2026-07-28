# WIKI.md — 이 repo 의 LLM Wiki 운영 규약

`wiki/` 는 이 repo·워크플로우의 **영속 지식베이스**다. 전역 `~/.claude/CLAUDE.md` §11 이 정의한 "영속 프로젝트 메모리"의 repo 측 실체이며, **이 파일이 규약의 단일 진실 소스**다. `/wiki ingest|query|lint` skill 은 이 규약을 실행하는 절차일 뿐, 규약을 소유하지 않는다.

```yaml
schema: 1
```

## 1. 무엇을 여기 두는가 — 다른 저장소와의 경계

| 저장소 | 소유하는 것 | 수명 |
|---|---|---|
| **`wiki/`** (여기) | 작업을 가로질러 재사용되는 지식 — 아키텍처, 확정된 결정, 교훈, 검증된 외부 사실 | 영속 |
| `.claude/plans/` | 진행 중 작업 **하나**의 상태·다음 액션 | 작업 종료 시 닫힘 |
| GitHub Issues | 백로그·할 일·우선순위 (repo `CLAUDE.md`) | 이슈 종결까지 |
| `~/.claude/.../memory/` | 세션마다 자동 주입되는 짧은 행동 지시문 | 영속(전역) |

경계 규칙:

- **plan 의 진행 상태를 wiki 로 복제하지 않는다.** 복제하면 두 곳이 어긋나고, wiki 쪽이 조용히 stale 해진다. plan 에서 승격하는 것은 *확정된 결정·교훈*뿐이다.
- **열린 이슈의 상태를 wiki 로 옮기지 않는다.** 옮기면 wiki 가 제2 백로그가 된다. 종결된 이슈의 *결정*만 승격한다.
- **memory 와 wiki 는 역할 분담이다** (전역 CLAUDE.md §13). wiki 는 상세(원인·재현·해결), memory 인덱스는 자동 상기용 한 줄. 교훈은 양쪽 다 필요하며, 이는 중복이 아니다.

## 2. 디렉토리

```
wiki/
├── WIKI.md          # 이 파일 — 규약
├── index.md         # 전 페이지 등재 (누락·잉여 시 lint 실패)
├── log.md           # ingest / lint 이력 append
├── pages/
│   ├── concept/     # 도메인·아키텍처 개념 (이 repo 가 무엇인가)
│   ├── decision/    # 이 repo 의 결정 + lesson-* 교훈
│   ├── entity/      # 외부 사실·버전 (API·런타임·인프라)
│   ├── source/      # 외부 원문 1:1 요약 (§5)
│   └── query/       # 재사용 가치 있는 질의 결과 filed
└── raw/             # 외부 원문 보관 — gitignored (§5)
```

## 3. 페이지 규약

**파일명(stem)** = 페이지 이름 = `[a-z0-9-]+`. **`pages/` 전체에서 유니크해야 한다** — 카테고리가 달라도 같은 stem 을 쓰면 안 된다. 검증기가 stem 을 키로 쓰기 때문에 중복은 조용히 덮어써지며 경고가 나오지 않는다(§6).

**frontmatter** — 위 5키는 필수(검증기가 존재를 강제), 아래 provenance 키는 이 규약이 요구한다:

```yaml
---
title: <한 줄 제목>
category: concept | decision | entity | source | query   # 실제 디렉토리와 일치할 것
created: YYYY-MM-DD
updated: YYYY-MM-DD
claim_state: current | historical | superseded
verified: YYYY-MM-DD — <무엇으로 확인했는지 (파일·명령·커밋)>
sources:
  - <repo 상대경로 또는 URL>
---
```

- **`claim_state`** — 이 페이지의 주장이 지금도 유효한가. `historical` 은 "당시엔 맞았고 지금 구조는 다르다", `superseded` 는 대체 페이지가 있다(본문에 `[[대체페이지]]` 링크).
- **`verified`** — 언제·무엇으로 확인했는지. **"코드로 확인"은 현재 HEAD 만 증명하며 운영 배포 상태를 증명하지 않는다.** 배포 상태를 주장하려면 그 근거를 따로 적는다.
- **`sources`** — 비워두지 않는다. 근거 없는 단정·추측 페이지는 금지(전역 CLAUDE.md §1).

**본문**

- 나가는 위키링크 `[[stem]]` **2개 이상**, 그리고 다른 페이지로부터 **1개 이상 링크받아야** 한다(고아 금지). 링크는 의미 있는 관계일 때만 — 검증기를 통과시키려는 장식용 "관련 문서" 나열은 규약 위반이다.
- 출처가 충돌하면 숨기지 말고 남긴다:
  ```
  > [!conflict] PROJECT_ANALYSIS 는 X 라고 하나 코드는 Y — 코드 기준으로 서술
  ```
- 코드 예시 안에서 `[[ ]]` 를 쓰지 않는다(검증기가 링크로 오인).

## 4. 출처 권위 순서

주장이 엇갈릴 때:

```
현재 코드 > 머지된 결정(커밋·PR) > active plan > 이슈 제안 > 과거 memory·문서
```

문서(`PROJECT_ANALYSIS.md` 등)가 코드와 어긋난 전례가 실제로 있다 — 리스크 파라미터를 정반대로 기재한 건이 `docs-sync` 작업에서 잡혔다. **문서만 보고 페이지를 쓰지 말고 코드를 확인**하고, 확인한 것만 쓴다. 확인 못 한 것은 쓰지 않는다.

## 5. `raw/` 와 `source/` — 외부 원문 전용

- `raw/` 는 **외부에서 가져온 원문**(웹 문서·API 스펙 캡처·로그 덤프)만 보관한다. 적재 후 편집·삭제하지 않는다.
- `raw/` 는 **gitignored** 다 → worktree 삭제 시 무경고로 함께 사라진다(전역 CLAUDE.md §8). 따라서 **다시 구할 수 있는 원문만** 둔다. 재취득 불가능한 자료는 raw 가 아니라 페이지 본문에 요약과 함께 보존한다.
- `source/` 는 그 외부 원문의 1:1 요약 페이지다.
- **repo 내 tracked 문서(`.claude/plans/`·`docs/`·`README.md` 등)는 raw/source 대상이 아니다** — git 이 이미 원문을 보존하므로 `sources:` 에 상대경로로 참조하면 추적이 성립한다.

## 6. 검증

**기계 검증** — `~/.claude/skills/wiki/check_links.py` 가 확인하는 불변식:

1. frontmatter 필수 5키(`title`/`category`/`created`/`updated`/`sources`) 존재
2. outbound 위키링크 ≥ 2 (자기 자신 제외, 실재 페이지만)
3. dead link 0 — 모든 `[[name]]` 이 `pages/` 에 실재
4. orphan 0 — 각 페이지가 다른 **페이지**로부터 inbound ≥ 1 (`index.md` 링크는 세지 않는다)
5. `index.md` ↔ `pages/` 완전 동기화 (누락·잉여 모두 위반)

```bash
uv run --no-project python "$HOME/.claude/skills/wiki/check_links.py" wiki
bash wiki/verify.sh   # 검증기가 안 보는 불변식 (아래)
bash wiki/smoke.sh    # "실제 질문에 답이 되는가" + 백로그 침범 검사
```

**`check_links.py` 가 못 잡는 것 — `wiki/verify.sh` 가 대신 본다:**

- **stem 중복**: 다른 카테고리에 같은 stem 이 있으면 뒤 파일이 앞 파일을 덮어쓰고 **아무 경고도 나오지 않는다**.
- **파일명 정규식**: `[a-z0-9-]+` 위반을 직접 판정하지 않는다.
- **frontmatter 값**: 키 존재만 본다. 빈 `sources`, 잘못된 날짜, 디렉토리와 다른 `category` 는 통과한다.
- **provenance 키**: `claim_state`·`verified` 는 필수 5키에 없어 아예 검사 대상이 아니다.
- **검사 자체가 생략된 경우**: `verify.sh` 는 "검사한 페이지 수 = 발견한 페이지 수" 를 확인해 false pass 를 막는다.

**두 검증기 모두 못 잡는 것 — 사람이 봐야 한다:**

- **링크의 의미**: 장식용 상호링크와 진짜 관계를 구분하지 못한다.
- **stale**: `updated`·`verified` 값의 최신성은 검증되지 않는다. 형식적으로 올리지 말 것(§7).
- **서술의 사실성**: 페이지가 코드와 어긋나게 적혀 있어도 통과한다. 이건 실제로 발생했다 — 초판 `exit-gates` 가 "진입 시점 파라미터 스냅샷으로 청산한다"고 적었으나 코드는 스냅샷을 소비하지 않는다(저장·복원 전용). **`sources` 의 파일을 열어 대조하는 것 외에 자동 방어가 없다.**

이 규약이 전제하는 것은 위 1~5다. 검증기는 홈 디렉토리의 비버전 파일이므로 바뀔 수 있다 — **규약의 소유자는 이 파일**이고, 검증기가 이 목록과 어긋나면 검증기 쪽을 의심한다.

## 7. 유지보수

- **write 는 메인 세션만** (single-writer, `plans/` 와 동일 원칙). subagent 는 wiki 에 쓰지 않는다.
- **코드·설정을 바꿨으면** 그 파일을 `sources` 로 가진 페이지가 있는지 확인하고 함께 갱신한다(`grep -rl "<경로>" wiki/pages/`).
- **`updated`·`verified` 를 형식적으로 올리지 않는다.** 실제로 다시 확인했을 때만 `verified` 를 갱신한다.
- **lint 는 보고까지** — 자동 수정하지 않는다. 수정은 사람 승인 후.
- 페이지가 틀린 것으로 드러나면 지우지 말고 `claim_state: superseded` + 대체 페이지 링크(이력은 git 이 기억한다).
