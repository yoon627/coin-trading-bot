package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.ShadowExitObservationRepository
import com.trading.bot.persistence.entity.ShadowExitObservationEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * 그림자 관측기는 **기록만** 한다. 이 테스트가 가두는 계약은 셋이다 —
 * 포지션당 1건, 저장 실패가 매매를 막지 않음, 그리고 `modeled ≥ observed`(모델이 낙관인 방향).
 */
class ShadowExitObserverTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC)

    private fun observer(repo: ShadowExitObservationRepository) =
        ShadowExitObserver(repo, userId = 1L, trailingStopPct = 1.5, trailingArmPct = 0.0, clock = clock)

    /** 진입 100 → 고점 110 → 현재가 108. 고점 대비 −1.82% 라 트레일링 1.5% 가 발동한다. */
    private fun armedState() = TradingState("KRW-BTC").apply {
        position = true
        avgBuyPrice = 100.0
        peakPrice = 110.0
    }

    @Test
    fun `records one observation per position with the modelled price above the observed tick`() = runBlocking {
        val repo = mockk<ShadowExitObservationRepository>()
        val captured = slot<ShadowExitObservationEntity>()
        every { repo.save(capture(captured)) } returns Mono.empty()
        val o = observer(repo)
        val state = armedState()

        o.onTick("KRW-BTC", state, 108.0)
        o.onTick("KRW-BTC", state, 107.0) // 두 번째 발동은 무시된다 — 이후는 두 팔이 갈라진다
        o.onLiveExit("KRW-BTC", exitPrice = 105.0, reason = "DAILY_RESET")

        verify(exactly = 1) { repo.save(any<ShadowExitObservationEntity>()) }
        val e = captured.captured
        assertEquals(110.0, e.peakPrice)
        assertEquals(108.0, e.observedTickPrice)
        // 백테가 체결됐다고 보는 값 = peak × (1 − 1.5/100)
        assertTrue(abs(e.modeledExitPrice - 110.0 * 0.985) < 1e-9)
        // 모델은 실제 발동 tick 이상이다 — 이 부등호가 깨지면 "모델 과대추정폭" 이라는 해석 자체가 무너진다.
        assertTrue(e.modeledExitPrice >= e.observedTickPrice)
        assertEquals("DAILY_RESET", e.liveExitReason)
    }

    @Test
    fun `does not record when the candidate gate never fires`() = runBlocking {
        val repo = mockk<ShadowExitObservationRepository>()
        val o = observer(repo)
        val state = armedState()

        o.onTick("KRW-BTC", state, 109.9) // 고점 대비 −0.09% — 발동하지 않는다
        o.onLiveExit("KRW-BTC", exitPrice = 109.0, reason = "DAILY_RESET")

        verify(exactly = 0) { repo.save(any<ShadowExitObservationEntity>()) }
    }

    @Test
    fun `save failure is swallowed so trading is never blocked`() = runBlocking {
        val repo = mockk<ShadowExitObservationRepository>()
        every { repo.save(any<ShadowExitObservationEntity>()) } throws RuntimeException("db down")
        val o = observer(repo)
        val state = armedState()

        o.onTick("KRW-BTC", state, 108.0)
        // 던지면 이 호출이 매도 경로를 깨뜨린다 — 삼켜야 한다.
        o.onLiveExit("KRW-BTC", exitPrice = 105.0, reason = "TRAILING_STOP")
    }

    @Test
    fun `forget clears state so the next position is not paired with the previous one`() = runBlocking {
        val repo = mockk<ShadowExitObservationRepository>()
        val o = observer(repo)
        val state = armedState()

        o.onTick("KRW-BTC", state, 108.0)
        o.forget("KRW-BTC")
        o.onLiveExit("KRW-BTC", exitPrice = 105.0, reason = "DAILY_RESET")

        verify(exactly = 0) { repo.save(any<ShadowExitObservationEntity>()) }
    }

    @Test
    fun `ignores flat or uninitialised positions`() = runBlocking {
        val repo = mockk<ShadowExitObservationRepository>()
        val o = observer(repo)

        o.onTick("KRW-BTC", TradingState("KRW-BTC"), 108.0) // position=false
        o.onTick("KRW-BTC", TradingState("KRW-BTC").apply { position = true }, 108.0) // 평단·peak 0
        o.onLiveExit("KRW-BTC", exitPrice = 105.0, reason = "DAILY_RESET")

        verify(exactly = 0) { repo.save(any<ShadowExitObservationEntity>()) }
    }
}
