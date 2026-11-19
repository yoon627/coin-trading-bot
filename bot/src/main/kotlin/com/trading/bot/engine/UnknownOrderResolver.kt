package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.OrderLookupException
import com.trading.bot.exchange.UnknownOrderPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** 응답을 못 받은 주문(identifier 만 있음)의 판정. 상태 전이는 호출부가 한다. */
internal sealed interface UnknownOrderVerdict {
    /** 거래소에서 찾았다. [order] 의 uuid 는 비어 있지 않다. */
    data class Found(val order: Order) : UnknownOrderVerdict

    /** 흔적 없는 404 가 끊김 없이 이어져 미접수로 확정했다. */
    data object NotPlaced : UnknownOrderVerdict

    /**
     * 잔고 흔적을 본 주문이 정한 시간이 지나도 identifier 조회와 종료 주문 목록 어디에도 없어 미접수로 확정했다.
     * [freeNow] 는 이번 조회에서 본 free 잔고다(매도 장부를 줄이는 데 쓴다).
     */
    data class TracedNotPlaced(val freeNow: Double) : UnknownOrderVerdict

    /** 아직 판단할 수 없어 pending 을 유지한다. */
    data class Undecided(val reason: Reason) : UnknownOrderVerdict

    enum class Reason {
        /** identifier 조회 또는 종료 주문 목록 조회 실패. uuid 없는 응답도 여기에 든다. */
        ORDER_LOOKUP_FAILED,

        /** 거래소에 없는데 잔고 조회가 실패했거나 비교할 주문 전 잔고가 없다. */
        BALANCE_UNSEEN,

        /** 거래소에 없는데 잔고가 주문 전과 달랐던 적이 있다. 첫 흔적 뒤 정한 시간이 지나기를 기다린다. */
        TRACED,

        /** 잔고 변화 없는 404. 확정 조건을 기다린다. */
        AWAITING_CONFIRMATION,

        /** 거래소에 아직 보이지 않는다. 장애가 아니다. */
        NOT_YET_VISIBLE,
    }
}

/**
 * 응답을 못 받은 주문이 실제로 접수됐는지 identifier 로 판정한다. pending 처리와 저장은 PositionManager 가 한다.
 * 못 찾은 이력은 엔진마다 만드는 이 인스턴스의 메모리에만 있어 재시작·reload 하면 처음부터 다시 센다(더 오래 기다리는 쪽).
 */
