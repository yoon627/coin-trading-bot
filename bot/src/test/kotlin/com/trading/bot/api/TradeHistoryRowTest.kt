package com.trading.bot.api

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.FillSource
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** orders 한 줄에서 지금 화면(`/api/trades`)이 trade_records 에서 내는 것과 같은 값을 만든다. */
class TradeHistoryRowTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val at = LocalDateTime.of(2026, 11, 23, 8, 58, 22, 123_456_000)

    private fun order(
        side: String,
        fillSource: String?,
        decisionPrice: Double? = null,
        requestedVolume: Double? = null,
        filledVolume: Double? = null,
        filledFunds: Double? = null,
        positionVolumeAfter: Double? = null,
        bookAvgPrice: Double? = null,
        completedAt: Instant? = at.toInstant(ZoneOffset.UTC),
    ) = OrderRowEntity(
        id = 9, userId = 4, exchange = "UPBIT", market = "KRW-BTC", side = side, status = "FILLED",
        decisionPrice = decisionPrice, requestedVolume = requestedVolume, filledVolume = filledVolume, filledFunds = filledFunds,
        positionVolumeAfter = positionVolumeAfter, bookAvgPrice = bookAvgPrice, pnlPercent = 1.5, pnlAmount = 3.0,
        reason = "TAKE_PROFIT", strategy = "combined", fillSource = fillSource, completedAt = completedAt,
    )

    private fun record(side: String, price: Double, volume: Double, total: Double, orderAmount: Double?) = TradeRecordEntity(
        id = 3, ticker = "KRW-BTC", side = side, price = price, volume = volume, totalAmount = total, orderAmount = orderAmount,
        pnlPercent = 1.5, pnlAmount = 3.0, reason = "TAKE_PROFIT", strategy = "combined", userId = 4, createdAt = at,
    )

    private fun TradeHistoryRow.withoutId() = copy(id = null, fillSource = null)

    @Test
    fun `each kind of orders row gives the same values the trade record gave`() {
        val cases = listOf(
            // 매도: 판단 가격 × 이 주문 수량.
            order("SELL", "ORDER", decisionPrice = 110.0, filledVolume = 2.0, filledFunds = 218.0) to record("SELL", 110.0, 2.0, 220.0, 218.0),
            order("SELL", "BACKFILL", decisionPrice = 110.0, filledVolume = 2.0, filledFunds = null) to record("SELL", 110.0, 2.0, 220.0, null),
            // 잔고로 확정한 매도는 주문 시점 수량.
            order("SELL", "BALANCE", decisionPrice = 150.0, requestedVolume = 0.7) to record("SELL", 150.0, 0.7, 150.0 * 0.7, null),
            // 엔진 매수: 체결 뒤 장부 평단 × 장부 수량(스냅숏).
            order("BUY", "ORDER", filledVolume = 0.5, filledFunds = 50.0, positionVolumeAfter = 2.0, bookAvgPrice = 100.0) to
                record("BUY", 100.0, 2.0, 200.0, 50.0),
            order("BUY", "BACKFILL_SNAPSHOT", filledFunds = 150.0, positionVolumeAfter = 2.0, bookAvgPrice = 100.0) to
                record("BUY", 100.0, 2.0, 200.0, 150.0),
            order("BUY", "BALANCE", positionVolumeAfter = 0.0011, bookAvgPrice = 51_000_000.0) to record("BUY", 51_000_000.0, 0.0011, 51_000_000.0 * 0.0011, null),
            // 옛 수동 매수: 그 주문 하나.
            order("BUY", "BACKFILL_MANUAL", decisionPrice = 90.0, filledVolume = 1.0, filledFunds = 90.0) to record("BUY", 90.0, 1.0, 90.0, null),
        )
        for ((row, expected) in cases) {
            val projected = row.toHistoryRow(utc)
            assertThat(projected.withoutId()).describedAs("${row.side} ${row.fillSource}").isEqualTo(expected.toHistoryRow().withoutId())
            assertThat(projected.incomplete).isFalse()
        }
    }

    @Test
    fun `a missing value or unknown source does not throw — the row is marked incomplete`() {
        val missing = order("SELL", "ORDER", decisionPrice = null, filledVolume = 1.0).toHistoryRow(utc)
        assertThat(missing.incomplete).isTrue()
        assertThat(missing.price).isEqualTo(0.0)

        val unknown = order("BUY", "SOMETHING_NEW", positionVolumeAfter = 1.0, bookAvgPrice = 10.0).toHistoryRow(utc)
        assertThat(unknown.fillSource).isNull()
        assertThat(unknown.totalAmount).isEqualTo(10.0)
        assertThat(FillSource.parseOrNull("SOMETHING_NEW")).isNull()
        assertThat(FillSource.parseOrNull(null)).isNull()
    }

    @Test
    fun `the time is the completed instant in the given zone`() {
        val seoul = order("SELL", "ORDER", decisionPrice = 1.0, filledVolume = 1.0).toHistoryRow(ZoneId.of("Asia/Seoul"))
        assertThat(seoul.createdAt).isEqualTo(at.plusHours(9))
    }

    // 화면이 읽는 JSON 모양: 같은 키, 같은 순서, exchange·출처·incomplete 는 없다.
    @Test
    fun `the JSON shape matches what the trades endpoint sends today`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val today = mapper.readTree(mapper.writeValueAsString(record("SELL", 110.0, 2.0, 220.0, 218.0)))
        val projected = mapper.readTree(
            mapper.writeValueAsString(order("SELL", "ORDER", decisionPrice = 110.0, filledVolume = 2.0, filledFunds = 218.0).toHistoryRow(utc)),
        )

        assertThat(projected.fieldNames().asSequence().toList()).containsExactlyElementsOf(today.fieldNames().asSequence().toList())
        assertThat(projected.get("created_at")).isEqualTo(today.get("created_at"))
    }
}
