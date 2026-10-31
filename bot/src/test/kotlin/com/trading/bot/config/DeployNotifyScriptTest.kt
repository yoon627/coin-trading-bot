package com.trading.bot.config

import com.trading.bot.ci.RepoFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 배포 결과를 Discord 로 알린다. 배포 로그는 공개 repo 의 CI 로그라 webhook URL 이 인자·출력에 나오면 안 되고,
 * 알림이 실패해도 배포 종료 코드(0 성공, 2 자동 롤백, 1 그 밖)는 그대로여야 한다.
 * 가짜 curl 은 받은 인자와 표준입력을 기록할 뿐이라 진짜 curl 의 오류 문구가 URL 을 담는 경우는 재현하지 않는다
 * (스크립트가 curl 의 stderr 를 버리는 것으로 막는다).
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "bash 스크립트 — 배포는 CI(Linux)·macOS 에서 돈다")
class DeployNotifyScriptTest {

    @TempDir
    lateinit var dir: File

    private val bin by lazy { File(dir, "bin").apply { mkdirs() } }
    private val curlArgs by lazy { File(dir, "curl.args") }
    private val curlStdin by lazy { File(dir, "curl.stdin") }
    private val sshRemote by lazy { File(dir, "ssh.remote") }
    private val scpCalls by lazy { File(dir, "scp.calls") }

    private class Run(val exit: Int, val output: String)

    @Test
    fun `성공은 일반 webhook 으로 보내고 URL 은 표준입력으로만 넘긴다`() {
        val result = notify("success", "배포 성공", general = GENERAL, error = ERROR)

        assertThat(result.exit).isEqualTo(0)
        assertThat(curlStdin.readText()).isEqualTo("url = \"$GENERAL\"\n")
        assertThat(curlArgs.readText()).doesNotContain("example.test")
        assertThat(result.output).contains("HTTP 204").doesNotContain("example.test")
    }

    @Test
    fun `롤백과 실패는 에러 webhook 으로, 없으면 일반 webhook 으로 보낸다`() {
        notify("failure", "배포 실패", general = GENERAL, error = ERROR)
        val withError = curlStdin.readText()
        notify("rollback", "자동 롤백", general = GENERAL, error = "")
        val withoutError = curlStdin.readText()

        assertThat(withError).contains(ERROR)
        assertThat(withoutError).contains(GENERAL)
    }

    @Test
    fun `webhook 이 없으면 curl 을 부르지 않고 건너뛴다고 남긴다`() {
        val result = notify("failure", "배포 실패", general = "", error = "")

        assertThat(result.exit).isEqualTo(0)
        assertThat(curlArgs).doesNotExist()
        assertThat(result.output).contains("건너뜀")
    }

    @Test
    fun `curl 이 실패해도 0 으로 끝나고 실패를 남긴다`() {
        val result = notify("success", "배포 성공", general = GENERAL, error = "", curlExit = 7)

        assertThat(result.exit).isEqualTo(0)
        assertThat(result.output).contains("실패").contains("7")
    }

    @Test
    fun `메시지의 따옴표와 줄바꿈은 JSON 으로 이스케이프하고 멘션은 막는다`() {
        notify("success", "a \"b\"\nc\\d", general = GENERAL, error = "")

        assertThat(curlArgs.readLines()).contains("""{"content":"a \"b\"\nc\\d","allowed_mentions":{"parse":[]}}""")
    }

    @Test
    fun `deploy 는 원격 결과마다 알맞은 webhook 으로 알리고 종료 코드를 지킨다`() {
        val repo = deployRepo()
        val cases = mapOf(
            0 to Triple(0, GENERAL, "성공"),
            2 to Triple(2, ERROR, "자동 롤백"),
            1 to Triple(1, ERROR, "수동 개입"),
            4 to Triple(1, ERROR, "Caddyfile"),
            3 to Triple(1, ERROR, "앱 중단"),
        )

        for ((remoteRc, expected) in cases) {
            curlStdin.delete()
            val result = deploy(repo, remoteRc)

            assertThat(result.exit).describedAs("원격 rc=$remoteRc\n${result.output}").isEqualTo(expected.first)
            assertThat(curlStdin.readText()).describedAs("원격 rc=$remoteRc").contains(expected.second)
            assertThat(curlArgs.readText()).describedAs("원격 rc=$remoteRc").contains(expected.third)
            assertThat(result.output).describedAs("원격 rc=$remoteRc").doesNotContain("example.test")
        }
    }

