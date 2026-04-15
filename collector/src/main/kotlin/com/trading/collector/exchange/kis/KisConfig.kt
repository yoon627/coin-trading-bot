package com.trading.collector.exchange.kis

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
@ConditionalOnProperty(prefix = "collector.kis", name = ["enabled"], havingValue = "true")
class KisConfig {

    @Bean
    fun kisAuthProvider(
        kisWebClient: WebClient,
        @Value("\${collector.kis.app-key:}") appKey: String,
        @Value("\${collector.kis.app-secret:}") appSecret: String,
        objectMapper: ObjectMapper,
    ): KisAuthProvider = KisAuthProvider(kisWebClient, appKey, appSecret, objectMapper)
}
