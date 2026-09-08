---
title: tp-sl-grid — 익절×손절 격자 240분봉 라이브 의미론 사전명세 비교 (maxT · 진입일 페어링 · 진입봉 감도)
status: done
started: 2026-09-09
updated: 2026-09-09
---

# Goal

사용자 질문 "9시 전량매도·익절·손절 알고리즘 모두 판단해 달라" 중 **익절·손절**은 올바른 계기로 판정된 적이 없다.
선행 탐색([[parameter-search-2026-09]])은 일봉 계기였고 그 우위는 계기의 산물이었으며([[exit-resolution-verdict-2026-09]]),
7국면 확증([[trailing-arm-finding-2026-09]])에서 익절 off 후보 E 는 생존편향과 분리되지 않았다.

이 작업은 **익절 {3, 5, 8, off} × 손절 {3, 5, 7, 10, off}** 20셀(현행 5/5 포함, 후보 19)을 [[trailing-width-2026-09]] 과 같은 계기
(`LiveSemanticsArm`, 240분봉, 진입·청산 라이브 의미론)·같은 10창에서 **사전명세**로 비교한다. 라이브 파라미터는 바꾸지 않는다.

⚠️ **이것은 확증(confirmatory) 실험이 아니라 "이미 본 10창 위의 사전명세 비교"다.** 10창 중 7창은 트레일링 1.5 를 고를 때 썼고
10창 전부가 트레일링 폭 판정에 쓰였으며, 익절 {3,5,8,off}·손절 {3,5,7,10} 은 51,480좌표 일봉 탐색의 축에 있던 값이다(codex 지적;
손절 off 만 그 축에 없던 새 값). 결과를 보기 전에 규칙을 고정하는 가치는 있지만, 여기서 나온 어떤 셀도 **이 측정만으로 승격되지 않는다** —
승격은 전향 표본(그림자 관측)이 필요하다.

# Progress

- 2026-09-09 — worktree `tp-sl-grid` 생성(base `main@462a657`). plan 초안(사전고정) 작성.
- 2026-09-09 — plan-reviewer(CONDITIONAL, 강한 우려 5) + codex(Critical 2·Major 7) 검토 반영해 사전고정 재작성:
  통계량을 진입일 페어링·전 거래일 이동블록·maxT 로 교체, SL off 열 추가, 편향 방향 정정, 진입봉 손절 감도 팔, 생존편향 진단 4종,
  단순보유 창 분류 실측값 고정(아래 표), "확증 아님" 명시. 리팩터 전 base 에서 `trailing-width-intraday.md` 를 뽑아 보관(md5 `ce9c4f5f…`).
- 2026-09-09 — 기반 코드(결과와 무관): `PairedMaxTBootstrap` + 단위테스트 4건 통과, `LiveSemanticsArm.entryBarStopOnClose`·`Trade.exitOnEntryBar`(기본값 = 종전 동작)
  추가 후 `RUN_TRAILING_WIDTH` 리포트 **바이트 동일** 확인(md5 `ce9c4f5f…` 일치). 본 판정 테스트 `TakeProfitStopLossIntradayTest` 골격 작성.
- 2026-09-09 — plan-reviewer 2차(CONDITIONAL, 강한 우려 4) 반영: 한도봉 TP/SL 단정 → 진단(fixture 에 `open ∉ [직전 low, high]` 일 경계 36건 실측),
  손절 축 양방향 편향(임계선 무슬리피지 체결) 추가, 프로덕션 경로 정정, 판정 규칙 3건 pin, 상수 고정, `Trade.exitBarOpen/exitBarLow` 추가.
  **하네스는 이 시점까지 한 번도 실행하지 않았다** — `bot/build/reports/take-profit-stop-loss-intraday.md` 부재로 확인. 사전고정 커밋 = plan + 하네스.
