package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

/**
 * R2DBC 풀에 획득·연결 생성 시한이 없으면 풀이 고갈되거나 새 연결이 만들어지지 않을 때 매매 tick·봇 복원·health 가 오류 없이
 * 무한히 기다린다. 이미 연결을 잡은 쿼리는 이 시한으로 묶이지 않는다.
 * Boot 가 실제로 읽는 R2dbcProperties 로 yml 을 묶어 본다. 운영 컨테이너가 이 값을 덮지 않는 것은 ShutdownBudgetTest 가 본다.
 */
class R2dbcPoolTimeoutTest {

    @Test
    fun `기본·prod 프로필 모두 풀 획득 5초·연결 생성 3초 시한을 둔다`() {
        for (files in listOf(listOf(BASE_YML), listOf(PROD_YML, BASE_YML))) {
            val pool = bind(files).pool
            assertThat(pool.maxAcquireTime).describedAs("$files").isEqualTo(Duration.ofSeconds(5))
            assertThat(pool.maxCreateConnectionTime).describedAs("$files").isEqualTo(Duration.ofSeconds(3))
        }
        // prod 의 pool 블록과 합쳐져도 크기 설정은 그대로다.
        assertThat(bind(listOf(PROD_YML, BASE_YML)).pool.maxSize).isEqualTo(20)
    }

    /** prod 프로필은 Boot 처럼 prod 파일을 기본 파일 앞에 둔다. */
    private fun bind(files: List<String>): R2dbcProperties {
        val loader = YamlPropertySourceLoader()
        val sources = files.flatMap { loader.load(it, FileSystemResource(RepoFiles.file(it))) }
        return Binder(ConfigurationPropertySources.from(sources)).bindOrCreate("spring.r2dbc", R2dbcProperties::class.java)
    }

    private companion object {
        const val BASE_YML = "bot/src/main/resources/application.yml"
        const val PROD_YML = "bot/src/main/resources/application-prod.yml"
    }
}
