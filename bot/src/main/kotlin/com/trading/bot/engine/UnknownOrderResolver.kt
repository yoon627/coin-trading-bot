package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
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

    /** 아직 판단할 수 없어 pending 을 유지한다. */
    data class Undecided(val reason: Reason) : UnknownOrderVerdict

    enum class Reason {
        /** identifier 조회 실패. uuid 없는 응답도 여기에 든다. */
        ORDER_LOOKUP_FAILED,

        /** 거래소에 없는데 잔고 조회가 실패했거나 비교할 주문 전 잔고가 없다. */
        BALANCE_UNSEEN,

        /** 거래소에 없는데 잔고가 주문 전과 달랐던 적이 있다. 이 엔진이 도는 동안은 자동으로 풀지 않는다. */
        TRACED,

        /** 잔고 변화 없는 404. 확정 조건을 기다린다. */
        AWAITING_CONFIRMATION,
    }
}

/**
 * 응답을 못 받은 주문이 실제로 접수됐는지 identifier 로 판정한다. pending 처리와 저장은 PositionManager 가 한다.
 * 못 찾은 이력은 엔진마다 만드는 이 인스턴스의 메모리에만 있어 재시작·reload 하면 처음부터 다시 센다.
 */
internal class UnknownOrderResolver(
    private val upbitClient: UpbitClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        // 미접수로 확정하려면 연속으로 이만큼 못 찾고, 처음 못 찾은 뒤 이 시간이 지나야 한다.
        const val NOT_PLACED_MISSES = 2
        val NOT_PLACED_AFTER: Duration = Duration.ofSeconds(60)
    }

    /**
     * identifier 로 못 찾은 이력. [traced] 는 잔고 흔적을 한 번이라도 봤다는 뜻이고, 그런 주문은 자동으로 풀지 않는다.
     * 단 이 엔진 안에서만이다. 재시작·reload 뒤에는 흔적이 남아 있으면 다시 잡지만, 그 사이 사라졌으면 일반 규칙으로 풀린다.
     * [misses] 를 다시 세는 것은 더 오래 기다리는 쪽이라 안전하다.
     */
    private data class IdentifierMiss(val misses: Int = 0, val firstMissAt: Instant? = null, val traced: Boolean = false)

    // 확정되지 않고 풀린 항목은 지우지 않는다. 불명 주문 수만큼이라 쌓여도 작다.
    private val identifierMisses = ConcurrentHashMap<String, IdentifierMiss>()

    private sealed interface IdentifierLookup {
        data class Found(val order: Order) : IdentifierLookup

        /** 거래소가 그 identifier 를 모른다. */
        data object NotFound : IdentifierLookup

        /** 조회 자체가 실패해 있는지 없는지 모른다. */
        data object Unknown : IdentifierLookup
    }

    /** 조회·잔고 실패는 [UnknownOrderVerdict.Undecided] 로 돌려주고 `CancellationException` 외에는 던지지 않는다. */
    suspend fun resolve(ticker: String, side: TradeSide, state: TradingState, identifier: String): UnknownOrderVerdict =
        when (val lookup = lookupByIdentifier(ticker, identifier)) {
            is IdentifierLookup.Found -> {
                identifierMisses.remove(identifier)
                UnknownOrderVerdict.Found(lookup.order)
            }
            IdentifierLookup.NotFound -> {
                val trace = when (side) {
                    TradeSide.BUY -> buyTrace(ticker, state)
                    TradeSide.SELL -> sellTrace(ticker, state)
                }
                judgeMiss(ticker, side, identifier, trace)
            }
            IdentifierLookup.Unknown -> {
                breakMissStreak(identifier)
                UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.ORDER_LOOKUP_FAILED)
            }
        }

    private suspend fun lookupByIdentifier(ticker: String, identifier: String): IdentifierLookup =
        try {
            val order = upbitClient.getOrderByIdentifier(identifier)
            when {
                order == null -> IdentifierLookup.NotFound
                order.uuid.isBlank() -> {
                    log.warn("identifier lookup for {} order {} returned no uuid", ticker, identifier)
                    IdentifierLookup.Unknown
                }
                else -> IdentifierLookup.Found(order)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("identifier lookup failed for {} order {}: {}", ticker, identifier, e.message)
            IdentifierLookup.Unknown
        }

    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    /** 주문 전보다 보유가 늘었는지. 시장가 매수는 접수되면 바로 체결된다. null 이면 판단할 수 없다. */
    private suspend fun buyTrace(ticker: String, state: TradingState): Boolean? {
        val prior = state.pendingBuyPriorVolume ?: return null
        return try {
            val held = findAccount(ticker.substringAfter("-"))?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
            held - prior > VOLUME_EPSILON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown buy failed: {}", ticker, e.message)
            null
        }
    }

    /** 주문 전보다 free 가 줄었는지. 매도가 접수됐으면 팔렸거나 주문에 잠겼다. null 이면 판단할 수 없다. */
    private suspend fun sellTrace(ticker: String, state: TradingState): Boolean? {
        val prior = state.pendingSellPriorVolume ?: return null
        return try {
            val free = findAccount(ticker.substringAfter("-"))?.balanceDouble() ?: 0.0
            prior - free > VOLUME_EPSILON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown sell failed: {}", ticker, e.message)
            null
        }
    }

    /**
     * 잔고 변화 없는 404 가 끊김 없이 이어져야 미접수로 확정한다. 시장가 주문은 접수되면 바로 잔고가 움직인다는 전제라
     * 증명은 아니어서, 잔고 흔적이 한 번이라도 보인 주문은 pending 을 둔 채 사람이 확인하게 한다.
     * 흔적이 나중에 사라져도 그 사이의 수동 매매와 구분할 수 없으므로 이 엔진 안에서는 풀지 않는다.
     */
    private fun judgeMiss(ticker: String, side: TradeSide, identifier: String, trace: Boolean?): UnknownOrderVerdict {
        val label = when (side) {
            TradeSide.BUY -> "Buy"
            TradeSide.SELL -> "Sell"
        }
        val prev = identifierMisses[identifier] ?: IdentifierMiss()
        if (trace == true && !prev.traced) {
            log.error(
                "{} 주문 {}({}) 을 거래소가 찾지 못하는데 잔고가 주문 전과 다릅니다 — 자동 처리하지 않습니다(이 티커의 매매가 멈춥니다). " +
                    "봇을 정지하고 Upbit 주문 내역을 확인한 뒤(미체결이면 먼저 취소) POST /api/bot/pending/clear 로 해제하고 다시 시작하세요.",
                label, identifier, ticker,
            )
        }
        if (trace != false || prev.traced) {
            identifierMisses[identifier] = IdentifierMiss(traced = prev.traced || trace == true)
            // 호출부가 조회 장애로 셀 수 있게, 잔고를 못 봤으면 이전 흔적과 무관하게 BALANCE_UNSEEN 이다.
            return UnknownOrderVerdict.Undecided(
                if (trace == null) UnknownOrderVerdict.Reason.BALANCE_UNSEEN else UnknownOrderVerdict.Reason.TRACED,
            )
        }
        val now = clock.instant()
        val next = prev.copy(misses = prev.misses + 1, firstMissAt = prev.firstMissAt ?: now)
        val waited = Duration.between(next.firstMissAt, now)
        if (next.misses >= NOT_PLACED_MISSES && waited >= NOT_PLACED_AFTER) {
            identifierMisses.remove(identifier)
            log.warn(
                "{} order {} for {} was never placed (not found {} times over {}s, no balance change) — released",
                label, identifier, ticker, next.misses, waited.seconds,
            )
            return UnknownOrderVerdict.NotPlaced
        }
        identifierMisses[identifier] = next
        return UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.AWAITING_CONFIRMATION)
    }

    /** 조회가 실패하면 연속 횟수를 끊는다. 흔적 기록은 남긴다. */
    private fun breakMissStreak(identifier: String) {
        identifierMisses.computeIfPresent(identifier) { _, miss -> IdentifierMiss(traced = miss.traced) }
    }
}
