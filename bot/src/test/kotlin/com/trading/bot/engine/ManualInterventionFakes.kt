package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.ManualInterventionService
import com.trading.bot.persistence.TradingStateService
import io.mockk.coEvery
import io.mockk.mockk

/** 기록은 건너뛰고 상태 저장만 실제로 부른다. 해제 시험의 저장 단언이 그대로 성립한다. 실제처럼 저장 뒤 새 버전을 넣는다. */
internal fun savingInterventions(states: TradingStateService): ManualInterventionService = mockk {
    coEvery { saveWithRecord(any(), any(), any(), any()) } coAnswers {
        val state = secondArg<TradingState>()
        state.rowVersion = states.upsert(firstArg(), state)
    }
}
