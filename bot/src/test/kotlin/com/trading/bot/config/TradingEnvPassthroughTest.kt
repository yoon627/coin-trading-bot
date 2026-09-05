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
 * 앱이 읽는 `trading.*` 설정이 **배포 전달 화이트리스트 둘 다에** 있는가.
 *
 * 전달 경로에는 화이트리스트가 **둘** 있고 **둘 다** 통과해야 값이 앱에 닿는다:
 *   1. `deploy/vultr/deploy.sh` 의 `TRADING_OVERRIDE_KEYS` — 서버 `.env` 에 그 줄을 쓸지
 *   2. `deploy/vultr/docker-compose.prod.yml` 의 `environment:` — 그 값을 컨테이너에 넘길지
 *
 * 한쪽에만 있으면 `.env` 에 적어도 앱까지 도달하지 않고 **조용히 기본값으로 돈다** — 켰다고 믿는데
 * 안 켜진 상태가 가장 나쁘다. 실사고 이력: PR #74 / issue #75 → 2026-09-05 그림자 관측에서 재발 →
 * 같은 날 compose 만 고치고 `deploy.sh` 를 빠뜨려 **세 번째** 재발.
 *
 * 그래서 "문서에 적어두기" 대신 테스트로 가둔다. 새 설정을 추가하면 이 테스트가 먼저 깨진다.
 */
class TradingEnvPassthroughTest {

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }

    @Test
    fun `every trading property is listed in both deploy passthrough whitelists`() {
        val composeListed = repoFile("deploy/vultr/docker-compose.prod.yml").readLines()
            .mapNotNull { COMPOSE_ENTRY.find(it.trimEnd())?.groupValues?.get(1) }
            .toSet()
        assertTrue(composeListed.size > MIN_PARSED) { "compose 파싱 실패로 보인다 — ${composeListed.size}개" }

        // deploy.sh 의 배열은 여러 줄이고 주석이 섞인다 — 배열 블록만 잘라 이름을 뽑는다.
        val block = OVERRIDE_ARRAY.find(repoFile("deploy/vultr/deploy.sh").readText())?.groupValues?.get(1)
        assertTrue(block != null) { "deploy.sh 에서 TRADING_OVERRIDE_KEYS 배열을 못 찾았다" }
        val deployListed = ENV_NAME.findAll(block!!).map { it.value }.toSet()
        assertTrue(deployListed.size > MIN_PARSED) { "deploy.sh 파싱 실패로 보인다 — ${deployListed.size}개" }

        val expected = listOf(
            TradingProperties::class, AccumulateProperties::class,
            UniverseProperties::class, ShadowExitProperties::class,
        ).flatMap { klass ->
            val prefix = klass.findAnnotation<ConfigurationProperties>()?.prefix
                ?: error("${klass.simpleName} 에 @ConfigurationProperties prefix 가 없다")
            // 생성자 파라미터 = 실제 설정 입력. 파생 캐시(private val 등)는 env 로 주는 값이 아니다.
            (klass.primaryConstructor?.parameters ?: error("${klass.simpleName} 에 주 생성자가 없다"))
                .mapNotNull { it.name }
                .map { envName(prefix, it) }
        }.toSet()

        val missingInDeploy = (expected - deployListed).sorted()
        val missingInCompose = (expected - composeListed).sorted()
        assertTrue(missingInDeploy.isEmpty() && missingInCompose.isEmpty()) {
            buildString {
                append("전달 화이트리스트에서 빠졌다 → .env 에 적어도 앱에 도달하지 않는다.\n")
                if (missingInDeploy.isNotEmpty()) append("  deploy.sh TRADING_OVERRIDE_KEYS: $missingInDeploy\n")
                if (missingInCompose.isNotEmpty()) append("  compose environment: $missingInCompose\n")
                append("  두 곳 모두에 추가하고 .env.example 에도 적을 것.")
            }
        }
    }

    /** `trading.shadow-exit` + `trailingStopPct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    private fun envName(prefix: String, property: String): String {
        val head = prefix.replace('.', '_').replace('-', '_')
        val tail = property.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        return "${head}_$tail".uppercase()
    }

    private companion object {
        val COMPOSE_ENTRY = Regex("""^\s*-\s+(TRADING_[A-Z0-9_]+)\s*$""")
        val OVERRIDE_ARRAY = Regex("""TRADING_OVERRIDE_KEYS=\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
        val ENV_NAME = Regex("""TRADING_[A-Z0-9_]+""")

        /** 파싱이 통째로 실패하면 "빠진 게 없다"로 조용히 통과한다 — 하한을 둬서 그 상태를 실패로 만든다. */
        const val MIN_PARSED = 10
    }
}
