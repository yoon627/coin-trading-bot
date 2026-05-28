package com.trading.bot.config

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
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

    // 인증 불필요한 public 엔드포인트(시세/캔들)용 공용 클라이언트.
    // 주문/잔고 등 인증 필요 호출은 UserTradingManager 가 사용자 키로 별도 생성한다.
    @Bean
    fun publicUpbitClient(): UpbitClient = UpbitClientImpl(upbitWebClient(), authProvider = null)

    @Bean
    fun discordWebClient(): WebClient {
        return WebClient.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }
}
