---
title: lesson — 브래킷·감도 family 는 계기의 체결 규칙을 코드로 확인한 뒤 설계한다 (유령 손절이 없었던 이유)
category: decision
created: 2026-09-09
updated: 2026-09-09
claim_state: current
verified: 2026-09-09 — 사다리 리뷰(code-reviewer)가 `CombinedStrategy.shouldBuy` 의 `currentPrice <= targetPrice` 거부와 `LiveSemanticsArm` 의 `fill = max(target, open)` 을 대조해 확인. codex 가 `pessimisticTrailing` 의 불가능한 경로를 확인, 단위테스트 red → green 으로 수정
sources:
  - common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt
  - bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArm.kt
  - bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArmPessimisticTrailingTest.kt
---

# lesson: 브래킷·감도 family 는 계기의 체결 규칙을 코드로 확인한 뒤 설계한다

**언제**: 2026-09-09 (익절×손절 격자 → 해상도 사다리, [[take-profit-stop-loss-2026-09]] · [[exit-resolution-ladder-2026-09]])

## 증상

두 사전고정 작업이 연달아 "진입 봉 손절은 돌파 **이전** 저가가 만든 유령일 수 있다"는 전제 위에 감도 family(`entryBarStopOnClose`)를 **브래킷**으로 설계했다.
손절 off 우위의 2/3 가 이 family 에서 사라져 "손절 축은 순서 가정에 민감, 미판정"이라고 결론냈다.

리뷰가 코드로 반증했다. `combined` 는 `currentPrice <= targetPrice` 면 거부하므로 `max(돌파선, 시가)` 체결은 시가 ≤ 돌파선인 봉에서 절대 성립하지 않는다.
체결은 항상 돌파선 위에서 **여는** 첫 봉의 시가이고, 진입 봉 저가는 체결 이후다. 유령 손절은 없었다. 감도 family 는 브래킷이 아니라 실재 손절을 지우는 한쪽 처리였다.

같은 리뷰에서 두 번째 브래킷(`pessimisticTrailing`)도 경로 가정과 모순되는 청산을 냈다. "이 봉 고가가 저가보다 먼저"라 가정하면서, 같은 봉에서 익절선을 지난 경우에도
그 고가에서 파생한 트레일링선을 먼저 걸었다. 고가로 가는 길에 익절선이 있으면 익절이 먼저다.

## 원인 (3 Whys)

1. **왜 유령 손절을 전제했나** — 라이브 의미론("돌파 순간 현재가 체결")을 팔의 의미론으로 **가정**했다. 팔의 `fill = max(target, open)` 만 보고, 그 fill 을 받는 `shouldBuy` 가 무엇을 거부하는지 보지 않았다.
2. **왜 두 작업이 연달아 놓쳤나** — 사전고정이 "판정 규칙을 먼저 적는다"에 집중돼, 계기의 **체결 규칙을 한 줄로 적고 코드로 대조하는 단계**가 없었다. plan Decisions 에 "체결가 하한이 돌파선"이라 적혀 있었고 그 문장은 틀렸지만 검증 대상이 아니었다.
3. **왜 비관 브래킷의 모순을 못 봤나** — 브래킷을 "armPeak 하나를 바꾼다"로 구현하고 기존 `evaluate` 의 게이트 순서(TRAILING → SL → TP)를 그대로 썼다. 경로 가정(고가-우선)이 바뀌면 순서도 바뀌어야 하는데, 그 가정 아래 불가능한 조합을 단위테스트로 막지 않았다.

## 잘못된 방법

- 계기 편향의 방향을 라이브 상식으로 추론해 브래킷을 정의한다.
- 브래킷 노브를 기존 판정식의 인자 하나만 바꿔 구현하고, 가정 아래 불가능한 청산 조합(익절선을 지난 봉의 트레일링 우선)을 검사하지 않는다.

## 올바른 방법

- 사전고정 전에 계기의 **체결 규칙을 코드로 확인해 plan 에 한 줄로 적는다**: "어느 가격에(시가 / 돌파선 / 종가), 어느 조건에서(전략의 거부 조건 포함) 체결되는가". 그 문장이 브래킷의 방향(한쪽 / 양쪽)을 결정한다.
- 브래킷마다 경로 가정을 명시하고, 그 가정 아래 불가능한 청산 조합을 **합성 봉 단위테스트**로 막는다(예: 고가-우선이면 "고가 ≥ 익절선인 봉은 TAKE_PROFIT").
- 리뷰 프롬프트에 "계기의 체결 규칙이 문서와 같은가"를 명시 질문으로 넣는다 — 이번에 잡힌 경로가 그것이다.

## 영향

판정은 바뀌지 않았다(한쪽 처리는 통과를 어렵게만 하고, 비관 열은 재실행으로 정정). 바뀐 것은 해석이다 — "손절 축은 미판정"이 "손절 축 우위는 작고 유의하지 않다"로.
