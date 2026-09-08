package com.trading.bot.config

import com.trading.common.config.TradingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.mock.env.MockEnvironment

/**
 * 청산 파라미터 미선언을 **드러내되 막지는 않는다**는 계약을 고정한다 (#179).
 *
 * 막지 않는 이유는 [ExitParamsDeclarationCheck] KDoc 참조 — 차단은 배포 preflight 가 맡는다.
 */
class ExitParamsDeclarationCheckTest {

    private fun envWith(keys: Collection<String>) = MockEnvironment().apply {
        keys.forEach { setProperty(it, "1.0") }
    }

    private fun check(declared: Collection<String>) =
        ExitParamsDeclarationCheck(TradingProperties(), envWith(declared))

    @Test
    fun `모두 선언되면 미선언 키가 없다`() {
        assertThat(check(ExitParamsDeclarationCheck.REQUIRED_KEYS).undeclaredKeys).isEmpty()
    }

    @Test
    fun `아무것도 선언되지 않으면 전부 미선언으로 잡는다`() {
        assertThat(check(emptyList()).undeclaredKeys)
            .containsExactlyElementsOf(ExitParamsDeclarationCheck.REQUIRED_KEYS)
    }

    @ParameterizedTest(name = "{0} 하나만 빠져도 잡는다")
    @MethodSource("requiredKeys")
    fun `키 하나가 빠지면 그 키를 지목한다`(missing: String) {
        val declared = ExitParamsDeclarationCheck.REQUIRED_KEYS - missing
        assertThat(check(declared).undeclaredKeys).containsExactly(missing)
    }

    @Test
    fun `검사는 값이 아니라 선언 여부만 본다`() {
        // 값을 강제하면 운영이 값을 바꿀 때마다 코드를 고쳐야 한다 — 그게 지금 막으려는 결합이다.
        val env = MockEnvironment().apply {
            ExitParamsDeclarationCheck.REQUIRED_KEYS.forEach { setProperty(it, "-999") }
        }
        assertThat(ExitParamsDeclarationCheck(TradingProperties(), env).undeclaredKeys).isEmpty()
    }

    @Test
    fun `요구 키는 TradingProperties 프로퍼티에서 파생돼 실제 바인딩 키와 일치한다`() {
        // 문자열로 적으면 rename 시 옛 키가 계속 선언돼 있어 검사가 조용히 무력해진다.
        assertThat(ExitParamsDeclarationCheck.REQUIRED_KEYS).containsExactly(
            "trading.take-profit-pct",
            "trading.max-loss-pct",
            "trading.trailing-stop-pct",
            "trading.trailing-arm-pct",
            "trading.max-hold-days",
            "trading.chart-exit-enabled",
        )
    }

    @Test
    fun `보고는 예외를 던지지 않는다 — 거래를 막지 않는 것이 계약이다`() {
        check(emptyList()).report("테스트") // 미선언이 전부여도 throw 없음
        check(ExitParamsDeclarationCheck.REQUIRED_KEYS).report("테스트")
    }

    companion object {
        @JvmStatic
        fun requiredKeys(): List<String> = ExitParamsDeclarationCheck.REQUIRED_KEYS
    }
}
