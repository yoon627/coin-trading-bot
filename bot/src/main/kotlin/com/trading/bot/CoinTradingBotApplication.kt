package com.trading.bot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = ["com.trading.bot", "com.trading.common.config"])
@EnableScheduling
class CoinTradingBotApplication

fun main(args: Array<String>) {
    runApplication<CoinTradingBotApplication>(*args)
}
