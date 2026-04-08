package com.trading.bot.auth

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.security.core.context.ReactiveSecurityContextHolder

suspend fun currentUserId(): Long {
    val auth = ReactiveSecurityContextHolder.getContext()
        .map { it.authentication }
        .awaitSingle()
    return auth.principal as Long
}
