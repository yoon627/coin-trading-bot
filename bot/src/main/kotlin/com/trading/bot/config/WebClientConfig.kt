package com.trading.bot.config

import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig(private val upbitProperties: UpbitProperties) {

    @Bean
    fun upbitWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(10))

        return WebClient.builder()
            .baseUrl(upbitProperties.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
            .build()
    }

    @Bean
    fun discordWebClient(): WebClient {
        return WebClient.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }
}
