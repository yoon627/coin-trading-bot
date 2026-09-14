---
title: backtest-ui-exit-params — 백테 UI 에 보유상한·트레일링 arm·마켓 필터 입력 노출 (#32)
status: done
started: 2026-09-15
updated: 2026-09-15
---

# Goal

`BacktestPage` 가 `strategy/ticker/days/chart_exit_enabled` 만 보내 `BacktestRequest` 의 `max_hold_days`·`trailing_arm_pct`·`use_market_filter` 를 화면에서 바꿀 수 없다(#32). 입력 3개를 노출해 보유지평·arm 조합을 화면에서 비교할 수 있게 한다.

# Progress

- 2026-09-15 — worktree 생성(base `main@f6e831f`). 서버는 이미 세 필드를 받고 미전송 시 라이브 설정으로 폴백한다(`StrategyController.runBacktest`).
- 2026-09-15 — 구현, babel 파싱 통과, code-reviewer(+codex) Major 1 fix. 커밋 후 로컬 ff-merge.

# Next

없음 — 로컬 ff-merge 로 종결. 배포 후 백테 페이지 렌더 1회 확인(Acceptance 3).

# Decisions

## 1) 비워두면 전송하지 않는다

서버 폴백이 "라이브 설정과 정합"(#27)이라 빈 입력은 키 자체를 빼서 그 폴백을 그대로 탄다. `0` 을 보내면 상한 1일로 coerce 되는 등 의미가 바뀌므로 빈 문자열은 undefined 로 만든다. 마켓 필터는 체크박스라 항상 보내되 기본 off(라이브 매수 경로에 MA50 필터가 없어 서버 기본도 false).

## 2) JSON 키는 snake_case

Jackson SNAKE_CASE — 기존 주석 그대로. `max_hold_days`·`trailing_arm_pct`·`use_market_filter`.

# Review Disposition (code-reviewer + codex, 2026-09-15)

- **fix** 보유상한을 `parseInt` 로 절삭·서버 `coerceIn(1,365)` 로 바꾸는데 헤더는 원문 표시 → 클라에서 한 번 정규화(반올림·1~365)한 값을 전송·표시에 함께 사용. arm 은 서버 400 이 토스트로 드러나 그대로.
- **fix** 스페이싱 불균일(기간 20→14).
- **defer** `validity.badInput` 미구분(브라우저가 거부한 입력이 ''→라이브 폴백) — 기존 입력들과 같은 부채, 별도.
- **wontfix** a11y label 미연결 — 파일 전반의 기존 관례, 이번만 고치면 불균일.
- **판정** README: 폼 필드를 열거하지 않고 마켓 필터는 이미 서술 → 갱신 불필요. wiki: `tide-app` 을 sources 로 가진 페이지 0건.

# Acceptance

1. 입력 3개가 폼에 있고, 값이 있을 때만 snake_case 키로 요청에 실린다 — 코드 정적 확인 + `screens.jsx` babel react preset 파싱 통과.
2. 결과 헤더에 실행 조건(보유상한·arm·필터)이 표시된다 — 정적 확인.
3. 렌더는 로컬 미검증(Postgres 없이 기동 불가) — 배포 후 백테 페이지 1회 확인.

# Key Files

- `bot/src/main/resources/static/tide-app/screens.jsx` — `BacktestPage`

# Blockers

없음.