- 2026-09-09 — **사전고정 커밋 `a04ab34` 뒤 실행.** 배관 단정 전부 통과(20셀×2처리 진입 집합 동일, off 셀 0건, frame 1,500일·결측 0·기준선 1,058건, 한도봉 TP/SL 0건).
  **통과 9셀 / 후보 0**: 익절 8·off × 손절 ≥5 (8셀, 격차/거래 +0.092 ~ +0.388, 동시 하한 전부 > 0, bhNeg 에서 더 큼, LOWO·탑생존자제거·ρ 전부 무해) 는 사전고정 8(e) 로 후보 불가;
  TP5/SLoff(+0.131, 하한 +33.8) 는 진입봉 감도 family 미통과(+138.6 → +47.0) 로 "진입봉-민감". 익절 3·손절 3 은 통과 없이 대부분 열세. L1/L10 열은 결론 불변, 선행정의는 TP5/SLoff 만 미통과(0.057).
  wiki [[take-profit-stop-loss-2026-09]] 기록, index·log·sources 3페이지 verified 갱신. `./gradlew build` 실행 1010 / skip 23 / 실패 0, wiki 검증 3종 통과.
- 2026-09-09 — codex 리뷰가 창 순서 위반(사전고정 4 "시간순")을 잡아 `EXPANSION + TIME_INDEPENDENT` 로 고치고 **같은 규칙으로 재실행**: 판정·통과 집합 불변,
  q 2.655 → 2.644, 하한·구간 소수 둘째 자리 이동(첫 실행 리포트는 scratchpad `tp-sl-run1.md` 로 보관). wiki 서술 오류 4건(선행정의 P·상한 산술 건수·유령 손절 인과·전환 건수) 정정.
- 2026-09-09 — code-reviewer(Major 2·Minor 6) 반영: `LiveSemanticsArmEntryBarTest` 3건(합성 봉 — 첫 시나리오는 이후 봉 108 이 peak 110 의 1.5% 되돌림에 걸려 TRAILING 이 나 109.5 로 수정,
  계기는 맞고 시나리오가 틀렸던 것) 통과, 리포트 열 3건(오버슛 전체/진입봉 이후 분리·frame 문구·지문 없음 명시)·진입봉-민감 대칭 표기로 3차 실행 — 판정·수치 불변.
  최종 `./gradlew build` 실행 1013 / skip 23 / 실패 0, wiki 검증 3종·plan-lint 통과. simplify: 무효 NaN 가드 제거, 지문 hashCode → 문자열, `!!` 없음.

- 2026-09-09 — 종결(`minute-ladder` 브랜치에서). 후속 판별 (a) 를 사다리(240→15→5분봉)로 수행: 통과 9 의 우위는 해상도를 올릴수록 0 으로 수렴, 5분봉 통과 0.
  사다리 리뷰(code-reviewer + codex)가 이 작업의 계기 전제 하나를 반증했다 — `combined` 가 현재가 ≤ 돌파선을 거부해 체결은 항상 봉 시가이므로 진입 봉 저가는 체결 이후이고,
  "진입봉 유령 손절"·감도 family=브래킷 프레이밍은 성립하지 않는다(한쪽 처리). wiki 페이지 답·손절 축·읽는 법·index 정정. 하네스(`PairedMaxTBootstrap`·`LiveSemanticsArm`)는
  그 리뷰에서 검토됐고 240분봉 5셀 격차는 사다리 7b 단정이 ±0.01 로 재현했다. `TakeProfitStopLossIntradayTest` 자체의 별도 코드 리뷰는 없었다.

# Next

없음 — 종결. 후속 판별 (a) 는 `minute-ladder`(plan `2026-09-09-minute-ladder`, wiki [[exit-resolution-ladder-2026-09]])가 15·5분봉으로 수행했다: 5분봉 통과 0, 현행 유지.

# Decisions

## 1) 이 계기의 잔여 편향은 "거의 없음"이 아니라 **넓은 익절·넓은 손절 쪽**이다 (검토로 정정)

처음엔 "익절은 봉 고가, 손절은 봉 저가로 판정하니 편향이 거의 없다"고 적었다. 코드로 보면 틀렸다.

- **트레일링이 전 셀에 공통이고 stale-peak 편향이 살아 있다.** `IntrabarExitModel.evaluate` 순서는 트레일링 → 손절 → 익절이고,
  트레일링은 직전 봉까지의 `armPeak` 으로 판정된다(`LiveSemanticsArm`). 240분봉은 트레일링을 **덜** 걸어 포지션이 라이브보다 오래 산다 —
  오래 살아서 이득인 셀은 **익절 8·off** 다(후보 E 가 D1→일중에서 뒤집힌 메커니즘). 익절 3 은 상대적으로 불리하다.
