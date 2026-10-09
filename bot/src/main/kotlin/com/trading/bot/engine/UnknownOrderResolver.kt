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
    /** 거래소가 안다 — uuid 를 이어받아 기존 확정 경로로 넘긴다. [order] 의 uuid 는 비어 있지 않다. */
    data class Found(val order: Order) : UnknownOrderVerdict

    /** 흔적 없는 404 가 끊김 없이 이어져 미접수로 확정했다. */
    data object NotPlaced : UnknownOrderVerdict

    /** 아직 판단할 수 없다 — pending 을 둔다. */
    data class Undecided(val reason: Reason) : UnknownOrderVerdict

    enum class Reason {
        /** identifier 조회가 실패했다(uuid 없는 응답 포함) — 있는지 없는지 모른다. */
        ORDER_LOOKUP_FAILED,

        /** 거래소는 모르는데 잔고를 못 봤다 — 조회가 실패했거나, 비교할 주문 전 기준값이 없다(그 전의 pending). */
        BALANCE_UNSEEN,

        /** 거래소는 모르는데 잔고가 주문 전과 다르다(이 엔진에서 한 번이라도) — 자동으로 풀지 않고 사람을 부른다. */
        TRACED,

        /** 흔적 없는 404 — 확정 조건(횟수·시간)을 기다린다. */
        AWAITING_CONFIRMATION,
    }
}

/**
 * 응답을 못 받은 주문을 identifier 로 확정하는 규칙(#227). 조회·잔고 흔적·미접수 확정만 하고 상태는 바꾸지 않는다 —
 * pending 을 이어받거나 지우고, halt 를 세고, 저장하는 것은 호출부(PositionManager)다.
 *
 * 못 찾은 이력([identifierMisses])의 수명이 이 인스턴스다 — PositionManager(= 엔진)가 만들어 소유하므로 재시작·reload
 * 하면 처음부터 다시 센다.
 */
internal class UnknownOrderResolver(
    private val upbitClient: UpbitClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        // 응답을 못 받은 주문을 미접수로 확정하는 조건 — 연속으로 못 찾은 횟수와 처음 못 찾은 뒤 지난 시간(#227).
        const val NOT_PLACED_MISSES = 2
        val NOT_PLACED_AFTER: Duration = Duration.ofSeconds(60)
    }

    /**
     * identifier 조회로 못 찾은 이력. 비영속 — 재시작·reload 하면 [misses] 는 처음부터 다시 센다(더 오래 기다리는 쪽이라
     * 안전하다). [traced] 는 잔고 흔적을 한 번이라도 봤다는 뜻이고 그 주문은 자동으로 풀지 않는다 — 단 이 엔진 안에서만이다.
     * 재시작·reload 뒤에는 흔적이 남아 있으면 다시 잡지만, 그 사이 사라졌으면 일반 규칙으로 풀린다.
     */
    private data class IdentifierMiss(val misses: Int = 0, val firstMissAt: Instant? = null, val traced: Boolean = false)

    // 키가 identifier 라 다른 주문의 기록이 섞이지 않는다. 확정되지 않고 풀린 항목은 남지만 불명 주문 수만큼이라 무시한다.
    private val identifierMisses = ConcurrentHashMap<String, IdentifierMiss>()

    private sealed interface IdentifierLookup {
        data class Found(val order: Order) : IdentifierLookup

        /** 거래소가 그 identifier 를 모른다. */
        data object NotFound : IdentifierLookup

        /** 조회 자체가 실패했다 — 있는지 없는지 모른다. */
        data object Unknown : IdentifierLookup
    }

    /**
     * [identifier] 로 낸 [side] 주문을 판정한다. [state] 는 읽기만 한다(흔적 판정의 주문 전 기준값·락 상한).
     *
     * `CancellationException` 외에는 던지지 않는다 — 조회·잔고 실패는 [UnknownOrderVerdict.Undecided] 로 돌려준다.
     * 잔고는 거래소가 주문을 모를 때(404)만 읽는다.
     */
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

    /** 주문 전보다 보유가 늘었나 — 시장가 매수가 접수됐다면 곧바로 체결돼 코인이 들어온다. null = 판단할 수 없다. */
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

    /** 주문 전보다 free 가 줄었나 — 매도가 접수됐다면 코인이 팔렸거나 주문에 잠겼다. null = 판단할 수 없다. */
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
     * 거래소가 모르는 주문을 미접수로 확정해도 되는가. 흔적 없는 404 가 끊김 없이 [NOT_PLACED_MISSES] 회 이상 이어지고
     * 그 첫 404 뒤 [NOT_PLACED_AFTER] 가 지났을 때만 [UnknownOrderVerdict.NotPlaced] — 조회 실패·잔고 불명이 끼면
     * 처음부터 다시 센다.
     *
     * 증명이 아니다 — 시장가 주문은 접수되면 곧바로 체결돼 잔고가 움직인다는 전제에 기대고, 조회 가시성 지연은 문서에 없다.
     * 그래서 흔적이 한 번이라도 보인 주문은 끝까지 자동으로 판단하지 않고 ERROR 로 사람을 부른다(pending 유지, 조회는
     * 계속 — 찾히면 정상 확정). 흔적이 나중에 사라져도 이 엔진 안에서는 풀지 않는다: 그 사이의 수동 매매를 이 주문과
     * 구분할 수 없다([IdentifierMiss] 의 범위 참조).
     *
     * @param trace 주문 전 기준값 대비 잔고 흔적. null = 잔고를 못 봤다.
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
            // 잔고를 못 봤으면 이전 흔적과 무관하게 BALANCE_UNSEEN — 호출부가 조회 장애로 셀 수 있게.
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

    /** 조회 자체가 실패했다 — 못 찾음이 끊김 없이 이어졌다고 말할 수 없다. 흔적 기록은 남긴다. */
    private fun breakMissStreak(identifier: String) {
        identifierMisses.computeIfPresent(identifier) { _, miss -> IdentifierMiss(traced = miss.traced) }
    }
}
