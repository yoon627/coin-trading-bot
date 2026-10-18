package com.trading.bot.engine

/**
 * 자격증명·설정은 저장됐지만 런타임 교체에 실패한 경우. 호출자는 저장 실패가 아니라
 * 반영 실패로 응답해야 사용자가 키를 다시 입력하지 않는다.
 */
class RuntimeReloadFailedException(
    val userId: Long,
    cause: Throwable,
    /** false 면 이전 엔진도 되살리지 못해 봇이 정지돼 있고 손절도 돌지 않는다. */
    val engineRestored: Boolean,
) : RuntimeException(
    "user $userId 런타임 교체 실패 — " +
        if (engineRestored) "이전 자격증명 엔진으로 복귀" else "복귀 실패, 엔진 정지 상태",
    cause,
)

/** 화면에 그대로 뜨는 문구. 저장 실패로 읽히지 않게 이전 설정으로 거래 중임을 함께 알린다. */
const val RELOAD_FAILED_MESSAGE: String =
    "저장은 완료됐지만 실행 중인 봇에 반영하지 못했습니다. " +
        "봇은 이전 설정(자격증명·웹훅)으로 계속 거래 중이니, 잠시 후 다시 저장해 반영하세요."

/** 이전 엔진 복구까지 실패해 봇이 정지된 경우. 손절이 돌지 않으므로 즉시 조치를 요구한다. */
const val RELOAD_FAILED_ENGINE_STOPPED_MESSAGE: String =
    "저장은 완료됐지만 봇을 다시 시작하지 못해 봇이 정지된 상태입니다. " +
        "보유 포지션의 자동 손절이 동작하지 않으니, 봇을 수동으로 시작하거나 포지션을 직접 확인하세요."

fun reloadFailureMessage(e: RuntimeReloadFailedException): String =
    if (e.engineRestored) RELOAD_FAILED_MESSAGE else RELOAD_FAILED_ENGINE_STOPPED_MESSAGE