    /** 원격 4 는 앱이 새 SHA 로 정상이라는 뜻이라 롤백 기준은 그 SHA 로 옮기고, 배포는 실패로 남긴다. */
    @Test
    fun `원격 4 는 롤백 기준을 대상 커밋으로 갱신하고 실패로 끝난다`() {
        val repo = deployRepo()
        val target = git(repo, "rev-parse", "HEAD").trim()

        val result = deploy(repo, remoteRc = 4)

        assertThat(result.exit).describedAs(result.output).isEqualTo(1)
        assertThat(File(repo, "deploy/vultr/.state").readText()).contains("LAST_GOOD_SHA=$target")
    }

    /** 업로드가 중간에 끊겨도 빈 ./caddy 를 가리키는 compose 가 남지 않게 Caddyfile 을 먼저 올린다. */
    @Test
    fun `Caddyfile 은 compose 가 마운트하는 디렉터리로 compose 파일보다 먼저 올린다`() {
        val repo = deployRepo()
        val compose: Map<*, *> = Yaml().load(RepoFiles.file("deploy/vultr/docker-compose.prod.yml").readText())
        val caddyVolumes = ((compose["services"] as Map<*, *>)["caddy"] as Map<*, *>)["volumes"] as List<*>

        deploy(repo, remoteRc = 0)

        // 파일 하나를 마운트하면 올린 Caddyfile 이 컨테이너에 닿지 않거나 바꿔치기한 뒤 옛 inode 를 읽는다.
        assertThat(caddyVolumes).contains("./caddy:/etc/caddy:ro")
        val uploads = scpCalls.readLines().map { it.substringAfterLast(':') }
        assertThat(uploads).containsSubsequence("/opt/app/caddy/Caddyfile", "/opt/app/docker-compose.yml")
        assertThat(uploads).containsSubsequence("/opt/app/apply_caddyfile.sh", "/opt/app/docker-compose.yml")
        assertThat(sshRemote.readText()).contains("mkdir -p /opt/app/caddy")
    }

    /** 원격 배포 스크립트라 실행하지 않고 본문을 본다. exec 가 표준입력을 붙잡으면 bash -s 로 들어온 남은 스크립트를 먹는다. */
    @Test
    fun `원격 스크립트는 앱이 healthy 이고 롤백 기준을 쓴 뒤에만 Caddyfile 을 적용하고 표준입력을 넘기지 않는다`() {
        val remote = remoteScript()
        val healthy = remote.substringAfter("if health_ok; then").substringBefore("echo \"ERROR: 180s")

        val apply = healthy.indexOf("bash ./apply_caddyfile.sh </dev/null || caddy_rc=\$?")
        val verdict = healthy.indexOf("caddy_verdict \"\$caddy_rc\" \"\$tls_ok\" || verdict=\$?")
        assertThat(healthy.indexOf(".last-good-sha")).isGreaterThanOrEqualTo(0).isLessThan(apply)
        // 판정이 HTTPS 확인보다 앞서면 바뀐 Caddyfile 을 적용한 배포가 모두 4 가 된다.
        assertThat(verdict).isGreaterThan(healthy.indexOf("tls_ok=true"))
        assertThat(healthy).contains("exit \"\$verdict\"")
        assertThat(remote.substringAfter("echo \"ERROR: 180s")).doesNotContain("apply_caddyfile.sh")
    }

    /**
     * 성공 배포의 종료 코드: 바뀐 Caddyfile 을 적용했으면 HTTPS e2e 까지 확인해야 0 이고, 적용 실패는 앱과 별개로 4 다.
     * 적용했는데 HTTPS 를 확인하지 못하면 적용 기록을 지워, 다시 돌린 배포가 같은 Caddyfile 을 다시 적용하고 다시 확인한다.
     */
    @Test
    fun `Caddyfile 적용 결과와 HTTPS 확인으로 성공 배포의 종료 코드와 적용 기록을 정한다`() {
        val verdict = "caddy_verdict() {" + remoteScript().substringAfter("caddy_verdict() {").substringBefore("\n}") + "\n}\n"
        val marker = File(dir, ".caddyfile-applied")
        // 입력 → 종료 코드, 판정 뒤 적용 기록이 남는지
        val cases = mapOf(
            ("0" to "false") to (0 to true), ("0" to "true") to (0 to true), ("10" to "true") to (0 to true),
            ("10" to "false") to (4 to false), ("1" to "true") to (4 to true), ("1" to "false") to (4 to true),
        )

        for ((input, expected) in cases) {
            marker.writeText("hash\n")
            val result = exec(
                listOf("bash", "-c", verdict + "caddy_verdict \"\$1\" \"\$2\"", "verdict", input.first, input.second),
                mapOf("TARGET_SHA" to "abc"),
            )
            val label = "caddy_rc=${input.first} tls_ok=${input.second}\n${result.output}"
            assertThat(result.exit).describedAs(label).isEqualTo(expected.first)
            assertThat(marker.exists()).describedAs(label).isEqualTo(expected.second)
        }
    }

