---
id: 2026-04-28-upbit-errors-4xx
title: Upbit API 에러를 깔끔한 4xx로 노출 (no_authorization_ip 포함)
status: completed
created: 2026-04-28
updated: 2026-04-28
---

# Goal
현재 `UpbitClient`가 throw하는 `UpbitApiException`은 컨트롤러에서 잡히지 않고
`SafeErrorAttributes` 정책상 message가 stripped된 채로 raw 500이 클라이언트에
전달된다. 사용자는 "그냥 안 되네" 외에 진단할 정보가 없다.

오늘 발견된 케이스: Upbit가 `401 no_authorization_ip` (API 키 IP 화이트리스트
미등록)를 반환했지만 FE는 단지 500만 받았다.

이 작업은 `UpbitApiException`을 의미 있는 `ResponseStatusException`으로
변환해서 SafeErrorAttributes의 reason 노출 채널로 사용자에게 actionable한
메시지를 전달한다.

**핵심 제약**: FE `api.js`가 401 응답을 받으면 자동 logout (login.html
redirect)을 트리거한다. Upbit가 반환한 401은 **절대 raw 401로 그대로 전달하면
안 된다** — 모두 400 등 다른 status로 변환해야 한다.

# Acceptance criteria
- [x] `UpbitApiException`이 `statusCode: Int`, `errorName: String?`,
      `errorMessage: String?` 필드 보유 (+ rawBody)
- [x] `UpbitClient.handleError()`가 응답 body를 JSON 파싱해서 위 필드 채우고
      throw (파싱 실패해도 graceful)
- [x] 새 `@RestControllerAdvice`가 `UpbitApiException`을 캐치하여 매핑:
  - 401 + `no_authorization_ip` → **400** + IP 등록 안내 한글 메시지
  - 그 외 401 → **400** + "Upbit 인증 실패 (...)" (FE auto-logout 회피)
  - 429 → **429** + rate-limit 메시지
  - 그 외 4xx → **400** + 일반 거부 메시지
  - 5xx / 그 외 → **502** + "Upbit 일시 장애" 메시지
- [x] 단위 테스트: 위 매핑 모두 검증 (6 tests)
- [x] 컨트롤러 코드는 변경 안 함 (advice가 가로챔)
- [x] `:bot:compileKotlin :bot:test` 풀 통과
- [x] SafeErrorAttributes 정책과 호환 (reason만 노출)

# Plan
- [x] Step 1 — `UpbitApiException` refactor + `handleError()` JSON 파싱
- [x] Step 2 — 실패 테스트 작성 (`UpbitErrorHandlerAdviceTest.kt`, 6 cases)
- [x] Step 3 — `UpbitErrorHandlerAdvice` 구현
- [x] Step 4 — 테스트 통과 확인
- [x] Step 5 — `:bot:test` 풀 통과 + diff 검토
- [x] Step 6 — 커밋

# Progress log
## 2026-04-28 — Started
- Root cause 확인됨: docker logs에 `no_authorization_ip` 반복 발생 (203.251.147.201)
- 코드 인프라 확인: `SafeErrorAttributes`가 ResponseStatusException reason만 노출
  → `UpbitApiException`을 RSE로 변환해야 메시지가 전달됨
- FE constraint 확인: `bot/src/main/resources/static/tide-app/api.js:13` —
  `if (res.status === 401) { location.href = '/login.html' }` →
  Upbit 401을 raw로 전달 금지
- 영향 컨트롤러: PortfolioController, TradingController.getAccount,
  ManualTradeController, StrategyController, MlController — advice로 일괄 처리
  하면 컨트롤러 코드 수정 불필요

## 2026-04-28 — Completed
- `UpbitApiException` 시그니처 변경: (message) → (statusCode, errorName,
  errorMessage, rawBody). retryOnRateLimit 필터도 statusCode == 429로 정밀화.
- `UpbitClient.handleError()`에 `parseUpbitErrorBody` 추가 (Jackson, 파싱 실패 시 null fallback).
- 새 advice `bot/src/main/kotlin/com/trading/bot/api/UpbitErrorHandlerAdvice.kt` —
  `@RestControllerAdvice` + `@ExceptionHandler(UpbitApiException::class)` →
  `ResponseStatusException` 재throw.
- 단위 테스트 `bot/src/test/kotlin/com/trading/bot/api/UpbitErrorHandlerAdviceTest.kt` —
  6 케이스 통과 (401 no_authorization_ip / other 401 / 429 / 4xx / 5xx / unknown).
- :bot 풀 테스트 통과.

# Resume context
- **Branch**: main
- **Uncommitted files**: none
- **Next concrete action**: Step 1 — `UpbitClient.kt` 의 `UpbitApiException`
  data class에 statusCode/errorName/errorMessage 추가하고 `handleError()` 갱신
- **Related commits/PRs**: 없음 (새 작업)
- **Open questions**: 없음 — 매핑 정책은 acceptance criteria로 확정
- **Gotchas**:
  - 401을 raw 401로 노출하면 FE 자동 logout이 트리거됨 → 반드시 400으로 변환
  - Upbit body 파싱 실패해도 안전하게 fallback (statusCode만 있어도 매핑 가능)
  - SafeErrorAttributes는 RSE.reason만 통과시킴, getReason()이 null이면 메시지 없음
