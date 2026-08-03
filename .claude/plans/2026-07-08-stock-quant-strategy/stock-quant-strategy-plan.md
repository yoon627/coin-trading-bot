---
title: stock-quant-strategy — KIS 주식 자동매매 차트 기반 퀀트 전략 레이어 (백테스트 입증 → 점진 실거래)
status: in_progress
started: 2026-07-08
updated: 2026-08-03
---

# Goal

stock-bot-kis 기반(KIS client·WAL 주문·자율엔진 MVP) 위에 차트(기술지표) 기반 퀀트 전략 레이어를 구축해 KIS 주식 자동매매의 지속 가능한 수익 구조를 만든다. **철칙: 비용(수수료·세금·슬리피지) 반영 백테스트로 입증되기 전에는 어떤 전략도 live 로 가지 않는다.**

# Progress

- 2026-07-08: plan 작성(구현 미착수). 설계는 메인 세션 인라인 — 멀티에이전트 design 패널(3안 경쟁+심사)은 세션 쿼터 소진으로 3회 실패, 인접 사실(stock-bot-kis plan·전략 인터페이스·BacktestEngine 존재)은 코드·plan 문서로 확인.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — **major 2건**: ① 실행 모델 충돌: "일봉 확정 신호→익일 실행"은 현 KisStockTradingEngine 의 장중 tick 루프와 구조가 다름 → Phase 1 에 스케줄 기반 실행 상태 머신 재설계 추가. ② 생존 편향: 현재 상장 종목으로 유니버스를 짜면 상폐 제외로 성과가 구조적으로 부풀려짐 → ❌미확정에 상폐 이력 확보 추가 + 불가 시 한계 정량화 규칙. BacktestEngine 이 단일티커·단일포지션·all-in 구조(코드 실측)라 Phase 0 규모 M→L 재산정. KOSPI 지수 API 경로·배당 조정 추가. status: blocked(base 미확정) 정정.
- 2026-08-03: clean 상태와 `origin/main`을 대조해 stock-bot-kis 기반 커밋 `49573b3`, 완료 기록 `ad2961e`, 후속 수정 `bbf374e`가 모두 `origin/main`의 조상임을 확인. 강제 reset/rebase/push 없이 `origin/main`을 일반 merge해 `20a67f1`에 기반을 맞췄고, base blocker를 해소해 status를 `in_progress`로 전환.
- 2026-08-03: 병합된 stock-bot-kis plan D23을 반영해 **KIS는 주문·잔고·체결 전용, 연구 데이터는 KRX 원천 기반 point-in-time append-only 저장소**로 분리하는 결정을 채택. 공식 KIS 샘플은 `FHKST03010100`이 호출당 최대 100건이며 `FID_INPUT_DATE_1/2` 날짜 창을 직접 받는다고 명시하고, `FID_ORG_ADJ_PRC`는 `0=수정주가`, `1=원주가`로 정의한다. 따라서 일봉 연속조회는 `tr_cont`가 아니라 날짜 창을 뒤로 이동하는 collector로 설계하며, KIS 현재 종목마스터/상장일정 API만으로 과거 상폐 종목·point-in-time 유니버스를 보장할 수 있는지는 미확정으로 남긴다.
- 2026-08-03: collector를 테스트 우선으로 구현하고 조정주가 플래그·중복 제거·최신순 정렬·빈/희소 날짜창의 과거 이동을 mock으로 검증했다. 1차 code review의 sparse-window P2를 창 경계 이동으로 수정했고, 수정 후 targeted code review에서 확정 버그 없음 판정을 받았다.
- 2026-08-03: JDK 21 기준 `./gradlew compileKotlin`, `./gradlew test`, `./gradlew build`가 모두 성공했다. 실계정·모의 자격증명, 외부 KRX 데이터 승인, live 전환·실주문은 수행하지 않았다.

# Next

**다음 즉시 액션(승인·자격증명 대기)** — 사용자 승인과 KIS 모의 자격증명이 제공되면 env-gated read-only paper 조회로 실제 최대 lookback·호출 제한·수정주가 corporate action 의미를 측정한다. 승인 전에는 collector를 거래/실주문 경로에 배선하지 않으며, KRX point-in-time 스냅샷·상폐 이력의 수집·보관 범위도 사용자 승인 없이는 확정하지 않는다.

# Decisions

