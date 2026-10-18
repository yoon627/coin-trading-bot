package com.trading.bot.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

data class TradingState(
    val ticker: String,
    var position: Boolean = false,
    var avgBuyPrice: Double = 0.0,
    /**
     * 봇이 통제하는 수량. Upbit locked 에는 출금 대기·수동 주문도 섞여 있어 free 잔고에 우리 매도 주문이 잠근 만큼만 더한다.
     * 거래소 잔고에서 채울 때는 engine 의 `heldVolume` 만 쓴다.
     */
    var holdVolume: Double = 0.0,
    var peakPrice: Double = 0.0,
    var buyDate: LocalDate? = null,
    var boughtToday: Boolean = false,
    // boughtToday 가 가리키는 거래일. 재시작 후 그 플래그가 오늘 것인지 가리는 근거다.
    var boughtDate: LocalDate? = null,
    var lastTradeTime: LocalDateTime? = null,
    var entryStrategy: String? = null,
    // 주문은 나갔지만 체결 확인이 끝나지 않은 매수. 다음 tick 에 reconcile 한다.
    var pendingBuyUuid: String? = null,
    var pendingBuyStrategy: String? = null,
    // 체결 확인이 끝나지 않은 매도. 잃으면 늦게 체결된 청산이 기록에서 빠지거나 이중 매도된다.
    var pendingSellUuid: String? = null,
    var pendingSellReason: SellReason? = null,
    // 매도 주문 시점의 수량·평단. 재시작 후 syncPosition 이 잔고 0 을 반영하면 이 값 없이는 수량 0 인 유령 SELL 이 기록된다.
    var pendingSellVolume: Double? = null,
    var pendingSellAvgPrice: Double? = null,
    // 보유 여부가 불확실해 신규 매수를 막는다. 잔고 조회가 실패했거나 우리 주문으로 설명되지 않는 locked 잔고가 있을 때다.
    // 그 위에 진입하면 이중 포지션이 된다.
    var unsynced: Boolean = false,
    // pending reconcile 이 계속 실패하면 켠다. 신규 매수만 막는다. 매도·reconcile 까지 막으면 포지션을 청산할 수 없다.
    var halted: Boolean = false,
    var haltReason: String? = null,
    var reconcileFailureCount: Int = 0,
    // 재시작 횟수와 무관하게 미해소 기간을 재려고 카운터가 아니라 시각으로 남긴다.
    var pendingSellSince: Instant? = null,
    // 영속이라 재시작해도 같은 pending 을 다시 알리지 않는다.
    var pendingSellAlerted: Boolean = false,
    // 진입 시점 청산 파라미터. 없으면 전역 설정을 쓴다.
    var exitParams: ExitParamsSnapshot? = null,
    // pending 영속 기록이 실패했다(비영속). 크래시 시 pending 을 잃을 수 있어 신규 진입을 막는다.
    var pendingPersistFailed: Boolean = false,
    // 귀속 불명 locked 경고를 이미 남겼는지(비영속). unsynced 는 조회 실패로도 켜져 dedup 키로 쓸 수 없다.
    var unattributableLockWarned: Boolean = false,
    // wait 중 부분체결 로그의 직전 값(비영속). 값이 바뀔 때만 찍는다.
    var partialSellFillLogged: String? = null,
    var partialBuyFillLogged: String? = null,
    // 신고점 flush 실패(비영속). 고점 갱신 tick 에만 flush 하므로 다음 tick 에 다시 기록한다.
    // 고점 유실은 주문 위험이 아니라 매수는 막지 않는다.
    var peakPersistFailed: Boolean = false,
    // 매수 주문 직전 거래소 보유량. getOrder 장애 시 잔고 복원이 주문 전부터 있던 코인을 체결로 오판하지 않게 한다.
    var pendingBuyPriorVolume: Double? = null,
    // 매도를 결정한 tick 가격. 매도 기록의 price·pnl 기준이다.
    var pendingSellTriggerPrice: Double? = null,
    // 매도 주문 직전 free 보유량. 응답을 못 받은 매도의 체결 흔적 판정 기준이다.
    var pendingSellPriorVolume: Double? = null,
    // dust 보유를 이미 알렸는지(비영속). 포지션이 바뀔 때만 풀린다.
    var dustWarned: Boolean = false,
    // 주문 전에 영속으로 남기는 클라이언트 identifier. 응답 전에 끊긴 주문을 거래소에서 찾는 근거이고 uuid 를 알면 비운다.
    var pendingBuyIdentifier: String? = null,
    var pendingSellIdentifier: String? = null,
) {
    fun hasPendingBuy(): Boolean = pendingBuyUuid != null || pendingBuyIdentifier != null

    fun hasPendingSell(): Boolean = pendingSellUuid != null || pendingSellIdentifier != null

    fun pendingBuyRef(): String? = pendingBuyUuid ?: pendingBuyIdentifier

    fun pendingSellRef(): String? = pendingSellUuid ?: pendingSellIdentifier

    /** 매수 주문을 보내기 전의 의도 기록. 이 상태가 durable 이 된 뒤에만 주문을 보낸다. */
    fun beginBuyOrder(identifier: String, strategy: String, priorVolume: Double) {
        pendingBuyUuid = null
        pendingBuyIdentifier = identifier
        pendingBuyStrategy = strategy
        pendingBuyPriorVolume = priorVolume
    }

    /** 거래소 uuid 를 알게 되면 이후 확정은 uuid 로 한다. */
    fun adoptBuyOrder(uuid: String) {
        pendingBuyUuid = uuid
        pendingBuyIdentifier = null
    }

    fun beginSellOrder(
        identifier: String,
        reason: SellReason,
        since: Instant,
        volume: Double,
        triggerPrice: Double,
        priorVolume: Double,
    ) {
        pendingSellUuid = null
        pendingSellIdentifier = identifier
        pendingSellReason = reason
        pendingSellSince = since
        pendingSellAlerted = false
        pendingSellVolume = volume
        pendingSellAvgPrice = avgBuyPrice
        pendingSellTriggerPrice = triggerPrice
        pendingSellPriorVolume = priorVolume
    }

    fun adoptSellOrder(uuid: String) {
        pendingSellUuid = uuid
        pendingSellIdentifier = null
    }

    fun clearPendingBuy() {
        pendingBuyUuid = null
        pendingBuyIdentifier = null
        pendingBuyStrategy = null
        pendingBuyPriorVolume = null
    }

    fun pnlPercent(currentPrice: Double): Double {
        if (avgBuyPrice <= 0) return 0.0
        return ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0
    }

    fun dropFromPeakPercent(currentPrice: Double): Double {
        if (peakPrice <= 0) return 0.0
        return ((peakPrice - currentPrice) / peakPrice) * 100.0
    }

    /** 신고점이면 true. 매 tick 이 아니라 갱신 때만 영속 기록하는 데 쓴다. */
    fun updatePeakPrice(currentPrice: Double): Boolean {
        if (currentPrice <= peakPrice) return false
        peakPrice = currentPrice
        return true
    }

    fun resetDaily(tradingDate: LocalDate) {
        // pending 매수는 지우지 않는다. 지우면 미해소 주문 위에 다음 날 다시 매수한다.
        if (boughtDate != tradingDate) boughtToday = false
    }

    /**
     * [replace] 는 거래소 실잔고를 그대로 반영할 때 쓴다. syncPosition 이 이미 채운 포지션에 평균을 내 이중계상되지 않게 한다.
     */
    fun markBought(price: Double, volume: Double, strategy: String? = null, replace: Boolean = false, now: LocalDateTime = LocalDateTime.now(TradingDay.KST)) {
        // 기존 포지션의 연장이면 진입 메타를 유지한다. 신규 진입이 남아 있던 옛 값을 물려받으면
        // 곧바로 보유일 초과나 옛 고점 기준 트레일링으로 청산된다.
        val resuming = position
        if (position && !replace) {
            // 운영 경로는 completeBuy 가 항상 replace=true 라 여기 오지 않는다.
            val totalCost = avgBuyPrice * holdVolume + price * volume
            val totalVolume = holdVolume + volume
            avgBuyPrice = totalCost / totalVolume
            holdVolume = totalVolume
        } else {
            position = true
            avgBuyPrice = price
            holdVolume = volume
            buyDate = if (resuming) buyDate ?: TradingDay.of(now) else TradingDay.of(now)
            entryStrategy = if (resuming) entryStrategy ?: strategy else strategy
            if (!resuming) exitParams = null // 진입 시점 스냅샷은 호출부가 다시 찍는다
        }
        // 당일 1회 진입 게이트. resetDaily 에서만 푼다.
        boughtToday = true
        boughtDate = TradingDay.of(now)
        // 신규 진입이 옛 고점을 물려받으면 첫 tick 부터 트레일링이 걸린다.
        peakPrice = if (resuming) max(peakPrice, price) else price
        lastTradeTime = now
        clearPendingBuy()
        dustWarned = false
    }

    fun markSold(now: LocalDateTime = LocalDateTime.now(TradingDay.KST)) {
        releaseHoldings(now)
        clearEntryMeta()
    }

    /**
     * 보유가 0 으로 보이지만 귀속 불명 락이 풀리면 코인이 돌아올 수 있을 때 쓴다.
     * syncPosition 은 수량·평단만 복원하므로 진입 메타는 남긴다. 다음 신규 진입이 어차피 덮어쓴다.
     */
    fun releaseHoldings(now: LocalDateTime = LocalDateTime.now(TradingDay.KST)) {
        position = false
        avgBuyPrice = 0.0
        holdVolume = 0.0
        lastTradeTime = now
        clearPendingBuy()
        dustWarned = false
        clearPendingSell()
    }

    /**
     * 신규 진입 주문 시점에 옛 진입 메타를 지운다. 거래소에서 직접 청산된 포지션의 잔재가
     * 재시작 후 markBought 의 연장 판정을 타고 새 포지션에 이어지지 않게 한다.
     */
    fun clearEntryMeta() {
        buyDate = null
        peakPrice = 0.0
        entryStrategy = null
        exitParams = null
    }

    fun clearPendingSell() {
        pendingSellUuid = null
        pendingSellIdentifier = null
        pendingSellReason = null
        pendingSellVolume = null
        pendingSellAvgPrice = null
        pendingSellSince = null
        pendingSellAlerted = false
        pendingSellTriggerPrice = null
        pendingSellPriorVolume = null
    }

    fun pendingRef(side: TradeSide): PendingRef? = when (side) {
        TradeSide.BUY -> pendingBuyUuid?.let { PendingRef("uuid", it) } ?: pendingBuyIdentifier?.let { PendingRef("identifier", it) }
        TradeSide.SELL -> pendingSellUuid?.let { PendingRef("uuid", it) } ?: pendingSellIdentifier?.let { PendingRef("identifier", it) }
    }

    /** [releasePending] 이 지우는 필드(키는 `trading_states` 컬럼명). 해제를 되돌리거나 거래 기록을 맞출 근거로 남긴다. */
    fun pendingFields(side: TradeSide): Map<String, Any?> = when (side) {
        TradeSide.BUY -> linkedMapOf(
            "pending_buy_uuid" to pendingBuyUuid,
            "pending_buy_identifier" to pendingBuyIdentifier,
            "pending_buy_strategy" to pendingBuyStrategy,
            "pending_buy_prior_volume" to pendingBuyPriorVolume,
        )
        TradeSide.SELL -> linkedMapOf(
            "pending_sell_uuid" to pendingSellUuid,
            "pending_sell_identifier" to pendingSellIdentifier,
            "pending_sell_reason" to pendingSellReason?.name,
            "pending_sell_volume" to pendingSellVolume,
            "pending_sell_avg_price" to pendingSellAvgPrice,
            "pending_sell_since" to pendingSellSince?.toString(),
            "pending_sell_alerted" to pendingSellAlerted,
            "pending_sell_trigger_price" to pendingSellTriggerPrice,
            "pending_sell_prior_volume" to pendingSellPriorVolume,
        )
    }

    /**
     * 거래소를 확인한 사람이 막힌 pending 을 지운다. 매수는 체결이 남겼을 진입 흔적(전략·매수일·당일 진입)을 대신 남겨야
     * 들어온 코인이 다음 시작에 실리고 보유상한이 걸린다. 매도는 보유 중인데 매수일이 없으면 오늘로 채운다.
     */
    fun releasePending(side: TradeSide, today: LocalDate) {
        when (side) {
            TradeSide.BUY -> {
                entryStrategy = entryStrategy ?: pendingBuyStrategy
                buyDate = buyDate ?: today
                boughtToday = true
                boughtDate = today
                clearPendingBuy()
            }
            TradeSide.SELL -> {
                if (position && buyDate == null) buyDate = today
                clearPendingSell()
            }
        }
        reconcileFailureCount = 0
    }

    fun clearHalt() {
        halted = false
        haltReason = null
        reconcileFailureCount = 0
    }
}

/** [by] 는 `uuid`(거래소가 아는 주문) 또는 `identifier`(응답을 못 받아 클라이언트 식별자만 아는 주문). */
data class PendingRef(val by: String, val ref: String)