- **진입 봉 손절은 진입 이전 저가로 판정된다.** 진입가 `max(target, 봉 시가)` 는 봉 안 돌파 시점인데 손절은 봉 전체 `low` 로 본다.
  돌파 봉의 저가는 대개 돌파 **전**이라 유령 손절이 난다. 익절은 대칭이 아니다(봉 고가는 돌파 이후에만 나온다). 방향: **손절 3 < 5 < 7 < 10 < off 순으로 불리**.
- **같은 봉 손절·익절 동시 도달은 손절 우선** — 좁은 손절은 stop, 넓은 손절은 같은 봉에서 TP 로 판정될 수 있어 넓은 손절에 유리.
- **손절 체결가 = 손절선(무슬리피지)** — `IntrabarExitModel` 은 봉 저가가 손절선 아래로 얼마나 관통했든 손절선에 체결한다. 라이브는 첫 통과 tick 에
  시장가로 팔아 손절선 아래에서 체결되므로 이 가정은 **손절이 자주 걸리는 좁은 손절에 유리**하다(2차 검토 실측: 봉당 기대 낙관 상한 SL3 0.235%p vs SL5 0.107%p —
  봉 저가 기준이라 상한이지 기대값이 아니다). 익절도 임계선 체결이지만 라이브는 임계선 위에서 체결되므로 방향이 반대(모델이 익절 이익을 과소, 넓은 익절에 유리).
- 한도봉(09:00)의 TP/SL: 봉 시가가 직전 봉 `[low, high]` 안이면 발동 불가지만, fixture 에는 그 조건이 깨지는 일 경계가 **36건**(최대 +3.75% `p2022h2/KRW-POLYX`
  2022-11-13, −1.37% `p2021h1/KRW-PUNDIX` 2021-04-01) 있다. 발동하면 체결가가 시가가 아니라 임계선이라(익절은 보수, 손절은 낙관) 진단으로 건수·크기를 보고한다.

→ **읽는 규칙(사전고정)**: **익절 축**은 편향이 넓은 쪽 단방향이므로 익절 3 통과는 편향 역방향(강함), 익절 8·off 는 상한이며 이 측정만으로 후보가 되지 못한다(§Acceptance 8).
**손절 축은 양방향 편향**(진입봉 유령 손절 → 좁은 손절에 불리 / 무슬리피지 체결 → 좁은 손절에 유리)이라 **어느 쪽 통과도 단독으로 강하지 않다** —
진입봉 편향은 감도 팔로 브래킷하고, 체결가 편향은 셀별 손절 건수·평균 오버슛(임계선 − 봉 저가)/진입가 로 크기만 가늠한다.

## 2) 통계량 — 진입일 1:1 페어링 + 전 거래일 이동블록 + studentized maxT

선행 정의(청산일 합집합 frame, 관측 격차 재추출 꼬리비율 + Šidák)는 (a) 귀무 중심 p 가 아니라 FWER 를 보장하지 않고, (b) 같은 거래의 두 다리가
다른 날짜 블록에 떨어져 페어링을 깨며, (c) 청산 없는 날이 frame 에서 빠진다(두 검토 공통 지적). 익절·손절만 바꾸면 진입 `(market, entryDate,
entryPrice)` 가 전 셀 동일하므로 거래 단위 1:1 페어링이 가능하다 — 이번 사전고정은 그 구조를 쓴다. 선행 정의는 비교용 열로만 낸다.

## 3) 생존편향은 "방향 의존 실격 규칙"이 아니라 진단 4종 + 익절 8·off 후보 불가로 다룬다

부호세기 규칙은 귀무에서 5셀 중 1셀을 실격시키고(오탐 ≈ 20%), 정작 후보 E 는 잡지 못했다(bhNeg 3/3 양수). 생존편향의 메커니즘은 창의
방향이 아니라 **소수 대폭등 생존자**다. 그래서 (i) 익절 8·off 는 point-in-time 유니버스나 전향 표본 없이는 후보에 올리지 않고, (ii) 전 셀에
진단 4종을 고정 형식으로 낸다(§Acceptance 9). 단순보유 창 분류는 **거래구간(워밍업 50일 제외)** 기준이며 값은 §Acceptance 9 표에 못 박는다.

