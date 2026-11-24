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

    private val sshArgs by lazy { File(work, "ssh.args") }

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

    /**
     * 조회 SSH 가 조용히 끊긴 연결에 걸리면 job 시한(20분)까지 기다린다. SSH 한 번이 최악 약 130초(접속 10 + 무응답 120)라
     * 두 번이면 스텝 시한(3분)이 실효 상한이다 — 시한에 걸리면 조회 실패 문구 없이 스텝 시간 초과로 끝나고 서버는 그대로다.
     */
    @Test
    fun `운영 조회 SSH 는 무응답 시한을 걸고 스텝은 실행 시한 안에서 끝난다`() {
        val result = runPrepare("healthy")

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        val ssh = sshArgs.readLines()
        assertThat(ssh).describedAs(ssh.joinToString("\n")).hasSize(2).allSatisfy {
            assertThat(it).contains("-o ConnectTimeout=10", "-o ServerAliveInterval=15", "-o ServerAliveCountMax=8")
        }
        assertThat(step("deploy-vultr", "prepare")["timeout-minutes"] as Int?).isBetween(1, 5)
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

    @Test
    fun `롤백 호환 검사는 마이그레이션이 바뀌지 않았으면 이미지를 받지 않고 넘어간다`() {
        val head = initRepo(withScript = false)
        write("bot/src/main/kotlin/App.kt", "v2")
        commitAll("app only")
        val target = git("rev-parse", "HEAD").trim()

        val result = runStep("deploy-vultr", "compat", repo, compatEnv(head, target, check = "false"), compatFakes(scriptExit = 0))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(result.githubOutput).isEmpty()
        assertThat(File(work, "docker.calls")).doesNotExist()
    }

    @Test
    fun `롤백 호환 검사가 통과한 두 커밋만 deploy 에 넘기고 실패는 경고로 남긴다`() {
        val head = initRepo(withScript = false)
        write("bot/src/main/resources/db/migration/V99__x.sql", "SELECT 1;")
        commitAll("migration")
        val target = git("rev-parse", "HEAD").trim()

        val ok = runStep("deploy-vultr", "compat", repo, compatEnv(head, target, check = "false"), compatFakes(scriptExit = 0))
        assertThat(ok.exit).describedAs(ok.output).isEqualTo(0)
        assertThat(ok.githubOutput).isEqualTo("compat=$head..$target\n")

        for (rc in listOf(1, 2)) {
            val failed = runStep("deploy-vultr", "compat", repo, compatEnv(head, target, check = "false"), compatFakes(scriptExit = rc))
            assertThat(failed.exit).describedAs(failed.output).isEqualTo(0)
            assertThat(failed.githubOutput).isEmpty()
            assertThat(failed.output).contains("::warning::")
        }

        val forced = runStep("deploy-vultr", "compat", repo, compatEnv(target, target, check = "true"), compatFakes(scriptExit = 0))
        assertThat(forced.githubOutput).isEqualTo("compat=$target..$target\n")
    }

    @Test
    fun `롤백 호환 검사의 실패와 시한은 배포를 막지 않고 결과는 deploy 에 넘어간다`() {
        val compat = step("deploy-vultr", "compat")
        val deploy = steps("deploy-vultr").first { it["name"] == "Deploy to Vultr" }

        assertThat(compat["continue-on-error"]).isEqualTo(true)
        assertThat(compat["if"]).isEqualTo("(steps.changes.outputs.deploy == 'true' && steps.gate.outputs.go == 'true') || inputs.rollback_check")
        assertThat((deploy["env"] as Map<*, *>)["ROLLBACK_COMPAT"]).isEqualTo("\${{ steps.compat.outputs.compat }}")
    }

    // main push 는 자동 배포다. 다만 app 밖 컨테이너가 다시 만들어지는 배포는 수동 실행으로 남긴다.
    @Test
    fun `push 는 판정이 자동이라고 할 때만 배포하고 아니면 경고로 수동 실행을 안내한다`() {
        val auto = runStep("deploy-vultr", "gate", repo, gateEnv("push"), gateFake(exit = 0, output = "auto=true\napp 만 바뀌었다"))
        val manual = runStep("deploy-vultr", "gate", repo, gateEnv("push"), gateFake(exit = 0, output = "auto=false\nservices.postgres 가 바뀌었다"))
        val broken = runStep("deploy-vultr", "gate", repo, gateEnv("push"), gateFake(exit = 2, output = "usage"))

        assertThat(auto.githubOutput).describedAs(auto.output).isEqualTo("go=true\n")
        assertThat(manual.githubOutput).describedAs(manual.output).isEqualTo("go=false\n")
        assertThat(manual.output).contains("::warning::", "services.postgres")
        assertThat(broken.exit).describedAs(broken.output).isEqualTo(0)
        assertThat(broken.githubOutput).isEqualTo("go=false\n")
        assertThat(broken.output).contains("::warning::")
    }

    // 보류된 push 는 뒤 push 도 계속 보류되어 운영이 뒤처진다. 경고 주석만으로는 안 보여 배포 webhook 으로 알린다.
    @Test
    fun `자동 배포를 보류하면 배포 webhook 으로 알리고 webhook 값은 출력하지 않는다`() {
        fakeNotify()
        // deploy.sh 처럼 source 로 읽으므로 export 접두·따옴표·주석 줄도 같은 값이 된다.
        val env = gateEnv("push") + ("VULTR_DEPLOY_ENV" to "# comment\nDB_PASSWORD=x\nexport DISCORD_DEPLOY_WEBHOOK_URL=\"$WEBHOOK\"\n")

        val result = runStep("deploy-vultr", "gate", repo, env, gateFake(exit = 0, output = "auto=false\nservices.caddy 가 바뀌었다"))

        val calls = notifyCalls.readText()
        assertThat(result.githubOutput).isEqualTo("go=false\n")
        assertThat(calls).contains("notify level=failure webhook_set=yes", "exact", "자동 배포 보류", "services.caddy")
        assertThat(result.output).doesNotContain(WEBHOOK)
    }

    // 배포 job 까지 가지 못한 실패(2026-11-24 이미지 빌드의 Maven 429)는 알림 없이 run 만 빨갛게 남았다.
    @Test
    fun `test 나 이미지 빌드가 실패하면 실패한 job 을 배포 webhook 으로 한 번 알린다`() {
        fakeNotify()
        val cases = mapOf(
            Triple("success", "failure", "skipped") to "build-and-push: failure",
            Triple("failure", "skipped", "skipped") to "test: failure",
            Triple("success", "cancelled", "skipped") to "build-and-push: cancelled",
        )

        for ((results, expected) in cases) {
            notifyCalls.delete()
            val result = runStep("notify-failure", "notify", repo, failureEnv(results))

            assertThat(result.exit).describedAs(result.output).isEqualTo(0)
            assertThat(notifyCount()).describedAs("$results").isEqualTo(1)
            assertThat(notifyCalls.readText()).describedAs("$results")
                .contains("notify level=failure webhook_set=yes", "exact", SHA.take(12), "/actions/runs/7", expected, "배포되지 않았다")
            assertThat(result.output).doesNotContain(WEBHOOK)
        }
    }

    /**
     * 배포 job 이 실패 알림 스텝까지 가지 못한 경우는 그 job 의 스텝 결과(API)로 가른다. 같은 concurrency 그룹에서 대기하다
     * 밀려난 job 도 cancelled 라, 스텝이 하나도 없으면 알리지 않는다. 모르는 결과(timed_out 등)는 원격 배포가 돈 것으로 본다.
     */
    @Test
    fun `배포 job 이 알림 스텝까지 가지 못하고 끝나면 원격 배포가 돌았는지로 알림을 가른다`() {
        fakeNotify()
        val cases = listOf(
            Case("cancelled", mapOf("Deploy to Vultr" to "cancelled"), "결과 불명"),
            Case("cancelled", mapOf("Deploy to Vultr" to "in_progress"), "결과 불명"),
            Case("failure", mapOf("Deploy to Vultr" to "failure", "Notify deploy job failure" to "cancelled"), "결과 불명"),
            Case("cancelled", mapOf("Deploy to Vultr" to "timed_out"), "결과 불명"),
            Case("failure", mapOf("Set up job" to "failure"), "원격 배포 전에 멈췄다"),
            Case("cancelled", mapOf("Prepare Vultr deploy files" to "cancelled", "Deploy to Vultr" to "skipped"), "원격 배포 전에 멈췄다"),
            Case("cancelled", emptyMap(), null),
            Case("failure", mapOf("Deploy to Vultr" to "failure", "Notify deploy job failure" to "success"), null),
            Case("failure", mapOf("Deploy to Vultr" to "success"), null),
            Case("success", emptyMap(), null),
        )

        for (case in cases) {
            notifyCalls.delete()
            val result = runStep("notify-failure", "notify", repo, failureEnv(Triple("success", "success", case.result)), fakeGh(case.steps))

            assertThat(result.exit).describedAs(result.output).isEqualTo(0)
            if (case.expected == null) {
                assertThat(notifyCalls).describedAs("$case\n${result.output}").doesNotExist()
            } else {
                assertThat(notifyCount()).describedAs("$case").isEqualTo(1)
                assertThat(notifyCalls.readText()).describedAs("$case").contains("deploy-vultr: ${case.result}", case.expected)
            }
        }
    }

    @Test
    fun `배포 job 의 스텝을 조회하지 못하면 알린다`() {
        fakeNotify()

        val result = runStep("notify-failure", "notify", repo, failureEnv(Triple("success", "success", "cancelled")), fakeGh(null))

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
        assertThat(notifyCount()).isEqualTo(1)
        assertThat(notifyCalls.readText()).contains("조회하지 못했다")
    }

    @Test
    fun `배포 job 의 실패는 deploy sh 가 알리지 않았을 때만 알린다`() {
        fakeNotify()
        val marker = File(work, "deploy-notified")
        val env = mapOf(
            "VULTR_DEPLOY_ENV" to "DISCORD_DEPLOY_WEBHOOK_URL='$WEBHOOK'",
            "DEPLOY_NOTIFIED_MARKER" to marker.path,
            "GITHUB_SHA" to SHA,
        )

        marker.writeText("")
        val notified = runStep("deploy-vultr", "notify-failure", repo, env)
        assertThat(notified.exit).describedAs(notified.output).isEqualTo(0)
        assertThat(notifyCalls).describedAs(notified.output).doesNotExist()

        marker.delete()
        val silent = runStep("deploy-vultr", "notify-failure", repo, env)
        assertThat(silent.exit).describedAs(silent.output).isEqualTo(0)
        assertThat(notifyCount()).isEqualTo(1)
        assertThat(notifyCalls.readText()).contains("notify level=failure webhook_set=yes", "exact", "배포 job 실패", SHA.take(12))
        assertThat(silent.output).doesNotContain(WEBHOOK)
    }

    @Test
    fun `실패 알림은 main 의 push 와 수동 실행에서만 돌고 deploy sh 와 같은 표시 파일을 본다`() {
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(WORKFLOW).readText())
        val job = (workflow["jobs"] as Map<*, *>)["notify-failure"] as Map<*, *>
        val deploy = steps("deploy-vultr").first { it["name"] == "Deploy to Vultr" }
        val failureStep = step("deploy-vultr", "notify-failure")
        val marker = "\${{ runner.temp }}/deploy-notified"

        assertThat(job["needs"]).isEqualTo(listOf("test", "build-and-push", "deploy-vultr"))
        assertThat(job["if"] as String).startsWith("always() && (github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main'")
        assertThat(failureStep["if"]).isEqualTo("failure()")
        assertThat((job["permissions"] as Map<*, *>)["actions"]).isEqualTo("read")
        assertThat((deploy["env"] as Map<*, *>)["DEPLOY_NOTIFIED_MARKER"]).isEqualTo(marker)
        assertThat((failureStep["env"] as Map<*, *>)["DEPLOY_NOTIFIED_MARKER"]).isEqualTo(marker)
    }

    private val notifyCalls by lazy { File(work, "notify.calls") }

    private data class Case(val result: String, val steps: Map<String, String>, val expected: String?)

    private fun notifyCount() = notifyCalls.readLines().count { it.startsWith("notify level=") }

    /** `gh api …/jobs` 를 대신한다 — [steps] 가 null 이면 조회 실패, 아니면 deploy-vultr job 의 스텝 결과. */
    private fun fakeGh(steps: Map<String, String>?): File {
        val bin = File(work, "bin").apply { mkdirs() }
        val body = if (steps == null) {
            "echo 'HTTP 403' >&2\nexit 1\n"
        } else {
            val json = steps.entries.joinToString(",") { (name, state) ->
                if (state == "in_progress") "{\"name\":\"$name\",\"status\":\"in_progress\",\"conclusion\":null}"
                else "{\"name\":\"$name\",\"status\":\"completed\",\"conclusion\":\"$state\"}"
            }
            "cat <<'JSON'\n{\"jobs\":[{\"name\":\"test\",\"steps\":[]},{\"name\":\"deploy-vultr\",\"steps\":[$json]}]}\nJSON\n"
        }
        File(bin, "gh").apply {
            writeText("#!/usr/bin/env bash\n$body")
            setExecutable(true)
        }
        return bin
    }

    /** notify_deploy.sh 를 대신해 받은 결과·webhook 을 기록한다. webhook 을 읽는 notify_ci.sh 는 진짜를 쓴다. */
    private fun fakeNotify() {
        File(repo, "deploy/vultr").mkdirs()
        RepoFiles.file("deploy/vultr/notify_ci.sh").copyTo(File(repo, "deploy/vultr/notify_ci.sh"), overwrite = true)
        File(repo, "deploy/vultr/notify_deploy.sh").writeText(
            "echo \"notify level=${'$'}1 webhook_set=${'$'}{DISCORD_DEPLOY_WEBHOOK_URL:+yes}\" >> '${notifyCalls.path}'\n" +
                "[[ \"${'$'}DISCORD_DEPLOY_WEBHOOK_URL\" == '$WEBHOOK' ]] && echo exact >> '${notifyCalls.path}'\n" +
                "echo \"${'$'}2\" >> '${notifyCalls.path}'\n",
        )
    }

    private fun failureEnv(results: Triple<String, String, String>) = mapOf(
        "VULTR_DEPLOY_ENV" to "DISCORD_DEPLOY_WEBHOOK_URL='$WEBHOOK'",
        "TEST_RESULT" to results.first,
        "BUILD_RESULT" to results.second,
        "DEPLOY_RESULT" to results.third,
        "GITHUB_SHA" to SHA,
        "GITHUB_SERVER_URL" to "https://github.com",
        "GITHUB_REPOSITORY" to "owner/app",
        "GITHUB_RUN_ID" to "7",
        "GITHUB_RUN_ATTEMPT" to "1",
    )

    @Test
    fun `push 는 main 이 앞서 있으면 최신 커밋에 맡기고 문서만 앞서 있으면 그대로 배포한다`() {
        val older = initRepo(withScript = false)
        write("bot/src/main/kotlin/App.kt", "v2")
        commitAll("newer code")
        val newer = git("rev-parse", "HEAD").trim()
        git("branch", "-M", "main")
        git("remote", "add", "origin", repo.path)

        val stale = runStep("deploy-vultr", "main-head", repo, mapOf("EVENT_NAME" to "push", "GITHUB_SHA" to older))
        val current = runStep("deploy-vultr", "main-head", repo, mapOf("EVENT_NAME" to "push", "GITHUB_SHA" to newer))
        File(repo, "docs").mkdirs()
        File(repo, "docs/note.md").writeText("x\n")
        git("add", "docs")
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "docs")
        val docsOnly = runStep("deploy-vultr", "main-head", repo, mapOf("EVENT_NAME" to "push", "GITHUB_SHA" to newer))
        val manual = runStep("deploy-vultr", "main-head", repo, mapOf("EVENT_NAME" to "workflow_dispatch", "GITHUB_SHA" to older))

        assertThat(stale.githubOutput).describedAs(stale.output).isEqualTo("current=false\n")
        assertThat(stale.output).contains("::notice::")
        assertThat(current.githubOutput).describedAs(current.output).isEqualTo("current=true\n")
        assertThat(docsOnly.githubOutput).describedAs(docsOnly.output).isEqualTo("current=true\n")
        assertThat(manual.githubOutput).describedAs(manual.output).isEqualTo("current=true\n")
    }

    @Test
    fun `main 이 앞선 push 는 운영 조회·판정·배포를 하지 않는다`() {
        for (id in listOf("prepare", "changes")) {
            assertThat(step("deploy-vultr", id)["if"]).describedAs(id).isEqualTo("steps.main-head.outputs.current == 'true'")
        }
    }

    @Test
    fun `수동 실행은 자동 배포 판정을 거치지 않는다`() {
        val result = runStep("deploy-vultr", "gate", repo, gateEnv("workflow_dispatch"), gateFake(exit = 0, output = "auto=false"))

        assertThat(result.githubOutput).describedAs(result.output).isEqualTo("go=true\n")
        assertThat(File(work, "gate.calls")).doesNotExist()
    }

    @Test
    fun `배포 job 은 main 의 push 와 수동 실행에서 돌고 배포 스텝은 판정을 따른다`() {
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(WORKFLOW).readText())
        val job = (workflow["jobs"] as Map<*, *>)["deploy-vultr"] as Map<*, *>
        val deploy = steps("deploy-vultr").first { it["name"] == "Deploy to Vultr" }
        val gate = step("deploy-vultr", "gate")

        assertThat(job["if"]).isEqualTo("(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main'")
        assertThat(gate["if"]).isEqualTo("steps.changes.outputs.deploy == 'true'")
        assertThat(deploy["if"]).isEqualTo("steps.changes.outputs.deploy == 'true' && steps.gate.outputs.go == 'true'")
    }

    private fun gateEnv(event: String) = mapOf(
        "EVENT_NAME" to event,
        "LAST_GOOD_SHA" to SHA,
        "GITHUB_SHA" to SHA,
    )

    /** auto_deploy_gate.sh 를 대신한다 — 판정 자체는 AutoDeployGateScriptTest 가 본다. */
    private fun gateFake(exit: Int, output: String): File? {
        File(repo, "deploy/vultr").mkdirs()
        File(repo, "deploy/vultr/auto_deploy_gate.sh").writeText(
            "echo called >> '${File(work, "gate.calls").path}'\nprintf '%b\\n' '$output'\nexit $exit\n",
        )
        return null
    }

    private fun compatEnv(lastGood: String, target: String, check: String) = mapOf(
        "LAST_GOOD_SHA" to lastGood,
        "GITHUB_SHA" to target,
        "ROLLBACK_CHECK" to check,
        "GHCR_TOKEN" to "t",
        "GITHUB_ACTOR" to "a",
        "REGISTRY" to "ghcr.io",
        "IMAGE_NAME" to "owner/app",
    )

    /** docker login 과 rollback_compat.sh 를 대신한다 — 검사 스크립트 자체는 DeployRollbackCompatScriptTest 가 본다. */
    private fun compatFakes(scriptExit: Int): File {
        File(repo, "deploy/vultr").mkdirs()
        File(repo, "deploy/vultr/rollback_compat.sh").writeText("exit $scriptExit\n")
        val bin = File(work, "bin").apply { mkdirs() }
        File(bin, "docker").apply {
            writeText("#!/usr/bin/env bash\necho \"${'$'}*\" >> '${File(work, "docker.calls").path}'\ncat > /dev/null\n")
            setExecutable(true)
        }
        return bin
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
        sshArgs.delete()
        File(bin, "ssh").apply {
            writeText(
                """
                |#!/usr/bin/env bash
                |printf '%s\n' "${'$'}*" >> "${sshArgs.path}"
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

    private fun stepScript(job: String, id: String): String = step(job, id)["run"] as String

    private fun step(job: String, id: String): Map<*, *> =
        steps(job).firstOrNull { it["id"] == id } ?: error("$job 에 id=$id 스텝이 없다")

    private fun steps(job: String): List<Map<*, *>> {
        val workflow: Map<*, *> = Yaml().load(RepoFiles.file(WORKFLOW).readText())
        return (((workflow["jobs"] as Map<*, *>)[job] as Map<*, *>)["steps"] as List<*>).filterIsInstance<Map<*, *>>()
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
        const val WEBHOOK = "https://discord.example.test/api/webhooks/1/secret?wait=true&x=1"
    }
}
