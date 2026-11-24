package com.trading.bot.auth

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration

/** 리프레시 토큰을 보지 않는 인증 시험용. 발급은 늘 같은 값을 돌려준다. */
internal fun refreshTokens(): RefreshTokenService = mockk(relaxed = true) {
    coEvery { issue(any(), any()) } returns RefreshTokenService.Issued("refresh-token", Duration.ofHours(1))
}
