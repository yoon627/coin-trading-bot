package com.trading.bot.kis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS(한국투자증권) OpenAPI 연동 설정. `@ConfigurationPropertiesScan`(com.trading.bot) 으로 자동 등록.
 *
 * 안전 2축(실거래 직행 보완 — plan D5):
 *  - [liveEnabled] = false(기본): placeOrder 를 실제로 보내지 않고 WAL 에 DRY_RUN 으로만 기록.
 *  - 사용자별 `kis_paper`(users 컬럼): 실전(:9443) vs 모의(:29443) 도메인·tr_id 선택.
 *  실거래 = liveEnabled=true AND 사용자 kis_paper=false (둘 다 명시 필요).
 */
@ConfigurationProperties(prefix = "kis")
data class KisProperties(
    val realBaseUrl: String = "https://openapi.koreainvestment.com:9443",
    val paperBaseUrl: String = "https://openapivts.koreainvestment.com:29443",
    val custType: String = "P", // 개인 P / 법인 B
    val connectTimeoutMs: Int = 5000,
    val responseTimeoutSeconds: Long = 10,
    /** 실주문 송신 게이트. false 면 dry-run(주문 미송신). */
    val liveEnabled: Boolean = false,
    /** 단일 주문 명목금액(원) 상한. 0 이하면 무제한. */
    val maxOrderAmount: Long = 10_000_000,
    /** 토큰 만료 이 초 이전이면 갱신(연속 재발급 방지 — KIS 토큰 24h). */
    val tokenRefreshSkewSeconds: Long = 600,
    // reconcile 주기(ms)는 @Scheduled(fixedDelayString) 가 kis.reconcile-interval-ms 를 직접 읽는다(여기 필드 불필요).
    /** SUBMITTING/UNKNOWN 인데 조회 0건일 때 NEEDS_REVIEW 로 올리기까지의 유예(초). */
    val reconcileGraceSeconds: Long = 120,
    /** PLACED 인데 당일조회에 ODNO 가 안 잡힐 때 NEEDS_REVIEW 로 올리기까지의 유예(초). */
    val reconcileStaleSeconds: Long = 1_800,
)
