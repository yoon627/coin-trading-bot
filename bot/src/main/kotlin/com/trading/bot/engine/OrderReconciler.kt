package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingDay
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 매일 09:05 KST 에 한 번, 업비트의 봇 주문(identifier `ctb-`) 중 체결됐는데 거래 기록이 없는 것을 사용자별 ERROR 한 줄로 알린다(#341).
 * 같은 실행에서 1시간 넘게 대기·접수로 남은 `orders` 줄을 거래소에서 찾아 분명한 증거가 있을 때만 닫는다(#412, [closeStaleRows]).
 * 매도 기록이 실패한 채 강제 종료되거나, 잔고 흔적 뒤 미접수로 확정한 주문이 실제로는 체결됐으면 거래 기록이 빠지고 엔진은 다시 보지 않는다.
 * 거래 기록은 `orders` 의 업비트 체결 줄이다. 체결은 고치지 않는다 — 체결가·수수료·청산 사유를 사람이 확인해 그 주문의 줄을 FILLED 로
 * 바꾸거나(대기·접수 줄이 있을 때) FILLED 줄을 넣는다(README 체결 기록 대조 절).
 * 엔진 루프·공용 스케줄러를 막지 않게 자체 코루틴에서 돈다. 기동 직후에는 돌지 않고 다음 09:05 를 기다린다.
 */
@Component
class OrderReconciler internal constructor(
    private val botStates: BotStateRepository,
    private val users: UserRepository,
    private val secrets: UserSecretsService,
    private val clientFor: (UserEntity) -> UpbitClient,
    private val tradingStates: TradingStateService,
    private val orders: OrderRowRepository,
    private val clock: Clock,
    private val timings: Timings = Timings(),
) {
    internal data class Timings(val untilNextRun: (Instant) -> Duration = ::untilNextDailyRun)

    @Autowired
    constructor(
        botStates: BotStateRepository,
        users: UserRepository,
        secrets: UserSecretsService,
        managers: TradingManagers,
        tradingStates: TradingStateService,
        orders: OrderRowRepository,
    ) : this(botStates, users, secrets, { managers.get(Exchange.UPBIT).createUpbitClient(it) }, tradingStates, orders, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("order-reconciler"))
    private val started = AtomicBoolean(false)

    // 같은 주문은 이 프로세스에서 한 번만 알린다. 재시작하면 창 안에서 한 번 더 알린다.
    private val reported = mutableSetOf<String>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                delay(timings.untilNextRun(clock.instant()).toKotlinDuration())
                try {
                    reconcileAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Order reconcile run failed — trying again at the next run", e)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    internal suspend fun reconcileAll() {
        val userIds = botStates.findByExchange(Exchange.UPBIT.name).collectList().awaitSingle().map { it.userId }.distinct()
        var attempted = 0
        var failed = 0
        for (userId in userIds) {
            try {
                if (reconcileUser(userId)) attempted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempted++
                failed++
                log.warn("Order reconcile skipped user {}", userId, e)
            }
        }
        // 한 사용자의 키 문제는 WARN 이지만, 시도한 사용자가 모두 실패면 대조가 통째로 꺼진 것이라 알린다(키 없는 사용자는 세지 않는다).
        if (attempted > 0 && failed == attempted) {
            log.error("[ORDER RECONCILE] 대조를 시도한 사용자 {}명 모두 실패했다 — WARN 로그의 원인을 확인하라", failed)
        }
    }

    /** 키가 없어 시도하지 않았으면 false. */
    private suspend fun reconcileUser(userId: Long): Boolean {
        val user = users.findById(userId).awaitSingleOrNull()
        if (user == null || user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            log.info("Order reconcile for user {}: no user or no Upbit keys — skipped", userId)
            return false
        }
        val client = clientFor(secrets.decryptUserSecrets(user))
        // 두 점검은 서로 막지 않는다. 체결 대조의 실패만 사용자 실패로 센다(모든 사용자 실패 ERROR 는 체결 대조가 꺼진 것을 알린다).
        // 다만 키가 거절됐으면 줄 조회도 거절되고 그 조회는 인증 오류를 ERROR 로 남기므로 오래된 줄 점검을 건너뛴다.
        val fillFailure = try {
            checkFills(userId, client)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (fillFailure is UpbitApiException && fillFailure.statusCode in KEY_REJECTED) {
            log.warn("Stale order check skipped for user {}: the Upbit key was rejected ({})", userId, fillFailure.statusCode)
        } else {
            try {
                closeStaleRows(userId, client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Stale order check skipped for user {}", userId, e)
            }
        }
        fillFailure?.let { throw it }
        return true
    }

    private suspend fun pendingIds(userId: Long): Set<String> = tradingStates.loadStates(userId, Exchange.UPBIT).values
        .flatMap { listOfNotNull(it.pendingBuyUuid, it.pendingBuyIdentifier, it.pendingSellUuid, it.pendingSellIdentifier) }
        .toSet()

    private suspend fun checkFills(userId: Long, client: UpbitClient) {
        val now = clock.instant()
        val start = now.minus(WINDOW)
        val end = now.minus(SETTLE)
        // 읽는 순서: 거래소 → pending → 거래 기록. 기록부터 읽으면 그 사이 끝난 매도(기록과 pending 해제가 한 트랜잭션)가 기록 없음으로 보인다.
        val filled = (client.getClosedOrders("done", start, end) + client.getClosedOrders("cancel", start, end))
            .filter { it.identifier?.startsWith(BOT_PREFIX) == true && it.executed() > BigDecimal.ZERO }
            .distinctBy { it.uuid }
        val pending = pendingIds(userId)
        val candidates = filled.filter { it.uuid !in pending && it.identifier !in pending }
        if (candidates.isEmpty()) {
            log.info("Order reconcile for user {}: no bot fills to check", userId)
            return
        }
        val recorded = orders.findRecordedOrderIds(userId, Exchange.UPBIT.name, candidates.map { it.uuid }).collectList().awaitSingle().toSet()
        val missing = candidates.filter { it.uuid !in recorded }
        if (missing.isEmpty()) {
            log.info("Order reconcile for user {}: {} bot fills checked, all recorded", userId, candidates.size)
            return
        }
        val fresh = synchronized(reported) { missing.filter { reported.add(it.uuid) } }
        if (fresh.isEmpty()) {
            log.info("Order reconcile for user {}: {} unrecorded bot fills already reported", userId, missing.size)
            return
        }
        log.error(
            "[ORDER RECONCILE] user {} 의 봇 주문 {}건이 체결됐는데 거래 기록이 없다 — 업비트 주문을 확인해 orders 를 보정한다" +
                "(그 uuid 의 줄이 있으면 그 줄을 FILLED 로, 없으면 FILLED 줄을 넣는다): {}",
            userId, fresh.size,
            fresh.take(SHOWN).joinToString("; ") { "${it.market} ${if (it.side == "bid") "매수" else "매도"} ${it.executedVolume} ${it.createdAt} ${it.uuid}" } +
                if (fresh.size > SHOWN) " 외 ${fresh.size - SHOWN}건" else "",
        )
        // Discord 줄에는 앞 몇 건만 싣는다. 나머지 uuid 는 로그 파일에만 남긴다(WARN 은 Discord 로 가지 않는다).
        if (fresh.size > SHOWN) log.warn("[ORDER RECONCILE] user {} 기록 없는 체결 uuid 전체: {}", userId, fresh.joinToString(",") { it.uuid })
    }

    /**
     * 만든 지 1시간 넘게 대기·접수로 남은 줄(생애 쓰기는 최선이라 앱이 죽거나 쓰기가 실패하면 남는다)을 식별자로 거래소에서 찾아
     * 분명한 증거가 있을 때만 닫는다. 엔진이 붙잡은 주문은 건너뛴다. 주문번호로는 묻지 않는다 — 그 조회는 404 도 ERROR 로 남긴다(식별자 조회도 404 가 아닌 오류는 ERROR 다).
     * 조회가 실패하면 이 사용자의 나머지 줄은 다음 실행으로 미룬다(같은 원인으로 줄마다 실패하지 않게).
     */
    private suspend fun closeStaleRows(userId: Long, client: UpbitClient) {
        val rows = orders.findStaleUnfinished(userId, Exchange.UPBIT.name, STALE_LIMIT).collectList().awaitSingle()
        if (rows.isEmpty()) return
        val pending = pendingIds(userId)
        val counts = mutableMapOf<String, Int>()
        for (row in rows) {
            val outcome = try {
                staleOutcome(userId, client, row, pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Stale order check for user {} stopped at order row {} — the rest wait for the next run", userId, row.id, e)
                // 이 줄을 뒤로 보내 둔다 — 이 줄만 늘 실패하면 다음 날에도 맨 앞에서 나머지를 막는다. 이미 알린 미기록 체결의 표시는 지우지 않는다.
                val note = row.note?.takeIf { it.startsWith(ALERTED_NOTE) } ?: "$RECONCILE_NOTE: lookup failed (${e.javaClass.simpleName})"
                row.id?.let { orders.markUnresolved(it, note).awaitSingleOrNull() }
                break
            }
            counts.merge(outcome, 1, Int::plus)
        }
        log.info("Stale order check for user {}: {} of {} rows checked — {}", userId, counts.values.sum(), rows.size, counts)
    }

    private suspend fun staleOutcome(userId: Long, client: UpbitClient, row: OrderRowEntity, pending: Set<String>): String {
        val id = checkNotNull(row.id)
        if (row.clientOrderId in pending || row.exchangeOrderId in pending) return "held by engine"
        val identifier = row.clientOrderId ?: return unresolved(id, "no identifier")
        val found = client.getOrderByIdentifier(identifier)
        val was = "$RECONCILE_NOTE (was ${row.status})"
        if (found == null) {
            // 주문번호가 있는 줄은 거래소가 받은 주문이다 — 식별자로 안 보여도(다른 계정 키, 조회 보관 기간) 접수 안 됨이 아니다.
            if (row.exchangeOrderId != null) return unresolved(id, "identifier not found but the exchange accepted the order")
            // 식별자는 계정에서 영구 고유라 404 는 접수 안 됨이다. 다만 업비트가 식별자 조회를 얼마나 오래 지원하는지 문서에 없어 최근 줄만 믿는다.
            val recent = row.createdAt?.let { Duration.between(it, clock.instant()) < NOT_PLACED_WITHIN } == true
            if (!recent) return unresolved(id, "identifier not found, too old to conclude")
            return closed(id, "NOT_PLACED", null, "$was: identifier not found")
        }
        if (found.uuid in pending) return "held by engine"
        if (found.executed() > BigDecimal.ZERO) {
            val recordedRow = orders.findByExchangeOrderId(userId, Exchange.UPBIT.name, found.uuid).awaitSingleOrNull()
            if (recordedRow?.status == "FILLED") return unresolved(id, "fill recorded as order ${recordedRow.id}")
            val alreadyAlerted = row.note?.startsWith(ALERTED_NOTE) == true || !synchronized(reported) { reported.add(found.uuid) }
            if (!alreadyAlerted) {
                log.error(
                    "[ORDER RECONCILE] user {} 의 주문 줄 id={} 이 체결됐는데 거래 기록이 없다 — 업비트 주문을 확인해 이 줄을 FILLED 로 보정한다: {} {} {} {}",
                    userId, id, found.market, if (found.side == "bid") "매수" else "매도", found.executedVolume, found.uuid,
                )
            }
            return unresolved(id, "$FILLED_UNRECORDED ${found.uuid}")
        }
        if (found.state == "cancel" || found.state == "done") return closed(id, "CANCELLED", found.state, "$was: ${found.state} without a fill")
        return unresolved(id, "exchange state ${found.state}")
    }

    private suspend fun closed(id: Long, status: String, exchangeState: String?, note: String): String =
        // 엔진이 그사이 먼저 닫았으면 0 줄이다 — 정상.
        if (orders.closeStale(id, status, exchangeState, note).awaitSingleOrNull() == null) "closed by engine" else status

    private suspend fun unresolved(id: Long, reason: String): String {
        orders.markUnresolved(id, "$RECONCILE_NOTE: $reason").awaitSingleOrNull()
        return "unresolved"
    }

    private fun Order.executed(): BigDecimal = executedVolume?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private companion object {
        const val BOT_PREFIX = "ctb-"

        // Discord 메시지 칸이 1000자라 다 실리게 줄인다(전체 uuid 는 따로 WARN 으로 로그 파일에).
        const val SHOWN = 8
        val WINDOW: Duration = Duration.ofHours(48)

        // 막 끝난 주문은 엔진의 reconcile 이 기록할 시간을 둔다.
        val SETTLE: Duration = Duration.ofHours(1)

        val KEY_REJECTED = setOf(401, 403)
        const val STALE_LIMIT = 50
        val NOT_PLACED_WITHIN: Duration = Duration.ofDays(7)
        const val RECONCILE_NOTE = "reconcile"
        const val FILLED_UNRECORDED = "filled, no record"
        const val ALERTED_NOTE = "$RECONCILE_NOTE: $FILLED_UNRECORDED"
    }
}

// 업비트 거래일이 바뀌는 09:00 KST 에 맞춘다. 조회 창(48시간, 막 만든 1시간 제외)은 실행 시각과 무관하게 같다.
private val DAILY_RUN_AT: LocalTime = LocalTime.of(9, 5)

/** [now] 다음의 09:05 KST 까지. 정각이면 다음 날이다 — 같은 시각에 두 번 돌지 않는다. */
internal fun untilNextDailyRun(now: Instant): Duration {
    val local = now.atZone(TradingDay.KST)
    val today = local.toLocalDate().atTime(DAILY_RUN_AT).atZone(TradingDay.KST)
    val next = if (today.isAfter(local)) today else today.plusDays(1)
    return Duration.between(now, next)
}
