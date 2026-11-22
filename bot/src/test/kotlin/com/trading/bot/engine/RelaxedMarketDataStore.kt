package com.trading.bot.engine

import com.trading.bot.marketdata.MarketDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow

/** 느슨한 mock 의 체결 흐름은 바로 끝나 엔진이 수집기를 죽은 것으로 본다([ENGINE DEAD]). 체결이 오지 않는 열린 흐름을 준다. */
internal fun relaxedMarketDataStore(block: MarketDataStore.() -> Unit = {}): MarketDataStore =
    mockk<MarketDataStore>(relaxed = true) {
        every { trades } returns MutableSharedFlow()
        block()
    }
