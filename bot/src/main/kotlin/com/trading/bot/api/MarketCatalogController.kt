package com.trading.bot.api

import com.trading.bot.marketdata.MarketCatalog
import com.trading.common.domain.MarketPair
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 감시 코인 검색 화면이 쓰는 업비트 KRW 마켓 목록. */
@RestController
class MarketCatalogController(
    private val catalog: MarketCatalog,
) {
    @GetMapping("/api/market/markets")
    suspend fun markets(): ResponseEntity<Markets> {
        val listings = catalog.listings()
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60").build()
        return ResponseEntity.ok(Markets(listings.map { Market(MarketPair.toUpbitFormat(it.market), it.koreanName, it.englishName) }))
    }

    data class Markets(val markets: List<Market>)

    data class Market(val market: String, val koreanName: String, val englishName: String)
}
