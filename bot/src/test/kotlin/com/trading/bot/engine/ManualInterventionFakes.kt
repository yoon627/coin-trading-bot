package com.trading.bot.engine

import com.trading.bot.persistence.ManualInterventionService
import com.trading.bot.persistence.TradingStateService
import io.mockk.coEvery
import io.mockk.mockk

/** 기록은 건너뛰고 상태 저장만 실제로 부른다. 해제 시험의 저장 단언이 그대로 성립한다. */
internal fun savingInterventions(states: TradingStateService): ManualInterventionService = mockk {
    coEvery { saveWithRecord(any(), any(), any(), any()) } coAnswers { states.upsert(firstArg(), secondArg()) }
}
