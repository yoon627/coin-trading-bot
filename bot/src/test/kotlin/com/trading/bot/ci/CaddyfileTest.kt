package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/** 배포가 app 컨테이너를 바꾸는 동안(옛 컨테이너 정지 → 새 컨테이너 기동) caddy 가 요청을 502 로 끝내지 않는다(#328). */
class CaddyfileTest {

    @Test
    fun `앱 컨테이너를 바꾸는 동안 caddy 는 502 대신 연결될 때까지 기다린다`() {
        val lines = RepoFiles.file(CADDYFILE).readLines().map { it.trim() }.filterNot { it.startsWith("#") }
        val start = lines.indexOf("reverse_proxy app:8080 {")
        check(start >= 0) { "$CADDYFILE 에서 'reverse_proxy app:8080 {' 블록을 찾지 못했다 — 형식이 바뀌었으면 이 테스트도 고친다" }
        val block = blockBody(lines.drop(start + 1))

        // caddy 는 같은 지시어가 여러 번이면 마지막 값을 쓴다.
        val durations = block.filter { it.startsWith("lb_try_duration") }
        assertThat(durations).describedAs("reverse_proxy app:8080 블록에 lb_try_duration 이 한 줄 있다").hasSize(1)
        val seconds = DURATION.matchEntire(durations.single())?.groupValues?.get(1)?.toInt()
        assertThat(seconds).describedAs("lb_try_duration 은 '<정수>s' 로 적는다: ${durations.single()}").isNotNull()
        // 아래 25초는 실측 교체 공백(최대 16.5초)의 약 1.5배다. 위 45초를 넘기면 기동이 실패한 배포에서 요청마다 그만큼
        // 멈추고 화면의 주기 요청이 쌓여 분당 rate limit 에 다가간다.
        assertThat(seconds!!).isBetween(25, 45)
    }

    /**
     * caddy 는 정지할 때 새 연결을 받지 않고 진행 중 요청을 기본으로 끝없이 기다린다. 위 재시도로 붙잡힌 요청이 있으면 docker 의
     * 정지 시한(10초)까지 HTTPS 가 막힌 채 버티다 강제 종료된다. 그보다 짧게 끊어 caddy 를 바꾸는 배포의 공백을 줄인다.
     */
    @Test
    fun `caddy 정지는 진행 중 요청을 docker 정지 시한보다 짧게 기다린다`() {
        val lines = RepoFiles.file(CADDYFILE).readLines().map { it.trim() }.filterNot { it.isEmpty() || it.startsWith("#") }
        check(lines.firstOrNull() == "{") { "$CADDYFILE 의 첫 블록이 전역 옵션 블록이어야 한다: ${lines.firstOrNull()}" }
        val global = blockBody(lines.drop(1))

        val seconds = global.mapNotNull { GRACE.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        assertThat(seconds).describedAs("전역 옵션에 grace_period 가 한 줄 있다").hasSize(1)
        assertThat(seconds.single()).isBetween(1, 9)
        assertThat(caddyStopGrace()).describedAs("caddy 서비스는 docker 기본 정지 시한(10초)을 쓴다").isNull()
    }

    private fun caddyStopGrace(): Any? {
        val compose: Map<*, *> = Yaml().load(RepoFiles.file("deploy/vultr/docker-compose.prod.yml").readText())
        return ((compose["services"] as Map<*, *>)["caddy"] as Map<*, *>)["stop_grace_period"]
    }

    /** 블록 안 줄. 안쪽 블록(`transport http { … }` 등)의 줄도 포함하고 짝이 맞는 `}` 에서 멈춘다. */
    private fun blockBody(afterOpen: List<String>): List<String> {
        var depth = 1
        return afterOpen.takeWhile { line ->
            depth += line.count { it == '{' } - line.count { it == '}' }
            depth > 0
        }
    }

    private companion object {
        const val CADDYFILE = "deploy/vultr/Caddyfile"
        val DURATION = Regex("""lb_try_duration\s+(\d+)s""")
        val GRACE = Regex("""grace_period\s+(\d+)s""")
    }
}
