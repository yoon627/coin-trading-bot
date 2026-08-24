---
title: lesson — 한 곳에서 통과한 검증을 일반화하지 말 것 (SG 단일 IP · 코드 분기)
category: decision
created: 2026-07-28
updated: 2026-08-24
claim_state: current
verified: 2026-08-24 — 코드 분기 사례를 `TradingState.kt:95-116`(`resuming` 참조 5곳) 원문으로 확인. SG 단일 IP 사례는 2026-07-28 `docs/lessons.md`(2026-05-30 항목, 원본 커밋 331426f) 이관분 유지
sources:
  - docs/lessons.md
  - deploy/aws/deploy.sh
  - bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt
---

# lesson: 한 곳에서 통과한 검증을 일반화하지 말 것

**언제**: 2026-05-30

## 증상

배포를 마치고 "동작 확인됨"이라고 보고했는데, 사용자가 "URL 접속이 안 된다"고 알려왔다.

## 원인

검증을 **setup 을 수행한 그 머신에서만** 했다. 보안그룹이 그 머신의 공인 IP **/32 단일**로 잠겨 있어서, 폰·다른 PC·VPN 등 다른 네트워크에서는 애초에 도달할 수 없었다. 게다가 브라우저의 HTTPS 자동 업그레이드까지 겹쳐 접속이 더 막혔다.

`curl` 로 200 을 받은 것은 사실이지만, 그건 **한 지점에서의 사실**이었다.

## 현재 스택에서는

당시의 "SG 를 열어라 / `http://IP:포트` 로 접속하라" 안내는 **지금 구성에 맞지 않는다.** `deploy/aws/deploy.sh` 기준으로:

- **SSH(22)만** setup 머신 `/32` 로 제한된다.
- **앱은 443**(Caddy HTTPS)이고 기본이 `0.0.0.0/0` 이다 — `APP_ALLOW_CIDR` 로 좁힐 수 있다.
- **8080 은 호스트에 노출되지 않는다.** 컨테이너 내부 포트라 `http://IP:8080` 은 애초에 닿지 않는다.

따라서 지금 배포를 안내할 때는:

1. **HTTPS 도메인**을 준다(`http://IP:포트` 아님).
2. `APP_ALLOW_CIDR` 를 좁혀 놓았다면 그 사실과 갱신 방법을 함께 알린다. 기본값이면 SG 편집이 필요 없다.
3. 그래도 **본인 환경에서 직접 확인**하게 한다 — 이게 이 교훈의 핵심이고 스택이 바뀌어도 남는다([[deployment-stack]]).

이 교훈의 핵심은 SG 규칙이 아니라 **"검증 지점이 하나면 결론도 그 지점에 한정된다"** 는 것이다. 같은 뿌리에서 [[lesson-secure-cookie-http]](curl 은 통과, 브라우저는 실패)가 나왔다.

## 같은 실수, 이번엔 코드 분기에서

**언제**: 2026-08-23

검증 지점은 머신이나 네트워크만이 아니다. **조건식의 분기 하나만 확인하고 규칙을 일반화한 것도 같은 실수다.**

매도 기록의 전략 귀속을 소급 복구하면서([[persistence-schema]]) "한 포지션에 엔진 매수가 여러 번이면 어느 전략이 남는가"를 정해야 했다. 근거는 `TradingState.markBought`(`:95`, `:109`)의 한 줄이다.

```kotlin
val resuming = position
// ...
entryStrategy = if (resuming) entryStrategy ?: strategy else strategy
```

나는 `resuming = false` 경로만 짚어보고 "항상 나중 전략으로 덮인다"고 결론지어 backfill 을 **마지막 BUY** 기준으로 짰다. 그 전에 이미 "코드로 검증했다"고 보고한 뒤였다.

그러나 `resuming = true` 경로가 실재한다 — 재시작 후 `syncPosition` 이 `position = true` 로 만든 뒤 `reconcilePendingBuy`·`BalanceRecovery` 가 `completeBuy` 를 부르는 경우다. 그때는 `?:` 때문에 **먼저 찍힌 전략이 살아남는다.** 규칙은 "마지막"이 아니라 **"포지션 구간 내 첫 번째 non-manual BUY"** 였고, pre-push codex 가 P1 으로 잡아낼 때까지 규칙은 세 번 교정됐다.

가장 위험한 대목은 **결과가 우연히 같았다**는 것이다. 운영 데이터는 포지션당 엔진 BUY 가 1건뿐이라 어느 규칙으로 돌려도 집계가 `combined` 29 / `rsi_bounce` 1 로 나왔다. 잘못된 근거가 맞는 숫자를 내면 검증된 것처럼 보인다 — 여기서는 숫자가 아니라 **규칙**이 산출물이었으므로 결과 일치는 검증이 아니었다.

**적용**: 조건식을 근거로 "항상 ~이다"라고 쓰기 전에 **그 식의 모든 분기에 도달하는 호출 경로를 세어본다.** 도달 불가를 주장하려면 호출부 전수로 보여야 한다. 실제로 같은 함수의 `position && !replace` 가지는 프로덕션에서 도달하지 않는데(`completeBuy` 가 항상 `replace = true`), 그 역시 호출부를 전수로 확인해야만 말할 수 있는 사실이다.
