package com.trading.bot.kis.order

/**
 * WAL 주문 상태기계 (plan D7). non-terminal = reconcile 대상 + (user,exchange,account,symbol) 활성 슬롯 점유.
 *
 * 전이: SUBMITTING → {PLACED | FAILED | UNKNOWN}
 *       SUBMITTING(stale)/UNKNOWN → {PLACED | NEEDS_REVIEW}
 *       PLACED/PARTIAL → {FILLED | PARTIAL | CANCELLED | REJECTED}
 * FAILED 는 브로커 4xx 미접수/로컬검증 실패 확정 시에만(조회 0건은 절대 FAILED 금지 → NEEDS_REVIEW).
 */
enum class StockOrderStatus(val terminal: Boolean) {
    SUBMITTING(false),  // WAL INSERT 됨, placeOrder 진행 전/중
    PLACED(false),      // 접수됨(ODNO 확보), 미체결
    PARTIAL(false),     // 부분체결, 잔량 존재
    UNKNOWN(false),     // 전송 결과 불명 — reconcile 로 접수여부 확인 필요
    NEEDS_REVIEW(false),// 자동판정 불가(조회 0건/모호) — 사람 확인 필요
    FILLED(true),       // 전량 체결
    CANCELLED(true),    // 취소(부분체결 후 취소 포함)
    REJECTED(true),     // 브로커 거부(접수 후)
    FAILED(true),       // 미접수 확정(전송 전 거부)
    DRY_RUN(true);      // dry-run — 실주문 미송신

    companion object {
        val NON_TERMINAL: List<StockOrderStatus> = entries.filter { !it.terminal }
        val NON_TERMINAL_NAMES: List<String> = NON_TERMINAL.map { it.name }
    }
}
