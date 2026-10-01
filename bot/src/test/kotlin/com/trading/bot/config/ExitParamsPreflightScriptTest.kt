package com.trading.bot.config

import com.trading.common.config.ExitParamRanges
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaGetter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 배포 preflight(`deploy/vultr/preflight_exit_params.sh`)가 앱의 구간 정의([ExitParamRanges])와 같은 판정을 내는가 (#230).
 *
 * 스크립트의 표는 사본이다 — 이 테스트가 없으면 한쪽만 고쳐도 아무것도 깨지지 않는다(#75 의 목록 drift 와 같은 구조).
 * 계약은 비대칭이다: preflight 는 앱보다 엄격해도 되지만(업로드 전에 멈출 뿐) 느슨하면 안 된다 — 스크립트가 받은 값을
 * 앱이 바인딩하지 못하면 기동이 막히고, 자동 롤백도 같은 `.env` 로 기동한다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class ExitParamsPreflightScriptTest {

    /** 스크립트가 형식을 보는 키 하나. [entry] 가 있으면 구간도 본다. */
    private class Key(val env: String, val owner: Class<*>, val prefix: String, val property: KProperty1<*, *>, val format: Regex, val entry: ExitParamRanges.Entry<*>?)

    private class Run(val exit: Int, val stdout: String, val stderr: String) {
        /** 스크립트는 위반마다 `  KEY: 사유` 한 줄을 stderr 에 쓴다. */
        val flagged: Set<String> = FLAGGED.findAll(stderr).map { it.groupValues[1] }.toSet()
    }

    private val script = repoFile("deploy/vultr/preflight_exit_params.sh")

    private val rangeKeys: List<Key> = (ExitParamRanges.TRADING + ExitParamRanges.SHADOW).map { entry ->
        val integer = entry.property.returnType.classifier == Int::class
        Key(envName(entry.key), ownerOf(entry.property), entry.key.substringBeforeLast('.'), entry.property, if (integer) INTEGER else DECIMAL, entry)
    }

    // 두 설정 클래스의 불리언 전부 — 오기(`ture`)는 바인딩 실패로 기동을 막는다. 스위치를 더하면 스크립트 BOOLEAN_KEYS 에도 넣어야 한다.
    private val booleanKeys: List<Key> = listOf(TradingProperties::class, ShadowExitProperties::class).flatMap { owner ->
        val prefix = owner.findAnnotation<ConfigurationProperties>()!!.prefix
        owner.primaryConstructor!!.parameters.filter { it.type.classifier == Boolean::class }.map { param ->
            val property = owner.memberProperties.single { it.name == param.name }
            Key(envName("$prefix.${kebab(property.name)}"), owner.java, prefix, property, BOOLEAN, null)
        }
    }

    private val requiredEnvs = ExitParamsDeclarationCheck.REQUIRED_KEYS.map(::envName)

    /** 스크립트를 인자 그대로 실행한다 — 판정 검사는 [run] 이 한다. */
    private fun launch(vararg args: String): Run {
        val out = File.createTempFile("preflight", ".out")
        val err = File.createTempFile("preflight", ".err")
        try {
            val process = ProcessBuilder("bash", script.path, *args)
                .redirectOutput(out)
                .redirectError(err)
                // 개발자 셸의 TRADING_* 가 숨은 입력이 되지 않게 — 스크립트는 파일 인자만 읽어야 한다.
                .apply { environment().keys.retainAll(setOf("PATH")) }
                .start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("preflight 가 20초 안에 끝나지 않았다")
            }
            return Run(process.exitValue(), out.readText(), err.readText())
        } finally {
            listOf(out, err).forEach { it.delete() }
        }
    }

    private fun run(vararg lines: String): Run {
        val env = File.createTempFile("preflight", ".env")
        try {
            env.writeText(lines.joinToString("\n", postfix = "\n"))
            val run = launch(env.path)
            assertThat(run.exit).describedAs("종료코드는 0(통과)·1(위반)뿐이다 — 그 밖은 스크립트 오류\n${run.stderr}").isIn(0, 1)
            assertThat(run.exit == 1).describedAs("위반이면 1, 아니면 0\n${run.stderr}").isEqualTo(run.flagged.isNotEmpty())
            return run
        } finally {
            env.delete()
        }
    }

    /** 앱이 이 env 한 줄을 바인딩한 값 — 바인딩에 실패하면(기동 실패) null. 운영과 같은 `SystemEnvironmentPropertySource` 로 건다. */
    private fun appValue(key: Key, value: String): Any? {
        val source = SystemEnvironmentPropertySource("preflight-candidate", mapOf(key.env to value))
        val bound = runCatching {
            Binder(ConfigurationPropertySources.from(source)).bind(key.prefix, key.owner).orElse(null)
        }.getOrNull() ?: return null
        return key.property.getter.call(bound)
    }

    @Test
    fun `스크립트의 표는 Kotlin 표와 키·형식·구간이 같다`() {
        val text = script.readText()
        fun array(name: String) = Regex("""(?m)^$name=\((.*?)\)""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: throw AssertionError("스크립트에서 $name 배열을 못 찾았다")

        // 끝점을 숫자로 직접 대조한다 — 후보 대조만으로는 포함 끝이 ε 보다 작게 느슨해진 것을 놓친다.
        val rangeRows = RANGE_ROW.findAll(array("RANGE_RULES")).associate { m ->
            val (env, kind, lo, loInclusive, hi, hiInclusive) = m.destructured
            env to listOf(kind, plain(lo), loInclusive, if (hi == "-") "-" else plain(hi), hiInclusive).joinToString(" ")
        }
        assertThat(rangeRows).isEqualTo(
            rangeKeys.associate { key ->
                val range = key.entry!!.range
                key.env to listOf(
                    if (key.format == INTEGER) "integer" else "decimal",
                    plain(range.min.toString()),
                    if (range.minInclusive) "1" else "0",
                    range.max?.let { plain(it.toString()) } ?: "-",
                    if (range.max != null && range.maxInclusive) "1" else "0",
                ).joinToString(" ")
            },
        )
        assertThat(ENV_NAME.findAll(array("BOOLEAN_KEYS")).map { it.value }.toList())
            .containsExactlyInAnyOrderElementsOf(booleanKeys.map { it.env })
        assertThat(ENV_NAME.findAll(array("EXIT_PARAM_KEYS")).map { it.value }.toList())
            .containsExactlyInAnyOrderElementsOf(requiredEnvs)
    }

    @Test
    fun `후보마다 스크립트 판정은 문법과 구간이 낸 답과 같고, 받은 값은 앱도 같은 구간 안으로 받는다`() {
        val keys = rangeKeys + booleanKeys
        val mismatches = mutableListOf<String>()
        val accepted = mutableMapOf<String, Int>()
        val rejected = mutableMapOf<String, Int>()

        candidates().forEach { value ->
            val run = run(*keys.map { "${it.env}=$value" }.toTypedArray())
            keys.forEach { key ->
                val scriptAccepts = key.env !in run.flagged
                val expected = key.format.matches(value) && (key.entry == null || value.toDouble() in key.entry.range)
                if (scriptAccepts != expected) {
                    mismatches += "${key.env}='$value': 스크립트 ${verdict(scriptAccepts)}, 문법·구간 ${verdict(expected)}"
                }
                if (scriptAccepts) {
                    val app = appValue(key, value)
                    val inRange = key.entry == null || (app as? Number)?.toDouble()?.let { it in key.entry.range } == true
                    if (app == null || !inRange) mismatches += "${key.env}='$value': 스크립트는 받았는데 앱은 ${app ?: "바인딩 실패"}"
                }
                (if (scriptAccepts) accepted else rejected).merge(key.env, 1) { a, b -> a + b }
            }
        }

        assertThat(mismatches).isEmpty()
        // 후보 생성이 무너져 한쪽 판정만 남으면 대조가 허울이 된다 — 키마다 통과·거부가 둘 다 나와야 한다.
        keys.forEach {
            assertThat(accepted[it.env] ?: 0).describedAs("${it.env} 통과 후보").isPositive()
            assertThat(rejected[it.env] ?: 0).describedAs("${it.env} 거부 후보").isPositive()
        }
    }

    @Test
    fun `운영 형태의 설정은 통과한다`() {
        val run = run(
            "TRADING_AUTO_START=true",
            "TRADING_TAKE_PROFIT_PCT=5.0",
            "TRADING_MAX_LOSS_PCT=5.0",
            "TRADING_TRAILING_STOP_PCT=1.5",
            "TRADING_TRAILING_ARM_PCT=0",
            "TRADING_MAX_HOLD_DAYS=1",
            "TRADING_INVEST_RATIO=0.1",
            "TRADING_ROUND_TRIP_FEE_RATE=0.001",
            "TRADING_SHADOW_EXIT_ENABLED=true",
            "TRADING_TICKERS=KRW-BTC, KRW-ETH",
        )
        assertThat(run.exit).describedAs(run.stderr).isEqualTo(0)
    }

    @Test
    fun `자동매매가 꺼져 있어도 값은 검사하고 선언은 요구하지 않는다`() {
        assertThat(run("TRADING_AUTO_START=false", "TRADING_MAX_LOSS_PCT=-5").flagged).containsExactly("TRADING_MAX_LOSS_PCT")
        assertThat(run("TRADING_AUTO_START=false").exit).isEqualTo(0)
        // 선언하지 않으면 앱 기본값(false) — 선언 검사를 하지 않는다.
        assertThat(run("TRADING_TICKERS=KRW-BTC").exit).isEqualTo(0)
    }

    @Test
    fun `자동매매면 청산 파라미터가 하나라도 빠질 때 막고, 값 위반과 함께 한 번에 알린다`() {
        val declared = requiredEnvs.associateWith { "1" }
        assertThat(run("TRADING_AUTO_START=true", *declared.map { (k, v) -> "$k=$v" }.toTypedArray()).exit).isEqualTo(0)
        requiredEnvs.forEach { missing ->
            val lines = listOf("TRADING_AUTO_START=true") + (declared - missing).map { (k, v) -> "$k=$v" }
            assertThat(run(*lines.toTypedArray()).flagged).describedAs(missing).containsExactly(missing)
        }
        // `True` 는 형식 위반이고, 켜졌을 수 있으니 선언도 본다 — 자동매매 배포로 단정하지 않고 그 이유를 따로 알린다.
        val malformedGate = run("TRADING_AUTO_START=True")
        assertThat(malformedGate.flagged).contains("TRADING_AUTO_START", "TRADING_MAX_LOSS_PCT")
        assertThat(malformedGate.stderr).contains("켜졌을 수 있으므로").doesNotContain("자동매매 배포라")
        assertThat(run("TRADING_AUTO_START=true", "TRADING_MAX_LOSS_PCT=-5").flagged)
            .contains("TRADING_MAX_LOSS_PCT", "TRADING_TAKE_PROFIT_PCT")
    }

    @Test
    fun `배포 스크립트는 렌더한 env 를 올리기 전에 preflight 를 bash 로 부른다`() {
        // 스크립트가 맞아도 부르지 않거나 업로드 뒤에 부르면 잘못된 .env 가 서버에 닿는다. 실행 비트에 기대지 않게 bash 로 부른다.
        val lines = repoFile("deploy/vultr/deploy.sh").readLines().map { it.trim() }
        val render = lines.indexOf("render_server_env \"\$tmp_env\"")
        val call = lines.indexOf("bash \"\$SCRIPT_DIR/preflight_exit_params.sh\" \"\$tmp_env\"")
        val upload = lines.indexOfFirst { it.startsWith("scp ") && it.contains("\"\$tmp_env\"") }
        assertThat(render).describedAs("렌더").isNotNegative()
        assertThat(call).describedAs("preflight 호출").isGreaterThan(render)
        assertThat(upload).describedAs(".env 업로드").isGreaterThan(call)
    }

    @Test
    fun `인자가 없거나 파일을 읽을 수 없으면 사용법 오류(2)로 멈춘다`() {
        // 0 으로 끝나면 배포가 검사 없이 지나간다.
        assertThat(launch().exit).isEqualTo(2)
        val missing = File(System.getProperty("java.io.tmpdir"), "preflight-missing-${System.nanoTime()}.env")
        assertThat(launch(missing.path).exit).isEqualTo(2)
    }

    @Test
    fun `위반은 키와 허용 범위만 알리고 값은 찍지 않는다`() {
        // 배포 로그는 공개 repo 의 CI 로그다.
        val run = run("TRADING_MAX_LOSS_PCT=-7.3517", "TRADING_SHADOW_EXIT_ENABLED=7.3517x")
        assertThat(run.flagged).containsExactlyInAnyOrder("TRADING_MAX_LOSS_PCT", "TRADING_SHADOW_EXIT_ENABLED")
        assertThat(run.stderr).contains("(0, 100)")
        assertThat(run.stdout + run.stderr).doesNotContain("7.3517")
    }

    /** 표의 끝점 ±ε 에서 파생한 정규 형식 값 + 형식 후보. 끝점이 바뀌면 후보도 따라 바뀐다. */
    private fun candidates(): List<String> {
        val fromBounds = rangeKeys.flatMap { key ->
            val range = key.entry!!.range
            val steps = if (key.format == INTEGER) listOf("-1", "0", "1") else listOf("-1", "-0.001", "0", "0.001", "1")
            listOfNotNull(range.min, range.max).flatMap { bound ->
                steps.map { BigDecimal.valueOf(bound).add(BigDecimal(it)).stripTrailingZeros().toPlainString() }
            }
        }
        return (fromBounds + CANONICAL + NON_CANONICAL).distinct()
    }

    private fun verdict(accepts: Boolean) = if (accepts) "통과" else "거부"

    private fun plain(number: String) = BigDecimal(number).stripTrailingZeros().toPlainString()

    private fun ownerOf(property: KProperty1<*, *>): Class<*> = property.javaGetter!!.declaringClass

    private fun kebab(name: String) = name.replace(Regex("([A-Z])"), "-$1").lowercase()

    /** `trading.shadow-exit.trailing-stop-pct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    private fun envName(key: String) = key.replace('.', '_').replace('-', '_').uppercase()

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }

    private companion object {
        // 스크립트의 형식 문법과 같아야 한다 — 다르면 후보 대조가 깨진다.
        val DECIMAL = Regex("""-?[0-9]{1,9}(\.[0-9]+)?""")
        val INTEGER = Regex("""-?[0-9]{1,9}""")
        val BOOLEAN = Regex("""true|false""")

        val FLAGGED = Regex("""(?m)^ {2}(TRADING_[A-Z0-9_]+): """)
        val RANGE_ROW = Regex(""""(TRADING_[A-Z0-9_]+) (decimal|integer) (\S+) ([01]) (\S+) ([01])""")
        val ENV_NAME = Regex("""TRADING_[A-Z0-9_]+""")

        val CANONICAL = listOf("-5", "5", "5.0", "-0", "-0.0", "1.5", "123.456789", "999999999", "999999999.5")

        // 렌더 필터(`deploy.sh` 의 TRADING_VALUE_PATTERN)가 통과시키는 문자(영숫자 . _ , - 공백)로 만들 수 있는 값을 주로 넣는다.
        val NON_CANONICAL = listOf(
            "", " ", " 5", "5 ", "5 0", "5,0", "5_0", "5x", "5d", "5f", "0x10", "0x1p3", "#10",
            "1e-3", "1E-3", "1e3", "Infinity", "-Infinity", "NaN", "5.", ".5", "+5", "--5", "-", ".",
            "1000000000", "2147483648", "1.0", "abc",
            "true", "false", "TRUE", "True", "yes", "on", "1", "0", " true", "ture",
        )
    }
}
