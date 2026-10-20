package com.trading.common.domain

/** 모의(KIS_PAPER)와 실전(KIS)은 상태·기록·키가 섞이지 않도록 다른 값으로 둔다. */
enum class Exchange {
    UPBIT,
    KIS_PAPER,
    KIS,
}
