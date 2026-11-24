package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.config.MarketBoardProperties
import com.trading.bot.marketdata.MarketStream
import kotlinx.coroutines.flow.Flow
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MarketStreamController(
    private val stream: MarketStream,
    private val board: MarketBoardProperties,
) {
    /**
     * 시세판 실시간 시세(SSE). 자리가 없으면 503 을 본문 없이 돌려주고, 화면이 잠시 뒤 다시 연다(EventSource 는 200 이 아닌
     * 응답 뒤에 스스로 다시 붙지 않는다). 자리는 여기서 잡지 않는다 — HEAD 는 본문을 읽지 않아 잡은 자리가 돌아오지 않는다.
     */
    @GetMapping("/api/market/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(): ResponseEntity<Flow<ServerSentEvent<MarketStream.Payload>>> {
        val userId = currentUserId()
        if (!board.enabled || !stream.hasRoom(userId)) return unavailable()
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream.events(userId))
    }

    private fun <T> unavailable(): ResponseEntity<T> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10").build()
}
