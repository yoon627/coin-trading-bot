package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 배포가 앱 헬스 확인 뒤 서버에서 돌려 바뀐 Caddyfile 을 실행 중인 caddy 에 reload 로 적용한다. 0=변경 없음 10=적용 그 밖=실패.
 * 운영 호스트에서도 테스트가 돌므로 docker 는 PATH 앞의 가짜만 불리는지 기록으로 확인한다.
 */
@EnabledOnOs(OS.LINUX, disabledReason = "GNU timeout·sha256sum 을 쓰는 서버 스크립트 — 서버와 CI 는 Linux 다")
class CaddyApplyScriptTest {

    @TempDir
    lateinit var dir: File

    private val app by lazy { File(dir, "app").apply { File(this, "caddy").mkdirs() } }
    private val bin by lazy { File(dir, "bin").apply { mkdirs() } }
    private val dockerCalls by lazy { File(dir, "docker.calls") }
    private val caddyfile by lazy { File(app, "caddy/Caddyfile") }
    private val marker by lazy { File(app, ".caddyfile-applied") }

    private class Run(val exit: Int, val output: String)

    @Test
    fun `마지막으로 적용한 Caddyfile 과 같으면 caddy 를 건드리지 않고 0 으로 끝난다`() {
        caddyfile.writeText(CONFIG)
        marker.writeText(sha256(CONFIG) + "\n")

        val result = apply()

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(dockerCalls).doesNotExist()
    }

    @Test
    fun `Caddyfile 이 바뀌었거나 적용 기록이 없으면 실행 중인 caddy 에 reload 하고 기록을 남긴다`() {
        caddyfile.writeText(CONFIG)
        for (previous in listOf(null, sha256("old\n"))) {
            if (previous == null) marker.delete() else marker.writeText(previous + "\n")
            dockerCalls.delete()

            val result = apply()

            assertThat(result.exit).describedAs("기록=$previous\n${result.output}").isEqualTo(10)
            assertThat(dockerCalls.readLines())
                .containsExactly("compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile")
            assertThat(marker.readText().trim()).isEqualTo(sha256(CONFIG))
        }
    }

    @Test
    fun `reload 가 실패하면 기록을 바꾸지 않고 실패로 끝나며 이전 설정이 유지된다고 남긴다`() {
        caddyfile.writeText(CONFIG)
        marker.writeText(sha256("old\n") + "\n")

        val result = apply(reloadExit = 1)

        assertThat(result.exit).describedAs(result.output).isEqualTo(1)
        assertThat(marker.readText().trim()).isEqualTo(sha256("old\n"))
        assertThat(result.output).contains("이전 설정")
        assertThat(dockerCalls.readLines().first()).startsWith("compose exec -T caddy caddy reload")
    }

    /** 가짜 docker: 인자를 한 줄로 기록하고, reload 는 받은 코드로 끝난다. */
    private fun apply(reloadExit: Int = 0): Run {
        File(bin, "docker").apply {
            writeText(
                """
                |#!/usr/bin/env bash
                |printf '%s\n' "${'$'}*" >> "${dockerCalls.path}"
                |case "${'$'}*" in *' reload '*) exit $reloadExit ;; esac
                |exit 0
                """.trimMargin() + "\n",
            )
            setExecutable(true)
        }
        val out = File(dir, "out.txt")
        val process = ProcessBuilder("bash", RepoFiles.file(SCRIPT).path)
            .directory(app)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply {
                environment().keys.retainAll(setOf("PATH"))
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["HOME"] = dir.path
            }
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("$SCRIPT 가 60초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCRIPT = "deploy/vultr/apply_caddyfile.sh"
        const val CONFIG = "{\$APP_DOMAIN} {\n\treverse_proxy app:8080\n}\n"
    }
}
