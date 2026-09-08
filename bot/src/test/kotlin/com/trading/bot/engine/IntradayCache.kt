package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * 240분보다 짧은 분봉 fixture — `scripts/collect_intraday_fixtures.py --unit 15|5 --write` 가
 * `<repo>/backtest-cache/intraday{unit}/<regime>/<market>.json.gz` 에 둔다. 수백 MB 라 저장소에 넣지 않는다.
 *
 * 없으면 **실패**한다(skip 아님 — 건너뛴 판정이 초록불로 위장하는 것을 막는다, `lesson-skip-is-not-pass`).
 * [IntradayFixtures] 와 같은 최신순 규약.
 */
internal object IntradayCache {

    private val mapper = jacksonObjectMapper()

    /** 저장소 루트 — 테스트는 `bot/` 에서 돌아 `user.dir` 이 모듈 디렉토리다. `settings.gradle.kts` 를 만날 때까지 올라간다. */
    fun repoRoot(): Path {
        var p: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.parent
        return requireNotNull(p) { "settings.gradle.kts 를 가진 저장소 루트를 찾지 못했다" }
    }

    fun path(unit: Int, dir: String, market: String): Path =
        repoRoot().resolve("backtest-cache").resolve("intraday$unit").resolve(dir).resolve("$market.json.gz")

    fun available(unit: Int, dir: String, markets: Collection<String>): Boolean =
        markets.all { Files.exists(path(unit, dir, it)) }

    fun load(unit: Int, dir: String, market: String): List<Candle> {
        val p = path(unit, dir, market)
        require(Files.exists(p)) { "분봉 캐시 없음: $p — python3 scripts/collect_intraday_fixtures.py --unit $unit --only $dir --write" }
        return GZIPInputStream(Files.newInputStream(p)).use { mapper.readValue(it) }
    }

    fun loadAll(unit: Int, dir: String, markets: Collection<String>): Map<String, List<Candle>> =
        markets.associateWith { load(unit, dir, it) }
}
