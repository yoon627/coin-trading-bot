package com.trading.bot.engine

/** 기동 때 실행 중이던 봇의 복원 진행. [UserTradingManager.restoreStatus] 가 내고 배포 판정(health)이 읽는다. */
enum class RestoreStatus {
    /** ApplicationReadyEvent 전. 웹 서버는 먼저 떠서 그사이 health 요청이 온다. */
    NOT_STARTED,

    /** 자동 시작이 꺼져 있어 복원하지 않는다. */
    DISABLED,

    IN_PROGRESS,
    DONE,

    /** 재시도를 다 쓰고도 복원하지 못한 봇이 있거나, 사용자 목록 없이 복원이 끝났다. */
    FAILED,
}
