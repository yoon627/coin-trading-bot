package com.trading.bot.marketdata

import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedTicker
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component

/**
 * 시세판 저장소를 접속한 사용자마다 SSE 로 나눠 보낸다. 처음에 전체를 한 번, 그 뒤 [interval] 마다 바뀐 마켓만 묶어 보낸다.
 * 사용자마다 마지막 받은 번호 하나만 들고 당겨 읽어, 느린 사용자도 마켓을 잃지 않고 저장소(netty 스레드)는 아무도 깨우지 않는다.
 * 종료 때는 웹 서버 graceful 단계보다 먼저 모든 스트림을 끝내, 열린 화면이 배포의 drain 을 붙잡지 않게 한다.
 */
@Component
class MarketStream internal constructor(
    private val store: MarketBoardStore,
    private val maxConnections: Int,
    private val maxPerUser: Int,
    private val interval: Duration,
    private val heartbeat: Duration,
    private val dispatcher: CoroutineDispatcher,
) : SmartLifecycle {
    @Autowired
    constructor(store: MarketBoardStore) :
        this(store, MAX_CONNECTIONS, MAX_PER_USER, Duration.ofSeconds(1), Duration.ofSeconds(15), Dispatchers.Default)

    companion object {
        // 2GB 운영 서버라 측정 전에는 보수적으로 둔다.
        private const val MAX_CONNECTIONS = 500
        private const val MAX_PER_USER = 3

        // 끊긴 뒤 브라우저의 자동 재연결 간격. 배포로 모두 끊겨도 한꺼번에 몰리지 않게 기본(약 3초)보다 길게.
        internal val RETRY: Duration = Duration.ofSeconds(5)
    }

    /** 화면이 받는 한 줄. 키를 짧게 둬 300개 snapshot 을 작게 한다. */
    data class Row(val m: String, val p: Double, val r: Double, val v: Double, val t: Long)

    data class Payload(val rows: List<Row>, val removed: List<String> = emptyList())

    private val slots = StreamSlots("Market stream", maxConnections, maxPerUser)

    @Volatile
    private var running = false

    private val closing get() = slots.closing

    /** 지금 이 사용자에게 자리가 있는가. 응답 전에 503 을 정하는 데만 쓴다 — 자리는 본문을 읽기 시작할 때 [events] 가 잡는다. */
    fun hasRoom(userId: Long): Boolean = slots.hasRoom(userId)

    /** 자리가 있으면 잡는다. 없으면(전체·사용자 상한, 종료 중) null. */
    internal fun tryOpen(userId: Long): StreamSlots.Lease? = slots.tryOpen(userId)

    fun openConnections(): Int = slots.openConnections()

    /**
     * 사용자 스트림. 자리는 본문을 읽기 시작할 때 잡고 끝날 때 돌려준다 — HEAD 처럼 본문을 읽지 않는 요청이나 쓰기 전에 끊긴 요청은
     * 자리를 잡지 않는다. 그사이 자리가 찼으면 아무것도 보내지 않고 끝난다(브라우저가 다시 붙는다).
     */
    fun events(userId: Long): Flow<ServerSentEvent<Payload>> = flow {
        val lease = tryOpen(userId) ?: return@flow
        try {
            val snapshot = store.snapshot()
            var last = snapshot.upTo
            emit(ServerSentEvent.builder(Payload(snapshot.tickers.map(::row))).event("snapshot").retry(RETRY).build())
            var quiet = Duration.ZERO
            while (!closing) {
                delay(interval.toKotlinDuration())
                if (closing) break
                if (store.version() > last) {
                    val changes = store.changedSince(last)
                    last = changes.upTo
                    emit(ServerSentEvent.builder(Payload(changes.tickers.map(::row), changes.removed.map(MarketPair::toUpbitFormat))).event("delta").build())
                    quiet = Duration.ZERO
                } else {
                    quiet += interval
                    // 프록시·브라우저가 조용한 연결을 끊지 않게 주석 한 줄을 보낸다.
                    if (quiet >= heartbeat) {
                        emit(ServerSentEvent.builder<Payload>().comment("ping").build())
                        quiet = Duration.ZERO
                    }
                }
            }
        } finally {
            lease.release()
        }
    }
        // 코루틴 쪽 버퍼를 두지 않는다 — 받을 차례에 그때까지의 변경을 새로 묶는다. 전송 계층(reactor-netty)은 그 아래에서 몇십 개를 더 쌓을 수 있다.
        .buffer(Channel.RENDEZVOUS)
        .flowOn(dispatcher)

    private fun row(t: NormalizedTicker) =
        Row(MarketPair.toUpbitFormat(t.market), t.price, t.changeRate24h, t.quoteVolume24h, t.timestamp.toEpochMilli())

    override fun start() {
        slots.closing = false
        running = true
    }

    override fun stop() {
        slots.closing = true
        running = false
    }

    override fun isRunning(): Boolean = running

    // 높은 phase 가 먼저 멈춘다 — 웹 서버가 진행 중 요청을 기다리기(graceful) 전에 스트림부터 끝낸다.
    override fun getPhase(): Int = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1
}
