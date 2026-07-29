package com.trading.bot.kis.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class KisConfig {
    /** KST 기준 시계 — orderDate(YYYYMMDD) 및 reconcile grace 계산. 테스트는 고정 Clock 주입. */
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
