package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 이미 있는 인스턴스에는 secret 을 만들지 않는다 (#231).
 *
 * 새로 만든 DB_PASSWORD 는 기존 postgres 볼륨의 비밀번호와 달라 앱이 기동하지 못하고, 자동 롤백도 같은 서버 `.env` 를 쓴다.
 * 새 JWT_SECRET 은 모든 세션을 끊는다. 만드는 것은 이번 setup 이 새로 만든 인스턴스뿐이다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeploySecretsScriptTest {

    @TempDir
    lateinit var dir: File

    private class Run(val exit: Int, val output: String)

    private fun prepare(env: Map<String, String>, state: String?): File {
        repoFile("deploy/vultr/deploy.sh").copyTo(File(dir, "deploy.sh"))
        File(dir, ".env").writeText(env.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        state?.let { File(dir, ".state").writeText(it) }
        return File(dir, ".env")
    }

    private fun run(command: String, path: String? = null): Run {
        val out = File(dir, "out.txt")
        val process = ProcessBuilder("bash", File(dir, "deploy.sh").path, command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                environment().keys.retainAll(setOf("PATH", "HOME"))
                path?.let { environment()["PATH"] = "$it:${environment()["PATH"]}" }
            }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("deploy.sh $command 가 30초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    /** Vultr API 를 대신한다 — 모든 호출에 같은 본문을 돌려준다. [instances] 는 label 로 찾는 기존 인스턴스 목록이다. */
    private fun fakeVultr(instances: String = "[]"): String {
        val bin = File(dir, "bin").apply { mkdirs() }
        val body = """{"account":{"email":"t@example.com"},"instances":$instances,"ssh_keys":[],"firewall_rules":[],""" +
            """"instance":{"id":"new-1","status":"active","main_ip":"203.0.113.5"}}"""
        File(bin, "curl").apply {
            writeText("#!/usr/bin/env bash\nprintf '%s\\n200' '$body'\n")
            setExecutable(true)
        }
        return bin.path
    }

    private val secrets = listOf("DB_PASSWORD", "JWT_SECRET", "APP_ENCRYPTION_SECRET")

    private fun withMissing(missing: String, extra: Map<String, String> = emptyMap()) =
        secrets.associateWith { if (it == missing) "" else "existing-value" } + extra

    @ParameterizedTest
    @ValueSource(strings = ["DB_PASSWORD", "JWT_SECRET", "APP_ENCRYPTION_SECRET"])
    fun `deploy 는 비어 있는 secret 을 만들지 않고 아무것도 하기 전에 멈춘다`(missing: String) {
        val env = prepare(withMissing(missing), state = "PUBLIC_IP=203.0.113.10\n")
        val before = env.readText()

        val result = run("deploy")

        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("$missing 이 비어 있습니다"), result.output)
        assertEquals(before, env.readText(), "deploy 가 .env 에 secret 을 덧붙였다")
    }

    @Test
    fun `setup 은 기존 인스턴스를 다시 쓸 때 비어 있는 DB_PASSWORD 를 만들지 않는다`() {
        val env = prepare(
            withMissing("DB_PASSWORD", mapOf("VULTR_API_KEY" to "k", "SSH_ALLOW_CIDR" to "198.51.100.1/32")),
            state = "FIREWALL_ID=fw-1\nSSHKEY_ID=key-1\nINSTANCE_ID=old-1\nPUBLIC_IP=203.0.113.10\n",
        )
        val before = env.readText()

        val result = run("setup", fakeVultr())

        assertNotEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("DB_PASSWORD 이 비어 있습니다"), result.output)
        assertEquals(before, env.readText(), "기존 인스턴스에 secret 을 만들었다")
    }

    @Test
    fun `setup 은 같은 label 의 인스턴스를 찾아 다시 쓸 때도 비어 있는 DB_PASSWORD 를 만들지 않는다`() {
        // .state 를 잃은 체크아웃에서 setup 을 다시 돌리는 경우다 — 운영 인스턴스를 label 로 찾아 다시 쓴다.
        val env = prepare(
            withMissing("DB_PASSWORD", mapOf("VULTR_API_KEY" to "k", "SSH_ALLOW_CIDR" to "198.51.100.1/32")),
            state = "FIREWALL_ID=fw-1\nSSHKEY_ID=key-1\n",
        )
        val before = env.readText()

        val result = run("setup", fakeVultr("""[{"id":"old-2","label":"coin-trading-bot"}]"""))

        assertNotEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("재사용: old-2"), result.output)
        assertEquals(before, env.readText(), "기존 인스턴스에 secret 을 만들었다")
    }

    @Test
    fun `setup 은 새로 만든 인스턴스에만 비어 있는 secret 을 만든다`() {
        val env = prepare(
            mapOf("APP_ENCRYPTION_SECRET" to "existing-value", "VULTR_API_KEY" to "k", "SSH_ALLOW_CIDR" to "198.51.100.1/32"),
            state = "FIREWALL_ID=fw-1\nSSHKEY_ID=key-1\n",
        )

        val result = run("setup", fakeVultr())

        assertEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("생성됨: new-1"), result.output)
        val written = env.readLines()
        assertTrue(written.any { it.matches(Regex("DB_PASSWORD=[0-9a-f]{32}")) }, written.joinToString("\n"))
        assertTrue(written.any { it.startsWith("JWT_SECRET=") && it.length > "JWT_SECRET=".length }, written.joinToString("\n"))
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }
}
