package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** 이름이 바뀐 설정의 옛 이름에 값이 남아 있으면, 새 이름으로 넘어가지 않고 조용히 빠지지 않게 배포가 멈춘다. */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployRenamedKeyScriptTest {

    @TempDir
    lateinit var dir: File

    private fun run(env: Map<String, String>): Pair<Int, String> {
        RepoFiles.file("deploy/vultr/deploy.sh").copyTo(File(dir, "deploy.sh"), overwrite = true)
        File(dir, ".env").writeText(env.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        val out = File(dir, "out.txt")
        val process = ProcessBuilder("bash", File(dir, "deploy.sh").path, "status")
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply { environment().keys.retainAll(setOf("PATH", "HOME")) }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("deploy.sh status 가 30초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return process.exitValue() to out.readText()
    }

    @Test
    fun `the old reconcile threshold name stops the deploy before anything runs`() {
        val (exit, output) = run(mapOf("TRADING_RECONCILE_HALT_THRESHOLD" to "30"))

        assertEquals(1, exit, output)
        assertTrue(output.contains("TRADING_RECONCILE_HALT_THRESHOLD 는 TRADING_RECONCILE_ALERT_THRESHOLD 로 이름이 바뀌었습니다"), output)
    }

    @Test
    fun `without the old name the deploy goes on past the check`() {
        val (_, output) = run(mapOf("TRADING_RECONCILE_ALERT_THRESHOLD" to "30"))

        assertFalse(output.contains("이름이 바뀌었습니다"), output)
    }
}
