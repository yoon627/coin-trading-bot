package com.trading.bot.engine

/** 매매 값 미리보기 — 바뀌는 보유 포지션과, 이대로 저장할 때 보낼 확인 토큰. */
data class ParamsPreviewResult(val changes: List<PositionChange>, val confirmToken: String)
