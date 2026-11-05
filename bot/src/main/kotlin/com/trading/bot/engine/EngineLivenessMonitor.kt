package com.trading.bot.engine

import java.time.Clock
import java.util.Collections
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 도는 엔진 가운데 진행이 멈춘 루프를 알린다. 멈춤 한 번에 ERROR 한 번, 회복하면 INFO 한 번이다.
 * 끝난(죽은) 루프는 엔진이 끝나는 순간 이미 알려 여기서는 다루지 않는다.
 */
@Component
class EngineLivenessMonitor(
    private val managers: TradingManagers,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 엔진 인스턴스 기준 — 교체된 엔진이 옛 엔진의 알림 상태를 물려받지 않는다. 검사는 스케줄러 한 곳에서만 돈다.
    private val stalled: MutableSet<TradingEngine> = Collections.newSetFromMap(IdentityHashMap())

    fun check() {
        val now = clock.instant()
        val live = managers.all().flatMap { manager ->
            manager.liveEngines().map { (userId, engine) -> Triple(manager, userId, engine) }
        }
        stalled.retainAll(live.map { it.third }.toSet())
        for ((manager, userId, engine) in live) {
            when {
                !engine.isLoopAlive() -> stalled.remove(engine)
                engine.isStalled(now) -> if (stalled.add(engine)) {
                    log.error(
                        "[ENGINE STALLED] {} trading loop for user {} has made no progress since {} — 손절·청산 평가가 멈췄다. " +
                            "봇을 정지한 뒤 다시 시작하고, 정지가 끝나지 않으면 컨테이너를 재시작한다",
                        manager.exchange, userId, engine.lastProgressAt(),
                    )
                }
                stalled.remove(engine) ->
                    log.info("{} trading loop for user {} recovered (last progress {})", manager.exchange, userId, engine.lastProgressAt())
            }
        }
    }
}
