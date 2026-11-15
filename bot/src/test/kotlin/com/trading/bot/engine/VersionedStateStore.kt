package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateConflictException
import com.trading.bot.persistence.TradingStateService
import io.mockk.coEvery

/**
 * 행마다 버전을 세는 가짜 저장. relaxed mock 은 늘 같은 값을 돌려줘 버전을 빠뜨린 경로를 숨긴다.
 * Spring Data 처럼 새 행은 0, 저장마다 1 씩 오르고, 들고 온 버전이 다르면 충돌이다.
 */
internal class VersionedStateStore(service: TradingStateService) {
    val versions = mutableMapOf<String, Long>()
    var writes = 0
        private set

    init {
        coEvery { service.upsert(any(), any()) } coAnswers {
            val state = secondArg<TradingState>()
            val stored = versions[state.ticker]
            if (stored != state.rowVersion) {
                throw TradingStateConflictException(state.ticker, "stored $stored, state ${state.rowVersion}")
            }
            writes++
            (stored?.plus(1) ?: 0L).also { versions[state.ticker] = it }
        }
    }

    /** 이 엔진이 모르는 다른 쓰기(다른 프로세스, 잠금을 잡지 않은 경로). */
    fun writeElsewhere(ticker: String) {
        versions[ticker] = versions.getValue(ticker) + 1
    }
}