- **v1 접근 = 추세추종·모멘텀 스윙** (기존 자산 재사용 극대화): 스윙 전략 7종(common/strategy)을 주식 일봉 특성(상하한가·갭·거래정지·저유동성)에 맞게 재튜닝 + **시장 레짐 필터**(KOSPI 지수 MA 하회 시 신규 진입 차단) + **ATR 변동성 역비례 포지션 사이징** + 종목 분산 상한. ExitGates(손절·트레일링·보유상한) 재사용. (이유: TradingStrategy 인터페이스·ExitGates 가 이미 있어 검증 루프 완성이 새 전략 발명보다 선행 가치)
- **v2 후보 = 단면(cross-sectional) 모멘텀 로테이션**(유니버스 랭킹 상위 K·주기 리밸런스)은 Phase 2 통과 후 별도 검토. (이유: 포트폴리오 리밸런스 인프라 추가 필요)
- **일봉 확정봉 기준 신호, 익일 실행 — 실행 모델 재설계 포함**: 장 종료 후 확정 일봉으로 신호 산출(배치) → 익일 개장 시 주문 집행하는 **스케줄 기반 실행 상태 머신**이 필요 — 현 KisStockTradingEngine 은 장중 tick 루프 구조라 그대로 못 쓴다(plan-review major, KisStockTradingEngine.kt:79 장중 한정 실측). WAL 주문 경로는 재사용. (신호-실행 분리 이유: ① KIS 분봉 API 당일 한정 — 일중 신호는 과거 검증 불가능한 전략이 됨 ② 기존 블로커 M-E(장중 미완성봉 whipsaw) 구조적 회피 ③ 백테스트-라이브 정합 정확)
- **백테스트 비용 모델 필수 내장**: KIS 위탁수수료 + 증권거래세/농특세(세율 ❌미확정 — Phase 0 확정) + 슬리피지(익일 시초 체결 가정 대비 보수적 bp 가산). 비용 전 성과로는 어떤 판단도 하지 않는다.
- **생존 편향 통제(plan-review major)**: 유니버스를 현재 상장 종목으로만 구성하면 상폐 종목 제외로 백테스트가 상향 편향. KIS 로 상폐 종목 과거 데이터 확보 가능성을 ❌미확정으로 실측하고, **불가 시 한계 정량화 규칙**(결과 해석에 상향 편향 명시 + Phase 2 통과 기준에 보수 마진 가산 + 최근 N년 축소 검증 병행)을 Phase 0 산출물에 포함.
- **유니버스**: 시총·거래대금 필터(예: KOSPI200 구성 또는 일평균 거래대금 상위 N) — Phase 0 데이터로 확정. 저유동성 제외, 관리종목·거래정지 처리 규칙 포함.
- **과최적화 방지 장치를 게이트로**: walk-forward(rolling) + out-of-sample 홀드아웃 + 파라미터 안정성(이웃 파라미터 성과 급락 없음) — Phase 2 통과 기준에 명문화.
- ~~**기존 결정: 데이터 소스는 KIS API 한정**~~ **→ 데이터 계층 분리(D23 보정)**: KIS API는 주문·잔고·체결 및 일봉 수집 스파이크의 실행 경계로만 사용하고, 연구용 유니버스·생존편향 통제 데이터는 KRX 원천 기반 point-in-time append-only 저장소로 분리한다(유료 외부 데이터는 추가하지 않음). KIS 공식 샘플 기준 `FID_ORG_ADJ_PRC=0`은 수정주가, `1`은 원주가이며 `FHKST03010100`은 호출당 최대 100건이므로 `tr_cont`가 아닌 날짜 창 pagination을 사용한다. 수정주가가 액면분할·배당락을 모두 반영하는지와 최대 lookback은 실제 paper/real 조회로만 확정한다.
- **유니버스·생존편향**: KIS가 제공하는 현재 종목마스터/상장·권리 일정은 현재 거래 가능 종목과 향후 append 이벤트의 입력으로만 취급한다. 과거 시점 유니버스·상폐 종목을 재구성하려면 KRX point-in-time 스냅샷/상장폐지 이력의 지속 적재가 필요하며, 그 외에는 결과에 생존편향 한계와 보수 마진을 명시한다.
- crypto 백테스트 이슈(#31 config 분리, #33 intraday peak)의 교훈 선반영: 전략 파라미터는 live config 와 분리된 백테스트 입력으로.

# Roadmap (Phase 분해)

| Phase | 내용 | 규모 | 선행조건 |
|---|---|---|---|
| 0 | **데이터·검증 인프라**: KIS 일봉 이력 수집기(100건 날짜창 pagination·유니버스 전 종목·DB 적재·결측/수정주가 처리), KRX point-in-time 유니버스 정의, **주식 백테스트 엔진**(기존 BacktestEngine 은 단일티커·단일포지션·all-in 구조라 멀티종목·포지션 사이징·비용 모델은 사실상 신규 — 재사용은 신호 평가 루프 수준) | **L** | Phase 0 데이터 경계 확정 |
| 1 | **전략 v1 + 실행 모델**: 스윙 7종 주식 튜닝 + KOSPI 레짐 필터 + ATR 사이징 + 분산 상한 + **장마감 후 신호 배치→익일 개장 집행 상태 머신**(WAL 재사용) | M~L | Phase 0 |
| 2 | **검증**: walk-forward + OOS 홀드아웃 + 파라미터 안정성. 통과 기준(초안, Phase 0 후 재확정): 비용 후 OOS 샤프 > 0.8, MDD < 20%, 거래당 기대값 > 왕복비용 3배, 연 거래 ≥ 30(표본), 생존편향 한계 반영 | M | Phase 1 |
| 3 | **모의투자(paper) 운영**: KIS 모의 도메인 4주+ 실운영, 백테스트 대비 슬리피지·체결률 괴리 실측 | S~M | Phase 2 통과 |
| 4 | **점진 실거래**: live-enabled + max-order-amount 소액 → 단계 증액. stock-bot-kis 기존 블로커 4건(계좌 현금예약·시장시간 게이트·REST 폴백 캐시·일봉 whipsaw) 해소 선행 | S | Phase 3 + 블로커 해소 |

# Key Files

- (main) `common/src/main/kotlin/com/trading/common/strategy/` — TradingStrategy·ExitGates·Indicators·전략 7종 (재사용 접점)
- (main) `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — :54,:83,:130 단일티커·all-in 구조(재사용 한계 실측) — 신호 평가 참조용
- (stock-bot-kis) `bot/src/main/kotlin/com/trading/bot/kis/engine/KisStockTradingEngine.kt` — :79 장중 tick 루프(실행 모델 재설계 대상)
- (stock-bot-kis) `bot/src/main/kotlin/com/trading/bot/kis/client/KisClient.kt`, `bot/src/main/kotlin/com/trading/bot/kis/client/KisClientImpl.kt` — `FHKST03010100` 일봉 조회·수정주가 플래그·정규화
- (stock-bot-kis) `bot/src/main/kotlin/com/trading/bot/kis/marketdata/KisDailyCandleHistoryCollector.kt` — 100건 날짜창 pagination 스파이크(거래/실주문 경로와 분리)
- (stock-bot-kis) `bot/src/test/kotlin/com/trading/bot/kis/marketdata/KisDailyCandleHistoryCollectorTest.kt` — 창 이동·중복 제거·결정론 정렬 계약
- `.claude/plans/2026-06-14-stock-bot-kis/stock-bot-kis-plan.md` — 기반 작업의 설계·블로커 원본(status: done)

# Acceptance

- [ ] Phase 0: 유니버스 전 종목의 최대 가용 기간 일봉 DB 적재(결측·수정주가·상폐 처리 규칙 문서화), 주식 백테스트가 고정 입력에 결정론적 재현, 생존편향 한계 정량화 규칙 산출
- [x] Phase 0 스파이크: `FHKST03010100` 100건 날짜창 pagination, `adjusted` 플래그 전달, 중복 제거·최신순 정렬을 mock 기반 테스트로 결정론 검증(실계정 데이터 범위·조정 corporate action 의미는 미검증)
- [ ] Phase 1: 전략 v1 백테스트 실행 가능(신호→익일 시초 체결 시뮬→비용 반영 성과) + 실행 상태 머신 설계 spec
- [ ] Phase 2: 통과 기준 충족 전략 ≥ 1개 — **미충족 시 live 로 가지 않고 전략 재설계로 회귀하는 것이 정상 경로**(실패 은폐 금지)
- [ ] Phase 3: 모의 4주 운영 리포트(백테 대비 괴리 정량화)
- [ ] Phase 4 진입 전: stock-bot-kis 블로커 4건 해소 확인
- [ ] 각 Phase 종료 시 `./gradlew test` green + 이 plan Progress 갱신

# Blockers

- ~~**구현 base 미확정**~~ — **해소(2026-08-03)**: stock-bot-kis 기반 `49573b3` 및 후속 `bbf374e`가 `origin/main`에 포함됐고, 병합 직전 clean 상태의 이 worktree에서 `origin/main`을 일반 merge(`20a67f1`)했다. push/rebase/reset은 하지 않았다.
- ❌미확정(외부 데이터/자격증명 또는 사용자 승인 필요): KIS 일봉의 실제 최대 lookback·paper/real 지원·호출 제한 운용값 / `FID_ORG_ADJ_PRC=0`이 액면분할과 배당락을 각각 어떻게 조정하는지 / KIS 현재 종목마스터·상장일정만으로 과거 상폐 종목과 point-in-time 유니버스를 재구성할 수 있는지 / KRX 원천 스냅샷·상폐 이력의 수집·보관 승인 / KOSPI 업종지수·시장 레짐 입력의 최종 API 계약 / KIS 모의투자 시초가 체결 시뮬 정확도.
- ✅확정(공식 샘플·코드): `FHKST03010100`은 `FID_INPUT_DATE_1/2` 날짜 창을 받고 한 번에 최대 100건, `FID_ORG_ADJ_PRC`는 `0=수정주가·1=원주가`; 현재 `KisClientImpl`은 이 플래그를 올바르게 매핑하지만, 과거 범위 확장을 위해 `tr_cont`가 아닌 날짜창 collector가 필요하다. 근거: [KIS 공식 일봉 샘플](https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py), [KIS 종목정보 파일/가이드](https://apiportal.koreainvestment.com/apiservice-category).

# Review Disposition

| # | 심각도 | 항목 | 처리 |
|---|---|---|---|
| 1 | P2 | 빈/희소 응답에서 현재 창의 `oldest`가 `windowTo`와 같아 과거 봉을 누락할 수 있음 | **fix** — 응답이 과거로 전진하지 않으면 `windowFrom - 1`로 창을 이동하고 회귀 테스트 추가; 수정 후 targeted code review에서 확정 버그 없음 |
