package com.trading.bot.config

import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import java.io.File
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import org.yaml.snakeyaml.Yaml

/**
 * 앱이 읽는 `trading.*` 설정과 **배포 전달 화이트리스트 둘**이 같은 키 집합인가.
 *
 * 전달 경로에는 화이트리스트가 **둘** 있고 **둘 다** 통과해야 값이 앱에 닿는다:
 *   1. `deploy/vultr/deploy.sh` 의 `TRADING_OVERRIDE_KEYS` — 서버 `.env` 에 그 줄을 쓸지
 *   2. `deploy/vultr/docker-compose.prod.yml` 의 `services.app.environment` — 그 값을 컨테이너에 넘길지
 *
 * - 설정이 한쪽에서라도 빠지면 `.env` 에 적어도 앱까지 도달하지 않고 **조용히 기본값으로 돈다** — 켰다고 믿는데
 *   안 켜진 상태가 가장 나쁘다. 실사고 이력: PR #74 / issue #75 → 2026-09-05 그림자 관측에서 재발 →
 *   같은 날 compose 만 고치고 `deploy.sh` 를 빠뜨려 **세 번째** 재발.
 * - 반대로 설정을 지웠는데 키가 목록에 남으면 `.env` 의 그 값은 아무 효과 없이 전달돼, 아직 조정할 수 있다고 믿게 만든다.
 * - compose 의 `TRADING_*` 에 값을 붙이면(`=${...:-기본값}`) `.env` 에 키가 없을 때 앱 기본값 대신 그 값이 들어간다(#75).
 *
 * 그래서 "문서에 적어두기" 대신 테스트로 가둔다. 설정을 더하거나 지우면 이 테스트가 먼저 깨진다.
 */
class TradingEnvPassthroughTest {

    @Test
    fun `every trading property is listed in both deploy passthrough whitelists`() {
        val expected = expectedKeys()
        val missing = mapOf(
            DEPLOY_SH to expected - deployShKeys(),
            COMPOSE to expected - composeKeys(),
        ).filterValues { it.isNotEmpty() }
        assertTrue(missing.isEmpty()) {
            "전달 화이트리스트에서 빠졌다 → .env 에 적어도 앱에 도달하지 않는다.\n" +
                perFile(missing) + "  두 곳 모두에 추가하고 .env.example 에도 적을 것."
        }
    }

    @Test
    fun `deploy passthrough whitelists list only keys that a trading property reads`() {
        val expected = expectedKeys()
        val stale = mapOf(
            DEPLOY_SH to deployShKeys() - expected,
            COMPOSE to composeKeys() - expected,
        ).filterValues { it.isNotEmpty() }
        assertTrue(stale.isEmpty()) {
            "읽는 설정이 없는 키가 전달 화이트리스트에 남았다 → .env 에 적어도 아무 효과가 없다.\n" +
                perFile(stale) +
                "  설정을 지웠으면 두 곳과 .env.example 에서도 지우고, 새 설정이면 그 @ConfigurationProperties 생성자에 추가할 것" +
                "(새 설정 클래스면 이 테스트의 expectedKeys() 목록에도)."
        }
    }

    @Test
    fun `compose lists trading keys by name without values`() {
        val withValue = composeEntries().filter { '=' in it }
        assertTrue(withValue.isEmpty()) {
            "$COMPOSE 의 TRADING_* 에 값이 붙었다: $withValue\n" +
                "  이름만 적을 것(#75) — 값을 붙이면 .env 에 키가 없을 때 앱 기본값 대신 그 값이 들어간다."
        }
    }

    /** 설정 클래스의 생성자 파라미터 = 실제 설정 입력. 파생 캐시(private val 등)는 env 로 주는 값이 아니다. */
    private fun expectedKeys(): Set<String> = listOf(
        TradingProperties::class, ShadowExitProperties::class,
    ).flatMap { klass ->
        val prefix = klass.findAnnotation<ConfigurationProperties>()?.prefix
            ?: error("${klass.simpleName} 에 @ConfigurationProperties prefix 가 없다")
        (klass.primaryConstructor?.parameters ?: error("${klass.simpleName} 에 주 생성자가 없다"))
            .mapNotNull { it.name }
            .map { envName(prefix, it) }
    }.toSet()

    /**
     * `TRADING_OVERRIDE_KEYS=( … )` 의 키. 줄마다 `#` 뒤를 먼저 지운다 — 배열 안 설명 주석의 키는 목록이 아니고,
     * 주석 처리한 키는 빠진 키다. 주석 속 `)` 에서 배열이 끝난 것으로 읽지 않는 것도 이 순서 덕이다.
     */
    private fun deployShKeys(): Set<String> {
        val lines = repoFile(DEPLOY_SH).readLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(OVERRIDE_ARRAY_START) }
        assertTrue(start >= 0) { "$DEPLOY_SH 에서 TRADING_OVERRIDE_KEYS 배열을 못 찾았다" }
        val code = (listOf(lines[start].substringAfter(OVERRIDE_ARRAY_START)) + lines.drop(start + 1))
            .map { it.substringBefore('#') }
        val end = code.indexOfFirst { ')' in it }
        assertTrue(end >= 0) { "$DEPLOY_SH 의 TRADING_OVERRIDE_KEYS 배열이 닫히지 않는다" }
        val body = code.take(end + 1).joinToString("\n").substringBefore(')')
        return ENV_NAME.findAll(body).map { it.value }.toSet().also { assertParsed(DEPLOY_SH, it) }
    }

    /** `services.app.environment` 의 `TRADING_*` 항목 원문 — `NAME` 또는 `NAME=값`. */
    private fun composeEntries(): List<String> {
        val compose: Map<*, *> = Yaml().load(repoFile(COMPOSE).readText())
        val app = (compose["services"] as? Map<*, *>)?.get("app") as? Map<*, *>
        val environment = app?.get("environment") as? List<*>
            ?: error("$COMPOSE 의 services.app.environment 가 목록이 아니다")
        return environment.map { it.toString() }.filter { it.startsWith("TRADING_") }
            .also { entries -> assertParsed(COMPOSE, entries.toSet()) }
    }

    private fun composeKeys(): Set<String> = composeEntries().map { it.substringBefore('=') }.toSet()

    /** 파싱이 통째로 실패하면 역방향·이름만 단언이 "남은 게 없다"로 조용히 통과한다 — 하한을 둬서 그 상태를 실패로 만든다. */
    private fun assertParsed(file: String, keys: Set<String>) {
        assertTrue(keys.size > MIN_PARSED) {
            "$file 파싱 실패로 보인다 — ${keys.size}개(설정이 정말 ${MIN_PARSED}개 이하로 줄었다면 MIN_PARSED 를 낮출 것)"
        }
    }

    private fun perFile(keysByFile: Map<String, Set<String>>): String =
        keysByFile.entries.joinToString("") { (file, keys) -> "  $file: ${keys.sorted()}\n" }

    /** `trading.shadow-exit` + `trailingStopPct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    private fun envName(prefix: String, property: String): String {
        val head = prefix.replace('.', '_').replace('-', '_')
        val tail = property.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        return "${head}_$tail".uppercase()
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }

    private companion object {
        const val DEPLOY_SH = "deploy/vultr/deploy.sh"
        const val COMPOSE = "deploy/vultr/docker-compose.prod.yml"
        const val OVERRIDE_ARRAY_START = "TRADING_OVERRIDE_KEYS=("
        val ENV_NAME = Regex("""TRADING_[A-Z0-9_]+""")
        const val MIN_PARSED = 10
    }
}
