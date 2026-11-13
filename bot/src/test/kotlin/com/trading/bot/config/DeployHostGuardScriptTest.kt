package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** 잘못 가리킨 `.state`·label 로 운영 호스트에 setup 하거나 운영 호스트를 지우지 않는다 (#295). */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployHostGuardScriptTest {

    @TempDir
    lateinit var dir: File

    private class Run(val exit: Int, val output: String)

    private val calls get() = File(dir, "calls.txt").takeIf { it.exists() }?.readLines().orEmpty()

    private val baseEnv = mapOf(
        "APP_ENCRYPTION_SECRET" to "existing-value",
        "DB_PASSWORD" to "existing-value",
        "JWT_SECRET" to "existing-value",
        "VULTR_API_KEY" to "k",
        "SSH_ALLOW_CIDR" to "198.51.100.1/32",
    )

    private fun prepare(state: String?, envFile: File = File(dir, ".env"), env: Map<String, String> = baseEnv) {
        RepoFiles.file("deploy/vultr/deploy.sh").copyTo(File(dir, "deploy.sh"), overwrite = true)
        envFile.writeText(env.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        state?.let { File(dir, ".state").writeText(it) }
    }

    /** Vultr API 를 대신한다. 모든 호출에 같은 본문을 돌려주고 `METHOD path` 를 calls.txt 에 남긴다. */
    private fun fakeVultr(instances: String = "[]") {
        val body = """{"account":{"email":"t@example.com"},"instances":$instances,"ssh_keys":[],"firewall_rules":[],""" +
            """"instance":{"id":"new-1","status":"active","main_ip":"203.0.113.5"}}"""
        File(dir, "bin").mkdirs()
        File(dir, "bin/curl").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                    "method=GET; url=\"\"\n" +
                    "while [[ \$# -gt 0 ]]; do case \"\$1\" in -X) method=\"\$2\"; shift 2 ;; https://*) url=\"\$1\"; shift ;; *) shift ;; esac; done\n" +
                    "echo \"\$method \${url#https://api.vultr.com/v2}\" >> '${File(dir, "calls.txt").path}'\n" +
                    "printf '%s\\n200' '$body'\n",
            )
            setExecutable(true)
        }
    }

    private fun run(command: String, env: Map<String, String> = emptyMap(), input: String = ""): Run {
        val out = File(dir, "out.txt")
        val process = ProcessBuilder("bash", File(dir, "deploy.sh").path, command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                environment().keys.retainAll(setOf("PATH", "HOME"))
                if (!File(dir, "bin/curl").exists()) fakeVultr()
                environment()["PATH"] = "${File(dir, "bin").path}:${environment()["PATH"]}"
                environment().putAll(env)
            }
            .start()
        process.outputStream.use { it.write(input.toByteArray()) }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("deploy.sh $command 가 30초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    @Test
    fun `setup 은 state 없이 label 로만 찾은 인스턴스를 명시 없이는 다시 쓰지 않는다`() {
        prepare(state = "FIREWALL_ID=fw-1\nSSHKEY_ID=key-1\n")
        fakeVultr("""[{"id":"old-2","label":"coin-trading-bot","main_ip":"203.0.113.10"}]""")

        val refused = run("setup")

        assertNotEquals(0, refused.exit, refused.output)
        assertTrue(refused.output.contains("SETUP_REUSE_INSTANCE=old-2"), refused.output)
        assertFalse(File(dir, ".state").readText().contains("INSTANCE_ID"), "확인 없이 기존 인스턴스를 .state 에 적었다")

        val reused = run("setup", mapOf("SETUP_REUSE_INSTANCE" to "old-2"))

        assertEquals(0, reused.exit, reused.output)
        assertTrue(File(dir, ".state").readText().contains("INSTANCE_ID=old-2"))
        assertTrue(calls.none { it.startsWith("POST /instances") }, calls.joinToString("\n"))
    }

    @Test
    fun `같은 label 의 인스턴스가 여럿이면 명시해도 다시 쓰지 않고 모두 보여 준다`() {
        prepare(state = "FIREWALL_ID=fw-1\nSSHKEY_ID=key-1\n")
        fakeVultr(
            """[{"id":"prod-1","label":"coin-trading-bot","main_ip":"203.0.113.10"},""" +
                """{"id":"old-2","label":"coin-trading-bot","main_ip":"203.0.113.11"}]""",
        )

        val result = run("setup", mapOf("SETUP_REUSE_INSTANCE" to "prod-1"))

        assertNotEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("prod-1 203.0.113.10") && result.output.contains("old-2 203.0.113.11"), result.output)
        assertFalse(File(dir, ".state").readText().contains("INSTANCE_ID"))
    }

    @Test
    fun `DEPLOY_ENV_FILE 과 DEPLOY_STATE_FILE 로 다른 호스트를 같은 체크아웃에서 다룬다`() {
        prepare(state = "INSTANCE_ID=prod-1\nPUBLIC_IP=203.0.113.10\n")
        val newEnv = File(dir, "new-host.env")
        prepare(state = null, envFile = newEnv, env = baseEnv + ("APP_NAME" to "ctb-new"))
        val newState = File(dir, "new-host.state").apply { writeText("FIREWALL_ID=fw-2\nSSHKEY_ID=key-2\n") }

        val result = run("setup", mapOf("DEPLOY_ENV_FILE" to newEnv.path, "DEPLOY_STATE_FILE" to newState.path))

        assertEquals(0, result.exit, result.output)
        assertTrue(newState.readText().contains("INSTANCE_ID=new-1"), newState.readText())
        assertEquals("INSTANCE_ID=prod-1\nPUBLIC_IP=203.0.113.10\n", File(dir, ".state").readText())
        assertTrue(File(dir, "ctb-new-key.pem").exists(), "SSH 키가 새 APP_NAME 을 따르지 않았다")
    }

    @Test
    fun `destroy 는 대상 IP 를 그대로 입력해야만 지운다`() {
        prepare(state = "INSTANCE_ID=prod-1\nPUBLIC_IP=203.0.113.10\nFIREWALL_ID=fw-1\n")

        val cancelled = run("destroy", input = "yes\n")

        assertEquals(0, cancelled.exit, cancelled.output)
        assertTrue(cancelled.output.contains("취소"), cancelled.output)
        assertTrue(calls.none { it.startsWith("DELETE") }, calls.joinToString("\n"))
        assertTrue(File(dir, ".state").exists())

        val confirmed = run("destroy", input = "203.0.113.10\ndelete-my-data\n")

        assertEquals(0, confirmed.exit, confirmed.output)
        assertTrue(calls.contains("DELETE /instances/prod-1"), calls.joinToString("\n"))
    }
}
