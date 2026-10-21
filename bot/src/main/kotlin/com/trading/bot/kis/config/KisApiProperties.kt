package com.trading.bot.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS OpenAPI 주소와 HTTP·재시도·대기 상한. 배포가 넘기지 않는 값이라 운영에서는 기본값으로 돈다.
 * [KisTradingProperties] 와 같은 이유로 생성 시 값을 검사하지 않는다.
 */
@ConfigurationProperties(prefix = "kis.api")
data class KisApiProperties(
    val paperBaseUrl: String = "https://openapivts.koreainvestment.com:29443",
    val liveBaseUrl: String = "https://openapi.koreainvestment.com:9443",
    val connectTimeoutMs: Int = 5_000,
    val responseTimeoutSeconds: Long = 10,
    // 개인 P, 법인 B.
    val custType: String = "P",
    val tokenRefreshSkewSeconds: Long = 600,
    // 발급 요청이 1분에 1회를 넘으면 EGW00133 으로 막힌다.
    val tokenMinIssueIntervalSeconds: Long = 61,
    // 조회에만 쓴다. 주문은 몇 번이든 다시 보내지 않는다.
    val queryMaxAttempts: Int = 3,
    // 재시도마다 두 배로 늘린다.
    val queryRetryInitialBackoffMs: Long = 500,
    // QUOTE·QUERY 레인의 리미터 대기 상한. 넘으면 그 호출을 건너뛴다.
    val shortWaitLimitMs: Long = 2_000,
    // 보유 시세·주문·reconcile 레인의 리미터 대기 상한.
    val longWaitLimitMs: Long = 30_000,
    // 연속조회를 이 페이지 수 안에 끝내지 못하면 부분 결과 대신 실패로 본다.
    val maxContinuationPages: Int = 50,
)
