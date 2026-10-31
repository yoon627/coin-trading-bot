package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BashArraysTest {

    @Test
    fun `배열 안 줄 주석의 키 이름과 괄호는 원소로 읽지 않는다`() {
        val script = """
            KEYS=(
                # OLD_KEY) 지운 키
                FIRST_KEY   # 단위는 초
                "SECOND_KEY decimal 0 1 2 1"
            )
            OTHER=(X)
        """.trimIndent().lines()

        val body = bashArrayBody(script, "KEYS")!!
        assertThat(body).contains("FIRST_KEY", "\"SECOND_KEY decimal 0 1 2 1\"").doesNotContain("OLD_KEY", "단위", "OTHER")
        assertThat(bashArrayBody(script, "MISSING")).isNull()
        assertThat(bashArrayBody(listOf("OPEN=(", "  A"), "OPEN")).isNull()
    }
}