    @Test
    fun `알림 스크립트가 없어도 deploy 의 종료 코드는 그대로다`() {
        val repo = deployRepo()
        File(repo, "deploy/vultr/notify_deploy.sh").delete()

        val success = deploy(repo, remoteRc = 0)
        val rollback = deploy(repo, remoteRc = 2)

        assertThat(success.exit).describedAs(success.output).isEqualTo(0)
        assertThat(rollback.exit).describedAs(rollback.output).isEqualTo(2)
    }

    /** 원격 배포 스크립트라 실행하지 않고 본문을 본다. 시도 횟수만 세면 exec·curl 이 걸릴 때 job 시한까지 간다. */
    @Test
    fun `health 대기는 180초 마감으로 묶이고 exec 와 curl 에 시한이 있다`() {
        val healthOk = remoteScript().substringAfter("health_ok() {").substringBefore("\n}")

        assertThat(healthOk).contains("deadline=\$((SECONDS + 180))", "timeout -k ", "curl -fsS --max-time ")
    }

    /** 원격 스크립트는 서버에서만 돌아 문법 오류가 실제 배포에서야 드러난다. */
    @Test
    fun `원격 배포 스크립트는 bash 문법으로 읽힌다`() {
        val remote = File(dir, "remote.sh").apply { writeText(remoteScript()) }

        val result = exec(listOf("bash", "-n", remote.path), emptyMap())

        assertThat(result.exit).describedAs(result.output).isEqualTo(0)
    }

    @Test
    fun `Actions 에서 돌면 알림에 실행 링크를 붙인다`() {
        val repo = deployRepo()

        deploy(repo, remoteRc = 0, mapOf("GITHUB_RUN_ID" to "42", "GITHUB_REPOSITORY" to "owner/app"))

        assertThat(curlArgs.readText()).contains("https://github.com/owner/app/actions/runs/42")
    }

    /** 같은 커밋이면 compose 가 컨테이너를 다시 만들지 않아, 롤백 제외 사유를 마이그레이션과 따로 알린다. */
    @Test
    fun `마지막 정상 배포와 같은 커밋을 배포하면 same-sha 로 넘긴다`() {
        val repo = deployRepo(sameAsLastGood = true)

        deploy(repo, remoteRc = 1)

        assertThat(sshRemote.readText()).contains("MIGRATION_GATE='same-sha'")
    }

    /** `ssh_inst "…" <<'REMOTE' || deploy_rc=$?` 다음 줄부터 단독 `REMOTE` 줄 앞까지. */
    private fun remoteScript(): String {
        val text = RepoFiles.file("deploy/vultr/deploy.sh").readText()
        val start = text.indexOf("<<'REMOTE'")
        check(start >= 0) { "deploy.sh 에서 <<'REMOTE' 를 찾지 못했다" }
        val bodyStart = text.indexOf('\n', start) + 1
        val end = text.indexOf("\nREMOTE\n", bodyStart)
        check(end > bodyStart) { "deploy.sh 에서 원격 스크립트의 끝(REMOTE 줄)을 찾지 못했다" }
        return text.substring(bodyStart, end + 1)
    }

    private fun notify(level: String, message: String, general: String, error: String, curlExit: Int = 0): Run {
        fakeCurl(curlExit)
        return exec(
            listOf("bash", RepoFiles.file(NOTIFY).path, level, message),
            mapOf("DISCORD_WEBHOOK_URL" to general, "DISCORD_ERROR_WEBHOOK_URL" to error),
        )
    }

