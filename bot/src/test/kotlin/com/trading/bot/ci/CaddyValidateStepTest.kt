package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * 틀린 Caddyfile 은 배포가 reload 할 때에야 드러나므로 머지 전에 test job 에서 검증한다. 배포 job 이 test job 을
 * 기다려 검증에 실패한 커밋은 배포되지 않는다.
 */
class CaddyValidateStepTest {

    @Test
    fun `test job 은 운영 caddy 와 같은 이미지로 Caddyfile 을 검증한다`() {
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(".github/workflows/deploy.yml").readText())
        val compose: Map<*, *> = Yaml().load(RepoFiles.file("deploy/vultr/docker-compose.prod.yml").readText())
        val image = ((compose["services"] as Map<*, *>)["caddy"] as Map<*, *>)["image"] as String
        val jobs = workflow["jobs"] as Map<*, *>
        val runs = ((jobs["test"] as Map<*, *>)["steps"] as List<*>)
            .filterIsInstance<Map<*, *>>().mapNotNull { it["run"] as? String }

        val validate = runs.singleOrNull { "caddy validate" in it }
        assertThat(validate).describedAs("test job 에 caddy validate 스텝이 하나 있다").isNotNull()
        // 이미지가 운영과 다르면 운영 caddy 가 거절하는 설정이 통과할 수 있다.
        assertThat(Regex("(^|\\s)${Regex.escape(image)}(\\s|$)").containsMatchIn(validate!!))
            .describedAs("운영 이미지 $image 로 검증한다: $validate").isTrue()
        assertThat(validate).contains("deploy/vultr/Caddyfile")
        assertThat((jobs["deploy-vultr"] as Map<*, *>)["needs"] as List<*>).contains("test")
    }
}
