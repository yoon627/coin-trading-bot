package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME as SYSTEM_ENV
import org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME as SYSTEM_PROPERTIES
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource

/**
 * 설정 클래스의 기본값은 클래스에만 두고, application yml 에는 env 이름이 relaxed 이름과 다른 키의 통로만 둔다.
 * 같은 기본값을 두 곳에 적으면 한쪽만 바뀌어 조용히 갈린다. yml 의 `${…}` 를 풀어야 해서 [Binder.get] 으로 묶고,
 * 운영과 같은 env 매퍼가 걸리게 OS 환경변수 소스를 같은 이름의 테스트 소스로 바꾼다(호스트의 DISCORD_* 가 섞이지 않는다).
 */
class AppConfigBindingTest {

    @Test
    fun `application yml 은 설정 클래스 키 중 env 이름이 다른 통로만 둔다`() {
        val forbidden = (DeployPassthrough.PASSED + DeployPassthrough.EXCLUDED.keys).flatMap { klass ->
            DeployPassthrough.parameters(klass).mapNotNull { it.name }
                .map { ConfigurationPropertyName.of("${DeployPassthrough.prefix(klass)}.${kebab(it)}") }
        }
        for ((file, allowed) in mapOf(BASE_YML to ENV_ALIASES, PROD_YML to emptySet())) {
            val keys = yml(file).flatMap { (it as EnumerablePropertySource<*>).propertyNames.toList() }
            val underClasses = keys.filter { key ->
                val name = ConfigurationPropertyName.of(key)
                forbidden.any { it == name || it.isAncestorOf(name) }
            }
            assertThat(underClasses).describedAs("$file — 기본값은 설정 클래스에만 둔다").containsExactlyInAnyOrderElementsOf(allowed)
        }
    }

    @Test
    fun `env 가 없으면 기본·prod 프로필 모두 지금의 실효값이다`() {
        for (files in listOf(listOf(BASE_YML), listOf(PROD_YML, BASE_YML))) {
            val binder = binder(files, emptyMap())

            assertThat(binder.bindOrCreate("app", AppProperties::class.java)).describedAs("$files").isEqualTo(AppProperties("", 86_400_000, ""))
            assertThat(binder.bindOrCreate("upbit", UpbitProperties::class.java)).isEqualTo(UpbitProperties("https://api.upbit.com"))
            assertThat(binder.bindOrCreate("discord", DiscordProperties::class.java)).isEqualTo(DiscordProperties(""))
            assertThat(binder.bindOrCreate("discord.error-alert", ErrorAlertProperties::class.java)).isEqualTo(ErrorAlertProperties(false, ""))
        }
    }

    /** 운영 compose 가 넘기는 이름이다. JWT_SECRET·DISCORD_ERROR_WEBHOOK_URL 은 relaxed 이름이 아니라 yml 통로로만 닿는다. */
    @Test
    fun `배포 env 이름이 prod 프로필에서 앱·Discord 설정에 닿는다`() {
        val binder = binder(
            listOf(PROD_YML, BASE_YML),
            mapOf(
                "JWT_SECRET" to "jwt-test",
                "APP_ENCRYPTION_SECRET" to "enc-test",
                "DISCORD_WEBHOOK_URL" to "https://discord.example.test/general",
                "DISCORD_ERROR_ALERT_ENABLED" to "true",
                "DISCORD_ERROR_WEBHOOK_URL" to "https://discord.example.test/error",
            ),
        )

        assertThat(binder.bindOrCreate("app", AppProperties::class.java)).isEqualTo(AppProperties("jwt-test", 86_400_000, "enc-test"))
        assertThat(binder.bindOrCreate("discord", DiscordProperties::class.java))
            .isEqualTo(DiscordProperties("https://discord.example.test/general"))
        assertThat(binder.bindOrCreate("discord.error-alert", ErrorAlertProperties::class.java))
            .isEqualTo(ErrorAlertProperties(true, "https://discord.example.test/error"))
    }

    private fun yml(file: String): List<PropertySource<*>> = YamlPropertySourceLoader().load(file, FileSystemResource(RepoFiles.file(file)))

    /** prod 프로필은 Boot 처럼 prod 파일을 기본 파일 앞에 둔다. */
    private fun binder(files: List<String>, env: Map<String, Any>): Binder {
        val environment = StandardEnvironment()
        environment.propertySources.remove(SYSTEM_PROPERTIES)
        environment.propertySources.replace(SYSTEM_ENV, SystemEnvironmentPropertySource(SYSTEM_ENV, env))
        files.flatMap(::yml).forEach(environment.propertySources::addLast)
        return Binder.get(environment)
    }

    private fun kebab(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private companion object {
        const val BASE_YML = "bot/src/main/resources/application.yml"
        const val PROD_YML = "bot/src/main/resources/application-prod.yml"

        /** env 이름이 relaxed 이름(APP_JWT_SECRET, DISCORD_ERROR_ALERT_WEBHOOK_URL)과 달라 yml 통로가 있어야 닿는 키. */
        val ENV_ALIASES = setOf("app.jwt-secret", "discord.error-alert.webhook-url")
    }
}
