package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
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
 * 하루 한 번, 업비트의 봇 주문(identifier `ctb-`) 중 체결됐는데 거래 기록이 없는 것을 사용자별 ERROR 한 줄로 알린다(#341).
 * 매도 기록이 실패한 채 강제 종료되거나, 사람이 체결된 주문의 pending 을 지우면 거래 기록이 빠지고 엔진은 다시 보지 않는다.
 * 고치지는 않는다 — 체결가·수수료·청산 사유를 사람이 확인해 `exchange_order_id` 를 그 uuid 로 보정 행을 넣는다.
 * 엔진 루프·공용 스케줄러를 막지 않게 자체 코루틴에서 돈다.
 */
@Component
class OrderReconciler internal constructor(
    private val botStates: BotStateRepository,
    private val users: UserRepository,
    private val secrets: UserSecretsService,
    private val clientFor: (UserEntity) -> UpbitClient,
    private val tradingStates: TradingStateService,
    private val executions: TradeExecutionRepository,
    private val clock: Clock,
    private val timings: Timings = Timings(),
) {
    internal data class Timings(val firstRunAfter: Duration = Duration.ofMinutes(10), val every: Duration = Duration.ofHours(24))

    @Autowired
    constructor(
        botStates: BotStateRepository,
        users: UserRepository,
        secrets: UserSecretsService,
        managers: TradingManagers,
        tradingStates: TradingStateService,
        executions: TradeExecutionRepository,
    ) : this(botStates, users, secrets, { managers.get(Exchange.UPBIT).createUpbitClient(it) }, tradingStates, executions, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("order-reconciler"))
    private val started = AtomicBoolean(false)

    // 같은 주문은 이 프로세스에서 한 번만 알린다. 재시작하면 창 안에서 한 번 더 알린다.
    private val reported = mutableSetOf<String>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            delay(timings.firstRunAfter.toMillis())
            while (isActive) {
                try {
                    reconcileAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Order reconcile run failed — trying again in {}", timings.every, e)
                }
                delay(timings.every.toMillis())
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
        val now = clock.instant()
        val start = now.minus(WINDOW)
        val end = now.minus(SETTLE)
        // 읽는 순서: 거래소 → pending → 거래 기록. 기록부터 읽으면 그 사이 끝난 매도(기록과 pending 해제가 한 트랜잭션)가 기록 없음으로 보인다.
        val filled = (client.getClosedOrders("done", start, end) + client.getClosedOrders("cancel", start, end))
            .filter { it.identifier?.startsWith(BOT_PREFIX) == true && it.executed() > BigDecimal.ZERO }
            .distinctBy { it.uuid }
        val pending = tradingStates.loadStates(userId, Exchange.UPBIT).values
            .flatMap { listOfNotNull(it.pendingBuyUuid, it.pendingBuyIdentifier, it.pendingSellUuid, it.pendingSellIdentifier) }
            .toSet()
        val candidates = filled.filter { it.uuid !in pending && it.identifier !in pending }
        if (candidates.isEmpty()) {
            log.info("Order reconcile for user {}: no bot fills to check", userId)
            return true
        }
        val recorded = executions.findRecordedOrderIds(userId, candidates.map { it.uuid }).collectList().awaitSingle().toSet()
        val missing = candidates.filter { it.uuid !in recorded }
        if (missing.isEmpty()) {
            log.info("Order reconcile for user {}: {} bot fills checked, all recorded", userId, candidates.size)
            return true
        }
        val fresh = synchronized(reported) { missing.filter { reported.add(it.uuid) } }
        if (fresh.isEmpty()) {
            log.info("Order reconcile for user {}: {} unrecorded bot fills already reported", userId, missing.size)
            return true
        }
        log.error(
            "[ORDER RECONCILE] user {} 의 봇 주문 {}건이 체결됐는데 거래 기록이 없다 — 업비트 주문을 확인해 exchange_order_id 를 그 uuid 로 보정한다: {}",
            userId, fresh.size,
            fresh.take(SHOWN).joinToString("; ") { "${it.market} ${if (it.side == "bid") "매수" else "매도"} ${it.executedVolume} ${it.createdAt} ${it.uuid}" } +
                if (fresh.size > SHOWN) " 외 ${fresh.size - SHOWN}건" else "",
        )
        // Discord 줄에는 앞 몇 건만 싣는다. 나머지 uuid 는 로그 파일에만 남긴다(WARN 은 Discord 로 가지 않는다).
        if (fresh.size > SHOWN) log.warn("[ORDER RECONCILE] user {} 기록 없는 체결 uuid 전체: {}", userId, fresh.joinToString(",") { it.uuid })
        return true
    }

    private fun Order.executed(): BigDecimal = executedVolume?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private companion object {
        const val BOT_PREFIX = "ctb-"
        // Discord 메시지 칸이 1000자라 다 실리게 줄인다(전체 uuid 는 따로 WARN 으로 로그 파일에).
        const val SHOWN = 8
        val WINDOW: Duration = Duration.ofHours(48)
        // 막 끝난 주문은 엔진의 reconcile 이 기록할 시간을 둔다.
        val SETTLE: Duration = Duration.ofHours(1)
    }
}
