package com.trading.bot.kis.order

import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.domain.KisCcldRow
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import com.trading.bot.persistence.entity.TradeExecutionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * WAL 주문 reconcile (주문유실 방지의 확정측). 비terminal 주문을 KIS 당일조회로 확정한다(plan D7~D10).
 * 부팅 시 1회 + 주기 실행. 재시작 갭(SUBMITTING/UNKNOWN), 미체결(PLACED/PARTIAL) 을 결정론적으로 해소.
 *
 * 안전 규칙: 조회 0건은 절대 FAILED 로 단정하지 않는다(실제 접수 가능성) → grace 경과 시 NEEDS_REVIEW + ERROR 알림.
 * 동시성: 단일 인스턴스 가정 — in-process 가드로 패스 중복 방지(멀티인스턴스 행 lease 는 후속).
 */
@Component
class StockOrderReconciler(
    private val repository: StockOrderIntentRepository,
    private val tradeExecutionRepository: TradeExecutionRepository,
    private val userRepository: UserRepository,
    private val clientFactory: KisClientFactory,
    private val transactionalOperator: TransactionalOperator,
    private val kisProperties: KisProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Mutex: 부팅(reconcileNow)은 완료까지 대기, 주기(scheduled)는 진행중이면 skip — 엔진기동 전 reconcile 완료 보장(M1/D21).
    private val reconcileMutex = Mutex()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            log.info("stock reconcile: boot pass starting")
            reconcileNow()
        }
    }

    @Scheduled(fixedDelayString = "\${kis.reconcile-interval-ms:15000}")
    fun scheduled() {
        scope.launch {
            if (reconcileMutex.tryLock()) {
                try {
                    runPass()
                } finally {
                    reconcileMutex.unlock()
                }
            } else {
                log.debug("stock reconcile already in progress — skip periodic")
            }
        }
    }

    /** 완료까지 대기하는 reconcile 1패스 — 부팅/엔진기동 전 호출(진행중이면 끝날 때까지 대기 후 자기 패스 실행). */
    suspend fun reconcileNow() = reconcileMutex.withLock { runPass() }

    private suspend fun runPass() {
        try {
            val active = repository.findActive(StockOrderStatus.NON_TERMINAL_NAMES, BATCH_LIMIT)
                .collectList().awaitSingle()
            if (active.isEmpty()) return
            log.info("stock reconcile: {} active order(s)", active.size)
            active.groupBy { it.userId }.forEach { (userId, rows) -> reconcileUser(userId, rows) }
        } catch (e: Exception) {
            log.error("stock reconcile pass failed", e)
        }
    }

    private suspend fun reconcileUser(userId: Long, rows: List<StockOrderIntentEntity>) {
        val user = userRepository.findById(userId).awaitSingleOrNull()
        if (user == null) {
            log.error("stock reconcile: user {} not found for {} active order(s)", userId, rows.size)
            return
        }
        val client = try {
            clientFactory.forUser(user)
        } catch (e: Exception) {
            log.error("stock reconcile: cannot build KIS client for user {} — {}", userId, e.message)
            return
        }
        rows.groupBy { it.orderDate }.forEach { (date, dateRows) ->
            val conclusions = try {
                client.inquireDailyConclusions(date)
            } catch (e: Exception) {
                log.warn("stock reconcile: inquiry failed user={} date={} — {} (keep pending)", userId, date, e.message)
                return@forEach
            }
            val byOdno = conclusions.filter { it.odno.isNotBlank() }.associateBy { it.odno }
            // 이미 우리 WAL 의 다른 행에 링크된 ODNO 는 no-odno 매칭에서 제외(동일수량 반복매매 오매칭 방지 — C3/D20).
            val knownOdnos = repository.findKnownOdnos(userId, date).collectList().awaitSingle().toSet()
            dateRows.forEach { reconcileRow(it, conclusions, byOdno, knownOdnos) }
        }
    }

    private suspend fun reconcileRow(
        row: StockOrderIntentEntity,
        conclusions: List<KisCcldRow>,
        byOdno: Map<String, KisCcldRow>,
        knownOdnos: Set<String>,
    ) {
        if (!row.odno.isNullOrBlank()) {
            val match = byOdno[row.odno]
            if (match == null) {
                handleMissingOdno(row)
            } else {
                applyConclusion(row, match, linkOdno = null)
            }
            return
        }
        // ODNO 미상(SUBMITTING/UNKNOWN) — symbol+side+qty 로 매칭. 이미 링크된 ODNO 는 제외.
        val candidates = conclusions.filter {
            it.pdno == row.symbol && it.side()?.name == row.side && it.orderQty() == row.qty &&
                it.odno !in knownOdnos
        }
        when (candidates.size) {
            1 -> applyConclusion(row, candidates[0], linkOdno = candidates[0].odno)
            0 -> handleNoMatch(row)
            else -> escalate(row, "ambiguous match (${candidates.size} conclusions) — manual link required")
        }
    }

    private suspend fun applyConclusion(row: StockOrderIntentEntity, c: KisCcldRow, linkOdno: String?) {
        val executed = c.executedQty()
        val newStatus = when {
            c.cancelled() -> StockOrderStatus.CANCELLED
            c.remainingQty() == 0L && executed > 0L -> StockOrderStatus.FILLED
            executed > 0L -> StockOrderStatus.PARTIAL
            else -> StockOrderStatus.PLACED // 접수 확인, 미체결
        }
        if (newStatus.terminal) {
            finalizeTerminal(row, newStatus, executed, linkOdno, c.avgFillPrice())
        } else {
            transitionActive(row, newStatus, executed, linkOdno)
        }
    }

    private suspend fun transitionActive(
        row: StockOrderIntentEntity,
        newStatus: StockOrderStatus,
        executed: Long,
        linkOdno: String?,
    ) {
        val moved = repository.transition(
            row.id!!, row.status, newStatus.name, linkOdno, null, executed, null,
        ).awaitSingle()
        if (moved == 1L) {
            log.info("stock reconcile: {} {} {} -> {} (executed={}/{})", row.symbol, row.side, row.status, newStatus, executed, row.qty)
        }
    }

    /** 터미널(FILLED/CANCELLED) 전이 + 체결 audit 을 한 트랜잭션으로(멱등). */
    private suspend fun finalizeTerminal(
        row: StockOrderIntentEntity,
        newStatus: StockOrderStatus,
        executed: Long,
        linkOdno: String?,
        avgFillPrice: Double,
    ) {
        transactionalOperator.transactional(
            mono {
                val moved = repository.transition(
                    row.id!!, row.status, newStatus.name, linkOdno, null, executed, null,
                ).awaitSingle()
                if (moved == 1L && executed > 0L) {
                    val claimed = repository.claimAudit(row.id).awaitSingle()
                    if (claimed == 1L) {
                        tradeExecutionRepository.save(buildExecution(row, executed, linkOdno, avgFillPrice)).awaitSingle()
                    }
                }
                moved
            },
        ).awaitSingle()
        log.info("stock reconcile: {} {} {} -> {} (executed={})", row.symbol, row.side, row.status, newStatus, executed)
    }

    private fun buildExecution(
        row: StockOrderIntentEntity,
        executed: Long,
        linkOdno: String?,
        avgFillPrice: Double,
    ): TradeExecutionEntity {
        val price = if (avgFillPrice > 0) avgFillPrice else row.price?.toDouble() ?: 0.0
        return TradeExecutionEntity(
            userId = row.userId,
            exchange = "KIS",
            market = row.symbol,
            side = row.side,
            orderType = row.orderType,
            price = price,
            volume = executed.toDouble(),
            totalAmount = price * executed,
            exchangeOrderId = linkOdno ?: row.odno,
            status = "EXECUTED",
        )
    }

    /** PLACED 인데 당일조회에 ODNO 가 없음 — 일시적일 수 있어 유지, 오래되면 NEEDS_REVIEW. */
    private suspend fun handleMissingOdno(row: StockOrderIntentEntity) {
        val age = Duration.between(row.updatedAt, Instant.now(clock)).seconds
        if (age < kisProperties.reconcileStaleSeconds) {
            log.warn("stock reconcile: odno {} not in conclusions ({} {}) — keep {} (age={}s)", row.odno, row.symbol, row.side, row.status, age)
            return
        }
        escalate(row, "placed order ${row.odno} missing from conclusions after ${age}s")
    }

    /** SUBMITTING/UNKNOWN 인데 조회 0건 — grace 전엔 유지, 후엔 NEEDS_REVIEW(FAILED 단정 금지). */
    private suspend fun handleNoMatch(row: StockOrderIntentEntity) {
        val age = Duration.between(row.createdAt, Instant.now(clock)).seconds
        if (age < kisProperties.reconcileGraceSeconds) {
            log.info("stock reconcile: no match yet ({} {} {}) — keep (age={}s)", row.symbol, row.side, row.status, age)
            return
        }
        escalate(row, "no matching order after grace (${age}s) — placement uncertain, manual check")
    }

    private suspend fun escalate(row: StockOrderIntentEntity, reason: String) {
        if (row.status == StockOrderStatus.NEEDS_REVIEW.name) return
        val moved = repository.transition(
            row.id!!, row.status, StockOrderStatus.NEEDS_REVIEW.name, null, null, row.executedQty, reason.take(255),
        ).awaitSingle()
        if (moved == 1L) {
            // ERROR 로그 → DiscordErrorLogAppender(활성 시) durable 알림.
            log.error(
                "STOCK ORDER NEEDS REVIEW: id={} user={} {} {} qty={} odno={} — {}",
                row.id, row.userId, row.symbol, row.side, row.qty, row.odno, reason,
            )
        }
    }

    companion object {
        private const val BATCH_LIMIT = 200
    }
}
