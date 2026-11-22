package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * main push 의 자동 배포는 app 컨테이너만 바뀌는 배포에만 한다. `docker compose up -d` 는 정의가 바뀐 서비스를 다시 만들어,
 * postgres·caddy 정의가 바뀌면 DB·HTTPS 가 재시작된다 — 그런 배포는 사람이 시점을 정해 수동으로 돌린다.
 * 판정할 수 없으면 자동 배포하지 않는다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux) 에서 돈다")
class AutoDeployGateScriptTest {

    @TempDir
    lateinit var dir: File

    @TempDir
    lateinit var outDir: File

    private lateinit var base: String

    private class Run(val exit: Int, val output: String) {
        val decision: String? get() = output.lineSequence().firstOrNull { it.startsWith("auto=") }
    }

    private val compose = "deploy/vultr/docker-compose.prod.yml"

    private fun composeYaml(appEnv: String = "A=1", postgresImage: String = "postgres:17", caddyPorts: String = "\"443:443\"", volumes: String = "pgdata: {}") = """
        services:
          app:
            image: ghcr.io/o/app:x
            environment:
              - $appEnv
          postgres:
            image: $postgresImage
          caddy:
            image: caddy:2
            ports:
              - $caddyPorts
        volumes:
          $volumes
    """.trimIndent() + "\n"

    @BeforeEach
    fun initRepo() {
        git("init", "-q")
        write(compose, composeYaml())
        write("bot/src/main/kotlin/App.kt", "v1")
        commitAll("base")
        base = head()
    }

    @Test
    fun `a change to the app alone deploys automatically`() {
        write(compose, composeYaml(appEnv = "A=2"))
        write("bot/src/main/kotlin/App.kt", "v2")
        commitAll("app")

        val result = gate(base, head())

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("auto=true")
    }

    @Test
    fun `a postgres, caddy or volume change waits for a manual run and names what changed`() {
        for ((what, yaml) in listOf(
            "services.postgres" to composeYaml(postgresImage = "postgres:18"),
            "services.caddy" to composeYaml(caddyPorts = "\"8443:443\""),
            "volumes" to composeYaml(volumes = "pgdata2: {}"),
        )) {
            write(compose, yaml)
            commitAll(what)

            val result = gate(base, head())

            assertThat(result.exit).describedAs(result.output).isEqualTo(0)
            assertThat(result.decision).describedAs(result.output).isEqualTo("auto=false")
            assertThat(result.output).contains(what)
            write(compose, composeYaml())
            commitAll("back")
        }
    }

    @Test
    fun `a last good deploy missing from the checkout waits for a manual run`() {
        val result = gate("0".repeat(40), head())

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("auto=false")
    }

    @Test
    fun `an unreadable compose file waits for a manual run`() {
        write(compose, "services: [unclosed\n")
        commitAll("broken")

        val result = gate(base, head())

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("auto=false")
        // "바뀌었다"로 알리면 소유자가 쓸데없이 봇을 멈춘다.
        assertThat(result.output).contains("읽지 못했다").doesNotContain("바뀌었다")
    }

    @Test
    fun `a runner without PyYAML waits for a manual run and says so`() {
        val noYaml = File(outDir, "pylib").apply { mkdirs() }
        File(noYaml, "yaml.py").writeText("raise ImportError('no yaml here')\n")
        write(compose, composeYaml(appEnv = "A=2"))
        commitAll("app")

        val result = gate(base, head(), mapOf("PYTHONPATH" to noYaml.path))

        assertThat(result.decision).describedAs(result.output).isEqualTo("auto=false")
        assertThat(result.output).contains("PyYAML").doesNotContain("바뀌었다")
    }

    @Test
    fun `adding a service or changing a block shared with postgres waits for a manual run`() {
        write(compose, composeYaml() + "x-logging: &logging\n  driver: json-file\n")
        commitAll("anchor")
        val withAnchor = head()
        write(compose, composeYaml().replace("  caddy:", "  redis:\n    image: redis:7\n  caddy:"))
        commitAll("redis")

        val added = gate(base, head())
        val anchor = gate(base, withAnchor)

        assertThat(added.decision).describedAs(added.output).isEqualTo("auto=false")
        assertThat(added.output).contains("services.redis")
        assertThat(anchor.decision).describedAs(anchor.output).isEqualTo("auto=false")
        assertThat(anchor.output).contains("x-logging")
    }

    @Test
    fun `bad arguments are refused`() {
        assertThat(gate("abc", head()).exit).isEqualTo(2)
    }

    private fun gate(lastGood: String, target: String, env: Map<String, String> = emptyMap()): Run {
        val out = File(outDir, "out.txt")
        val process = ProcessBuilder("bash", RepoFiles.file("deploy/vultr/auto_deploy_gate.sh").path, lastGood, target)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply { environment().putAll(env) }
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "auto_deploy_gate.sh 가 30초 안에 끝나지 않았다" }
        return Run(process.exitValue(), out.readText())
    }

    private fun write(path: String, content: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun commitAll(message: String) {
        git("add", "-A")
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", message)
    }

    private fun head(): String = git("rev-parse", "HEAD").trim()

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output
    }
}
