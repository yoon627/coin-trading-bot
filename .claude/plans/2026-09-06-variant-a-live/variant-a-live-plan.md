---
title: variant-a-live — 변형 A 를 라이브에 승격하고 문서 라벨을 정합화
status: done
started: 2026-09-06
updated: 2026-09-06
---

# Goal

사용자 지시("더 벌 수 있게 해줘")로 변형 A(`trailingStopPct 2.0→1.5`, `trailingArmPct 3.0→0`)를
라이브에 적용하고, 그로 인해 어긋나는 문서·코드 라벨을 같은 작업에서 맞춘다.

# Progress

- 2026-09-06 — 적용 전 확인: 청산 게이트가 진입 스냅샷이 아니라 **전역 설정**을 읽으므로(`PositionManager:1036`)
  **열려 있는 포지션에도 즉시 적용**된다. 부팅 경고 조건 둘 다 통과(TP 5.0 > 트레일 1.5, arm 0 은 두 번째 검사 비대상).
- 2026-09-06 — `VULTR_DEPLOY_ENV` 시크릿 갱신 + `workflow_dispatch` 배포. **컨테이너 실측 확인**:
  `TRADING_TRAILING_STOP_PCT=1.5` · `TRADING_TRAILING_ARM_PCT=0`, TP/SL 5.0 유지, 부팅 경고 0, `UP`, ERROR 0.
- 2026-09-06 — 라벨 정합: `StrategySearchGrid.baselinePoint()` KDoc(= 승격 이전 설정임을 명시) + `currentLivePoint()` 추가,
  `TradingProperties` 주석, wiki `trading-engine-loop` 리스크 표(코드 기본값 ≠ 운영값), `trailing-arm-finding-2026-09` 승격 절.

# Next

없음 — PR #176 로 닫혔다. 관측 누적을 지켜본다(아래 Decisions 3).

# Decisions

## 1) 코드 기본값은 옮기지 않는다 — env 가 운영값을 소유한다

`BacktestConfig` 기본값·`legacy-golden.txt` 핀·`TradingProperties()` 기본 생성자를 쓰는 테스트 **76곳**이
2.0/3.0 을 전제한다. 이전은 골든 재생성을 동반한 별도 작업이다. env 오버라이드는 이 repo 가
`tickers`·`maxInvestAmount` 등에 쓰는 정식 경로이며, #75 가 금지한 것은 *배포 계층이 자기 기본값을 갖는 것*이지
운영값을 명시하는 것이 아니다.

**대신 라벨을 고쳤다** — 코드 기본값을 "라이브 현행"이라 부르던 곳(`baselinePoint()` KDoc, wiki 리스크 표)을
전부 "승격 이전"으로 바로잡고 `currentLivePoint()` 를 별도로 뒀다. 그러지 않으면 다음 측정이
옛 값을 라이브로 착각한다 — 이번 세션이 내내 다룬 실패 양식이다.

## 2) `baselinePoint()` 값 자체는 고정한다

공표된 측정 셋(`parameter-search-2026-09`·`exit-resolution-verdict-2026-09`·`trailing-arm-finding-2026-09`)이
전부 이 좌표를 기준선으로 쓴다. 값을 옮기면 그 수치들이 조용히 다른 대조군의 값이 되어 과거와 비교가 끊긴다
(`ORIGINAL_REGIMES`·`PUBLISHED_FOUR` 와 같은 이유).

## 3) 그림자 관측의 역할이 바뀌었다

후보와 라이브가 이제 같은 설정이라 "승격 전 검증"이 아니라 **모델↔현실 상시 감시**다.
사전고정 임계(`N≥30 & 평균 과대추정 상한 < 0.18%p`)는 그대로 건강검진 기준으로 쓰고,
상한을 넘으면 백테가 라이브를 과대평가한다는 뜻이므로 되돌림을 검토한다.

## 4) 모델 검증 전 승격임을 기록한다

원래 계획은 관측 2~3개월 뒤 승격이었고 그 단계를 건너뛰었다. 근거는 사전고정 7국면 결과 하나다.
숨기지 않고 wiki 승격 절에 명시했다 — 나중에 성과가 나빠졌을 때 "무엇을 근거로 켰나"가 남아야 한다.

# Key Files

- `deploy/vultr/.env` → `VULTR_DEPLOY_ENV` 시크릿 (운영값 단일 소스, gitignored)
- `bot/src/test/kotlin/com/trading/bot/engine/StrategySearchGrid.kt` — `baselinePoint()` / `currentLivePoint()`
- `wiki/pages/concept/trading-engine-loop.md` — 리스크 파라미터 표(코드 vs 운영)
- `wiki/pages/query/trailing-arm-finding-2026-09.md` — 승격 절·되돌리는 법

# Blockers

없음.

# Acceptance

1. ✅ 컨테이너에서 `TRADING_TRAILING_STOP_PCT=1.5` · `_ARM_PCT=0` 실측, TP/SL 무변경.
2. ✅ 부팅 경고 0, `UP`, ERROR 0.
3. ✅ "라이브 현행"이라 부르던 라벨이 전부 승격 이전/이후로 구분된다.
4. ✅ 되돌리는 법이 wiki 에 있다(시크릿 두 줄 원복 + 재배포 1회).
5. ✅ `./gradlew build` 통과, wiki 검증 3종 통과.

# Deferred

- **코드 기본값 이전(2.0/3.0 → 1.5/0)** — `legacy-golden.txt` 재생성 + 테스트 76곳 검토 동반. (중간)
- **`ExitParamsSnapshot` 소비** — 지금은 저장만 되고 청산이 전역 설정을 읽어, 파라미터를 바꾸면
  기존 포지션도 즉시 새 규칙으로 청산된다. 진입 시점 규칙을 지키려면 Phase 2 가 필요하다. (중간)
