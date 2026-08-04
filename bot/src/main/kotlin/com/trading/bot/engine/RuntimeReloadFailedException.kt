package com.trading.bot.engine

/**
 * 자격증명·설정 변경 후 런타임 교체가 실패해 **이전 자격증명을 쓰는 엔진이 되살아난 상태**.
 *
 * 저장 자체는 이미 성공했으므로 호출자는 "저장 실패"가 아니라 "반영 실패 + 이전 설정으로 거래 중"
 * 으로 응답해야 한다 — 그 구분이 없으면 사용자가 키를 다시 입력하는 헛수고를 한다.
 */
class RuntimeReloadFailedException(
    val userId: Long,
    cause: Throwable,
    /** 이전 엔진을 되살리는 데 성공했는지. false 면 봇이 **정지된 채** 남아 손절도 돌지 않는다. */
    val engineRestored: Boolean,
) : RuntimeException(
    "user $userId 런타임 교체 실패 — " +
        if (engineRestored) "이전 자격증명 엔진으로 복귀" else "복귀 실패, 엔진 정지 상태",
    cause,
)

/**
 * 사용자에게 그대로 노출되는 문구. 프론트(`tide-app/api.js`)가 `!res.ok` 일 때 이 message 를
 * 그대로 띄우므로, "저장 실패"로 읽히지 않게 저장 성공·미반영·이전 설정으로 거래 중·재시도를
 * 모두 담는다.
 */
const val RELOAD_FAILED_MESSAGE: String =
    "저장은 완료됐지만 실행 중인 봇에 반영하지 못했습니다. " +
        "봇은 이전 설정(자격증명·웹훅)으로 계속 거래 중이니, 잠시 후 다시 저장해 반영하세요."

/**
 * 되살리기까지 실패해 봇이 정지된 경우. 위와 정반대 상황이므로 문구를 공유하면 안 된다 —
 * 보유 포지션의 손절이 돌지 않으므로 사용자가 즉시 조치해야 한다.
 */
const val RELOAD_FAILED_ENGINE_STOPPED_MESSAGE: String =
    "저장은 완료됐지만 봇을 다시 시작하지 못해 봇이 정지된 상태입니다. " +
        "보유 포지션의 자동 손절이 동작하지 않으니, 봇을 수동으로 시작하거나 포지션을 직접 확인하세요."

/** 복구 성공 여부에 맞는 안내 문구. 둘을 섞으면 사용자가 정반대 상황으로 오해한다. */
fun reloadFailureMessage(e: RuntimeReloadFailedException): String =
    if (e.engineRestored) RELOAD_FAILED_MESSAGE else RELOAD_FAILED_ENGINE_STOPPED_MESSAGE
