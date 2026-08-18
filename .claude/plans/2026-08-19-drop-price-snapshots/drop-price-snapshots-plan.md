---
title: drop-price-snapshots — price_snapshots 테이블 제거 (dead-path-cleanup 2단계)
status: in_progress
started: 2026-08-19
updated: 2026-08-19
---

# Goal

`price_snapshots` 테이블을 DROP 한다. 1단계(PR #93)에서 watchlist 를 메모리 스냅샷 + `market_tickers`/`market_candles` 로 옮기고 `PriceCollector` 를 삭제해 **쓰기·읽기가 모두 없어진** 상태다.

# Progress

- 2026-08-19: 1단계 배포(main `dd0783d`)가 3 job 전부 success 로 안정된 것을 확인하고 2단계 착수. 코드 참조 0건 확인(남은 언급은 주석 3곳뿐). V19 작성 후 **실제 Postgres 16 에 V1~V19 전체를 순서대로 적용해 검증** — 테이블·인덱스 소멸, 나머지 테이블 보존. 641 tests green.

# Next

PR 생성·머지 → 배포 관찰. 배포 후에는 `price_snapshots` 가 운영 DB 에서도 사라진다.

# Decisions

## 왜 지금인가 (2단계 분리의 이유)

배포 롤백(`.last-good-sha`)이 실제 운영 절차라, 1단계와 함께 DROP 했다면 롤백한 구 이미지가 없는 테이블을 조회해 watchlist 가 비고 에러가 쌓였을 것이다. 1단계가 배포·안정된 지금은 롤백 대상 이미지도 이 테이블을 쓰지 않으므로 안전하다.

## 되돌릴 수 없다

`DROP TABLE` 은 데이터를 지운다. 다만 이 테이블은 **7일 보존**이었고 1단계 배포 이후로는 새 데이터가 쓰이지 않으므로, 남아 있는 것은 그 시점 이전의 만료 예정 스냅샷뿐이다. 되살릴 가치가 없다.

## 기존 마이그레이션은 건드리지 않는다

`V7__create_price_snapshots.sql`·`V9__add_missing_indexes.sql` 은 적용 완료된 이력이다. 수정하면 Flyway checksum 위반이 된다. V9 의 `idx_price_snapshots_captured_at_only` 는 `DROP TABLE` 로 함께 사라진다.

# Key Files

- `bot/src/main/resources/db/migration/V19__drop_price_snapshots.sql` — 신규(이 작업의 전부)
- `bot/src/main/resources/db/migration/V7__create_price_snapshots.sql`, `V9__add_missing_indexes.sql` — 이력, 수정 금지

# Blockers

(없음)

# Acceptance

- [x] **마이그레이션 실적용**: Postgres 16 컨테이너에 V1~V19 를 순서대로 적용 — 전부 성공
- [x] **테이블·인덱스 소멸**: `information_schema.tables` 0건, `pg_indexes` 의 `%price_snapshot%` 0건
- [x] **다른 테이블 보존**: `market_tickers`·`market_candles`·`trading_states`·`stock_position_state` 존재 확인
- [x] **코드 참조 0**: `price_snapshots`/`PriceSnapshot` 참조가 주석 3곳과 이력 마이그레이션 2개뿐
- [x] **빌드·테스트**: JDK 21 `./gradlew build` — 641 tests, 0 failures
- [ ] **배포 반영**: 머지 후 CI/CD 관찰

# Review Disposition

codex code-review (2026-08-19, effort=medium) — P0 0 / P1 2 / P2 3 / P3 1, 미해결 0.

| # | finding | 처분 |
|---|---|---|
| P1-a | 1단계가 실제 운영에 배포됐다는 전제를 저장소만으로는 검증 못 함 | **확인 완료** — CI/CD run `31504885143`(head `dd0783d`)의 `deploy-vultr` job 이 `success`. codex 는 sandbox 라 GH API 를 못 봤을 뿐 |
| P1-b | V19 를 미머지 브랜치가 선점했을 수 있음 | **확인 완료** — 열린 PR 0개, `git ls-remote` 의 모든 원격 브랜치에 `V19__` 파일 없음 |
| P2-a | 문서가 한쪽은 V18, 다른 쪽은 V19 라 stale | **fix** — `PROJECT_ANALYSIS.md:11,119`·`README.md:119`·`persistence-schema.md` 제목의 `V1~V18` → `V1~V19` |
| P2-b | `DROP TABLE` 의 `AccessExclusiveLock` 대기로 기동 health check 지연 가능 | **fix(문서)** — 마이그레이션 주석에 명시. 쓰는 쪽이 이미 없어 장기 트랜잭션이 이 테이블을 물고 있을 가능성은 낮다 |
| P2-c | 운영 DB 의 수동 생성 view/function 의존은 저장소로 검증 불가 | **wontfix(설계)** — 그래서 `CASCADE` 를 쓰지 않는다. 의존 객체가 있으면 조용히 지우는 대신 DROP 이 실패하는 편이 안전하다. 주석에 근거 명시 |
| P3 | 주석에 남은 `price_snapshots` 언급이 검색에서 잔여 참조로 보임 | **wontfix** — "왜 KST 로 맞추나"·"왜 0.0 으로 메우나"를 설명하는 맥락이라 지우면 의도가 사라진다 |

# Deferred

(없음)
