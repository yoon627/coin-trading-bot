package com.trading.bot.engine

/**
 * 봇 제어(정지·시작·halt 해제)의 상태 저장 실패. message 는 화면에 그대로 뜨므로
 * 무엇이 적용됐고 사용자가 무엇을 해야 하는지를 담는다.
 */
class BotControlPersistFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)

const val STOP_UNPERSISTED_MESSAGE: String =
    "봇은 멈췄지만 정지 상태를 저장하지 못했습니다. 서버가 정상 종료될 때 한 번 더 저장을 시도하지만, " +
        "그때도 저장하지 못하거나 그 전에 서버가 비정상 종료되면 재시작 때 봇이 다시 시작될 수 있습니다."

const val START_UNPERSISTED_MESSAGE: String =
    "봇 상태를 저장하지 못해 봇을 시작하지 않았습니다. 잠시 후 다시 시작하세요."

/** 실행 중인 봇에 같은 설정으로 시작했을 때 어긋난 복원 상태를 고쳐 저장하지 못한 경우. 실행 중 화면에는 정지 버튼만 있다. */
const val RUNNING_UNPERSISTED_MESSAGE: String =
    "봇은 실행 중이지만 재시작 때 복원에 쓰는 상태를 저장하지 못했습니다 — 이대로 서버가 재시작되면 봇이 복원되지 않을 수 있으니, " +
        "잠시 후 봇을 정지했다가 다시 시작하세요."

const val HALT_CLEAR_UNPERSISTED_MESSAGE: String =
    "halt 해제를 저장하지 못해 해제하지 않았습니다. 잠시 후 다시 시도하세요."

const val PENDING_CLEAR_UNPERSISTED_MESSAGE: String =
    "pending 해제를 저장하지 못해 해제하지 않았습니다. 잠시 후 다시 시도하세요."
