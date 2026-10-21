package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisApiProperties

enum class KisEnvironment {
    PAPER,
    LIVE,
    ;

    /** 모의 tr_id 는 실전 tr_id 의 첫 글자 T·J·C 를 V 로 바꾼 것이다. 시세 tr_id(FHK…)는 두 환경이 같다. */
    fun trId(liveTrId: String): String =
        if (this == PAPER && liveTrId.firstOrNull() in PAPER_PREFIXED) "V" + liveTrId.drop(1) else liveTrId

    fun baseUrl(properties: KisApiProperties): String = when (this) {
        PAPER -> properties.paperBaseUrl
        LIVE -> properties.liveBaseUrl
    }

    private companion object {
        val PAPER_PREFIXED = setOf('T', 'J', 'C')
    }
}
