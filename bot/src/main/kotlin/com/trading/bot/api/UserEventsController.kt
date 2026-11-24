package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.events.UserEventStream
import kotlinx.coroutines.flow.Flow
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class UserEventsController(private val stream: UserEventStream) {
    /**
     * 내 체결·봇 상태가 바뀌었다는 신호(SSE). 자리가 없으면 본문 없는 503 — 화면은 polling 만으로 계속하고 잠시 뒤 다시 연다.
     * 자리는 여기서 잡지 않는다 — HEAD 는 본문을 읽지 않아 잡은 자리가 돌아오지 않는다.
     */
    @GetMapping("/api/user/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(): ResponseEntity<Flow<ServerSentEvent<String>>> {
        val userId = currentUserId()
        if (!stream.hasRoom(userId)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10").build()
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream.events(userId))
    }
}