internal class UnknownOrderResolver(
    private val upbitClient: ExchangeClient,
    private val clock: Clock,
    private val policy: UnknownOrderPolicy = UnknownOrderPolicy.UPBIT,
    private val displayName: String = "Upbit",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // 못 찾음을 미접수로 읽는 규칙 전체가 이 전제에 기댄다. 엔진 생성이 먼저 거절하고 여기는 마지막 방어다.
        require(policy.supportsClientOrderId) { "$displayName 는 client order id 로 주문을 찾지 못해 미해소 주문을 대사할 수 없다" }
    }

    /**
     * identifier 로 못 찾은 이력. [tracedSince] 는 잔고 흔적을 처음 본 시각이고, 그 뒤로는 조회 실패·잔고 조회 실패·흔적이
     * 사라진 조회가 끼어도 그대로 둔다 — 끊으면 거래소가 불안정한 동안 영원히 확정하지 못한다.
     */
    private data class IdentifierMiss(val misses: Int = 0, val firstMissAt: Instant? = null, val tracedSince: Instant? = null)

    // 확정되지 않고 풀린 항목은 지우지 않는다. 불명 주문 수만큼이라 쌓여도 작다.
    private val identifierMisses = ConcurrentHashMap<String, IdentifierMiss>()

    private sealed interface IdentifierLookup {
        data class Found(val order: Order) : IdentifierLookup

        /** 거래소가 그 identifier 를 모른다. */
        data object NotFound : IdentifierLookup

        /** 조회 자체가 실패해 있는지 없는지 모른다. */
        data object Unknown : IdentifierLookup

        data object NotYetVisible : IdentifierLookup
    }

    /** 잔고 흔적 판정에 쓴 값. [free] 는 지금 free 잔고다. */
    private data class Observed(val traced: Boolean, val free: Double)

    /** 조회·잔고 실패는 [UnknownOrderVerdict.Undecided] 로 돌려주고 `CancellationException` 외에는 던지지 않는다. */
    suspend fun resolve(ticker: String, side: TradeSide, state: TradingState, identifier: String): UnknownOrderVerdict =
        when (val lookup = lookupByIdentifier(ticker, identifier)) {
            is IdentifierLookup.Found -> {
                identifierMisses.remove(identifier)
                UnknownOrderVerdict.Found(lookup.order)
            }
            IdentifierLookup.NotFound -> {
                val observed = when (side) {
                    TradeSide.BUY -> observeBuy(ticker, state)
                    TradeSide.SELL -> observeSell(ticker, state)
                }
                judgeMiss(ticker, side, state, identifier, observed)
            }
            IdentifierLookup.Unknown -> {
                breakMissStreak(identifier)
                UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.ORDER_LOOKUP_FAILED)
            }
            // 거래소가 "없다" 대신 "아직 안 보인다" 고 답했으니 연속 미발견이 아니다.
            IdentifierLookup.NotYetVisible -> {
                breakMissStreak(identifier)
                UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.NOT_YET_VISIBLE)
            }
        }

    private suspend fun lookupByIdentifier(ticker: String, identifier: String): IdentifierLookup =
        try {
            upbitClient.getOrderByIdentifier(identifier)?.let { IdentifierLookup.Found(it) } ?: IdentifierLookup.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: OrderLookupException.NotYetVisible) {
            log.info("identifier lookup for {} order {}: not visible yet ({})", ticker, identifier, e.message)
            IdentifierLookup.NotYetVisible
        } catch (e: Exception) {
            log.warn("identifier lookup failed for {} order {}: {}", ticker, identifier, e.message)
            IdentifierLookup.Unknown
        }

    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    /** 주문 전보다 보유가 늘었는지. 시장가 매수는 접수되면 바로 체결된다. null 이면 판단할 수 없다. */
    private suspend fun observeBuy(ticker: String, state: TradingState): Observed? {
        val prior = state.pendingBuyPriorVolume ?: return null
        return try {
            val account = findAccount(ticker.substringAfter("-"))
            val held = account?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
            Observed(traced = held - prior > VOLUME_EPSILON, free = account?.balanceDouble() ?: 0.0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown buy failed: {}", ticker, e.message)
            null
        }
    }

    /** 주문 전보다 free 가 줄었는지. 매도가 접수됐으면 팔렸거나 주문에 잠겼다. null 이면 판단할 수 없다. */
    private suspend fun observeSell(ticker: String, state: TradingState): Observed? {
        val prior = state.pendingSellPriorVolume ?: return null
        return try {
            val free = findAccount(ticker.substringAfter("-"))?.balanceDouble() ?: 0.0
            Observed(traced = prior - free > VOLUME_EPSILON, free = free)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown sell failed: {}", ticker, e.message)
            null
        }
    }

    /**
     * 잔고 변화 없는 404 가 끊김 없이 이어지면 미접수로 확정한다. 시장가 주문은 접수되면 바로 잔고가 움직인다는 전제다.
     * 잔고 흔적이 한 번이라도 보인 주문은 그 뒤 [UnknownOrderPolicy.tracedNotPlacedAfter] 가 지난 404 에서 종료 주문 목록을
     * 다시 보고 판정한다. 확정은 이번에 잔고를 본 조회에서만 한다(매도 장부를 줄일 free 가 필요하다).
     */
    private suspend fun judgeMiss(
        ticker: String,
        side: TradeSide,
        state: TradingState,
        identifier: String,
        observed: Observed?,
    ): UnknownOrderVerdict {
        val now = clock.instant()
        val prev = identifierMisses[identifier] ?: IdentifierMiss()
        val tracedSince = prev.tracedSince ?: now.takeIf { observed?.traced == true }
        if (tracedSince != null) {
            if (prev.tracedSince == null) {
                // ERROR 로그는 DiscordErrorLogAppender 가 알림으로 보낸다.
                log.error(
                    "{} 주문 {}({}) 을 거래소가 찾지 못하는데 잔고가 주문 전과 다르다 — {}분 뒤에도 못 찾고 종료 주문 목록에도 없으면 " +
                        "미접수로 확정한다. 그때까지 이 종목은 매매하지 않는다",
                    side.label(), identifier, ticker, policy.tracedNotPlacedAfter.toMinutes(),
                )
            }
            identifierMisses[identifier] = IdentifierMiss(tracedSince = tracedSince)
            // 호출부가 조회 장애로 셀 수 있게, 잔고를 못 봤으면 흔적과 무관하게 BALANCE_UNSEEN 이다.
            if (observed == null) return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.BALANCE_UNSEEN)
            if (Duration.between(tracedSince, now) < policy.tracedNotPlacedAfter) {
                return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.TRACED)
            }
            return confirmTraced(ticker, side, state, identifier, observed.free, now)
        }
        if (observed == null) {
            identifierMisses[identifier] = IdentifierMiss()
            return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.BALANCE_UNSEEN)
        }
        val next = prev.copy(misses = prev.misses + 1, firstMissAt = prev.firstMissAt ?: now)
        val waited = Duration.between(next.firstMissAt, now)
        if (next.misses >= policy.notPlacedMisses && waited >= policy.notPlacedAfter) {
            identifierMisses.remove(identifier)
            log.warn(
                "{} order {} for {} was never placed (not found {} times over {}s, no balance change) — released",
                side.label(), identifier, ticker, next.misses, waited.seconds,
            )
            return UnknownOrderVerdict.NotPlaced
        }
        identifierMisses[identifier] = next
        return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.AWAITING_CONFIRMATION)
    }

    /**
     * 흔적이 있는 주문을 identifier 조회와 다른 경로인 종료 주문 목록(done·cancel)에서 찾는다. 한쪽의 일시적 404 를 걸러 낸다.
     * 있으면 체결 내용을 담은 주문을 다시 읽어 이어받고, 없으면 미접수로 확정한다. 목록·주문 조회가 실패하면 다음 조회에서 다시 본다.
     */
    private suspend fun confirmTraced(
        ticker: String,
        side: TradeSide,
        state: TradingState,
        identifier: String,
        free: Double,
        now: Instant,
    ): UnknownOrderVerdict {
        val start = closedOrdersStart(side, state, now)
        val match = try {
            CLOSED_STATES.firstNotNullOfOrNull { s ->
                upbitClient.getClosedOrders(s, start, now, requireComplete = true, market = ticker).firstOrNull { it.identifier == identifier }
            }?.let { upbitClient.getOrder(it.uuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("closed orders check for {} order {} failed — kept pending: {}", ticker, identifier, e.message)
            return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.ORDER_LOOKUP_FAILED)
        }
        identifierMisses.remove(identifier)
        if (match != null) {
            log.warn("{} order {} for {} was missing by identifier but listed as {} — adopting {}", side.label(), identifier, ticker, match.state, match.uuid)
            return UnknownOrderVerdict.Found(match)
        }
        return UnknownOrderVerdict.TracedNotPlaced(free)
    }

    /**
     * 매도는 영속된 주문 시각부터 본다. 매수는 주문 시각이 저장되지 않고 흔적 타이머는 재시작마다 다시 시작해 주문보다 늦을 수
     * 있으므로 거래소가 허용하는 창 전체를 본다. 어느 쪽도 그 창보다 오래된 주문은 보지 못한다.
     */
    private fun closedOrdersStart(side: TradeSide, state: TradingState, now: Instant): Instant {
        val earliest = now.minus(CLOSED_ORDERS_MAX_WINDOW)
        val since = state.pendingSellSince?.takeIf { side == TradeSide.SELL }?.minus(ORDER_TIME_MARGIN) ?: return earliest
        return maxOf(since, earliest)
    }

    /** 미발견이 아닌 답이 끼면 연속 횟수를 끊는다. 흔적 타이머는 남긴다. */
    private fun breakMissStreak(identifier: String) {
        identifierMisses.computeIfPresent(identifier) { _, miss -> IdentifierMiss(tracedSince = miss.tracedSince) }
    }

    private fun TradeSide.label(): String = when (this) {
        TradeSide.BUY -> "Buy"
        TradeSide.SELL -> "Sell"
    }

    private companion object {
        val CLOSED_STATES = listOf("done", "cancel")

        // 업비트 종료 주문 조회 창의 상한.
        // 거래소 상한(7일)이 끝값을 받는지 문서에 없어 1분 안쪽으로 묻는다.
        val CLOSED_ORDERS_MAX_WINDOW: Duration = Duration.ofDays(7).minusMinutes(1)

        // 주문 시각은 보내기 직전에 찍으므로 거래소 시각과의 차이를 덮을 만큼 앞당긴다.
        val ORDER_TIME_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
