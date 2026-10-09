package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 커밋 전 민감 경로 검사(#299) — 비밀 파일은 막고, 값이 없는 dotenv 템플릿(`.env.example`)의 수정·삭제는 통과시킨다.
 * dotenv 템플릿만 경로가 아니라 내용으로 본다: 비밀처럼 보이는 키에 placeholder 가 아닌 값이 들어 있으면 막는다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 커밋 훅은 Linux·macOS 에서 돈다")
class StagedSensitiveFilesCheckScriptTest {

    @TempDir
    lateinit var repo: File

    private val script by lazy { repoFile("scripts/git-hooks/check-staged-sensitive.sh") }

    private class Run(val exit: Int, val output: String)

    private fun git(vararg args: String) {
        val p = ProcessBuilder("git", *args).directory(repo).redirectErrorStream(true).start()
        val finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(finished && p.exitValue() == 0, "git ${args.joinToString(" ")}: ${p.inputStream.bufferedReader().readText()}")
    }

    @BeforeEach
    fun init() {
        git("init", "-q")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        git("commit", "-q", "--allow-empty", "-m", "init")
    }

    private fun stage(path: String, content: String) {
        File(repo, path).apply { parentFile.mkdirs(); writeText(content) }
        git("add", path)
    }

    private fun check(): Run {
        val out = File(repo, ".check-out")
        val p = ProcessBuilder("bash", script.path).directory(repo).redirectErrorStream(true).redirectOutput(out).start()
        assertTrue(p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "check did not finish")
        return Run(p.exitValue(), out.readText())
    }

    // 옛 PreToolUse 패턴(`**/.env*`·`**/*.env`·`**/*.pem`·`*credentials*`·`*secret*`, 대소문자 무시)과 같은 범위다.
    @ParameterizedTest
    @ValueSource(strings = ["deploy/.env", "a/.ENV.local", ".envrc", "config/prod.env", "keys/X.PEM", "aws/credentials/config", "app-Secret.yml"])
    fun `a sensitive path is blocked`(path: String) {
        stage(path, "x\n")

        val run = check()

        assertEquals(EXIT_BLOCKED, run.exit, run.output)
        assertTrue(run.output.contains(path), run.output)
    }

    @Test
    fun `a dotenv template with empty secrets, comments and non-secret defaults passes`() {
        stage(
            ".env.example",
            "# DB\nDB_PASSWORD=\nJWT_SECRET=\nVULTR_REGION=icn\nTRADING_TICKERS=KRW-BTC,KRW-ETH\nAPP_ENCRYPTION_SECRET=\n",
        )

        assertEquals(EXIT_OK, check().exit)
    }

    @Test
    fun `a dotenv template with placeholder secrets passes`() {
        stage("deploy/vultr/.env.example", "UPBIT_SECRET_KEY=your_secret_key_here\nGHCR_TOKEN=<token>\nDB_PASSWORD=changeme\n")

        assertEquals(EXIT_OK, check().exit)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "JWT_SECRET=s3cr3t-value-123\n",
            "JWT_SECRET=s3cr3t-value-123",
            "export DB_PASSWORD=\"#starts-with-hash\"\n",
            "DB_PASSWORD=#starts-with-hash\n",
            "JWT_SECRET=your-real-prod-secret-2f9a\n",
            "PRIVATE_KEY=\"-----BEGIN\nabc\n\"\n",
        ],
    )
    fun `a dotenv template with a filled secret is blocked`(content: String) {
        // 개행 없는 마지막 줄·따옴표·# 로 시작하는 값·접두사만 맞는 placeholder·여러 줄 값까지 — 판정을 비켜 가면 실제 값이 커밋된다.
        stage(".env.example", content)

        assertEquals(EXIT_BLOCKED, check().exit, content)
    }

    @Test
    fun `a dotenv template value marked as not a secret passes`() {
        // 로컬 개발 기본값(application.yml 의 기본값과 같은 값)은 비밀이 아니다 — 줄에 표시해 두면 리뷰에서도 보인다.
        stage(".env.example", "DB_PASSWORD=trading  # not-a-secret: 로컬 기본값\nJWT_SECRET=\n")

        assertEquals(EXIT_OK, check().exit)
    }

    @ParameterizedTest
    @ValueSource(strings = ["credentials.json.template", "config/secrets.yml.example", "aws/credentials.example"])
    fun `a template that is not dotenv is judged by its path`(path: String) {
        // 줄 단위 KEY=VALUE 로 판정할 수 없는 형식이다 — 내용으로 통과시키면 옛 검사보다 좁아진다.
        stage(path, "{\"password\": \"hunter2\"}\n")

        assertEquals(EXIT_BLOCKED, check().exit)
    }

    @Test
    fun `deleting a template or a key file is not blocked`() {
        stage(".env.example", "DB_PASSWORD=\n")
        stage("old.pem", "-----BEGIN-----\n")
        git("commit", "-q", "-m", "add")
        git("rm", "-q", ".env.example", "old.pem")

        assertEquals(EXIT_OK, check().exit)
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val EXIT_OK = 0
        const val EXIT_BLOCKED = 2
    }
}
