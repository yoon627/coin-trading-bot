package com.trading.bot.kis.smoke

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.client.KisClientImpl
import com.trading.bot.kis.client.KisTokenProvider
import com.trading.bot.kis.domain.KisOrderRequest
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS 모의투자(paper) 실계정 스모크. **CI/일반 빌드에서 실행 안 됨** — `KIS_SMOKE=true` 일 때만.
 * 모의 도메인(:29443) 고정. 코드/단위테스트로 검증 불가한 항목(실 응답 필드·필수 query 파라미터·tr_id·
 * 토큰 발급)을 실 API 로 확인한다.
 *
 * 자격증명은 앱과 **같은 .env 변수**(KIS_APP_KEY/…)를 쓴다 — .env 에 한 번 넣으면 앱·스모크 공용.
 * 실행(시크릿이 대화/로그에 남지 않도록 **본인 터미널에서**; gradle 은 .env 를 자동 로드 안 하므로 source):
 *   set -a; source .env; set +a       # .env 의 KIS_APP_KEY 등을 셸 env 로
 *   export KIS_SMOKE=true              # 스모크 게이트(.env 에 안 넣었으면)
 *   JAVA_HOME=<jdk21> ./gradlew :bot:test --tests "*KisPaperSmokeTest*" -i
 *
 * 필요 .env 값: KIS_APP_KEY, KIS_APP_SECRET, KIS_CANO(앞 8자리), KIS_ACNT_PRDT_CD(뒤 2자리),
 *   KIS_PAPER=true(모의), (선택) KIS_SMOKE_SYMBOL(기본 005930).
 *
 * 주문까지 검증(1주 지정가 매수) — 추가로:
 *   export KIS_SMOKE_PLACE=true
 *   export KIS_SMOKE_LIMIT_PRICE=50000 # 체결 안 되게 시장가보다 충분히 낮은 유효 호가 권장
 * 남은 미체결 주문은 모의 HTS/앱에서 취소하거나 장 마감 시 자동 실효.
 */
@EnabledIfEnvironmentVariable(named = "KIS_SMOKE", matches = "true")
class KisPaperSmokeTest {

    private val symbol = System.getenv("KIS_SMOKE_SYMBOL")?.takeIf { it.isNotBlank() } ?: "005930"

    private fun env(key: String): String =
        requireNotNull(System.getenv(key)?.takeIf { it.isNotBlank() }) { "$key 환경변수 필요" }

    private val paper = System.getenv("KIS_PAPER")?.lowercase() != "false" // 기본 true(모의)

    private fun client(): KisClient {
        val baseUrl = if (paper) {
            System.getenv("KIS_PAPER_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://openapivts.koreainvestment.com:29443"
        } else {
            System.getenv("KIS_REAL_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://openapi.koreainvestment.com:9443"
        }
        val webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
            .build()
        val appKey = env("KIS_APP_KEY")
        val appSecret = env("KIS_APP_SECRET")
        val tokenProvider = KisTokenProvider(webClient, appKey, appSecret, refreshSkewSeconds = 600)
        println("[KIS smoke] target=${if (paper) "PAPER(모의)" else "REAL(실전)"} $baseUrl")
        return KisClientImpl(
            webClient, tokenProvider, appKey, appSecret,
            cano = env("KIS_CANO"), acntPrdtCd = env("KIS_ACNT_PRDT_CD"),
            paper = paper, custType = "P",
        )
    }

    private fun today(): String =
        LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)

    @Test
    fun `read path - token, current price, holdings, daily conclusions`() = runBlocking {
        val c = client()

        val price = c.getCurrentPrice(symbol)
        println("[KIS smoke] $symbol 현재가 = $price 원")
        assertTrue(price > 0) { "현재가 조회 실패(0/예외) — tr_id/파라미터/토큰 확인" }

        val holdings = c.getHoldings()
        println("[KIS smoke] 보유종목 ${holdings.size}건: " + holdings.joinToString { "${it.pdno}x${it.heldQty()}@${it.avgBuyPrice()}" })

        val ccld = c.inquireDailyConclusions(today())
        println("[KIS smoke] 당일주문 ${ccld.size}건: " + ccld.joinToString { "${it.odno}/${it.pdno}/${it.side()}/체결${it.executedQty()}/잔여${it.remainingQty()}" })
        // 잔고/당일주문은 비어 있을 수 있음 — 예외 없이 파싱되면 성공(필드 매핑 검증).
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KIS_SMOKE_PLACE", matches = "true")
    fun `place path - 1-share limit buy on paper then inquiry`() = runBlocking {
        val limitPrice = requireNotNull(System.getenv("KIS_SMOKE_LIMIT_PRICE")?.toLongOrNull()) {
            "KIS_SMOKE_LIMIT_PRICE 환경변수 필요(유효 호가, 미체결되게 낮게 권장)"
        }
        val c = client()
        val ack = c.placeOrder(
            KisOrderRequest(
                cano = env("KIS_CANO"), acntPrdtCd = env("KIS_ACNT_PRDT_CD"),
                symbol = symbol, side = KisSide.BUY, orderType = KisOrderType.LIMIT,
                qty = 1, price = limitPrice,
            ),
        )
        println("[KIS smoke] 주문 접수 ODNO=${ack.odno} orgNo=${ack.orgNo} time=${ack.ordTmd}")
        assertTrue(ack.odno.isNotBlank()) { "ODNO 없음 — 주문 응답 매핑/tr_id 확인" }

        val mine = c.inquireDailyConclusions(today()).find { it.odno == ack.odno }
        println("[KIS smoke] 당일조회에서 내 주문: $mine")
        assertTrue(mine != null) { "방금 낸 주문이 당일조회에 없음 — 연속조회/매칭 점검" }
    }
}
