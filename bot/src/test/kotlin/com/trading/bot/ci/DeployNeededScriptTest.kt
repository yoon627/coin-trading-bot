package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 수동 배포는 마지막 정상 배포 이후 운영에 들어가는 파일이 바뀌었을 때만 엔진을 재시작한다.
 * 판정을 모르는 경로는 배포 쪽으로 기울어야 한다 — 놓친 변경은 조용히 미반영되지만 불필요한 재시작은 보인다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux) 에서 돈다")
class DeployNeededScriptTest {

    @TempDir
    lateinit var dir: File

    /** 출력 파일을 저장소 밖에 둔다 — 안에 두면 다음 `git add -A` 가 커밋에 넣는다. */
    @TempDir
    lateinit var outDir: File

    private lateinit var base: String

    private class Run(val exit: Int, val output: String) {
        val decision: String? get() = output.lineSequence().firstOrNull { it.startsWith("deploy=") }
    }

    @BeforeEach
    fun initRepo() {
        git("init", "-q")
        (PRODUCTION_PATHS + IRRELEVANT_PATHS).forEach { write(it, "v1") }
        commitAll("base")
        base = head()
    }

    @ParameterizedTest
    @MethodSource("productionPaths")
    fun `운영에 들어가는 파일이 바뀌면 배포한다`(path: String) {
        val target = change(path)

        val result = judge(base, base, target)

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=true")
        assertThat(result.output).contains(path)
    }

    @ParameterizedTest
    @MethodSource("irrelevantPaths")
    fun `운영과 무관한 파일만 바뀌면 배포하지 않는다`(path: String) {
        val target = change(path)

        val result = judge(base, base, target)

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=false")
    }

    @Test
    fun `무관한 파일과 운영 파일이 함께 바뀌면 배포한다`() {
        write("README.md", "v2")
        write("bot/src/main/kotlin/App.kt", "v2")
        commitAll("mixed")

        val result = judge(base, base, head())

        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=true")
    }

    @Test
    fun `운영 파일을 제외 경로로 옮기거나 지우면 배포한다`() {
        git("mv", "bot/src/main/kotlin/App.kt", "bot/src/test/kotlin/App.kt")
        commitAll("move to test")
        val moved = judge(base, base, head())
        git("rm", "-q", "Dockerfile")
        commitAll("delete")
        val deleted = judge(base, base, head())

        assertThat(moved.decision).describedAs(moved.output).isEqualTo("deploy=true")
        assertThat(deleted.decision).describedAs(deleted.output).isEqualTo("deploy=true")
    }

    @Test
    fun `이미 배포된 커밋을 다시 실행하면 배포하지 않는다`() {
        val result = judge(base, base, base)

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=false")
    }

    @Test
    fun `운영 컨테이너가 마지막 정상 배포의 healthy 컨테이너가 아니면 문서만 바뀌어도 배포한다`() {
        val target = change("README.md")

        val notHealthy = judge(base, "-", target)
        val otherImage = judge(base, target, target)

        assertThat(notHealthy.decision).describedAs(notHealthy.output).isEqualTo("deploy=true")
        assertThat(otherImage.decision).describedAs(otherImage.output).isEqualTo("deploy=true")
    }

    @Test
    fun `마지막 정상 배포보다 옛 커밋은 배포하지 않고 실패한다`() {
        val newer = change("bot/src/main/kotlin/App.kt")

        val result = judge(newer, newer, base)

        assertThat(result.exit).describedAs(result.output).isEqualTo(3)
        assertThat(result.decision).isNull()
    }

    @Test
    fun `마지막 정상 배포 커밋이 저장소에 없으면 판정할 수 없으므로 배포한다`() {
        val target = change("README.md")

        val result = judge(MISSING_SHA, MISSING_SHA, target)

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=true")
    }

    @Test
    fun `배포 대상이 커밋이 아니면 판정하지 않고 실패한다`() {
        val result = judge(base, base, MISSING_SHA)

        assertThat(result.exit).describedAs(result.output).isEqualTo(2)
        assertThat(result.decision).isNull()
    }

    @Test
    fun `인자가 모자라거나 SHA 형식이 아니면 판정하지 않고 실패한다`() {
        val results = listOf(judge(base, base), judge("", base, base), judge(base, "", base), judge(base, base, "main"))

        results.forEach {
            assertThat(it.exit).describedAs(it.output).isEqualTo(2)
            assertThat(it.decision).describedAs(it.output).isNull()
        }
    }

    @Test
    fun `하위 디렉터리에서 불러도 저장소 전체를 비교한다`() {
        val target = change("bot/src/main/kotlin/App.kt")

        val result = exec(listOf("bash", repoFile(SCRIPT).path, base, base, target), File(dir, "deploy/vultr"))

        assertThat(result.decision).describedAs(result.output).isEqualTo("deploy=true")
    }

    private fun head(): String = git("rev-parse", "HEAD").trim()

    private fun change(path: String): String {
        write(path, "v2")
        commitAll("change $path")
        return head()
    }

    private fun write(path: String, content: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText("$content\n")
    }

    private fun commitAll(message: String) {
        git("add", "-A")
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String = exec(listOf("git", *args)).also {
        check(it.exit == 0) { "git ${args.joinToString(" ")} 실패:\n${it.output}" }
    }.output

    private fun judge(vararg args: String): Run = exec(listOf("bash", repoFile(SCRIPT).path, *args))

    private fun exec(command: List<String>, cwd: File = dir): Run {
        val out = File(outDir, "out.txt")
        val process = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                // 개발자의 전역 git 설정(서명·훅·기본 브랜치)이 판정에 끼지 않게 한다.
                environment().keys.retainAll(setOf("PATH"))
                environment()["HOME"] = dir.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.joinToString(" ")} 가 30초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        check(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found
    }

    companion object {
        private const val SCRIPT = "deploy/vultr/deploy_needed.sh"
        private const val MISSING_SHA = "0123456789abcdef0123456789abcdef01234567"

        private val PRODUCTION_PATHS = listOf(
            "bot/src/main/kotlin/App.kt", "bot/src/main/resources/application.yml", "common/src/main/kotlin/Lib.kt",
            "bot/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
            "Dockerfile", ".dockerignore", "deploy/vultr/docker-compose.prod.yml", "deploy/vultr/deploy.sh",
            "deploy/vultr/Caddyfile", "deploy/vultr/backup.sh", "deploy/vultr/preflight_exit_params.sh",
            "deploy/vultr/known_hosts", "deploy/vultr/deploy_needed.sh", ".github/workflows/deploy.yml",
        )
        private val IRRELEVANT_PATHS = listOf(
            "README.md", "deploy/vultr/README.md", "docs/notes.txt", "bot/src/test/kotlin/AppTest.kt",
            "common/src/test/kotlin/LibTest.kt", "scripts/run-db-tests.sh", ".gitignore", "docker-compose.yml",
            ".env.example", "gradlew", "gradlew.bat",
        )

        @JvmStatic
        fun productionPaths(): List<String> = PRODUCTION_PATHS

        @JvmStatic
        fun irrelevantPaths(): List<String> = IRRELEVANT_PATHS
    }
}
