package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** 새 이미지로 마이그레이션한 빈 DB 위에서 직전 정상 이미지가 healthy 인지로 롤백 호환을 가른다 (#323). */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployRollbackCompatScriptTest {

    @TempDir
    lateinit var dir: File

    private class Run(val exit: Int, val output: String)

    private val lastGood = "1".repeat(40)
    private val target = "2".repeat(40)
    private val calls get() = File(dir, "docker.calls").takeIf { it.exists() }?.readLines().orEmpty()

    /** 앱 컨테이너는 [healthy] 에 든 SHA 의 이미지로 떴을 때만 health 에 답한다. postgres 는 바로 healthy 다. */
    private fun run(vararg args: String, healthy: Set<String> = emptySet(), unpullable: String = "-"): Run {
        val vultr = File(dir, "deploy/vultr").apply { mkdirs() }
        listOf("rollback_compat.sh", "docker-compose.prod.yml").forEach { RepoFiles.file("deploy/vultr/$it").copyTo(File(vultr, it), overwrite = true) }
        val bin = File(dir, "bin").apply { mkdirs() }
        val current = File(dir, "app.sha").path
        File(bin, "docker").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                    "echo \"\$*\" >> '${File(dir, "docker.calls").path}'\n" +
                    "case \"\$*\" in\n" +
                    "  pull*:$unpullable) exit 1 ;;\n" +
                    "  run*-app*) echo \"\${@: -1}\" | sed 's/.*://' > '$current' ;;\n" +
                    "  *Health.Status*) echo healthy ;;\n" +
                    "  *State.Running*) echo true ;;\n" +
                    "  exec*) grep -qxF \"\$(cat '$current')\" <<< '${healthy.joinToString("\n")}' || exit 1 ;;\n" +
                    "esac\n",
            )
            setExecutable(true)
        }
        val out = File(dir, "out.txt")
        val process = ProcessBuilder(listOf("bash", File(vultr, "rollback_compat.sh").path) + args)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["HEALTH_DEADLINE_SECONDS"] = "1"
            }
            .start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "rollback_compat.sh 가 끝나지 않았다" }
        return Run(process.exitValue(), out.readText())
    }

    private fun appRuns() = calls.filter { it.startsWith("run ") && it.contains("-app ") }.map { it.substringAfterLast(':') }

    @Test
    fun `새 이미지로 마이그레이션한 뒤 직전 이미지가 뜨면 호환이다`() {
        val result = run("ghcr.io/o/app", lastGood, target, healthy = setOf(lastGood, target))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(appRuns()).containsExactly(target, lastGood)
        // 자동 시작을 켜야 복원이 bot_state 를 읽고 health 에 반영된다.
        assertThat(calls.filter { it.startsWith("run ") && it.contains("-app ") }).allMatch { it.contains("TRADING_AUTO_START=true") }
        assertThat(calls.filter { it.startsWith("run ") }.first()).contains("postgres:17")
        assertThat(calls.last()).startsWith("network rm ")
    }

    @Test
    fun `직전 이미지가 새 스키마에서 뜨지 않으면 비호환이다`() {
        val result = run("ghcr.io/o/app", lastGood, target, healthy = setOf(target))

        assertThat(result.exit).describedAs(result.output).isEqualTo(1)
        assertThat(result.output).contains("롤백 비호환")
    }

    @Test
    fun `새 이미지가 뜨지 않거나 입력이 틀리면 검사 불가다`() {
        val broken = run("ghcr.io/o/app", lastGood, target, healthy = setOf(lastGood))
        assertThat(broken.exit).describedAs(broken.output).isEqualTo(2)
        assertThat(appRuns()).containsExactly(target)

        File(dir, "docker.calls").delete()
        val unpulled = run("ghcr.io/o/app", lastGood, target, healthy = setOf(lastGood, target), unpullable = lastGood)
        assertThat(unpulled.exit).describedAs(unpulled.output).isEqualTo(2)
        assertThat(appRuns()).isEmpty()

        File(dir, "docker.calls").delete()
        val badArgs = run("ghcr.io/o/app", "main", target)
        assertThat(badArgs.exit).isEqualTo(2)
        assertThat(calls).isEmpty()
    }
}
