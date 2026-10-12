package com.trading.bot.config

import com.trading.common.config.ExitParamRanges
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME as SYSTEM_ENV
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 배포 preflight(`deploy/vultr/preflight_exit_params.sh`)가 앱의 구간 정의([ExitParamRanges])와 같은 판정을 내는가 (#230).
 * 배포가 넘기는 키([DeployPassthrough.PASSED])는 전부 검사 하나를 받는다 — 구간 표에 없으면 타입으로 정한다(#232, #271).
 *
 * 스크립트의 표는 사본이다 — 이 테스트가 없으면 한쪽만 고쳐도 아무것도 깨지지 않는다(#75 의 목록 drift 와 같은 구조).
 * 계약은 비대칭이다: preflight 는 앱보다 엄격해도 되지만(업로드 전에 멈출 뿐) 느슨하면 안 된다 — 스크립트가 받은 값을
 * 앱이 바인딩하지 못하면 기동이 막히고, 자동 롤백도 같은 `.env` 로 기동한다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class ExitParamsPreflightScriptTest {

    /** 스크립트의 검사 종류. [array] 는 그 키들을 담는 스크립트 배열이다(구간은 RANGE_RULES 표). */
    private enum class Grammar(val array: String?) {
        RANGE_DECIMAL(null), RANGE_INTEGER(null),
        BOOLEAN("BOOLEAN_KEYS"), POSITIVE_INTEGER("POSITIVE_INTEGER_KEYS"), DECIMAL("DECIMAL_KEYS"), TICKER_LIST("TICKER_LIST_KEYS");

        fun accepts(value: String): Boolean = when (this) {
            RANGE_DECIMAL, DECIMAL -> DECIMAL_FORMAT.matches(value)
            RANGE_INTEGER -> INTEGER_FORMAT.matches(value)
            BOOLEAN -> BOOLEAN_FORMAT.matches(value)
            POSITIVE_INTEGER -> POSITIVE_INTEGER_FORMAT.matches(value)
            // 빈 조각은 앱이 거르므로 받는다 — 조각이 하나도 없으면 막는다.
            TICKER_LIST -> value.split(',').map { it.trim(' ') }.filter { it.isNotEmpty() }
                .let { pieces -> pieces.isNotEmpty() && pieces.all { MARKET_FORMAT.matches(it) } }
        }
    }

    /** 스크립트가 보는 키 하나. [entry] 가 있으면 구간도 본다. */
    private class Key(val env: String, val owner: Class<*>, val prefix: String, val property: KProperty1<*, *>, val grammar: Grammar, val entry: ExitParamRanges.Entry<*>?)

    private class Run(val exit: Int, val stdout: String, val stderr: String) {
        /** 스크립트는 위반마다 `  KEY: 사유` 한 줄을 stderr 에 쓴다. */
        val flagged: Set<String> = FLAGGED.findAll(stderr).map { it.groupValues[1] }.toSet()
    }

    private val script = repoFile("deploy/vultr/preflight_exit_params.sh")

    private val rangeKeys: List<Key> = (ExitParamRanges.TRADING + ExitParamRanges.SHADOW).map { entry ->
        val grammar = if (entry.property.returnType.classifier == Int::class) Grammar.RANGE_INTEGER else Grammar.RANGE_DECIMAL
        Key(envName(entry.key), ownerOf(entry.property), entry.key.substringBeforeLast('.'), entry.property, grammar, entry)
    }

    // 표에 없는 키는 타입으로 검사를 정한다 — 손으로 고르면 새 키가 검사를 빠져나간다. 오기는 바인딩·require 실패로
    // 기동을 막고 자동 롤백도 같은 .env 로 뜬다.
    private val typedKeys: List<Key> = DeployPassthrough.PASSED.flatMap { owner ->
        val prefix = DeployPassthrough.prefix(owner)
        val rangeEnvs = rangeKeys.map { it.env }.toSet()
        DeployPassthrough.parameters(owner).mapNotNull { param ->
            val property = owner.memberProperties.single { it.name == param.name }
            val env = DeployPassthrough.envName(prefix, property.name)
            val grammar = when {
                env in rangeEnvs -> return@mapNotNull null
                param.type.classifier == Boolean::class -> Grammar.BOOLEAN
                // 0·음수는 require·스케줄 등록이 기동을 막거나, 바인딩에 성공해 폭주한다. 0 을 허용할 정수는 구간 표로 옮긴다.
                param.type.classifier == Long::class || param.type.classifier == Int::class -> Grammar.POSITIVE_INTEGER
                param.type.classifier == Double::class -> Grammar.DECIMAL
                DeployPassthrough.TICKER_LIST.any { it.name == property.name && ownerOf(it) == owner.java } -> Grammar.TICKER_LIST
                else -> error("$env: preflight 검사 배정이 없다 — 구간 표나 DeployPassthrough.TICKER_LIST 에 넣거나 여기 규칙을 더할 것")
            }
            Key(env, owner.java, prefix, property, grammar, null)
        }
    }

    private val allKeys: List<Key> = rangeKeys + typedKeys

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

    /**
     * 스크립트가 받은 값을 앱이 쓸 수 없으면 그 이유, 쓸 수 있으면 null. 바인딩(기동)은 운영과 같은 `SystemEnvironmentPropertySource` 로 건다 —
     * 이름이 -systemEnvironment 로 끝나야 Boot 가 운영과 같은 env 매퍼를 고른다.
     */
    private fun appProblem(key: Key, value: String): String? {
        val source = SystemEnvironmentPropertySource("preflight-$SYSTEM_ENV", mapOf(key.env to value))
        val bound = runCatching {
            Binder(ConfigurationPropertySources.from(source)).bind(key.prefix, key.owner).orElse(null)
        }.getOrNull() ?: return "바인딩 실패"
        val app = key.property.getter.call(bound)
        return when {
            key.entry != null -> if ((app as? Number)?.toDouble()?.let { it in key.entry.range } == true) null else "구간 밖 $app"
            // 문자열은 무엇이든 바인딩된다 — 티커는 앱이 실제로 쓰는 목록을 꺼내 마켓마다 본다.
            key.grammar == Grammar.TICKER_LIST -> tickerListOf(bound).firstOrNull { !usableMarket(it) }?.let { "쓸 수 없는 마켓 '$it'" }
            else -> null
        }
    }

    private fun tickerListOf(bound: Any): List<String> =
        (bound::class.memberFunctions.single { it.name == "tickerList" }.call(bound) as List<*>).map { it.toString() }

    /**
     * 정규화(`BTC/KRW`) 왕복이 원래 코드로 돌아오고 소문자·공백이 없는 마켓만 앱이 쓴다 — 거래 티커는 그대로 주문에 들어간다.
     * 스크립트의 형식 문법과 독립이어야 대조가 뜻을 가진다.
     */
    private fun usableMarket(market: String): Boolean =
        market.none { it.isLowerCase() || it.isWhitespace() } &&
            runCatching { MarketPair.toUpbitFormat(MarketPair.normalize(Exchange.UPBIT, market)) == market }.getOrDefault(false)

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
                    if (key.grammar == Grammar.RANGE_INTEGER) "integer" else "decimal",
                    plain(range.min.toString()),
                    if (range.minInclusive) "1" else "0",
                    range.max?.let { plain(it.toString()) } ?: "-",
                    if (range.max != null && range.maxInclusive) "1" else "0",
                ).joinToString(" ")
            },
        )
        Grammar.entries.mapNotNull { grammar -> grammar.array?.let { grammar to it } }.forEach { (grammar, name) ->
            assertThat(ENV_NAME.findAll(array(name)).map { it.value }.toList()).describedAs(name)
                .containsExactlyInAnyOrderElementsOf(typedKeys.filter { it.grammar == grammar }.map { it.env })
        }
        assertThat(ENV_NAME.findAll(array("EXIT_PARAM_KEYS")).map { it.value }.toList())
            .containsExactlyInAnyOrderElementsOf(requiredEnvs)
    }

    @Test
    fun `배포가 넘기는 키는 전부 preflight 검사 하나를 받는다`() {
        assertThat(allKeys.map { it.env })
            .containsExactlyInAnyOrderElementsOf(DeployPassthrough.PASSED.flatMap(DeployPassthrough::envNames))
    }

    @Test
    fun `후보마다 스크립트 판정은 문법과 구간이 낸 답과 같고, 받은 값은 앱도 쓸 수 있다`() {
        val keys = allKeys
        val mismatches = mutableListOf<String>()
        val accepted = mutableMapOf<String, Int>()
        val rejected = mutableMapOf<String, Int>()

        candidates().forEach { value ->
            val run = run(*keys.map { "${it.env}=$value" }.toTypedArray())
            keys.forEach { key ->
                val scriptAccepts = key.env !in run.flagged
                val expected = key.grammar.accepts(value) && (key.entry == null || value.toDouble() in key.entry.range)
                if (scriptAccepts != expected) {
                    mismatches += "${key.env}='$value': 스크립트 ${verdict(scriptAccepts)}, 문법·구간 ${verdict(expected)}"
                }
                if (scriptAccepts) {
                    appProblem(key, value)?.let { mismatches += "${key.env}='$value': 스크립트는 받았는데 앱은 $it" }
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
            "WATCHLIST_TICKERS=KRW-BTC,KRW-ETH",
            "MARKETDATA_WATCHDOG_ENABLED=false",
            "MARKETDATA_WATCHDOG_STALE_MS=60000",
        )
        assertThat(run.exit).describedAs(run.stderr).isEqualTo(0)
    }

    @Test
    fun `앱 기본값을 그대로 적어도 통과한다`() {
        // 운영자가 기본값을 secret 에 명시해도 배포가 막히면 안 된다 — 기본값이 preflight 형식 밖으로 바뀌면 여기서 깨진다.
        val lines = allKeys.map { key ->
            "${key.env}=${key.property.getter.call(key.owner.kotlin.createInstance())}"
        }
        val run = run(*lines.toTypedArray())
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
            val steps = if (key.grammar == Grammar.RANGE_INTEGER) listOf("-1", "0", "1") else listOf("-1", "-0.001", "0", "0.001", "1")
            listOfNotNull(range.min, range.max).flatMap { bound ->
                steps.map { BigDecimal.valueOf(bound).add(BigDecimal(it)).stripTrailingZeros().toPlainString() }
            }
        }
        return (fromBounds + CANONICAL + NON_CANONICAL + TICKERS).distinct()
    }

    private fun verdict(accepts: Boolean) = if (accepts) "통과" else "거부"

    private fun plain(number: String) = BigDecimal(number).stripTrailingZeros().toPlainString()

    private fun ownerOf(property: KProperty1<*, *>): Class<*> = property.javaGetter!!.declaringClass

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
        // 정수부 자릿수 상한 — 넘치면 앱이 Int 바인딩에 실패하거나 Double 이 무한대가 된다.
        const val MAX_INTEGER_DIGITS = 9

        // 스크립트의 형식 문법과 같아야 한다 — 다르면 후보 대조가 깨진다.
        val DECIMAL_FORMAT = Regex("""-?[0-9]{1,$MAX_INTEGER_DIGITS}(\.[0-9]+)?""")
        val INTEGER_FORMAT = Regex("""-?[0-9]{1,$MAX_INTEGER_DIGITS}""")
        val BOOLEAN_FORMAT = Regex("""true|false""")
        val POSITIVE_INTEGER_FORMAT = Regex("""[1-9][0-9]{0,${MAX_INTEGER_DIGITS - 1}}""")
        // 길이는 보지 않는다 — Upbit 에는 1글자 자산(KRW-T 등)이 있다.
        val MARKET_FORMAT = Regex("""[A-Z]+-[A-Z0-9]+""")

        val FLAGGED = Regex("""(?m)^ {2}([A-Z][A-Z0-9_]+): """)
        val RANGE_ROW = Regex(""""(TRADING_[A-Z0-9_]+) (decimal|integer) (\S+) ([01]) (\S+) ([01])""")
        val ENV_NAME = Regex("""[A-Z][A-Z0-9]*_[A-Z0-9_]+""")

        val CANONICAL = listOf("-5", "5", "5.0", "-0", "-0.0", "1.5", "123.456789", "999999999", "999999999.5")

        // 렌더 필터(`deploy.sh` 의 OVERRIDE_VALUE_PATTERN)가 통과시키는 문자(영숫자 . _ , - 공백)로 만들 수 있는 값을 주로 넣는다.
        val NON_CANONICAL = listOf(
            "", " ", " 5", "5 ", "5 0", "5,0", "5_0", "5x", "5d", "5f", "0x10", "0x1p3", "#10",
            "1e-3", "1E-3", "1e3", "Infinity", "-Infinity", "NaN", "5.", ".5", "+5", "--5", "-", ".",
            "1000000000", "2147483648", "1.0", "abc",
            "true", "false", "TRUE", "True", "yes", "on", "1", "0", " true", "ture",
        )

        // 티커 목록 후보 — 앞쪽은 받아야 할 값(쉼표 뒤 공백·앞뒤 공백·빈 조각·숫자로 시작하는 자산·1글자 자산·다른 통화),
        // 뒤쪽은 막아야 할 값(공백 구분·소문자·조각 수·구분자·빈 자산/통화·조각 없음).
        val TICKERS = listOf(
            "KRW-BTC", "KRW-BTC,KRW-ETH", "KRW-BTC, KRW-ETH", " KRW-BTC ", "KRW-BTC,", ",KRW-BTC", "KRW-BTC,,KRW-ETH", "KRW-1INCH", "KRW-T", "USDT-BTC",
            "KRW-BTC KRW-ETH", "krw-btc", "KRW-btc", "KRW-BTC-X", "KRW-", "-BTC", "KRW_BTC", "KRWBTC", ",", ",,",
        )
    }
}