## 4) 산출물 이름은 브랜치명(`tp-sl-grid`)과 다르게 짓는다

테스트 `TakeProfitStopLossIntradayTest`, 리포트 `take-profit-stop-loss-intraday.md`, wiki `take-profit-stop-loss-2026-09`,
부트스트랩 `PairedMaxTBootstrap`.

## 5) 격자에서 뺀 값의 이유

익절 2·12, 손절 2 는 탐색 축에 있지만 넣지 않는다 — 익절 2 는 트레일 유효 arm(1.523%)과 거의 겹치고, 손절 2 는 240분봉 봉내 레인지 안이라
진입봉 유령 손절이 판정을 지배하며, 익절 12 는 1일 보유에서 off 와 거의 같은 행동이다. **따라서 off 가 이겨도 "8 과 off 사이 어디가 충분한지"는
이 격자가 답하지 못한다.** 프로덕션 경로: 익절 off·손절 off 둘 다 라이브 env 로는 설정 가능하다(`TradingProperties` 는 두 값을 검증하지 않는다).
그러나 `/api/strategy/backtest` 가 두 값 모두 `[0, 100]` 을 강제하므로(`StrategyController`) 1000 으로 배포하면 그 필드를 생략한 백테스트 요청이 400 이 된다 —
어느 쪽이 이겨도 별도 이슈다.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/TakeProfitStopLossIntradayTest.kt` — 사전명세 비교(신규, 작성됨·미실행)
- `bot/src/test/kotlin/com/trading/bot/engine/PairedMaxTBootstrap.kt` + `PairedMaxTBootstrapTest.kt` — 창별 이동블록 draw + 단일단계 maxT(신규, 단위테스트 4건 통과)
- `bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArm.kt` — `entryBarStopOnClose` 옵션, `Trade.exitOnEntryBar`·`exitBarOpen`·`exitBarLow` 필드(전부 기본값 = 종전 동작, 리포트 바이트 동일)
- `bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt` — 계기(무변경)
- `wiki/pages/query/take-profit-stop-loss-2026-09.md` — 결과(신규)
- `wiki/pages/query/trailing-width-2026-09.md` · `exit-resolution-verdict-2026-09.md` · `trailing-arm-finding-2026-09.md` — `LiveSemanticsArm` 을 sources 로 가짐 → verified 갱신

# Blockers

없음.

# Acceptance

**아래 1~12 전부가 결과를 보기 전에 커밋하는 사전고정이다. 실행 후 문구를 고치지 않는다. 배관 단정(7)이 실패하면 배관을 고쳐 같은 규칙으로 재실행하며 규칙을 조정하지 않는다.**

> 실행 결과(2026-09-09, 사전고정 커밋 `a04ab34`, 문구는 위·아래 그대로): 1~7 ✅ 배관 전부 통과(재실행 없음) · 6 통과 9셀 · 8 후보 0(익절 8·off 8셀은 (e), TP5/SLoff 는 (c) 미달) ·
> 9·10·11 리포트에 고정 형식으로 산출 · 12 build 실행/skip 건수는 `# Progress` 마지막 줄. 결론 = "현행 TP5/SL5 유지".

1. **계기** = `LiveSemanticsArm`(진입 장중 돌파 즉시, 청산 240분봉 `IntrabarExitModel`). **기준** = 현행 라이브
   `StrategySearchGrid.currentLivePoint()`(TP 5 / SL 5 / 트레일 1.5 / arm 0 / k 0.5 / h 1).
2. **셀** = 익절 {3, 5, 8, off(=`TAKE_PROFIT_OFF`)} × 손절 {3, 5, 7, 10, off(=1000.0)} 20셀. 후보 family = (5, 5) 를 뺀 **19셀**. 나머지 축 불변. 참고 행 없음.
3. **주 판정 창** = 10창 `BacktestFixtures.TIME_INDEPENDENT + EXPANSION_2020_2023`. yearly·bear 는 19셀 격차 행렬만 진단 표기.
4. **페어링·frame**: 셀과 기준의 거래를 `(market, entryDate, entryPrice)` 로 1:1 join 하고 `Δ = 후보 net pnl% − 기준 net pnl%` 를 **진입일**에 귀속한다.
   frame = 10창 각각의 워밍업(50일) 이후 **240분봉이 하나라도 있는 거래일 전부**(0 기여일 포함; 봉 없는 날은 창별 결측 건수로 보고). 창은 시간순으로 이어붙이되
   블록은 **창 경계를 넘지 않는다**. **기대 상수(fixture 실측, 어긋나면 배관 실패)**: 창당 150일, frame 1,500일, 결측 마켓-일 0, 기준선 거래 1,058건.
