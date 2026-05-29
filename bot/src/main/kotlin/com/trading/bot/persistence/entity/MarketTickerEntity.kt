package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("market_tickers")
data class MarketTickerEntity(
    @Id val id: Long? = null,
    val exchange: String,
    val market: String,
    val price: Double,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    // R2DBC 기본 snake_case 변환은 'volume24h' → 'volume24h' 로 두기 때문에 DB 컬럼('volume_24h')과
    // 어긋나 INSERT 가 bad SQL grammar 로 모두 실패한다. 명시적 @Column 으로 매핑 고정.
    @Column("volume_24h") val volume24h: Double? = null,
    @Column("quote_volume_24h") val quoteVolume24h: Double? = null,
    @Column("change_rate_24h") val changeRate24h: Double? = null,
    @Column("high_price_24h") val highPrice24h: Double? = null,
    @Column("low_price_24h") val lowPrice24h: Double? = null,
    val recordedAt: Instant = Instant.now(),
)