    /** 가짜 curl: 인자는 한 줄에 하나씩, 표준입력은 그대로 기록하고 HTTP 코드 자리에 204 를 낸다. */
    private fun fakeCurl(exit: Int = 0) {
        curlArgs.delete()
        File(bin, "curl").apply {
            writeText(
                """
                |#!/usr/bin/env bash
                |printf '%s\n' "${'$'}@" > "${curlArgs.path}"
                |cat > "${curlStdin.path}"
                |printf '204'
                |exit $exit
                """.trimMargin() + "\n",
            )
            setExecutable(true)
        }
    }

    /**
     * deploy.sh 가 `git rev-parse --show-toplevel` 로 저장소를 찾으므로 배포 파일을 임시 git 저장소에 둔다. 서버는 가짜 ssh 가
     * 대신한다 — 원격 배포 스크립트(`bash -s`)는 받은 결과 코드로 끝나고 나머지 명령은 성공한다.
     */
    private fun deployRepo(sameAsLastGood: Boolean = false): File {
        val repo = File(dir, "repo").apply { mkdirs() }
        val vultr = File(repo, "deploy/vultr").apply { mkdirs() }
        for (name in listOf("deploy.sh", NOTIFY.substringAfterLast('/'), "preflight_exit_params.sh", "docker-compose.prod.yml", "Caddyfile", "apply_caddyfile.sh", "backup.sh")) {
            RepoFiles.file("deploy/vultr/$name").copyTo(File(vultr, name))
        }
        git(repo, "init", "-q")
        git(repo, "add", "deploy")
        commit(repo, "last good")
        val lastGood = git(repo, "rev-parse", "HEAD").trim()
        File(repo, "README").writeText("target\n")
        git(repo, "add", "README")
        commit(repo, "target")
        val target = if (sameAsLastGood) lastGood else git(repo, "rev-parse", "HEAD").trim()
        File(vultr, ".env").writeText(
            """
            |DB_PASSWORD=db
            |JWT_SECRET=jwt
            |APP_ENCRYPTION_SECRET=enc
            |TRADING_AUTO_START=false
            |APP_DOMAIN=bot.invalid
            |APP_VERSION=$target
            |DISCORD_WEBHOOK_URL=$GENERAL
            |DISCORD_ERROR_WEBHOOK_URL=$ERROR
            """.trimMargin() + "\n",
        )
        File(vultr, ".state").writeText("PUBLIC_IP=203.0.113.10\nLAST_GOOD_SHA=$lastGood\n")
        script(
            "ssh",
            "remote=\"${'$'}{@: -1}\"\nprintf '%s\\n' \"${'$'}remote\" >> \"${sshRemote.path}\"\n" +
                "case \"${'$'}remote\" in *'bash -s'*) cat > /dev/null; exit \"${'$'}FAKE_REMOTE_RC\" ;; esac\nexit 0",
        )
        script("scp", "printf '%s\\n' \"${'$'}{@: -1}\" >> \"${scpCalls.path}\"\nexit 0")
        script("dig", "echo 203.0.113.10")
        fakeCurl()
        return repo
    }

    private fun deploy(repo: File, remoteRc: Int, env: Map<String, String> = emptyMap()): Run =
        exec(listOf("bash", File(repo, "deploy/vultr/deploy.sh").path, "deploy"), mapOf("FAKE_REMOTE_RC" to "$remoteRc") + env)

    private fun script(name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/usr/bin/env bash\n$body\n")
            setExecutable(true)
        }
    }

    private fun commit(repo: File, message: String) {
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(repo: File, vararg args: String): String {
        val result = exec(listOf("git", "-C", repo.path, *args), emptyMap())
        check(result.exit == 0) { "git ${args.joinToString(" ")} 실패:\n${result.output}" }
        return result.output
    }

    private fun exec(command: List<String>, env: Map<String, String>): Run {
        val out = File(dir, "out.txt")
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply {
                // 개발자의 전역 git 설정과 CI 의 GITHUB_* 값이 끼지 않게 한다.
                environment().keys.retainAll(setOf("PATH"))
                environment()["PATH"] = "${bin.path}:${environment()["PATH"]}"
                environment()["HOME"] = dir.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment().putAll(env)
            }
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.take(3)} 가 60초 안에 끝나지 않았다:\n${out.readText()}")
        }
        return Run(process.exitValue(), out.readText())
    }

    private companion object {
        const val NOTIFY = "deploy/vultr/notify_deploy.sh"
        const val GENERAL = "https://discord.example.test/api/webhooks/1/general"
        const val ERROR = "https://discord.example.test/api/webhooks/2/error"
    }
}
