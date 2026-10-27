package com.trading.bot.ci

import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * 배포는 엔진 재시작이라 main 수동 실행으로만 하고, `deploy/vultr/deploy_needed.sh` 의 `:(exclude)` 목록 밖 파일이
 * 바뀌었을 때만 재시작한다. 그 목록이 이미지나 서버 설정에 들어가는 파일을 삼키면 그 파일만 바꾼 배포가 조용히 건너뛰어진다.
 */
class DeployWorkflowPathListTest {

    private val root: File = findRepoRoot()

    private val workflow: Map<*, *> = Yaml().load(File(root, WORKFLOW).readText())

    /** IDE 에서 이 테스트만 돌리면 `repo.root` 가 없을 수 있어 위로 올라가며 찾는다. */
    private fun findRepoRoot(): File {
        System.getProperty("repo.root")?.let { root ->
            if (File(root, WORKFLOW).isFile) return File(root)
        }
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, WORKFLOW).isFile) return dir
            dir = dir.parentFile
        }
        error("$WORKFLOW 을 찾지 못했다 (repo.root=${System.getProperty("repo.root")}, cwd=${File(".").absolutePath})")
    }

    private fun job(name: String): Map<*, *> {
        val jobs = workflow["jobs"] as Map<*, *>
        return jobs[name] as? Map<*, *> ?: error("$name job 이 없다")
    }

    private fun steps(job: Map<*, *>): List<Map<*, *>> = (job["steps"] as List<*>).filterIsInstance<Map<*, *>>()

    private fun step(job: Map<*, *>, id: String): Map<*, *> =
        steps(job).firstOrNull { it["id"] == id } ?: error("id=$id 인 스텝이 없다")

    private fun scriptExcludes(): List<String> {
        val diff = diffCommand(File(root, SCRIPT).readText())

        // 작은따옴표·큰따옴표 모두 유효한 pathspec 이라 한쪽만 읽으면 어긋난 목록이 검사를 통과한다.
        val parsed = EXCLUDE_PATHSPEC.findAll(diff).map { it.groupValues[1] }.toList()
        val occurrences = EXCLUDE_MARKER.findAll(diff).count()
        check(parsed.size == occurrences) {
            "판정 스크립트의 :(exclude) $occurrences 개 중 ${parsed.size} 개만 파싱했다 — " +
                "인용 형식이 바뀌었으면 이 테스트의 정규식도 함께 고친다"
        }
        check(parsed.isNotEmpty()) { "판정 스크립트에서 :(exclude) 를 하나도 찾지 못했다" }
        // 짧은 형식(`:!`·`:^`)이나 다른 magic 과 섞은 exclude 는 위 정규식이 세지 못해 검사를 빠져나간다.
        check(SHORT_EXCLUDE.findAll(diff).none()) { "판정 스크립트의 exclude 는 ':(exclude)<경로>' 형식으로만 쓴다" }
        return parsed
    }

    /** 주석에 남은 옛 pathspec 을 제외 목록으로 오인하지 않게 실제 `git diff` 명령의 인자만 본다. */
    private fun diffCommand(script: String): String {
        val start = script.indexOf(DIFF_COMMAND)
        check(start >= 0) {
            "판정 스크립트에서 '$DIFF_COMMAND' 를 찾지 못했다 — 명령이 바뀌었으면 이 테스트도 함께 고친다"
        }
        // 줄 끝 백슬래시로 이어지는 논리적 한 줄을 모은다.
        return buildString {
            for (line in script.substring(start).lines()) {
                append(line).append('\n')
                if (!line.trimEnd().endsWith("\\")) break
            }
        }
    }

    @Test
    fun `배포 job 은 main 수동 실행에서만 돌고 판정 결과로 배포한다`() {
        val deploy = job("deploy-vultr")

        assertThat(deploy["if"])
            .describedAs("push 가 배포하면 머지할 때마다 엔진이 재시작된다")
            .isEqualTo("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'")

        val decide = step(deploy, "changes")
        assertThat(decide["run"] as String).contains("bash ./$SCRIPT ")
        val env = decide["env"] as Map<*, *>
        assertThat(env["FORCE"]).isEqualTo("\${{ inputs.force }}")
        assertThat(env["LAST_GOOD_SHA"]).isEqualTo("\${{ steps.prepare.outputs.last_good_sha }}")
        assertThat(env["RUNNING_SHA"]).isEqualTo("\${{ steps.prepare.outputs.running_sha }}")
        val prepare = step(deploy, "prepare")["run"] as String
        assertThat(prepare).contains("""echo "last_good_sha=${'$'}last_good_sha" >> "${'$'}GITHUB_OUTPUT"""")
        assertThat(prepare).contains("""echo "running_sha=${'$'}running_sha" >> "${'$'}GITHUB_OUTPUT"""")
        val deployStep = steps(deploy).firstOrNull { it["name"] == "Deploy to Vultr" } ?: error("Deploy to Vultr 스텝이 없다")
        assertThat(deployStep["if"]).isEqualTo("steps.changes.outputs.deploy == 'true'")
    }

    /** 이미 배포·검증한 `:sha` 태그를 다시 빌드하면 내용이 바뀌어, 롤백·재기동이 검증하지 않은 이미지를 받는다. */
    @Test
    fun `빌드는 이미지 확인에 걸리고 force 와 무관하며 같은 커밋끼리 줄을 선다`() {
        val on = workflow[true] ?: workflow["on"] ?: error("deploy.yml 에 on: 트리거가 없다")
        val dispatch = (on as Map<*, *>)["workflow_dispatch"] as Map<*, *>
        val force = (dispatch["inputs"] as Map<*, *>)["force"] as Map<*, *>
        assertThat(force["type"]).isEqualTo("boolean")
        assertThat(force["default"]).isEqualTo(false)

        val build = job("build-and-push")
        val check = step(build, "image")
        assertThat(check["if"]).describedAs("push 빌드도 확인해야 같은 커밋의 늦은 push 빌드가 배포한 태그를 덮지 않는다").isNull()
        assertThat(check.toString().lowercase()).describedAs("force 가 재빌드를 켜면 검증한 태그를 덮는다").doesNotContain("force")
        val buildStep = steps(build).firstOrNull { it["uses"].toString().startsWith("docker/build-push-action") }
            ?: error("build-push 스텝이 없다")
        assertThat(buildStep["if"]).isEqualTo("steps.image.outputs.exists != 'true'")
        val concurrency = build["concurrency"] as Map<*, *>
        assertThat(concurrency["group"].toString()).contains("\${{ github.sha }}")
        assertThat(concurrency["cancel-in-progress"]).describedAs("수동 실행이 진행 중인 push 빌드를 끊으면 안 된다").isEqualTo(false)
    }

    /**
     * 제외 목록에는 바뀌어도 배포가 필요 없는 것만 있어야 한다. 이미지와 서버 설정에 들어가는 추적 파일을 열거해
     * 그중 하나라도 제외 pathspec 에 걸리면 실패한다. 매칭은 판정 스크립트와 같은 git pathspec 으로 한다
     * (git 의 `*` 는 `/` 를 넘어 매칭해 glob 과 다르다).
     */
    @Test
    fun `배포 입력 중 어느 것도 제외 패턴에 삼켜지지 않는다`() {
        val patterns = scriptExcludes()
        val inputs = trackedFiles().filter(::isDeployInput).toSet()

        check(inputs.size > 50) { "배포 입력을 ${inputs.size} 개밖에 못 찾았다 — 열거가 깨졌다" }

        val swallowed = trackedFiles(patterns).filter { it in inputs }

        assertThat(swallowed)
            .describedAs(
                "배포 입력이 제외 패턴 $patterns 에 매칭된다 — 그 파일만 바꾼 배포가 조용히 건너뛰어진다. " +
                    "삼켜진 예: ${swallowed.take(5)}",
            )
            .isEmpty()
    }

    /**
     * 이미지에 들어가는 것(`COPY bot`·`COPY common` 등, 테스트는 `bootJar -x test` 라 빠진다)과 배포 스크립트·워크플로.
     * 배포 스크립트 쪽 `.md` 는 문서지만 앱 리소스의 `.md` 는 jar 에 들어가므로 입력으로 남긴다.
     */
    private fun isDeployInput(path: String): Boolean = when {
        path.startsWith("bot/") || path.startsWith("common/") ->
            "/src/test/" !in path && (!path.endsWith(".md") || "/src/main/" in path)
        path.startsWith("gradle/") -> true
        path.startsWith("deploy/") -> !path.endsWith(".md")
        else -> path in ROOT_INPUTS
    }

    private fun trackedFiles(pathspecs: List<String> = emptyList()): List<String> {
        val out = File.createTempFile("ls-files", ".txt").apply { deleteOnExit() }
        val process = ProcessBuilder(listOf("git", "ls-files", "-z", "--") + pathspecs)
            .directory(root)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "git ls-files 가 30초 안에 끝나지 않았다" }
        check(process.exitValue() == 0) { "git ls-files 실패:\n${out.readText()}" }
        return out.readText().split('\u0000').filter { it.isNotEmpty() }
    }

    private companion object {
        const val WORKFLOW = ".github/workflows/deploy.yml"
        const val SCRIPT = "deploy/vultr/deploy_needed.sh"
        val ROOT_INPUTS = setOf("Dockerfile", ".dockerignore", "build.gradle.kts", "settings.gradle.kts", WORKFLOW)
        val EXCLUDE_PATHSPEC = Regex("['\"]:\\(exclude\\)([^'\"]+)['\"]")
        val EXCLUDE_MARKER = Regex(":\\(exclude\\)")
        val SHORT_EXCLUDE = Regex("(^|[\\s'\"]):[!^]|:\\(exclude,|,exclude\\)")
        const val DIFF_COMMAND = "git diff --name-only"
    }
}
