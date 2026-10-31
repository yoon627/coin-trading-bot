package com.trading.bot.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 배포 워크플로의 판정 스텝과 이미지 확인 스텝 bash 를 YAML 에서 꺼내 그대로 실행한다. 정적 검사는 연결만 보므로,
 * force 가 판정을 건너뛰는지·옛 커밋 거부가 배포를 막는지·이미 있는 이미지를 다시 빌드하지 않는지는 여기서 본다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux) 에서 돈다")
class DeployWorkflowStepScriptTest {

    @TempDir
    lateinit var repo: File

    /** 출력·가짜 실행 파일을 저장소 밖에 둔다. */
    @TempDir
    lateinit var work: File

    private class Run(val exit: Int, val output: String, val githubOutput: String)

    @Test
    fun `force 면 판정 스크립트를 부르지 않고 배포한다`() {
        val head = initRepo(withScript = false)

        val result = runStep("deploy-vultr", "changes", repo, judgeEnv(force = "true", lastGood = head, target = head))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.githubOutput).isEqualTo("deploy=true\n")
    }

    @Test
    fun `운영 변경이 없으면 알림을 남기고 배포하지 않는다`() {
        val head = initRepo()

        val result = runStep("deploy-vultr", "changes", repo, judgeEnv(force = "false", lastGood = head, target = head))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.githubOutput).isEqualTo("deploy=false\n")
        assertThat(result.output).contains("::notice::")
    }

    @Test
    fun `마지막 정상 배포보다 옛 커밋이면 스텝이 실패하고 배포 출력을 남기지 않는다`() {
        val older = initRepo()
        write("bot/src/main/kotlin/App.kt", "v2")
        commitAll("newer")
        val newer = git("rev-parse", "HEAD").trim()

        val result = runStep("deploy-vultr", "changes", repo, judgeEnv(force = "false", lastGood = newer, target = older))

        assertThat(result.exit).describedAs(result.output).isNotEqualTo(0)
        assertThat(result.output).contains("::error::")
        assertThat(result.githubOutput).isEmpty()
    }

    /** SSH 연결 실패(255)를 컨테이너 비정상으로 읽으면 바뀐 것 없이 엔진을 재시작한다. */
    @Test
    fun `운영 컨테이너 조회는 healthy 면 SHA, 원격 검사 실패면 '-', SSH 실패면 스텝 실패다`() {
        val healthy = runPrepare("healthy")
        val unhealthy = runPrepare("unhealthy")
        val sshDown = runPrepare("down")

        assertThat(healthy.githubOutput).describedAs(healthy.output).isEqualTo("last_good_sha=$SHA\nrunning_sha=$SHA\n")
        assertThat(unhealthy.githubOutput).describedAs(unhealthy.output).isEqualTo("last_good_sha=$SHA\nrunning_sha=-\n")
        assertThat(sshDown.exit).describedAs(sshDown.output).isNotEqualTo(0)
        assertThat(sshDown.githubOutput).doesNotContain("running_sha")
    }

    @Test
    fun `이미 있는 이미지는 다시 빌드하지 않는다`() {
        val result = runStep("build-and-push", "image", work, imageEnv(), fakeDocker(exit = 0, stderr = ""))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.githubOutput).isEqualTo("exists=true\n")
    }

    @Test
    fun `이미지를 확인하지 못하면 이유를 남기고 빌드한다`() {
        val result = runStep("build-and-push", "image", work, imageEnv(), fakeDocker(exit = 1, stderr = "ERROR: manifest unknown"))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.githubOutput).isEmpty()
        assertThat(result.output).contains("manifest unknown")
    }

    private fun judgeEnv(force: String, lastGood: String, target: String) = mapOf(
        "FORCE" to force,
        "LAST_GOOD_SHA" to lastGood,
        "RUNNING_SHA" to lastGood,
        "GITHUB_SHA" to target,
    )

    private fun imageEnv() = mapOf(
        "REGISTRY" to "ghcr.io",
        "IMAGE_NAME" to "owner/app",
        "GITHUB_SHA" to SHA,
    )

    /**
     * 서버를 대신하는 ssh: `.last-good-sha` 를 읽는 첫 호출에는 [SHA] 를, 운영 컨테이너 조회에는 [running] 에 따라
     * 이미지 이름·원격 검사 실패(1)·연결 실패(255)를 돌려준다.
     */
    private fun runPrepare(running: String): Run {
        val workspace = File(work, "ws").apply { File(this, "deploy/vultr").mkdirs() }
        File(workspace, "deploy/vultr/known_hosts").writeText("203.0.113.10 ssh-ed25519 AAAA\n")
        val bin = File(work, "bin").apply { mkdirs() }
        File(bin, "ssh").apply {
            writeText(
                """
                |#!/usr/bin/env bash
                |remote="${'$'}{@: -1}"
                |if [[ "${'$'}remote" == *last-good-sha* ]]; then echo "$SHA"; exit 0; fi
                |case "$running" in
                |  healthy) echo "ghcr.io/owner/app:$SHA" ;;
                |  unhealthy) exit 1 ;;
                |  down) exit 255 ;;
                |esac
                """.trimMargin() + "\n",
            )
            setExecutable(true)
        }
        val env = mapOf(
            "VULTR_DEPLOY_ENV" to "DB_PASSWORD=x",
            "VULTR_PUBLIC_IP" to "203.0.113.10",
            "VULTR_SSH_PRIVATE_KEY" to "key",
            "VULTR_SSH_USER" to "root",
            "GITHUB_SHA" to SHA,
            "GITHUB_REPOSITORY" to "owner/app",
            "GITHUB_WORKSPACE" to workspace.path,
        )
        return runStep("deploy-vultr", "prepare", workspace, env, bin)
    }

    /** `docker buildx imagetools inspect` 를 대신한다. */
    private fun fakeDocker(exit: Int, stderr: String): File {
        val bin = File(work, "bin").apply { mkdirs() }
        File(bin, "docker").apply {
            writeText("#!/usr/bin/env bash\necho '$stderr' >&2\nexit $exit\n")
            setExecutable(true)
        }
        return bin
    }

    private fun initRepo(withScript: Boolean = true): String {
        git("init", "-q")
        write("bot/src/main/kotlin/App.kt", "v1")
        commitAll("base")
        if (withScript) RepoFiles.file(SCRIPT).copyTo(File(repo, SCRIPT))
        return git("rev-parse", "HEAD").trim()
    }

    private fun write(path: String, content: String) {
        File(repo, path).apply { parentFile.mkdirs() }.writeText("$content\n")
    }

    private fun commitAll(message: String) {
        git("add", "bot")
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String {
        val result = exec(listOf("git", *args), repo, emptyMap(), null)
        check(result.exit == 0) { "git ${args.joinToString(" ")} 실패:\n${result.output}" }
        return result.output
    }

    /** Actions 의 `shell: bash` 는 `bash --noprofile --norc -eo pipefail {0}` 이다. */
    private fun runStep(job: String, id: String, cwd: File, env: Map<String, String>, bin: File? = null): Run =
        exec(listOf("bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", stepScript(job, id)), cwd, env, bin)

    private fun stepScript(job: String, id: String): String {
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(WORKFLOW).readText())
        val steps = ((workflow["jobs"] as Map<*, *>)[job] as Map<*, *>)["steps"] as List<*>
        val step = steps.filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id } ?: error("$job 에 id=$id 스텝이 없다")
        return step["run"] as String
    }

    private fun exec(command: List<String>, cwd: File, env: Map<String, String>, bin: File?): Run {
        val out = File(work, "out.txt")
        val githubOutput = File(work, "github_output").apply { writeText("") }
        val process = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                // 개발자의 전역 git 설정과 CI 의 GITHUB_* 값이 끼지 않게 한다.
                environment().keys.retainAll(setOf("PATH"))
                environment()["HOME"] = work.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GITHUB_OUTPUT"] = githubOutput.path
                environment().putAll(env)
                bin?.let { environment()["PATH"] = "${it.path}:${environment()["PATH"]}" }
            }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.take(2)} 가 30초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText(), githubOutput.readText())
    }

    private companion object {
        const val WORKFLOW = ".github/workflows/deploy.yml"
        const val SCRIPT = "deploy/vultr/deploy_needed.sh"
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