5. **재추출** = 창별 stratified 이동블록: 창 w 의 n_w 일에서 길이 L=5 의 겹치는 블록 시작점을 `[0, n_w−L]` 에서 균등하게 ⌈n_w/L⌉ 개 뽑아 이어붙이고 n_w 로 절단
   (n_w < L 이면 창 전체 1블록 — 150일에서는 정확히 30블록으로 타일링돼 절단이 없다). 비순환. B = 20,000, seed 고정. **같은 draw 를 모든 셀·모든 family 가 공유한다.**
   보고 전용(판정 불변): L ∈ {1, 10} 에서의 통과 여부 열.
6. **판정 통계량(주)** = 단일단계 maxT(Westfall–Young; se 는 재추출마다가 아니라 상수). 셀 c 의 관측 격차 G_c = ΣΔ, 재추출 합 S_c^b,
   중심화 D_c^b = S_c^b − mean_b(S_c^b), se_c = sd_b(S_c^b), T_c^b = D_c^b/se_c, q = 19셀 family 의 `max_c T_c^b` 95% 분위(정렬 index ⌊0.95·B⌋).
   **통과** = `G_c/se_c > q` (동치: 동시 95% 하한 `G_c − q·se_c > 0`). se_c = 0(기준과 완전 동일 행동)이면 그 셀은 max 에서 제외하고 미통과·"동일 행동" 표기.
   보고값: G_c, 격차/거래, se_c, T_c, 동시 95% 하한, 한계 단측 p_c = (#{D_c^b ≥ G_c}+1)/(B+1). **한계 p 는 판정에 쓰지 않는다.**
   단일 배열 구간(§8d·§9i) = 재추출 합의 2.5/97.5 백분위를 관측 격차에 재중심화한 `[G + q₂.₅(D), G + q₉₇.₅(D)]`.
   비교용 열: 선행 정의(청산일 합집합 frame, 길이 1 iid 재추출 꼬리비율 `P(G≤0)`, `DateBlockBootstrap.of` — 이름과 달리 블록이 아니다).
7. **배관 단정(실패 = 배관 수정 후 같은 규칙으로 재실행)**: (a) 20셀 × 2처리 전부 `(market, entryDate, entryPrice)` 집합이 같은 처리의 기준과 동일(키 중복 없음),
   (b) 익절 off 셀의 TAKE_PROFIT 0건, 손절 off 셀의 STOP_LOSS 0건, (c) 4 의 기대 상수, (d) 거래 지문(창·마켓·진입일·청산일·청산가·사유 문자열 집합)이 같은 셀은 "동일 행동" 으로 묶어 표기.
   **진단(단정 아님)**: 한도봉(진입 다음 날 첫 봉)에서 난 TP/SL 건수와 `Σ(봉 시가 − 체결가)/진입가`(시가 체결이었다면의 차이) 를 셀별 보고.
8. **후보(사람 승인 대상) 조건 — 전부 만족**: (a) 6 의 통과, (b) 격차/거래(1,058건 전체 평균) ≥ 0.10%p(왕복수수료 1회분 — 익절·손절이 실제로 청산가를 바꾸는 거래는
   각 8% 안팎이라 이는 영향 거래당 약 +1.2%p 를 요구한다; 통과 0 은 효과 부재가 아니라 검정력 부족일 수 있다), (c) **진입봉 감도 family**(§10)에서도 통과,
   (d) §9 (i) 의 bhNeg pooled 격차 구간(6 의 정의) **상한** ≥ 0 (하락 창에서 유의하게 지지 않는다), (e) **익절 8·off 셀은 후보 불가** — 통과해도 "전향 검증 필요" 로 표기(Decisions 1·3).
   후보가 여럿이면 **동시 95% 하한(격차/거래 기준)** 이 큰 순, 동률이면 현행과의 축 스텝 거리(이 격자 축 `{3,5,8,off}`·`{3,5,7,10,off}` 인덱스 기준) 오름차순, 그래도 같으면
   익절 인덱스 오름차순으로 보고하고 점추정 최대를 고르지 않는다(winner's curse — 선택된 셀의 점추정은 기대 이득이 아니다).
   **통과 셀이 0 이면 결론은 "현행 TP5/SL5 유지 — 이 격자·계기에서 바꿀 근거 없음"** 이며 창 재분할·yearly 승격·창별 부호검정 등 사후 재슬라이스를 하지 않는다.
9. **생존편향 진단(전 셀, 고정 형식)**: 단순보유 = 거래구간(시간순 index 50 시가 → 마지막 종가) 로스터 중앙값, 실측값(2026-09-09, `bh_median.py`):

   | 창 | 단순보유 중앙값 % | 분류 |
   |---|---|---|
   | bull | −4.5 | bhNeg |
   | p2024h2 | +30.7 | bhPos |
   | p2025h1 | +4.1 | bhPos |
   | p2020h1 | +90.4 | bhPos |
   | p2020h2 | +215.1 | bhPos |
   | p2021h1 | −34.4 | bhNeg |
   | p2021h2 | −31.4 | bhNeg |
   | p2022h1 | −28.6 | bhNeg |
   | p2022h2 | +10.4 | bhPos |
   | p2023h1 | +19.8 | bhPos |

   (i) bhPos·bhNeg 각각 pooled 격차/거래 + 같은 draw 의 구간(6 의 정의), (ii) 창 하나씩 제외(leave-one-window-out) 시 pooled **격차/거래**의 최소값과 그 창,
   bhNeg 4창 안에서도 같은 LOWO(bull 이 경계값 −4.5 라 게이트 (d) 가 한 창에 민감함을 드러내기 위해),
   (iii) 창별 단순보유 최고 마켓 제거 후 pooled 격차/거래, (iv) (market, window) 80점의 단순보유 수익률 ↔ 그 마켓-창 **격차 합** Spearman ρ.
10. **감도 family(판정 아님·표기)**: `entryBarStopOnClose=true` — 진입 봉의 **손절 게이트만** 봉 저가 대신 **종가**로 판정(발동 시 체결가는 손절선; 진입 봉은
    `armPeak = 체결가` 라 트레일링 무발동, 익절은 봉 고가 그대로). **기준선도 같은 처리로 돌린 것과 페어링한다.** 19셀에 같은 maxT 를 적용해 통과 여부를 병기한다.
    주 family 와 감도 family 의 통과가 다르면 "진입봉-민감". 주 family 에서 셀별 **진입 봉 STOP_LOSS 건수·pnl 기여**, **유효 N**(기준과 청산가가 다른 거래 수),
    **STOP_LOSS 건수·평균 오버슛 (손절선 − 봉 저가)/진입가**(무슬리피지 체결 편향의 상한) 를 함께 보고한다.
11. **보고 형식(고정)**: (1) 익절 행 × 손절 열 4×5 행렬 — Σpnl, 격차/거래, 통과 표시, (2) 19셀 전부의 6·8·9·10 값 표(통과분만 싣지 않는다), (3) 10창 × 19셀 격차 행렬과
    **최악 창** = min(창 격차/창 페어 수)·동률은 창 순서, (4) 20셀 청산 사유 구성 + 한도봉 진단, (5) yearly·bear 19셀 격차/거래 행렬, (6) 사전고정 커밋 sha 는 **wiki 페이지**가 인용한다.
12. `./gradlew build` 통과(실행/skip 건수 기록) + wiki 검증 3종 + `TradingProperties`·`deploy/` diff 0 + `LiveSemanticsArm` 변경 후
    `RUN_TRAILING_WIDTH` 리포트 md5 = `ce9c4f5f066c6e3cd640fbda2b0d59b2`(base 에서 생성한 값; 보관본 없이도 재생성 후 md5 로 비교 가능).
   단순보유 표는 `bh_median.py` 가 아니라 정의(시간순 index 50 시가 → 마지막 종가, 로스터 중앙값)로 재현한다 — 스크립트는 repo 에 없다.

# Review Disposition

| # | 출처 | finding | 처분 |
|---|---|---|---|
| 1 | codex Major | 창을 시간순으로 이어붙이라는 사전고정 4 와 달리 구현이 2023~2025 창을 앞에 둠(블록은 창을 안 넘지만 seed 배정이 달라짐) | **fix** — 배관 실패로 취급, 순서를 `EXPANSION + TIME_INDEPENDENT` 로 고쳐 같은 규칙으로 재실행 |
| 2 | codex Major | wiki "선행 정의로 통과 9셀 전부 P ≤ 0.011" — TP5/SLoff 는 0.060 | **fix** — 정정 |
| 3 | codex Major | 익절 편향 상한 산술에 다른 family 의 93건 혼입(기준 TAKE_PROFIT 은 86건) | **fix** — 86건·−138%p 로 정정, 최악/최선 양끝을 명시 |
| 4 | codex Major | "유령 손절" 인과 단정 — `exitOnEntryBar` 는 같은 봉 청산만 뜻하고 저가가 진입 전인지 모른다 | **fix** — "순서 가정에 민감" 으로 서술 축소 |
| 5 | codex Minor | 86건 → 75 TRAILING + 11 TIME_EXIT 로 갈림 / L1·L10 은 draw 공유 불가 / "익절 3 전부 열세" 과장 / index "10창 전부" 과장 / 열 이름 `P(G≤0)` → `P(S*≤0)` | **fix** — 전부 반영 |
| 6 | code-reviewer Major | `entryBarStopOnClose`(후보 게이트 8c)에 단위테스트 0건 | **fix** — `LiveSemanticsArmEntryBarTest` 3건(유령 저가 → 저가 판정 SL/종가 판정 TIME_EXIT · 저가가 손절선 위면 두 처리 동일 · 동시 도달 시 저가=SL/종가=TP, 진단 필드는 원본 저가) |
| 7 | code-reviewer Minor | 진입봉-민감 표기 비대칭 / 오버슛에 진입봉 손절 혼입 / 창별 stratified 라 창 간 분산 미재추출 / `align` KDoc·오류메시지 / "손절 3 어느 처리" 과장 / frame 문구·지문 없음 미표기 | **fix** — 대칭 표기, 오버슛 전체/진입봉 이후 분리, wiki·리포트 한계 명시, 오류메시지, 문구 정정 |
| 8 | code-reviewer PLAUSIBLE | 창 간 분산이 se 에 없어 통과가 자유주의적일 수 있음(사전고정 5 의 stratified 규칙 자체) | **defer** — 규칙 위반 아님. 다음 사전고정에서 창 단위 재추출 감도 열 검토(#Deferred) |

# Deferred

- ⏳ 선행 판정(`exit-resolution-verdict`·`trailing-arm-finding`·`trailing-width`)의 통계 정의(청산일 frame 꼬리비율+Šidák)는 그대로 남아 있다 — 이번 정의로 재실행하려면
  각각 새 사전고정이 필요하다. (중간)
- ⏳ **익절 축 해상도 판별** — 15분봉 fixture(10창 × 8마켓 × 150일 × 96봉 ≈ 5,800요청) 재수집 후 익절 {5, 8, off} 만 같은 사전고정으로 재판정. 해상도가 오를수록 우위가
  줄어드는 폭이 곧 stale-peak 편향의 크기다. 진입봉 유령 손절도 같은 데이터로 대부분 사라진다(손절 축 재판정 가능). (높음)
- ⏳ **라이브 가상 보유 관측** — `ShadowExitObserver` 를 확장해 라이브 익절(5%) 청산 뒤에도 09:00 경계까지 tick 으로 익절 8·off + 트레일링 1.5 의 가상 청산가를 기록.
  라이브 무변경, 코드 필요. 익절 축의 tick 해상도 증거를 전향으로 쌓는 유일한 경로. (중간)
- ⏳ `IntrabarExitModel` 한도봉 TP/SL 체결가가 시가가 아니라 임계선 — 이번 데이터에서는 0건이라 영향 없음. 백테 공용 코드라 골든 재생성이 따르므로 별도 작업. (낮음)
- ⏳ 창별 stratified 재추출은 창 간(국면) 변동을 se 에 넣지 않는다 — 다음 사전고정에서 창 단위(cluster) 재추출을 감도 열로 병기. (중간)
