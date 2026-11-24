package com.trading.bot.events

import com.trading.bot.marketdata.StreamSlots
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component

/**
 * 사용자별 "바뀜" 신호(SSE). 연결마다 먼저 `hello`, 그 뒤 [interval] 마다 [UserEventHub] 번호를 당겨 읽어 바뀐 종류만
 * (`trade`·`state`) 보낸다. 데이터는 보내지 않는다 — 화면이 받는 즉시 REST 로 다시 읽는다.
 * [lifetime] 뒤에 끊어, 브라우저가 다시 붙을 때 인증을 다시 거친다(로그아웃·만료된 토큰으로 열려 있지 않게).
 */
@Component
class UserEventStream internal constructor(
    private val hub: UserEventHub,
    maxConnections: Int,
    maxPerUser: Int,
    private val interval: Duration,
    private val heartbeat: Duration,
    private val lifetime: Duration,
    private val dispatcher: CoroutineDispatcher,
) : SmartLifecycle {
    @Autowired
    constructor(hub: UserEventHub) :
        this(hub, MAX_CONNECTIONS, MAX_PER_USER, Duration.ofMillis(500), Duration.ofSeconds(15), Duration.ofHours(1), Dispatchers.Default)

    companion object {
        // 시세 스트림과 따로 센다 — 탭마다 하나씩 여는 가벼운 연결이라 사용자당 몫을 넓힌다.
        private const val MAX_CONNECTIONS = 200
        private const val MAX_PER_USER = 5

        // 배포로 모두 끊겨도 한꺼번에 몰리지 않게 브라우저 기본(약 3초)보다 길게.
        internal val RETRY: Duration = Duration.ofSeconds(5)
    }

    private val slots = StreamSlots("User event stream", maxConnections, maxPerUser)

    @Volatile
    private var running = false

    private val closing get() = slots.closing

    /** 지금 이 사용자에게 자리가 있는가. 응답 전에 503 을 정하는 데만 쓴다 — 자리는 본문을 읽기 시작할 때 [events] 가 잡는다. */
    fun hasRoom(userId: Long): Boolean = slots.hasRoom(userId)

    internal fun tryOpen(userId: Long): StreamSlots.Lease? = slots.tryOpen(userId)

    fun openConnections(): Int = slots.openConnections()

    // EventSource 는 data 가 빈 이벤트를 내지 않으므로 그 종류의 번호를 담는다(화면은 읽지 않는다).
    fun events(userId: Long): Flow<ServerSentEvent<String>> = flow {
        val lease = tryOpen(userId) ?: return@flow
        try {
            var last = hub.versions(userId)
            emit(ServerSentEvent.builder("0").event("hello").retry(RETRY).build())
            var quiet = Duration.ZERO
            // 읽지 않는 연결도 수명을 넘기지 않게 시계로 잰다(보내기를 기다린 시간도 든다).
            withTimeoutOrNull(lifetime.toKotlinDuration()) {
                while (!closing) {
                    delay(interval.toKotlinDuration())
                    if (closing) break
                    val now = hub.versions(userId)
                    val changed = UserEventHub.Kind.entries.filter { now[it.ordinal] != last[it.ordinal] }
                    last = now
                    if (changed.isNotEmpty()) {
                        changed.forEach { emit(ServerSentEvent.builder(now[it.ordinal].toString()).event(it.event).build()) }
                        quiet = Duration.ZERO
                    } else {
                        quiet += interval
                        if (quiet >= heartbeat) {
                            emit(ServerSentEvent.builder<String>().comment("ping").build())
                            quiet = Duration.ZERO
                        }
                    }
                }
            }
        } finally {
            lease.release()
        }
    }
        // 받을 차례에 그때까지의 변화를 새로 묶는다 — 느린 화면에도 서버 쪽에 쌓이지 않는다.
        .buffer(Channel.RENDEZVOUS)
        .flowOn(dispatcher)

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
