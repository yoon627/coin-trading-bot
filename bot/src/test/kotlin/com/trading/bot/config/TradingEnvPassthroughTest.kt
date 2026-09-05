package com.trading.bot.config

import com.trading.common.config.AccumulateProperties
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import com.trading.common.config.UniverseProperties
import java.io.File
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 앱이 읽는 `trading.*` 설정이 **운영 compose 의 전달 목록에 전부 있는가**.
 *
 * `docker-compose.prod.yml` 은 `TRADING_*` 를 **이름으로 일일이 나열**해야 컨테이너에 전달한다(#75).
 * 목록에서 빠지면 `.env` 에 적어도 앱까지 도달하지 않고 **조용히 기본값으로 돈다** — 켰다고 믿는데
 * 안 켜진 상태가 가장 나쁘다(실사고: PR #74 / issue #75, 그리고 2026-09-05 그림자 관측에서 재발).
 *
 * 그래서 "문서에 적어두기" 대신 테스트로 가둔다. 새 설정을 추가하면 이 테스트가 먼저 깨진다.
 */
class TradingEnvPassthroughTest {

    /**
     * 의도적으로 전달하지 않는 것. **사유 없이 여기 넣지 말 것** — 여기 넣는 순간 그 설정은
     * 운영에서 바꿀 수 없고, 그 사실이 이 목록 밖에서는 드러나지 않는다.
     */
    private val intentionallyNotPassed = emptyMap<String, String>()

    @Test
    fun `every trading property is listed in the production compose env passthrough`() {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다 —
        // 경로를 상수로 박으면 루트에서 돌릴 때와 서브프로젝트에서 돌릴 때가 갈린다.
        val compose = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "deploy/vultr/docker-compose.prod.yml") }
            .firstOrNull { it.exists() }
        assertTrue(compose != null) { "compose 를 못 찾았다 (cwd=${File("").absolutePath})" }
        requireNotNull(compose)
        val listed = compose.readLines()
            .mapNotNull { Regex("""^\s*-\s+(TRADING_[A-Z0-9_]+)\s*$""").find(it.trimEnd())?.groupValues?.get(1) }
            .toSet()
        assertTrue(listed.size > 10) { "compose 파싱 실패로 보인다 — 찾은 이름 ${listed.size}개" }

        val expected = listOf(
            TradingProperties::class, AccumulateProperties::class,
            UniverseProperties::class, ShadowExitProperties::class,
        ).flatMap { klass ->
            val prefix = klass.findAnnotation<ConfigurationProperties>()?.prefix
                ?: error("${klass.simpleName} 에 @ConfigurationProperties prefix 가 없다")
            // 생성자 파라미터 = 실제 설정 입력. 파생 캐시(`private val params` 등)는 env 로 주는 값이 아니다.
            (klass.primaryConstructor?.parameters ?: error("${'$'}{klass.simpleName} 에 주 생성자가 없다"))
                .mapNotNull { it.name }
                .map { envName(prefix, it) }
        }.toSet() - intentionallyNotPassed.keys

        val missing = (expected - listed).sorted()
        assertTrue(missing.isEmpty()) {
            "compose 전달 목록에 없다 → .env 에 적어도 앱에 도달하지 않는다: $missing\n" +
                "deploy/vultr/docker-compose.prod.yml 의 environment 목록에 추가하고 .env.example 에도 적을 것."
        }
    }

    /** `trading.shadow-exit` + `trailingStopPct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    private fun envName(prefix: String, property: String): String {
        val head = prefix.replace('.', '_').replace('-', '_')
        val tail = property.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        return "${head}_$tail".uppercase()
    }
}
