package com.trading.bot.kis.order

import com.trading.bot.kis.client.KisApiException
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.domain.KisOrderRequest
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.domain.KisSide
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 주문 요청 검증 실패(주문 미송신). */
class StockOrderValidationException(message: String) : RuntimeException(message)

data class SubmitOrderCommand(
    val userId: Long,
    val cano: String,
    val acntPrdtCd: String,
    val symbol: String,
    val side: KisSide,
    val orderType: KisOrderType,
    val qty: Long,
    val price: Long? = null, // 지정가 단가(원). 시장가는 null.
)

/**
 * KIS 주식 주문 송신 + WAL 기록(주문유실 방지의 송신측). 트랜잭션 경계가 핵심(plan D4):
 *   tx1: INSERT(SUBMITTING) 커밋 →  (트랜잭션 밖) placeOrder  →  tx2: 조건부 UPDATE
 * 셋을 한 트랜잭션으로 묶으면 placeOrder 후 사망 시 INSERT 까지 롤백돼 WAL 이 소멸한다 — 절대 금지.
 * 그래서 각 repository 호출을 TransactionalOperator 로 감싸지 않는다(호출당 auto-commit).
 */
@Service
class StockOrderService(
    private val repository: StockOrderIntentRepository,
    private val kisProperties: KisProperties,
    private val clock: Clock,
    private val marketCalendar: KisMarketCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun submit(client: KisClient, cmd: SubmitOrderCommand): StockOrderIntentEntity {
        validate(client, cmd, dryRun = !kisProperties.liveEnabled)
        val accountNo = "${cmd.cano}-${cmd.acntPrdtCd}"

        // 사전 가드(흔한 경우, 같은 종목·같은 side). 경합은 DB partial unique index 가 최종 차단.
        // side 포함이라 미체결 매수가 손절매도를 막지 않는다(D19/C2).
        val existing = repository.findActiveByKey(
            cmd.userId, EXCHANGE, accountNo, cmd.symbol, cmd.side.name, StockOrderStatus.NON_TERMINAL_NAMES,
        ).awaitSingleOrNull()
        if (existing != null) {
            throw StockOrderValidationException(
                "active ${cmd.side} order exists for ${cmd.symbol} (id=${existing.id}, status=${existing.status})",
            )
        }

        val dryRun = !kisProperties.liveEnabled
        val initialStatus = if (dryRun) StockOrderStatus.DRY_RUN else StockOrderStatus.SUBMITTING

        // tx1: write-ahead INSERT (자체 커밋).
        val saved = try {
            repository.save(
                StockOrderIntentEntity(
                    userId = cmd.userId,
                    clientRef = UUID.randomUUID().toString(),
                    exchange = EXCHANGE,
                    accountNo = accountNo,
                    symbol = cmd.symbol,
                    side = cmd.side.name,
                    orderType = cmd.orderType.name,
                    qty = cmd.qty,
                    price = cmd.price?.let(BigDecimal::valueOf),
                    status = initialStatus.name,
                    orderDate = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE),
                ),
            ).awaitSingle()
        } catch (e: DataIntegrityViolationException) {
            // partial unique index 위반 = 동시에 다른 활성 주문이 들어옴.
            throw StockOrderValidationException("active order exists for ${cmd.symbol} (concurrent)")
        }

        if (dryRun) {
            log.warn(
                "DRY_RUN stock order (kis.live-enabled=false) — NOT sent: {} {} qty={} price={}",
                cmd.side, cmd.symbol, cmd.qty, cmd.price,
            )
            return saved
        }

        // (트랜잭션 밖) 주문 송신 — 비멱등, 재시도 없음.
        val req = KisOrderRequest(cmd.cano, cmd.acntPrdtCd, cmd.symbol, cmd.side, cmd.orderType, cmd.qty, cmd.price)
        return try {
            val ack = client.placeOrder(req)
            transition(saved, StockOrderStatus.PLACED, odno = ack.odno, orgNo = ack.orgNo)
        } catch (e: KisApiException) {
            if (e.definitiveReject) {
                log.warn("Stock order rejected by broker (FAILED): {} {} — rt_cd={} msg={}", cmd.side, cmd.symbol, e.rtCd, e.msg)
                transition(saved, StockOrderStatus.FAILED, failReason = "${e.rtCd}:${e.msgCd}:${e.msg}")
            } else {
                log.error("Stock order send ambiguous (UNKNOWN — will reconcile): {} {} — {}", cmd.side, cmd.symbol, e.msg)
                transition(saved, StockOrderStatus.UNKNOWN, failReason = e.msg)
            }
        } catch (e: Exception) {
            // 분류 불가 — 안전쪽(접수됐을 수 있음)으로 UNKNOWN → reconcile.
            log.error("Stock order send error (UNKNOWN — will reconcile): {} {}", cmd.side, cmd.symbol, e)
            transition(saved, StockOrderStatus.UNKNOWN, failReason = e.message)
        }
    }

    /** tx2: SUBMITTING → newStatus 조건부 전이. affected=0 이면 이미 다른 경로가 전이(멱등 skip). */
    private suspend fun transition(
        entity: StockOrderIntentEntity,
        newStatus: StockOrderStatus,
        odno: String? = null,
        orgNo: String? = null,
        failReason: String? = null,
    ): StockOrderIntentEntity {
        val moved = repository.transition(
            id = entity.id!!,
            expected = StockOrderStatus.SUBMITTING.name,
            newStatus = newStatus.name,
            odno = odno,
            orgNo = orgNo,
            executedQty = 0,
            failReason = failReason?.take(255),
        ).awaitSingle()
        if (moved == 0L) {
            log.warn("Transition SUBMITTING -> {} skipped (already moved) id={}", newStatus, entity.id)
        }
        return entity.copy(
            status = newStatus.name,
            odno = odno ?: entity.odno,
            orgNo = orgNo ?: entity.orgNo,
            failReason = failReason ?: entity.failReason,
        )
    }

    /**
     * 모든 실주문이 지나는 공용 경계. 진입점(자율엔진 / 수동 REST)별로 가드를 흩뿌리면 한쪽이 빠지므로
     * 계좌 안전 불변식은 여기서 강제한다.
     */
    private suspend fun validate(client: KisClient, cmd: SubmitOrderCommand, dryRun: Boolean) {
        if (cmd.qty <= 0) throw StockOrderValidationException("qty must be > 0 (was ${cmd.qty})")
        if (cmd.orderType == KisOrderType.LIMIT && (cmd.price == null || cmd.price <= 0)) {
            throw StockOrderValidationException("limit order requires positive price")
        }

        // 시장가는 현재가 기준 추정. getCurrentPrice 는 0/실패 시 예외(가드 우회 방지).
        val unitPrice = cmd.price ?: try {
            client.getCurrentPrice(cmd.symbol)
        } catch (e: Exception) {
            throw StockOrderValidationException("cannot determine price for ${cmd.symbol}: ${e.message}")
        }
        if (unitPrice <= 0) throw StockOrderValidationException("non-positive price for ${cmd.symbol}")

        val cap = kisProperties.maxOrderAmount
        if (cap > 0) {
            val buffered = if (cmd.orderType == KisOrderType.MARKET) {
                (unitPrice * MARKET_SLIPPAGE_BUFFER).toLong()
            } else {
                unitPrice
            }
            // Long 곱셈 오버플로가 나면 음수·작은 양수로 접혀 cap 검증을 통과한다(수동 주문은 qty 상한이 없다).
            val notional = try {
                Math.multiplyExact(cmd.qty, buffered)
            } catch (_: ArithmeticException) {
                throw StockOrderValidationException("order notional overflows (qty=${cmd.qty}, unit=$buffered)")
            }
            if (notional > cap) {
                throw StockOrderValidationException("order notional $notional exceeds max-order-amount $cap")
            }
        }

        if (dryRun) return // 실송신이 없으므로 아래 계좌·시장 상태 불변식은 적용하지 않는다.

        // 컨트롤러 게이트를 통과한 뒤 DB·시세 조회 사이에 장이 닫힐 수 있다 — 송신 직전에 다시 본다.
        if (!marketCalendar.isTradingNow()) {
            throw StockOrderValidationException("market is closed")
        }

        if (cmd.side == KisSide.BUY) {
            // 미수 방지의 최종 상한. 엔진 sizing 에도 같은 값을 쓰지만, 수동 REST 는 sizing 을 거치지 않으므로
            // 여기서 막지 않으면 상한이 통째로 우회된다. 조회 실패는 fail-closed.
            val buyable = try {
                client.getBuyableQty(cmd.symbol, unitPrice)
            } catch (e: Exception) {
                throw StockOrderValidationException("buyable qty unavailable for ${cmd.symbol}: ${e.message}")
            }
            if (cmd.qty > buyable) {
                throw StockOrderValidationException("qty ${cmd.qty} exceeds buyable $buyable for ${cmd.symbol}")
            }
        }
    }

    companion object {
        private const val EXCHANGE = "KIS"
        private const val MARKET_SLIPPAGE_BUFFER = 1.1 // 시장가 명목금액 보수적 상향
    }
}
