package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * 배포는 매번 `docker compose pull` 을 돌므로 움직이는 태그(`caddy:2-alpine`)면 앱만 바꾸는 배포가 caddy·postgres 를 몰래
 * 새 이미지로 다시 만든다 — caddy 는 그동안 HTTPS 를 받지 않고, postgres 는 헬스체크 중에 DB 가 다시 뜨며 롤백으로 되돌리지
 * 못한다. 그래서 운영 이미지는 버전 태그와 digest 로 고정하고, 올리는 것은 따로 PR 로 한다.
 */
class InfraImagePinTest {

    private val compose: Map<*, *> = Yaml().load(RepoFiles.file(PROD_COMPOSE).readText())
    private fun prodImage(service: String) = ((compose["services"] as Map<*, *>)[service] as Map<*, *>)["image"] as String

    @Test
    fun `운영 caddy 와 postgres 는 버전 태그와 digest 로 고정한다`() {
        for (service in listOf("caddy", "postgres")) {
            assertThat(prodImage(service)).describedAs("$PROD_COMPOSE 의 $service").matches(PINNED)
        }
    }

    /** DB 통합테스트·로컬 개발이 운영과 다른 postgres 로 돌면 운영에서만 나는 차이를 놓친다. */
    @Test
    fun `CI·로컬·DB 테스트 스크립트의 postgres 는 운영과 같은 이미지다`() {
        val prod = prodImage("postgres")
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(".github/workflows/deploy.yml").readText())
        val ciService = (((workflow["jobs"] as Map<*, *>)["test"] as Map<*, *>)["services"] as Map<*, *>)["postgres"] as Map<*, *>
        val local: Map<*, *> = Yaml().load(RepoFiles.file("docker-compose.yml").readText())
        val localImage = ((local["services"] as Map<*, *>)["postgres"] as Map<*, *>)["image"]
        val script = Regex("""^IMAGE="([^"]+)"""", RegexOption.MULTILINE)
            .find(RepoFiles.file("scripts/run-db-tests.sh").readText())?.groupValues?.get(1)

        assertThat(listOf(ciService["image"], localImage, script)).containsOnly(prod)
    }

    private companion object {
        const val PROD_COMPOSE = "deploy/vultr/docker-compose.prod.yml"
        val PINNED = Regex("""[a-z]+:\d+(\.\d+)+-alpine@sha256:[0-9a-f]{64}""").pattern
    }
}
