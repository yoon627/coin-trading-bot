package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ml")
data class MlProperties(
    val modelDir: String = "./ml-models",
    val autoLoadOnStartup: Boolean = true,
    val autoRetrainEnabled: Boolean = false,
    val retrainCron: String = "0 0 4 * * *",
)
